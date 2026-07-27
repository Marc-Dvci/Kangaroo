# Clinical safety

What Kangaroo does to be safe, why each choice was made, and what remains unsafe about it.

---

## The one-line version

**The deterministic WHO rule is the floor. Every other component may raise the answer and none of
them may lower it. When anything is unsure, the answer goes up, not down. Every number a health
worker sees comes from arithmetic, never from a model.**

---

## 1. Illegal states are unrepresentable

The clinical domain is modelled with sealed interfaces and records, and every consumer switches
exhaustively with no `default` clause.

```java
public sealed interface Classification
        permits UrgentReferral, TreatmentNeeded, HomeCare { … }
```

Adding a danger sign or a classification kind that some consumer forgets to handle is a **compile
error**. The referral-letter renderer, the offline narrative generator and the traffic-light mapper
all break the build rather than silently omitting a case.

There is deliberately **no "unknown" or "error" classification**. A classification that could not be
computed is not represented at all; the caller receives `UrgentReferral`. *"I don't know" resolves to
"go".*

---

## 2. The model may request a calculation. It may never produce a number.

A language model that writes "give 4 ml" is a language model making up a dose.

Every dose, volume, z-score and date comes from `com.kangaroo.clinical`, from a WHO reference table,
through arithmetic that is a dozen lines long and can be checked by hand against the chart booklet.
The tools are exposed as a **registry with schemas**, so the model is one caller among several and
has no privileged path to the arithmetic. An integrator cannot bypass them, because they are not
reachable only through a prompt.

The ceiling is applied unconditionally and last. Nothing below that line can raise it:

```java
double uncapped = weightKg * med.perKgPerDose();
double dose = Math.min(uncapped, med.maxSingleDose());
assert dose <= med.maxSingleDose();
```

And it is never applied **silently**. A capped dose emits a `DoseCapped` flight-recorder event and is
surfaced in the interface, because a dose that hit a ceiling means the weight, the age or the
medication choice deserves a second look by a person.

**Proved, not asserted.** `DosingPropertyTest` sweeps every medication across its entire admissible
weight range at 1-gram resolution, 200,000 random weights each, both boundaries ±1 ULP, plus zero,
negative zero, subnormals, infinities and NaN. Roughly ten million assertions.

*This found a real bug.* `weight < MIN || weight > MAX` is false for NaN, so a NaN weight passed
validation, propagated through the arithmetic and produced a dose of NaN — a failure in the worst
available way: quietly.

---

## 3. Refusal beats guessing

Three places refuse rather than answer:

| Where | Refuses when | Instead of |
|---|---|---|
| `ZScore` | age outside 0–28 days, or a non-positive weight | extrapolating a growth standard past its published domain |
| `Dosing` | weight outside 0.5–10 kg, or not finite | clamping an implausible weight into range |
| `JaundiceAnalyzer` | too dark, too bright, no card visible, too little skin | grading a photograph it cannot correct |

The last one matters most in practice. **Rejecting a bad capture before inference is worth more
accuracy than any model change**, and the message is phrased as an instruction — "move to better
light, or switch the torch on, and take it again" — rather than an error.

---

## 4. Three heads, one floor

| Head | What it is | What it may do |
|---|---|---|
| WHO rule engine | The chart booklet, written out | **Sets the floor.** Nothing lowers it. |
| Gradient-boosted head | Calibrated, distilled from the rule, abstains | May raise |
| Language model | Whatever rung answered | May raise |

All three read **the same `SignProfile`**, so a disagreement is a genuine disagreement about the same
evidence rather than two components looking at different inputs.

```java
TrafficLight light = ruleOutcome.light()
        .escalatedWith(Abstention.escalate(modelVerdict));
narrativeLight.ifPresent(n -> light = light.escalatedWith(n));
```

### The heads genuinely do disagree

Measured on a swept sample of 20,000 profiles: **1.57% disagreement — 247 under-called, 67
over-called.** The trained head under-calls rare signs (severe dehydration, ten-or-more pustules)
because the corpus it was distilled from contains them at their natural, very low prevalence.

That number is **printed by the test suite on every run** and the build fails if it regresses past
3%. It is not hidden, because it is the most important empirical fact about the shipped model, and
because the architecture was designed around it being true rather than around hoping it is not.
`modelCanOnlyEscalateNeverDeEscalate` proves over 50,000 profiles that the reconciliation contains it.

**A model that talks a health worker out of a referral the protocol called for is the single worst
thing this system could do.** It is structurally prevented.

---

## 5. Abstention

The argmax of `[0.34, 0.33, 0.33]` looks exactly like the argmax of `[0.98, 0.01, 0.01]` by the time
it reaches a screen. So the model returns a *set*, and a non-singleton set escalates to the most
severe class it admits. Uncertainty between amber and red resolves to red; never the reverse.

`Abstention` is explicit about what it is:

- **What ships**: a top-two margin rule. **No coverage guarantee.** `Rule.MARGIN.guaranteed()` returns
  `false` and the interface reports it.
- **What is available**: `Abstention.calibrated(holdout, alpha)` implements genuine split-conformal
  prediction, with the finite-sample correction, for a deployment that has collected a labelled
  holdout from its own population.

Calling a hard-coded threshold "conformal prediction" would be a claim the evidence does not support.

---

## 6. Escalation is asymmetric everywhere

| Situation | Effect |
|---|---|
| Parent mode, borderline green | → amber. A parent has less signal and more at stake. |
| Model abstains | → the worst class in the set |
| Language model disagrees upward | → raised, and flagged for supervisor review |
| Worsening trend across visits | green → amber |
| A pass failed (parent mode) | green → amber |
| Any classification could not be computed | → urgent referral |

Nothing in that table ever moves an answer down.

---

## 7. Never says the baby is fine

A green result is phrased **"Nothing here needs a clinician today"**, and it always ships with the
return-immediately list. `EndToEndTest#greenIsHonestNotReassuring` asserts the narrative contains
neither "your baby is fine" nor "nothing to worry".

The distinction is not pedantry. A photograph and three questions cannot see a great deal of what is
wrong with a newborn, and a caregiver who has been told everything is fine will wait longer before
coming back.

---

## 8. Referral is verified, and "referral not possible" is a real state

A referral that nobody checks is a referral that did not happen — families often cannot reach the
facility, and the commonest reason is transport, not refusal. So a red classification schedules a
**mandatory** next-day visit whose purpose is to find out whether they got there.

If they did not, `Psbi` implements the WHO 2015 simplified outpatient antibiotic regimen. Two hard
conditions are enforced in code: it is only offered **after** urgent referral has been advised and
found impossible, and it is refused outright when convulsions, inability to feed, unconsciousness or
inability to move are present, because those need inpatient care.

Most decision-support tools omit this branch. It is the branch that decides outcomes, because the
practical alternative to it is that the infant receives nothing at all.

---

## 9. The audit trail is a flight recording

Every clinical decision emits a JFR event: `ClinicalDecision`, `DoseCapped`, `ModelDisagreement`,
`Abstention`, `Failover`, `SensorReading`, `NativeInference`, `CaptureRejected`.

- Near-zero overhead, so it is always on, in production, on a Raspberry Pi.
- Opens in JDK Mission Control with no bespoke tooling — a supervisor can be handed a `.jfr` file.
- Replayable: an encounter's events can be pushed through a later build to prove a fix changed the
  outcome, or that a refactor did not.
- Contains no images, no identifiers that leave the device, and no API keys.

---

## 10. Provenance is preserved

A measured respiratory rate of 62 and a reported "she's breathing fast" are not the same evidence.
`DangerSign` is sealed over `Measured`, `Visual`, `Auditory` and `Reported`, and the referral letter
separates the numbers from the reports and states explicitly that reported signs were not verified by
an examiner.

Every extracted finding also carries **the words that caused it**. A classification whose reasons
cannot be traced back to something the caregiver actually said is not auditable, and "the model
thought so" is not a reason a supervisor can act on.

---

## The residual risk register

Every safety argument above is written against a known set of residual risks. They are listed here
with the control that contains each one, because a control nobody can name is a control nobody can
check.

| Residual risk | Control |
|---|---|
| **Not a medical device, not clinically validated.** No prospective study, no regulatory submission. | Stated on every screen and in the referral letter. The output is a classification with its reasons and a referral instruction, never a diagnosis or a prescription. |
| **The colorimetric head is the softest signal**: 55.5% pooled held-out severity accuracy, not stratified by skin tone. | It enters the rule as one sign among many, never decides alone, and refuses to grade a capture it cannot stand behind. Design and evidence status in [fairness.md](fairness.md). |
| **Text extraction reads the phrasings it was written for.** Natural language is open-ended and the extractor is not. | Deliberately biased toward missing a sign rather than inventing one: a missed sign is recovered by the guided captures and the explicit checkboxes, while an invented one poisons every head at once. Three real misses became named regression tests. |
| **The audio pass carries no cry classifier.** | The pass reports the gap as missing evidence rather than as a negative finding. In parent mode a gap escalates. Recordings are stored for a clinician. |
| **Trend logic depends on the caregiver reusing the same subject reference.** | A missed match costs longitudinal context only. The assessment itself is computed from the current encounter and remains correct. |
| **Any decision-support tool can be overridden by the person holding it.** | This is why the output is worded the way it is: a colour, a sentence, a reason for every finding, and a return-immediately list that ships with every green result. |
