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
| 516 AOT Caching | Cold start to first assessment: **325 ms → 281 ms (13%)**, measured | no |
| 522 G1 throughput | The Pod's concurrent-assessment load; `packaging/gc-benchmark.sh` | no |
| 500 Final means final | Runs under `--illegal-final-field-access=deny` | no |
| 504 Applet removal | Clean bill of health — the runtime dependency set is exactly the JDK | no |

Also: **FFM** (the entire native layer, no JNI), **virtual threads** (thread-per-request), **sealed
types and exhaustive switches** (the clinical domain), **JFR** (the audit trail), **JEP 458/512/511**
(`tools/Check.java`, which runs with no build tool at all).

The running application serves this table at `GET /api/jeps`.

### Functionality and stability

Works end to end. 69 tests, all green. Verified live during development:

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

A README a beginner can follow, a full BOM, an architecture document, a clinical-safety document, a
fairness document that states plainly what is **not** known, an i18n document that does not
overclaim, a printable colour card, and a timed demo script.

---

## Honesty

Stated at length in the README and in `docs/`, and worth repeating here:

- Not a medical device, not clinically validated. Decision support implementing published guidance.
- The colorimetric jaundice head is weak (55.5% held-out severity accuracy) and **there is no
  stratified evaluation by skin tone**, so nobody knows whether it is worse on darker skin.
- The trained clinical head disagrees with the WHO rule on **1.57%** of swept profiles, mostly by
  under-calling rare signs. The test suite prints that number on every run, and the architecture
  contains it by construction — proved over 50,000 profiles.
- The Vector API speedup is **1.47× on the kernels and 1.03× end to end**, not 8×. The pipeline is
  memory-bandwidth bound and the end-to-end figure is dominated by an exact percentile sort that
  does not vectorise. Both numbers are reported.
- Interface translations exist for English and French only.
- The cry classifier is not implemented; the pass reports its own absence.

Six real defects were found by the test suite during development and are listed in the README as
named regression tests, including a NaN weight producing a NaN dose and a canonical-serialisation
bug that silently broke every stored signature.

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
