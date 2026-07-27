/*
 * Kangaroo — the scripted demo.
 *
 * This drives the real application. It clicks the real buttons, types into the real textarea, and
 * posts to the real /api/assess, so every classification, every jaundice grade, every cry pitch and
 * every benchmark figure a viewer sees was computed by the Java engine while the camera was
 * rolling. Nothing here is a recording or a mock-up of the interface.
 *
 * The one thing it supplies is sensor input. A laptop being screen-recorded has no infant in front
 * of its camera, so the demo synthesises a photograph, a ten-second cry and a chest-motion trace,
 * and hands them to the app through the narrow bridge in app.js. Those fixtures are generated here,
 * in the browser, from arithmetic you can read below — and the analysis performed on them is the
 * product's own, unmodified.
 *
 * Pacing comes from speech/manifest.json, which the voice-over generator writes with each clip's
 * measured duration. The choreography therefore cannot drift out of sync with the narration: edit
 * the script, regenerate, and every beat re-times itself.
 */

const K = window.__kangaroo;
const $ = (sel, root = document) => root.querySelector(sel);

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────────────────────── overlay chrome ─────────────────────────────── */

function buildOverlay() {
  const html = `
    <div id="demo-veil"></div>
    <div id="demo-cursor"></div>
    <div id="demo-live"><span class="dot"></span>LIVE — REAL APPLICATION</div>
    <div id="demo-note"></div>
    <div id="demo-card"></div>
    <div id="demo-captions">
      <div id="demo-caption-text"></div>
      <div id="demo-progress"><span></span></div>
    </div>`;
  const host = document.createElement('div');
  host.id = 'demo-overlay';
  host.innerHTML = html;
  document.body.appendChild(host);
}

function caption(text) { $('#demo-caption-text').textContent = text; }

function progress(fraction) {
  $('#demo-progress > span').style.width = `${Math.min(100, fraction * 100)}%`;
}

async function card(html, ms) {
  const el = $('#demo-card');
  el.innerHTML = html;
  el.classList.add('visible');
  if (ms) {
    await wait(ms);
    el.classList.remove('visible');
    await wait(500);
  }
}

function hideCard() { $('#demo-card').classList.remove('visible'); }

async function note(text, ms = 2600) {
  const el = $('#demo-note');
  el.textContent = text;
  el.classList.add('visible');
  if (ms) {
    await wait(ms);
    el.classList.remove('visible');
  }
}

function hideNote() { $('#demo-note').classList.remove('visible'); }

/* ─────────────────────────────── pointer and spotlight ─────────────────────────────── */

const cursor = () => $('#demo-cursor');

async function moveTo(el, { centre = true } = {}) {
  if (!el) return;
  const r = el.getBoundingClientRect();
  const x = centre ? r.left + r.width / 2 : r.left + 24;
  const y = r.top + r.height / 2;
  const c = cursor();
  c.classList.add('visible');
  c.style.transition = 'opacity .25s ease, left .5s cubic-bezier(.4,0,.2,1), top .5s cubic-bezier(.4,0,.2,1), transform .12s ease';
  c.style.left = `${x}px`;
  c.style.top = `${y}px`;
  await wait(520);
}

async function tap(el, { click = true } = {}) {
  if (!el) return;
  await moveTo(el);
  const c = cursor();
  c.classList.add('tapping');

  const r = el.getBoundingClientRect();
  const ripple = document.createElement('div');
  ripple.className = 'demo-ripple';
  ripple.style.left = `${r.left + r.width / 2}px`;
  ripple.style.top = `${r.top + r.height / 2}px`;
  document.body.appendChild(ripple);
  setTimeout(() => ripple.remove(), 650);

  await wait(120);
  c.classList.remove('tapping');
  if (click) el.click();
  await wait(140);
}

let spotlit = null;

async function spotlight(el, { scroll = true } = {}) {
  clearSpotlight();
  if (!el) return;
  if (scroll) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    await wait(520);
  }
  $('#demo-veil').classList.add('visible');
  el.classList.add('demo-spotlit');
  spotlit = el;
}

function clearSpotlight() {
  if (spotlit) spotlit.classList.remove('demo-spotlit');
  spotlit = null;
  $('#demo-veil').classList.remove('visible');
}

/** Fill one of the form's named fields, quickly, the way a hurried thumb would. */
async function fill(name, value) {
  const el = document.querySelector(`#check-form [name="${name}"]`);
  if (!el) return;
  await type(el, String(value), 55);
}

/** Type into a real field, dispatching the events the app listens for. */
async function type(el, text, msPerChar = 18) {
  await moveTo(el);
  el.focus();
  el.value = '';
  for (const ch of text) {
    el.value += ch;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    await wait(msPerChar);
  }
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

/* ─────────────────────────────── fixtures ───────────────────────────────
 *
 * Sensor input, generated here so the demo needs no binary assets and so anybody can read exactly
 * what the analysis was given. The analysis itself is the product's.
 */

/*
 * Two skin tones, chosen for what the colour pipeline genuinely makes of them.
 *
 * HEALTHY is a pinker tone; the pipeline grades it Kramer zone 0 and finds no jaundice. JAUNDICED
 * is shifted along the blue-yellow axis, which is the direction bilirubin actually moves skin, and
 * the pipeline reads it as extensive. Neither result is scripted — both were measured against this
 * engine, and if the colorimetry changes, the demo's answers change with it.
 */
const SKIN_HEALTHY = [208, 176, 166];
const SKIN_JAUNDICED = [206, 178, 143];

/**
 * A capture shaped the way the colour pipeline expects one: a bright, near-neutral reference card
 * filling the border, and the skin window in the middle. Those are the same proportions
 * ColourPipeline crops to (0.27 to 0.73), so the illuminant estimate and the skin statistics both
 * get the region they are looking for.
 */
function makeCapture({ size = 640, skin = SKIN_HEALTHY, warmth = 1.0 } = {}) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');

  // The card: bright and near-neutral, so the white-reference mask fires.
  ctx.fillStyle = 'rgb(214, 214, 212)';
  ctx.fillRect(0, 0, size, size);

  // A grey ramp along the top edge, which is what the printed card carries.
  const steps = 6;
  for (let i = 0; i < steps; i++) {
    const v = Math.round(238 - i * 26);
    ctx.fillStyle = `rgb(${v},${v},${v - 2})`;
    ctx.fillRect(i * (size / steps), 0, size / steps, size * 0.075);
  }

  // The skin window.
  const lo = Math.round(size * 0.27);
  const hi = Math.round(size * 0.73);
  const w = hi - lo;
  const image = ctx.createImageData(w, w);
  for (let y = 0; y < w; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      // Gentle shading plus grain, so the percentile statistics have a real distribution to work on
      // rather than one repeated value.
      const shade = 1 - 0.10 * (y / w);
      const grain = (Math.random() - 0.5) * 10;
      image.data[i] = Math.min(255, skin[0] * shade * warmth + grain);
      image.data[i + 1] = Math.min(255, skin[1] * shade + grain);
      image.data[i + 2] = Math.min(255, skin[2] * shade / warmth + grain);
      image.data[i + 3] = 255;
    }
  }
  ctx.putImageData(image, lo, lo);

  return canvas.toDataURL('image/jpeg', 0.9);
}

/** Ten seconds of a healthy newborn cry: harmonic bursts around 520 Hz, with breaths between. */
function makeCry({ f0 = 520, seconds = 10, rate = 16000 } = {}) {
  const n = seconds * rate;
  const samples = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / rate;
    let v = (Math.random() - 0.5) * 0.006;          // the room
    if ((t % 1.7) < 1.2) {                           // cry, then a breath
      v += 0.34 * (Math.sin(2 * Math.PI * f0 * t)
                 + 0.5 * Math.sin(4 * Math.PI * f0 * t)
                 + 0.25 * Math.sin(6 * Math.PI * f0 * t)) / 1.75;
    }
    samples[i] = Math.max(-1, Math.min(1, v));
  }
  return `data:audio/wav;base64,${base64(encodeWav(samples, rate))}`;
}

function encodeWav(samples, rate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const ascii = (o, s) => { for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); };
  ascii(0, 'RIFF'); view.setUint32(4, 36 + samples.length * 2, true); ascii(8, 'WAVE');
  ascii(12, 'fmt '); view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  ascii(36, 'data'); view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buffer);
}

function base64(bytes) {
  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

/** A chest-motion trace at a given rate, with the noise a hand-held phone contributes. */
function makeMotion({ rate = 50, seconds = 15, fps = 10 } = {}) {
  const signal = [];
  const hz = rate / 60;
  for (let i = 0; i < seconds * fps; i++) {
    const t = i / fps;
    signal.push(Math.sin(2 * Math.PI * hz * t) + (Math.random() - 0.5) * 0.3);
  }
  return { signal, fps };
}

/* ─────────────────────────────── narration ─────────────────────────────── */

let manifest = null;
let audios = [];

async function loadNarration() {
  const res = await fetch('/demo/speech/manifest.json', { cache: 'no-store' });
  manifest = await res.json();
  audios = manifest.beats.map((b) => {
    const a = new Audio(`/demo/speech/${b.file}`);
    a.preload = 'auto';
    return a;
  });
}

/**
 * Play one beat's narration while its choreography runs, and keep the caption in step with the
 * voice by advancing through the sentences in proportion to the clip's elapsed time.
 *
 * The beat ends when BOTH the audio and the choreography are done, so a slow machine never cuts the
 * voice off and a long clip never runs ahead of the picture.
 */
function playBeat(index, choreography) {
  const audio = audios[index];
  const beat = manifest.beats[index];
  const sentences = beat.text.split(/(?<=[.—])\s+/).filter(Boolean);

  const beatStart = manifest.beats.slice(0, index).reduce((n, b) => n + b.seconds, 0);

  const ended = new Promise((resolve) => {
    const done = () => resolve();
    audio.addEventListener('ended', done, { once: true });
    audio.addEventListener('error', done, { once: true });
  });

  const captionTimer = setInterval(() => {
    if (!audio.duration) return;
    const fraction = audio.currentTime / audio.duration;
    const i = Math.min(sentences.length - 1, Math.floor(fraction * sentences.length));
    caption(sentences[i]);
    progress((beatStart + fraction * beat.seconds) / manifest.total_seconds);
  }, 120);

  caption(sentences[0]);

  try { audio.currentTime = 0; } catch { /* ignore */ }
  audio.play().catch(() => setTimeout(() => audio.dispatchEvent(new Event('ended')), 60));

  return Promise.all([ended, choreography()]).finally(() => clearInterval(captionTimer));
}

/* ─────────────────────────────── the beats ─────────────────────────────── */

const INTAKE_PARENT = 'She has been feeding well and is bright and alert.';
const INTAKE_CHW = 'Mother reports reduced feeding since yesterday. On examination the infant is lethargic.';

async function beat1Problem() {
  await card(`
    <div class="demo-stat">2 300 000</div>
    <div class="demo-stat-sub">newborns die within twenty-eight days of birth, every year.</div>
    <div class="demo-personas">
      <div class="demo-persona">
        <div class="demo-persona-icon">◍</div>
        <div class="demo-persona-label">A parent</div>
        <div class="demo-persona-sub">who has never done this before</div>
      </div>
      <div class="demo-persona">
        <div class="demo-persona-icon">✚</div>
        <div class="demo-persona-label">A health worker</div>
        <div class="demo-persona-sub">two hours from a clinic</div>
      </div>
    </div>`, 0);
  await wait(11500);
  hideCard();
  await wait(600);
  $('#demo-live').classList.add('visible');
}

async function beat2Parent() {
  await tap($('.door[data-mode="parent"]'));
  await wait(400);

  await fill('age_days', 6);
  await fill('weight_kg', '3.4');
  await type($('#intake'), INTAKE_PARENT, 14);
  await wait(200);

  // The real counter, tapped the real number of times. The rate is the tap count, so the window is
  // ended early rather than waiting out sixty seconds of silence.
  const tapBtn = $('#breath-tap');
  await moveTo(tapBtn);
  for (let i = 0; i < 44; i++) { tapBtn.click(); await wait(26); }
  K.finishBreathing();
  await wait(500);

  // A well infant: the pipeline grades this one at Kramer zone 0 and finds nothing.
  K.setCapture('CHEST', makeCapture({ skin: SKIN_HEALTHY }));
  await wait(500);

  await tap($('#assess-btn'));
  await waitForResult();

  await spotlight($('#reasons-card'));
  await wait(2200);
  clearSpotlight();
}

async function beat3HealthWorker() {
  await tap($('#home-btn'));
  await wait(350);
  await tap($('.door[data-mode="chw"]'));
  await wait(300);

  await fill('age_days', 6);
  await fill('weight_kg', '2.9');
  await type($('#intake'), INTAKE_CHW, 11);

  const tapBtn = $('#breath-tap');
  await moveTo(tapBtn);
  for (let i = 0; i < 62; i++) { tapBtn.click(); await wait(17); }
  K.finishBreathing();
  await note('62 breaths a minute — the WHO threshold is 60', 2400);

  K.setCapture('CHEST', makeCapture({ skin: SKIN_JAUNDICED }));
  K.setCapture('FACE', makeCapture({ skin: SKIN_JAUNDICED }));
  K.setCapture('PALMS_SOLES', makeCapture({ skin: SKIN_JAUNDICED, warmth: 1.04 }));
  K.setCry(makeCry(), 10);
  // Deliberately at odds with the tapped 62, because a disagreement is the thing worth showing.
  const motion = makeMotion({ rate: 48 });
  K.setMotion(motion.signal, motion.fps);

  await wait(300);
  await tap($('#assess-btn'));
  await waitForResult();

  await spotlight($('#verdict'));
  await wait(1800);
  clearSpotlight();
  await spotlight($('#referral-card'));
  await wait(1600);
  clearSpotlight();
}

async function beat4Sensors() {
  const jaundice = $('#jaundice-card');
  if (jaundice && !jaundice.hidden) {
    await spotlight(jaundice);
    await wait(4200);
  }

  const cry = $('#cry-card-result');
  if (cry && !cry.hidden) {
    await spotlight(cry);
    await wait(5200);
  }

  const motion = $('#motion-card-result');
  if (motion && !motion.hidden) {
    await spotlight(motion);
    await wait(5600);
  }
  clearSpotlight();
}

async function beat5Safety() {
  await spotlight($('#reasons-card'));
  await note('every finding carries its provenance', 3200);
  await wait(1400);
  clearSpotlight();

  // The action plan is where the dosing instruction appears, and the point of the beat is that the
  // numbers in it came out of a WHO table rather than out of a model.
  await spotlight($('#narrative-card'));
  await note('every number is computed in code, never written by a model', 3400);
  await wait(1800);
  clearSpotlight();
}

async function beat6Java() {
  hideNote();
  await tap($('[data-nav="tech"]'));
  await wait(900);

  const jeps = $('#jep-list');
  if (jeps) {
    await spotlight(jeps);
    await wait(3600);
    clearSpotlight();
  }

  await tap($('#bench-btn'));
  await wait(1200);
  const bench = $('#bench-result');
  if (bench) {
    await spotlight(bench);
    await wait(5200);
    clearSpotlight();
  }
}

async function beat7Close() {
  clearSpotlight();
  hideNote();
  $('#demo-live').classList.remove('visible');
  await card(`
    <div class="demo-card-title">It never goes dark.</div>
    <div class="demo-card-line">One Java 26 process. Zero runtime dependencies.<br>
      A phone you already own, and a sheet of paper.</div>
    <div class="demo-card-line" style="margin-top:26px;opacity:.6;font-size:1rem;">
      github.com/Marc-Dvci/Kangaroo</div>`, 0);
}

/** Wait until the result screen is showing, with a ceiling so a failure cannot hang the film. */
async function waitForResult(timeout = 12000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    if ($('#screen-result')?.classList.contains('active')) {
      await wait(700);   // let the result paint before pointing at it
      return true;
    }
    await wait(120);
  }
  return false;
}

/* ─────────────────────────────── run ─────────────────────────────── */

function gate() {
  return new Promise((resolve) => {
    const el = document.createElement('div');
    el.id = 'demo-gate';
    el.innerHTML = `
      <div class="demo-logo"></div>
      <h1>Kangaroo</h1>
      <p>A ${Math.round(manifest.total_seconds)}-second narrated walk-through — driving the real
         application, computing real answers.</p>
      <button id="demo-start">▶ Start the demo</button>
      <p class="demo-hint">Turn the sound on. Press F11 for full screen before you record.</p>`;
    document.body.appendChild(el);

    $('#demo-start', el).addEventListener('click', async () => {
      // Prime every clip while the user gesture is still in hand: Chrome grants autoplay credit per
      // element, and a clip first played twenty seconds into the film would otherwise be silent.
      for (const a of audios) {
        try { a.muted = true; await a.play(); a.pause(); a.currentTime = 0; } catch { /* best effort */ }
        finally { a.muted = false; }
      }
      el.style.opacity = '0';
      setTimeout(() => el.remove(), 360);
      resolve();
    }, { once: true });
  });
}

const BEATS = [beat1Problem, beat2Parent, beat3HealthWorker, beat4Sensors,
               beat5Safety, beat6Java, beat7Close];

async function run() {
  buildOverlay();
  await loadNarration();
  await gate();

  for (let i = 0; i < BEATS.length && i < audios.length; i++) {
    try {
      await playBeat(i, BEATS[i]);
    } catch (e) {
      // One broken beat must not end the film: the recording is expensive and a gap is recoverable.
      console.error(`[demo] beat ${i + 1} failed`, e);
    }
  }

  progress(1);
  caption('');
}

run();
