# Fairness across skin tone

## Why this document exists at the front of the docs and not in an appendix

A colorimetric jaundice grader that works on light skin and fails on dark skin is not merely
imperfect. It **fails hardest in exactly the populations with the highest neonatal mortality** —
sub-Saharan Africa and South Asia carry three quarters of the world's neonatal deaths — which means
the failure mode is precisely aligned with the need.

This is not a hypothetical risk in this class of tool. Pulse oximeters systematically overestimate
oxygen saturation in patients with darker skin, and it took decades and a pandemic for that to be
widely acknowledged. Smartphone bilirubinometry has the same structural vulnerability, for the same
reason: melanin absorbs across the visible spectrum, and a naive measurement of "how yellow is this
skin" measures pigment and bilirubin together without distinguishing them.

So this page states what Kangaroo does about it, and exactly how far the evidence for that goes.

---

## What the design does about it

### 1. The card, and dividing out the illuminant

Bilirubin shifts skin along the blue-yellow axis. So does a paraffin lamp. So does a phone's
auto white balance. Without a known reference in the frame, the three are not separable, and the
grader is measuring the lighting.

The printed colour-reference card gives every photograph a known white and a grey ramp in the same
frame under the same light. The pipeline estimates the illuminant from the bright near-neutral card
pixels and divides it out before any skin statistic is computed. The gains it applied are kept as
features in their own right, because the illuminant a photograph was taken under is itself evidence
about how far to trust the rest of it.

This does not correct for pigment. It corrects for lighting, which is the confound that would
otherwise be mistaken for pigment.

### 2. Extent rather than intensity

The strongest thing in the design is that the clinically decisive variable is **not** "how yellow".
It is **how far down the body the yellow has reached** — the cephalocaudal progression the Kramer
zones describe. Jaundice starts at the head and moves downward as bilirubin rises.

Extent is a *relative* measurement: it compares regions of the same infant, in the same frame, under
the same light, on the same skin. Almost all of the pigment term cancels. A grader that asks "is the
trunk yellower than the head, and are the soles yellower still?" is far less pigment-dependent than
one that asks "how yellow is this patch?".

Kangaroo's `highestZone` therefore requires both an absolute threshold **and** a margin over the head
zone, because a warm illuminant raises b\* everywhere at once and a purely absolute test would read
that as whole-body jaundice.

### 3. Palms, soles and sclera

The guided capture sequence includes **palms and soles** specifically because those sites are far
less pigment-variable than the forehead or chest, across the whole Fitzpatrick range. The face
capture is framed to include the sclera for the same reason. These are the sites a clinician uses
when assessing jaundice in a darkly pigmented infant, and the capture coaching asks for them rather
than treating them as optional extras.

### 4. Refusal

The grader declines to produce a number when it cannot stand behind one: too dark, too bright, no
reference card visible, or too little skin in the window. **Abstention is a feature.** A tool that
always answers is a tool that is confidently wrong in exactly the conditions where it is least
reliable — a dim hut, at night, with a phone torch.

### 5. It never decides alone

The colorimetric head contributes to a danger-sign profile. It does not produce the traffic light.
The deterministic WHO rule is the floor, and jaundice extent enters that rule alongside age, feeding
and every other sign. A wrong colour reading degrades one input among many rather than flipping the
answer.

---

## Where the evidence stops, and the study that would extend it

The five design decisions above are what makes the pipeline robust across skin tone. They are not, on
their own, a *measurement* of that robustness, and this project draws the line between the two
explicitly rather than letting the design stand in for the number.

### The stratified evaluation is the next piece of work, not a shipped result

The colorimetric head reports a **held-out severity accuracy of 55.5%** and a macro-F1 of 0.483
across five severity grades. That figure is pooled, not stratified by skin tone: the evaluation set
it came from does not carry reliable skin-tone labels, and inferring them would produce a stratified
number that looks rigorous and is not.

This is precisely why the head is wired into the product the way it is — as one sign among many,
under a deterministic floor, with a refusal path — rather than as a grader trusted on its own output.
The architecture is designed to be correct under the assumption that this number is unknown per
stratum, which is the assumption that actually holds.

### What a stratified answer requires

- An evaluation set with Fitzpatrick or Monk-scale labels assigned by trained raters, not inferred.
- Enough cases in types V and VI to give a stratified confidence interval that means anything —
  which, for a five-class problem, is hundreds per stratum, not dozens.
- Paired serum bilirubin as ground truth, not a clinician's visual estimate, because visual estimation
  is itself the thing that fails on darker skin.
- Per-tone calibration fitted on held-out data, and re-validated.
- Reporting that includes the strata where it does **not** work, with the same prominence as the ones
  where it does.

That is a clinical study rather than an engineering task, and it is the correct next step for this
work.

### In the meantime

Kangaroo treats the colorimetric head as **evidence that can raise concern and never lower it**,
and the product says so on the screen. The Kramer-zone reading is presented as extent with the
sentence "the extent matters more than the shade", the refusal path is prominent, and the deterministic
WHO rule — which depends on reported and measured signs, not on colour — remains the floor under every
answer.

---

## The same question, for the other head

The clinical danger-sign head has a different fairness problem, and it is measured rather than
assumed: it was distilled from a synthetic corpus, and it **under-calls rare danger signs** —
severe dehydration and ten-or-more pustules in particular — because the corpus contains them at their
natural, very low prevalence.

The measured disagreement with the WHO rule on a swept sample is **1.57%** (247 under-called, 67
over-called out of 20,000). The test suite prints that number on every run and fails the build if it
regresses past 3%. The architecture contains it by construction: the deterministic rule is the floor
and the model may only escalate, which
[`modelCanOnlyEscalateNeverDeEscalate`](../src/test/java/com/kangaroo/ImnciConformanceTest.java)
proves over 50,000 profiles.

---

## Reading list

- Sjoding et al., *Racial bias in pulse oximetry measurement*, NEJM 2020 — the canonical example of
  this exact failure mode reaching the clinic and staying there.
- Taylor et al., *BiliCam: using mobile phones to monitor newborn jaundice*, UbiComp 2014 — the
  card-based smartphone bilirubinometry approach this pipeline follows.
- Groos et al., *Fitzpatrick 17k* and Google Research's *SCIN* dataset — skin-tone-labelled corpora
  that a stratified evaluation would build on.
- WHO, *Integrated Management of Newborn and Childhood Illness chart booklet* — the protocol, which
  is deliberately built around signs that do not depend on pigment.
