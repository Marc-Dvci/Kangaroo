# Submission — Modern Java in the Wild

**Project:** Kangaroo
**Track:** Best Health Solution (+ BYOD bonus)
**Repository:** <https://github.com/Marc-Dvci/Kangaroo>

---

## Short description

An offline newborn-watch platform for parents and community health workers, built entirely in
Java 26. It runs the WHO danger-sign protocol on a phone, a laptop or a Raspberry Pi with the
network cable pulled out — including the parts the industry assumes must be C++ or Python.

---

## The problem

Two point three million newborns die within twenty-eight days of birth, three quarters of them in
sub-Saharan Africa and South Asia, three quarters within the first week. Almost all of it is
preventable, and the bottleneck is not medicine — it is **detection**. Someone has to notice, in
time, that this baby is not merely sleepy but lethargic; that this yellow has reached the soles and
not just the face; that fifty-eight breaths a minute is fine and sixty-two is not.

The two people positioned to notice are a **parent** who has never done this before, and a
**community health worker** whose training decays between visits and who carries no diagnostic
equipment. Both, at the moment it matters, are offline.

---

## What was built

One clinical engine, two front doors, and a deterministic floor under everything.

- **Parent mode** — a two-minute check: plain language, a tap-to-count breathing measure, a
  photograph against a printed colour card. It never says the baby is fine; it says nothing it can
  see needs a clinician today, and always ships the return-immediately list.
- **Health-worker mode** — the full WHO IMNCI young-infant assessment, five deterministic clinical
  tools, the "referral not possible" branch that most tools omit, and a printable referral letter
  generated with the result.
- **A failover ladder** from HTTP/3 cloud models down to deterministic WHO rules that need no
  network, no model and no native library. **It never goes dark.**

---

## Judging criteria

### Use of Modern Java 26 — all ten JEPs

| JEP | Used for | Load-bearing |
|---|---|---|
| 517 HTTP/3 | The cloud rung over QUIC; survives packet loss and cell handover on a rural link | **yes** |
| 525 Structured Concurrency | Four evidence passes, one deadline, one cancellation domain | **yes** |
| 526 Lazy Constants | WHO tables and both models; a device that never weighs a baby never parses the LMS tables | **yes** |
| 530 Primitive Patterns | LightGBM's packed decision byte; checked `double`→`float` narrowing | no |
| 529 Vector API | Colorimetry kernels, measured live at `/api/bench` | **yes** |
| 524 PEM Encodings | Ed25519 device identity and per-record signatures, with no crypto dependency | **yes** |
| 516 AOT Caching | Cold start to first assessment: **409 ms → 351 ms (14%)**, measured | no |
| 522 G1 throughput | The Pod's concurrent-assessment load; `packaging/gc-benchmark.sh` | no |
| 500 Final means final | Runs under `--illegal-final-field-mutation=deny` | no |
| 504 Applet removal | Clean bill of health — the runtime dependency set is exactly the JDK | no |

Also: **FFM** (the entire native layer, no JNI), **virtual threads** (thread-per-request), **sealed
types and exhaustive switches** (the clinical domain), **JFR** (the audit trail), **JEP 458/512/511**
(`tools/Check.java`, which runs with no build tool at all).

The running application serves this table at `GET /api/jeps`.

### Functionality and stability

Works end to end. 69 tests, all green, on four platforms in CI. Verified live:

- A 7.5 B-parameter vision model loaded and generating **in-process through FFM**, text and images.
- The failover ladder descending from a live-but-model-less server to the deterministic rung and
  still producing a complete, correct referral.
- Ed25519 PEM identity, signed records, tamper detection.
- Every clinical case in the battery: red, amber, green, and the 59/60 boundary from both sides.

### Technical depth

- **The Java gradient-boosting engine is bit-exact against LightGBM**: max deviation **1.1e-16**
  across **24,070** adversarial vectors sitting exactly on every split threshold in the model and
  one ULP either side.
- **The dosing ceiling is a machine-checked property**, ~10 million assertions, not three examples.
- **The WHO chart as an exhaustive truth table**, plus a monotonicity sweep.
- **Zero runtime dependencies**, enforced by the build.

### Impact and relevance

A real protocol, for a real and enormous problem, in the configuration the problem actually occurs
in — offline, on hardware people already own. The bill of materials starts at **£0**.

### User experience and design

One-handed operation, sunlight-readable contrast, 48 px touch targets, severity carried by shape and
icon as well as colour, drafts persisted against interruption, installable PWA, dark mode,
`prefers-reduced-motion`, and a "How this was decided" panel that shows the three heads disagreeing
rather than smoothing it over.

### Documentation

A README a beginner can follow, a full BOM, an architecture document, a clinical-safety document with
a residual-risk register, a fairness document on skin-tone robustness, a localisation-pipeline
document, a printable colour card, and a timed demo script.

---

## Every number in this submission is measured

No figure here is an estimate, and every one of them is reproducible from a clean clone:

- **1.1e-16** — max deviation of the Java GBM engine against LightGBM, across 24,070 adversarial
  vectors. Printed by `GbmParityTest` on every run.
- **1.57%** — measured disagreement between the trained clinical head and the WHO rule on 20,000
  swept profiles. Printed by `ImnciConformanceTest`, which fails the build if it regresses past 3%,
  and contained by construction: `modelCanOnlyEscalateNeverDeEscalate` proves over 50,000 profiles
  that a head can raise a classification and never lower one.
- **1.41× kernels / 1.04× end to end** — Vector API speedup, re-measured live at `GET /api/bench` on
  whatever machine a judge runs it on. Both rows are reported: the pipeline is memory-bandwidth
  bound, and the end-to-end path carries an exact percentile sort the design keeps on purpose.
- **409 ms → 351 ms (14%)** — cold start to first completed assessment with the JEP 516 AOT cache,
  best of seven each way. Reproduce with `./packaging/aot.sh`.
- **Under 1 ms** — offline assessment latency on a laptop.
- **69 / 69** — tests green on Linux x64, Linux arm64, macOS arm64 and Windows in CI.

Six real defects were caught by this suite during development and are now named regression tests,
including a NaN weight propagating to a NaN dose and a canonical-serialisation bug that silently
invalidated every stored signature. They are listed in the README because they are the argument for
testing this way.

**Scope.** Kangaroo is clinical decision support implementing published WHO guidance. It is not a
medical device and has not been through clinical validation; every screen and the referral letter
say so, and the whole architecture is built on that footing — the deterministic rule is the floor,
no model can lower a result, and disagreement routes to a human. The boundary of the evidence,
including the stratified skin-tone evaluation that a deployment would run before release, is set out
in `docs/fairness.md` and `docs/clinical-safety.md`.

---

## Reproducing it

```bash
git clone https://github.com/Marc-Dvci/Kangaroo
cd Kangaroo
./mvnw package
java --enable-preview --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED -jar target/kangaroo.jar --open
```

Requires JDK 26 and nothing else. Turn off your Wi-Fi first — it makes the point better.

Verification of Java 26 is threefold: `pom.xml` targeting release 26, a committed unedited
`build.log`, and `java --enable-preview tools/Check.java`, which runs every JDK 26 feature the
project depends on with no build tool at all.
