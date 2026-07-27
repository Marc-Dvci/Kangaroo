# Demo script — 110 seconds

The contest asks for 90–120 seconds. This is timed at **1:50** with about eight seconds of slack.

Everything below is filmed **live**, on the real application, with no simulated mode and no cuts
inside a shot. The failover ladder in beat 5 is the one thing that cannot be faked, and it is the
reason to film it rather than animate it.

---

## Before you record

```bash
./mvnw package
./packaging/aot.sh                 # so the cold-start number in beat 5 is real
./packaging/jlink.sh               # optional, if you want the size figure on screen
```

Have ready:

- A laptop, a phone on the same Wi-Fi, and a printed colour card.
- A doll or a mannequin. **Do not film a real infant.** The clinical content is real; the subject
  should not be a person who cannot consent.
- A network cable you can physically unplug, or airplane mode on the laptop — visible on camera.
- A terminal, large font, dark background.

Start the server bound to the LAN so the phone can reach it:

```bash
./packaging/run.sh --lan
```

---

## The beats

### 0:00–0:12 — the problem, and the two people who face it

**On screen.** A phone in a dark room, 3 a.m. Then a health worker walking a dirt road.

**Voiceover.**
> Two point three million newborns die within a month of birth. Almost all of it is preventable, and
> the bottleneck is not medicine — it's noticing in time. The two people positioned to notice are a
> parent who has never done this before, and a health worker two hours from a clinic. Both of them
> are offline.

---

### 0:12–0:32 — parent mode

**On screen.** The phone. Tap *I am a parent*. Type a few words — or tap two of the quick chips.
Tap the breathing counter in rhythm; the number climbs. Take one photograph with the colour card
held against the doll's chest. Tap *Check this baby*. The amber result appears.

**Voiceover.**
> Parent mode is a two-minute check. Plain words, a tap-to-count for breathing, one photograph
> against a printed card. And then a colour, and a sentence.
>
> Notice what it does not say. It never says the baby is fine. It says nothing here needs a clinician
> today — and it always shows the signs that mean going straight away.

**Hold on** the return-immediately list for a full second.

---

### 0:32–0:52 — health-worker mode

**On screen.** The laptop. *I am a health worker*. Intake text already filled. Seven capture tiles.
Respiratory rate 62 entered. Submit. **Red.** Scroll through the findings — each one shows its
provenance — then the referral letter.

**Voiceover.**
> The same engine, a different front door. The full WHO danger-sign assessment: seven guided
> captures, a counted respiratory rate, twenty-one checks.
>
> Sixty-two breaths a minute. The WHO threshold is sixty, so this is a referral — and the letter is
> already written, because a health worker with no signal can't come back for it later.
>
> Every finding says where it came from. Measured, or reported. Those are not the same evidence.

---

### 0:52–1:12 — the Java 26 reveal, and the ladder

**This is the beat that has to be live.**

**On screen.** Split: the browser on the left, a terminal on the right tailing the log.

1. Submit an assessment with the network up. The badge reads the cloud rung.
2. **Physically pull the cable / switch on airplane mode, on camera.**
3. Submit the same assessment again. It takes a moment. The badge changes to
   **offline · on this device**. The traffic light is *identical*.
4. Cut to *Under the hood*. Tap **Run the benchmark**. The bars fill in.

**Voiceover.**
> This is all Java 26. One process, no sidecars, no Python, no interpreter.
>
> Watch what happens when I pull the network out.
>
> *(pause — let the badge change on screen)*
>
> Same baby, same answer. What changed is who wrote the explanation, not what the answer was. That
> invariant is the whole safety argument: the deterministic WHO rule is the floor, and nothing
> — no model, no cloud — is allowed to lower it.
>
> The colour pipeline runs on the Vector API. This is measuring it, on this machine, right now.

---

### 1:12–1:26 — the parts that are usually C++ or Python

**On screen.** The terminal. Run:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -cp target/kangaroo.jar com.kangaroo.app.NativeCheck model.gguf mmproj.gguf photo.jpg
```

Let the output scroll: struct sizes verified, model loaded, tokens generating, the image encoded.

**Voiceover.**
> A seven-billion-parameter vision model, running inside the same Java process. No JNI. No
> subprocess. No model server. The Foreign Function and Memory API calls libllama directly, and a
> captured frame goes to the vision projector by address — decoded once, copied never.
>
> The gradient-boosted classifier is Java too, and it's proven bit-exact against the original across
> twenty-four thousand adversarial vectors.

---

### 1:26–1:40 — the audit trail

**On screen.** JDK Mission Control, opening `~/.kangaroo/audit.jfr`. Filter to the Kangaroo events.
Point at a `ClinicalDecision` row, then a `DoseCapped` row.

**Voiceover.**
> Every clinical decision is a flight-recorder event. Free enough to leave on permanently, on a
> Raspberry Pi, and openable by a supervisor with no special tooling.
>
> That row is a dose that hit its WHO ceiling. The model can ask for a calculation. It can never
> write the number.

---

### 1:40–1:50 — close

**On screen.** The phone, the laptop, the printed card, and a Raspberry Pi in a lunchbox, side by
side on a table. Then the green result screen.

**Voiceover.**
> A phone you already own. A sheet of paper. Nothing else required.
>
> It works with the cable pulled out. It never goes dark. And it's all Java.

---

## Filming notes

- **Do not cut inside beat 5.** The whole point is that the network is really being pulled. A cut
  there is the one thing that would make a viewer doubt everything else.
- **Let the pauses land.** The badge change and the identical traffic light need a beat of silence.
  Talking over them is what makes a demo feel like a pitch.
- The benchmark takes a second or two. Do not speed it up; the wait is evidence that it is real.
- Shoot the phone screen with a second camera or a phone-mirroring window, not a screen recording —
  a hand holding a phone reads as a field tool, a screen recording reads as a slide.
- If the model load in beat 6 is slow on your machine, start that process before the shot and film
  the generation, not the load.

## What not to claim

The voiceover above says nothing the repository cannot back up. In particular it does **not** say
"validated", "accurate", "diagnoses", or give any accuracy percentage for the jaundice grader. If a
line gets added in the edit, check it against [clinical-safety.md](clinical-safety.md) and
[fairness.md](fairness.md) first.
