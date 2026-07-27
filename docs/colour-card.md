# The colour-reference card

A sheet of paper that turns a phone camera into a crude transcutaneous bilirubinometer.

Print [`colour-card.svg`](colour-card.svg) on any inkjet or laser printer, on plain matte A4 or
Letter paper. Cut out the window in the middle. That is the whole build.

---

## What it is for

A phone camera does not measure colour. It measures colour *under whatever light happens to be
there*, after whatever automatic white balance the manufacturer's firmware decided on. A daylight
window, a paraffin lamp and a fluorescent tube produce three visibly different photographs of the
same infant, and the difference between them is larger than the difference jaundice makes.

The card puts a **known reference in the same frame, under the same light**. The pipeline finds the
bright near-neutral patches, works out what white should have been, and divides the illuminant out
before computing a single skin statistic.

Without the card, a jaundice reading is a reading of the lighting.

---

## What is on it

| Element | Purpose |
|---|---|
| **White border** | The primary white reference. The largest, most reliable neutral area. |
| **Grey ramp** (5 steps, 20%–80%) | Lets the pipeline check the camera's tone response, not just its white point. A camera that crushes shadows shows up here. |
| **Cut-out window** | The skin goes here, surrounded by the reference, at the same depth and under the same light. |
| **Skin-tone reference strip** | Six patches spanning the Fitzpatrick range, so a reviewer can see by eye how the capture is rendering pigment. |
| **Yellow reference patches** (3 steps) | A visual aid for a health worker, and a sanity check that the frame's yellow axis has not been clipped. |
| **Alignment corners** | Give the capture-quality gate something to find. |

---

## How to use it

1. **Find the best light you have.** Daylight near a window, indirect, is ideal. Direct sun blows out
   the highlights; a single bulb overhead casts hard shadows.
2. **Hold the card flat against the baby's skin** — chest or forehead — so the skin fills the cut-out
   window and the card lies in the same plane.
3. **Fill the frame with the card.** All four corners visible.
4. **Do not use the flash.** It creates a specular highlight that destroys the white reference. Use
   the torch only in a genuinely dark room, and hold it off to one side.
5. **Take the photograph straight on**, not at an angle.

If the frame is not usable, Kangaroo says so — "too dark, take it again", "the card is not visible
enough to correct the colour" — rather than grading it anyway.

---

## Checking your printer

Printers vary enormously and a badly printed card is worse than none, because it looks fine.

**The test:** print the card, then photograph it on its own in good daylight with nothing else in
frame. Open `/api/bench`… no — open the *Under the hood* screen and check:

1. **The white border must read as white**, not cream, not blue-grey. Hold it next to a sheet of
   plain office paper: if the border is visibly warmer or cooler, your printer is laying down ink on
   it. Reprint with "no background printing" or on a printer whose white is the paper.
2. **The five grey steps must be distinguishable** from each other, all five. If the darkest two
   merge, your printer is clipping shadows and the tone response is unreliable.
3. **Matte, not glossy.** Glossy paper produces specular reflections that the white-balance mask
   reads as blown-out highlights.

If your printer fails these, the card still works for the **extent** measurement — Kramer zones
compare regions of the infant against each other and barely use the card — but the absolute
colorimetry will be poor. That is a reason to trust the zone reading over the severity grade, which
is what the interface tells the user to do anyway.

---

## Replacing it

Cards get dirty, wet and creased in a bag. A dirty card is a bad white reference. Reprint them; they
cost about five pence. Plan for one per health worker per month in the field.

---

## What it does not do

It does not make this a calibrated instrument. It removes the single largest confound — the
illuminant — from a measurement that remains a **crude** estimate feeding a decision-support tool.
Serum bilirubin is measured by a laboratory. See [fairness.md](fairness.md) for what is and is not
known about how well this works across skin tones.
