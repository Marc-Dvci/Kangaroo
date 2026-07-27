# Kangaroo — automated demo narration

The source of truth for the voice-over. `generate-voiceover.py` reads **this file** and produces one
MP3 per beat into `src/main/resources/web/demo/speech/`, plus a manifest holding each clip's
measured duration. The demo driver paces itself from that manifest, so the script and the audio and
the choreography cannot drift apart: change a line here, re-run the generator, and the demo says the
new line and waits exactly as long as the new line takes.

**Target: 110 seconds.** The contest asks for 90–120, and the generator fails loudly outside that.

The chosen voice delivers about **135 words per minute** at `-8%`, so a beat's word budget is
roughly `2.25 × seconds`. The generator prints the measured drift per beat; trust that over any
word count written here.

Every claim is checked against `docs/clinical-safety.md` and `docs/fairness.md`. It does not say
*validated*, *accurate*, or *diagnoses*, and it gives no accuracy figure for the jaundice grader. If
you edit a line, check it against those two documents first.

---

## Beat 1 · problem · ~15 s

> Two point three million newborns die within a month of birth. Almost all of it preventable. The
> bottleneck is not medicine — it is noticing in time. And the people positioned to notice are
> offline.

## Beat 2 · parent · ~16 s

> This is Kangaroo — one Java process, and everything here is the real application computing live.
> Parent mode: plain words, a tap-to-count for breathing, one photograph. And note what it never
> says: it never says the baby is fine.

## Beat 3 · health worker · ~17 s

> Same engine, different front door. The full W H O danger-sign assessment. Sixty-two breaths a
> minute — the threshold is sixty. So, a referral. And the letter is already written, because a
> health worker with no signal cannot come back for it.

## Beat 4 · the sensors · ~18 s

> Three sensors, all already in the phone. The camera grades jaundice by extent. The microphone
> measures the cry, its pitch computed on the device. And the camera counts breathing a second time
> — when the two disagree, that is what gets reported.

## Beat 5 · the safety argument · ~15 s

> Every finding says where it came from. Measured, or reported — not the same evidence. And no model
> writes a number. The milligrams come from a W H O table, through arithmetic you can check by hand.

## Beat 6 · Java 26 · ~17 s

> All of it is Java 26. Ten J E Ps, and the application serves that table itself. Structured
> concurrency, lazy constants, the Vector API — that benchmark is measuring this machine, right now.

## Beat 7 · close · ~12 s

> A phone you already own. A sheet of paper. It works with the cable pulled out, it never goes dark
> — and it is all Java.
