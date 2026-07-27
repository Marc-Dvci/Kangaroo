# Architecture

One process, one artifact, zero runtime dependencies. The build enforces the last one:
`maven-enforcer-plugin` fails if any dependency reaches `compile` or `runtime` scope.

---

## The package graph

Dependencies point downward only. `core` knows about nothing; `app` knows about everything.

```
                              app
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                  │
          http            orchestrate            store
            │                  │                  │
            │        ┌─────────┼─────────┐        │
            │        │         │         │        │
          i18n     infer   clinical     ml      crypto
                     │         │         │
                  ffm.llama    │       color
                     │         │         │
                     └─────────┴─────────┘
                               │
                             core ── util
                               │
                             audit
```

| Package | Responsibility | Depends on |
|---|---|---|
| `core` | The domain: records, sealed types, the feature space | nothing |
| `util` | JSON, as a sealed hierarchy | nothing |
| `audit` | JFR clinical events, the JEP map | `util` |
| `clinical` | The WHO protocol: rule, dosing, z-score, ORS, referral, PSBI | `core`, `util`, `audit` |
| `ml` | GBM engine, abstention, text feature extraction | `core`, `util`, `color` |
| `color` | Colorimetry, Kramer zones, Vector API, benchmark | `core`, `audit`, `ml` |
| `ffm.llama` | FFM bindings to libllama and mtmd | `audit` |
| `infer` | The sealed engine hierarchy and the failover ladder | `core`, `clinical`, `i18n`, `ffm` |
| `orchestrate` | The structured-concurrency assessment pipeline | everything below |
| `store` | Signed encounters, longitudinal patient memory | `core`, `crypto`, `util` |
| `crypto` | PEM device identity, record signing | nothing |
| `i18n` | ResourceBundle catalogues | nothing |
| `http` | Server, routes, static assets | everything |
| `app` | Entry point, native diagnostic | everything |

---

## The assessment pipeline

```
  Encounter
      │
      ├─ StructuredTaskScope, one deadline, one cancellation domain ─────┐
      │                                                                  │
      │   Visual pass        Audio pass      Vitals pass    History pass │
      │   decode frame       decode WAV      text + vitals  trend across │
      │   colour pipeline    pitch, voicing  extraction     prior visits │
      │   Kramer zones       refuse if unclear                           │
      │        │                  │              │              │        │
      └────────┴──────────────────┴──────────────┴──────────────┘        │
                                  │                                      │
                          merge into one SignProfile ────────────────────┘
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
        WHO rule engine    gradient-boosted head   language model
        (deterministic)    (calibrated, abstains)  (down the ladder)
              │                   │                   │
              └───────────────────┴───────────────────┘
                                  │
                            reconcile
                    rule is the floor · heads may only raise
                    disagreement → supervisor · abstain → escalate
                                  │
                            Assessment
                     JFR event · signed record · referral letter
```

### Why the passes share one profile

All three heads read **the same `SignProfile`**. That is what makes comparing them honest: a
disagreement is a genuine disagreement about the same evidence, not two components looking at
different inputs and appearing to disagree.

The merge step only ever *raises* a flag. A colorimetric grade that disagrees with what the caregiver
reported is additional evidence, not a correction — if the parent says the baby is yellow and the
photograph says otherwise, the photograph may simply be badly lit.

### Why a failed pass is a gap, not a failure

Losing the cry analysis should not deny a health worker the twenty other danger signs that were
assessed fine. A failed or timed-out pass is recorded as a gap in the evidence; in parent mode a gap
escalates rather than being ignored, because there is no trained observer to have noticed what the
missing pass would have caught.

The deterministic rule runs on whatever evidence was gathered, so the answer is never *unsafe*
because a pass failed — only less well informed, and the interface says so.

---

## The inference ladder

```
Rung             Where it runs          Needs                    Narrative quality
────────────────────────────────────────────────────────────────────────────────────
CLOUD_HTTP3      a provider's servers   network + key            best
CLOUD_HTTP2      same, degraded         network + key            best
CLOUD_HTTP1      same, degraded         network + key            best
LOCAL_SERVER     your LAN               a machine in the room    very good
NATIVE           this process, FFM      a GGUF file              good
DETERMINISTIC    this process           nothing at all           templates
```

**No rung decides the traffic light.** Every engine returns a `Narrative` whose `suggested` colour is
an *opinion*, recorded for comparison and for supervisor review. The decision is made by the
deterministic rule and the calibrated head, which run **before** the ladder is consulted and do not
depend on it.

That is the whole reason descending is safe, and why the network cable can be pulled on stage: what
degrades is the prose, not the answer.

A privacy-flagged encounter skips every network rung. That is enforced in `FailoverEngine` **and** in
`EncounterStore`, because a rule implemented in one place is a rule with a bypass.

---

## Concurrency

- **One virtual thread per HTTP request.** A request that spends eight seconds inside a language model
  occupies a thread for eight seconds; with virtual threads that costs a few hundred bytes rather than
  a pooled platform thread, so a Pod serving a dozen phones needs no pool tuning and no queue.
- **One `StructuredTaskScope` per assessment**, with the group deadline described above.
- **Shared state is immutable.** `SignProfile`, `Encounter`, `Assessment`, `Tree` and every record in
  `core` are immutable and freely shared. `PatientMemory` is the only mutable shared structure and is
  backed by a `ConcurrentHashMap` of synchronized lists.
- **The models are `LazyConstant`s**: initialised at most once, safely published, constant-folded
  afterwards.

`EndToEndTest#concurrency` runs 24 simultaneous assessments and asserts none is contaminated by its
neighbours. Its sibling `repeatVisitsShareTheLongitudinalRecord` asserts the opposite for the same
subject reference, because repeat visits are *supposed* to see each other.

---

## The native layer

```
  Java                                  C
  ────────────────────────────────────────────────────────────
  NativeRuntime.openLlama(arena)   →    dlopen/LoadLibrary
      ggml-base, ggml, llama, mtmd       (deepest first)
                                        
  Llama.<init>                     →    ~20 downcallHandles
      loadGgmlBackends()           →    ggml_backend_load_all_from_path
      installLogBridge()           ←    upcall stub: logs into java.lang.System.Logger
      llama_model_load_from_file   →    struct by value, layout-verified
                                        
  chat(system, user)               →    llama_chat_apply_template
                                   →    llama_tokenize
                                   →    llama_decode
                                   ←    llama_sampler_sample (grammar-constrained)
                                        
  chatWithImages(...)              →    mtmd_helper_bitmap_init_from_buf   ← by address
                                   →    mtmd_tokenize
                                   →    mtmd_helper_eval_chunks
```

**No JNI.** The whole native surface is about twenty `Linker.downcallHandle`s and one upcall stub.

**Memory.** The model, context and sampler live in a shared `Arena` closed by `Llama.close()`.
Everything belonging to a single generation lives in a confined arena closed when the generation
returns, normally or exceptionally. There is no manual free in the codebase and no way to leak one.

**Layout verification.** `LlamaLayouts.verify()` asserts the struct sizes the pinned upstream header
implies, at startup. A layout that has drifted does not crash — it reads `n_gpu_layers` out of the
middle of a pointer and carries on — so it has to be caught explicitly.

**Zero-copy images.** A captured frame is decoded into a `MemorySegment` once and handed to the
projector by address. No base64, no JSON, no second copy.

---

## Data flow and retention

| Artifact | Where it lives | Retained? |
|---|---|---|
| Captured photographs | Heap, for the duration of the assessment | **No.** Never written to disk. |
| Cry recording | Heap | No |
| Intake text | In the signed record | Yes, locally |
| Danger signs and classification | In the signed record | Yes, locally |
| Narrative | In the signed record | Yes, locally |
| Subject reference | In the signed record | Yes, locally; caregiver-chosen, not a name |
| Device private key | `~/.kangaroo/device-key.pem`, owner-only | Yes, never transmitted |
| API keys | Memory only | Never logged, never serialised, redacted from JFR |

`EndToEndTest#imagesAreNotStored` asserts the image bytes never reach disk.

---

## Why `jdk.httpserver` and no framework

Kangaroo routes about a dozen endpoints and serves a handful of static files. A servlet container or
a web framework would add a dependency tree, a second configuration language, and a startup cost, in
exchange for routing sugar. The whole product is one process with one artifact and zero runtime
dependencies; a web stack would undo that for very little.

`Http.java` is the entire abstraction over it: read a body, write a response, and the security
headers every response carries.
