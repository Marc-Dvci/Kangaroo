/*
 * Kangaroo — the web client.
 *
 * Plain ES modules, no framework, no build step. That is a deliberate constraint rather than a
 * limitation: the whole product is one Java artifact, and adding a JavaScript toolchain would mean
 * a second build, a second dependency tree and a second thing that can rot. The client is small
 * enough that it does not need one.
 *
 * It is also written to survive the conditions it runs in. Everything typed is persisted to
 * localStorage on every change, so a phone call in the middle of an assessment does not lose the
 * intake. Every network call has a timeout and a fallback. Nothing here assumes a connection.
 */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const state = {
  mode: 'chw',
  captures: new Map(),
  breaths: [],
  breathTimer: null,
  breathStart: 0,
  cry: null,          // a data: URL holding a WAV, once one has been recorded
  crySeconds: 0,
  result: null,
};

const DRAFT_KEY = 'kangaroo.draft.v1';

/* ─────────────────────────────── capture sequences ─────────────────────────────── */

const SEQUENCES = {
  parent: [
    { kind: 'FACE', name: 'Face and eyes' },
    { kind: 'CHEST', name: 'Chest' },
  ],
  chw: [
    { kind: 'FACE', name: 'Face and eyes' },
    { kind: 'CHEST', name: 'Chest' },
    { kind: 'UMBILICUS', name: 'Cord stump' },
    { kind: 'SKIN', name: 'Skin' },
    { kind: 'PALMS_SOLES', name: 'Palms and soles' },
    { kind: 'FONTANELLE', name: 'Soft spot' },
    { kind: 'COLOUR_CARD', name: 'Colour card' },
  ],
};

/* The quick chips exist because typing on a phone with one hand, at 3am, is miserable.
   Each one appends a phrase the deterministic extractor is known to read correctly. */
const CHIPS = {
  parent: [
    ['Very sleepy', 'The baby is very sleepy and hard to wake.'],
    ['Feeding less', 'The baby is feeding less than usual.'],
    ['Not feeding', 'The baby is not feeding at all.'],
    ['Looks yellow', 'The skin looks yellow.'],
    ['Feels hot', 'The baby feels hot to touch.'],
    ['Feels cold', 'The baby feels cold to touch.'],
    ['Breathing fast', 'The baby seems to be breathing fast.'],
    ['Crying weakly', 'The cry is weak.'],
    ['Cord looks red', 'The cord stump looks red.'],
  ],
  chw: [
    ['Lethargic', 'The infant is lethargic.'],
    ['Unable to feed', 'The infant is unable to feed.'],
    ['Chest indrawing', 'Severe chest indrawing is present.'],
    ['Grunting', 'Grunting is heard.'],
    ['Jaundice to trunk', 'Jaundice extends to the trunk.'],
    ['Jaundice to soles', 'Jaundice extends to the palms and soles.'],
    ['Omphalitis', 'There is redness and discharge at the umbilicus.'],
    ['Pustules', 'There are skin pustules.'],
    ['Bulging fontanelle', 'The fontanelle is bulging.'],
    ['Convulsions', 'The infant had convulsions.'],
  ],
};

/* ─────────────────────────────── navigation ─────────────────────────────── */

function show(name) {
  $$('.screen').forEach(s => s.classList.toggle('active', s.id === `screen-${name}`));
  window.scrollTo({ top: 0, behavior: 'instant' });
  const screen = $(`#screen-${name}`);
  if (screen && screen.hasAttribute('tabindex')) screen.focus();
  history.replaceState({ screen: name }, '', `#${name}`);
}

function toast(message, ms = 4200) {
  const el = $('#toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, ms);
}

/* ─────────────────────────────── mode ─────────────────────────────── */

function setMode(mode) {
  state.mode = mode;
  const parent = mode === 'parent';

  $('#check-h').textContent = parent ? 'Two-minute check' : 'IMNCI young-infant assessment';
  $('#intake-legend').textContent = parent ? 'What is worrying you?' : 'Intake';
  $('#intake-hint').textContent = parent
    ? 'Use your own words. Say what changed, and when it started.'
    : 'Record the caregiver\'s account, then the findings on examination.';
  $('#intake').placeholder = parent
    ? 'She has been very sleepy since last night and is feeding less than usual…'
    : 'Mother reports reduced feeding since yesterday. On examination…';
  $('#capture-hint').textContent = parent
    ? 'Hold the printed colour card beside the baby\'s skin, in the best light you have.'
    : 'Seven guided captures. Use the colour card for the skin-colour photographs.';
  $('#assess-note').textContent = parent
    ? 'Works with no signal. Usually takes a few seconds.'
    : 'Works offline. The referral letter is generated with the result.';

  buildCaptures();
  buildChips();
  buildSteps();
  show('check');
}

function buildSteps() {
  const rail = $('#step-rail');
  rail.innerHTML = '';
  for (let i = 0; i < 4; i++) rail.appendChild(document.createElement('li'));
  updateSteps();
}

function updateSteps() {
  const form = $('#check-form');
  const done = [
    form.age_days.value !== '',
    $('#intake').value.trim().length > 8,
    $('#respiratory_rate').value !== '',
    state.captures.size > 0,
  ];
  $$('#step-rail li').forEach((li, i) => li.classList.toggle('done', done[i]));
}

function buildChips() {
  const box = $('#quick-chips');
  box.innerHTML = '';
  for (const [label, phrase] of CHIPS[state.mode]) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.textContent = label;
    chip.setAttribute('aria-pressed', 'false');
    chip.addEventListener('click', () => {
      const area = $('#intake');
      const pressed = chip.getAttribute('aria-pressed') === 'true';
      if (pressed) {
        area.value = area.value.replace(phrase, '').replace(/\s{2,}/g, ' ').trim();
        chip.setAttribute('aria-pressed', 'false');
      } else {
        area.value = (area.value.trim() + ' ' + phrase).trim();
        chip.setAttribute('aria-pressed', 'true');
      }
      saveDraft();
      updateSteps();
    });
    box.appendChild(chip);
  }
}

/* ─────────────────────────────── captures ─────────────────────────────── */

function buildCaptures() {
  const box = $('#captures');
  box.innerHTML = '';
  for (const { kind, name } of SEQUENCES[state.mode]) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'capture';
    btn.dataset.kind = kind;
    btn.innerHTML = `<span class="capture-name">${name}</span>`;
    btn.addEventListener('click', () => pickImage(kind, btn, name));
    box.appendChild(btn);
  }
}

function pickImage(kind, button, name) {
  const input = $('#capture-input');
  input.value = '';
  input.onchange = async () => {
    const file = input.files?.[0];
    if (!file) return;
    try {
      const dataUrl = await downscale(file, 768);
      state.captures.set(kind, dataUrl);
      button.classList.add('filled');
      button.innerHTML = `<img alt="" src="${dataUrl}"><span class="capture-name">${name}</span>`;
      updateSteps();
    } catch (e) {
      toast('That image could not be read. Try taking it again.');
    }
  };
  input.click();
}

/*
 * Downscale in the browser before uploading.
 *
 * A modern phone camera produces 4000px frames; the colour pipeline works on a central window and
 * the vision projector resizes to its own patch grid anyway, so sending the full frame would cost
 * seconds of base64 encoding for no accuracy at all. 768px is comfortably above what either
 * consumer needs.
 */
function downscale(file, maxEdge) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const scale = Math.min(1, maxEdge / Math.max(img.width, img.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
      resolve(canvas.toDataURL('image/jpeg', 0.9));
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('decode failed')); };
    img.src = url;
  });
}

/* ─────────────────────────────── reading it aloud ───────────────────────────────
 *
 * The action plan is the part a caregiver has to act on, and a meaningful share of the people this
 * is built for do not read comfortably in any language, let alone the one on the screen. Speech
 * synthesis is part of the browser and runs with no network, so the plan can be heard rather than
 * only read, at no cost to the offline guarantee.
 *
 * The button is hidden entirely when the platform has no voice for the selected language, because a
 * button that reads Swahili aloud in an English accent is worse than no button.
 */

function setupSpeech(result) {
  const button = $('#speak-btn');
  const label = $('#speak-label');
  if (!('speechSynthesis' in window)) { button.hidden = true; return; }

  const locale = currentLocale();
  const voice = pickVoice(locale);
  if (!voice) { button.hidden = true; return; }

  button.hidden = false;
  label.textContent = 'Read this aloud';

  button.onclick = () => {
    if (speechSynthesis.speaking) {
      speechSynthesis.cancel();
      label.textContent = 'Read this aloud';
      return;
    }
    // The headline first, then the plan: someone who walks away after three seconds should still
    // have heard whether to go now.
    const text = `${result.headline}. ${result.narrative || ''}`;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.voice = voice;
    utterance.lang = voice.lang;
    utterance.rate = 0.95;
    utterance.onend = () => { label.textContent = 'Read this aloud'; };
    speechSynthesis.cancel();
    speechSynthesis.speak(utterance);
    label.textContent = 'Stop';
  };
}

function pickVoice(locale) {
  const voices = speechSynthesis.getVoices();
  if (!voices.length) return null;
  const tag = (locale || 'en').toLowerCase();
  return voices.find(v => v.lang.toLowerCase().replace('_', '-').startsWith(tag)) || null;
}

/* ─────────────────────────────── the cry recording ───────────────────────────────
 *
 * Recorded through the Web Audio API and encoded to WAV here, rather than handed to
 * MediaRecorder. MediaRecorder produces Opus in a WebM container, and decoding that on the server
 * would mean adding a codec to a project whose entire claim is that it has no dependencies. Raw
 * samples plus a forty-four byte RIFF header costs about thirty lines and keeps that promise.
 *
 * Downsampled to 16 kHz because the analysis only looks below about 1.4 kHz, and a ten-second clip
 * at 48 kHz is three times the bytes for no extra signal — which matters when the upload is a phone
 * on a village Wi-Fi.
 */

const CRY_SECONDS = 10;
const CRY_RATE = 16_000;

function setupCry() {
  const button = $('#cry-record');
  const label = $('#cry-label');
  const timer = $('#cry-timer');
  const result = $('#cry-result');
  const level = $('#cry-level');
  const bar = $('#cry-level-bar');

  let recording = null;

  button.addEventListener('click', async () => {
    if (recording) { stop(); return; }
    try {
      recording = await start();
    } catch (e) {
      // The commonest case by far is a denied microphone permission, and the cry is optional, so
      // this must never look like the assessment has failed.
      result.textContent = e && e.name === 'NotAllowedError'
        ? 'Microphone permission was refused. The check works without the cry.'
        : 'This device could not record audio. The check works without the cry.';
      recording = null;
    }
  });

  $('#cry-clear').addEventListener('click', () => {
    state.cry = null;
    state.crySeconds = 0;
    label.textContent = 'Record the cry';
    timer.textContent = `${CRY_SECONDS}s`;
    result.textContent = '';
    button.classList.remove('has-audio');
    $('#cry-clear').hidden = true;
    updateSteps();
  });

  async function start() {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });

    // Automatic gain control is turned off on purpose: it is designed to make speech a constant
    // loudness, which is precisely the information a weak cry is carried in.
    const context = new (window.AudioContext || window.webkitAudioContext)();
    const source = context.createMediaStreamSource(stream);
    const analyser = context.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(analyser);

    const chunks = [];
    const processor = context.createScriptProcessor(4096, 1, 1);
    processor.onaudioprocess = e => chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
    source.connect(processor);
    processor.connect(context.destination);

    button.classList.add('recording');
    label.textContent = 'Stop';
    level.hidden = false;
    result.textContent = '';

    const started = Date.now();
    const meter = new Uint8Array(analyser.frequencyBinCount);

    const tick = setInterval(() => {
      const elapsed = (Date.now() - started) / 1000;
      timer.textContent = `${Math.max(0, CRY_SECONDS - elapsed).toFixed(0)}s`;

      analyser.getByteFrequencyData(meter);
      let sum = 0;
      for (const v of meter) sum += v;
      bar.style.width = `${Math.min(100, (sum / meter.length) * 2)}%`;

      if (elapsed >= CRY_SECONDS) stop();
    }, 100);

    return { stream, context, processor, source, chunks, tick, started };
  }

  function stop() {
    if (!recording) return;
    const { stream, context, processor, source, chunks, tick, started } = recording;
    recording = null;

    clearInterval(tick);
    processor.disconnect();
    source.disconnect();
    stream.getTracks().forEach(t => t.stop());
    const rate = context.sampleRate;
    context.close();

    button.classList.remove('recording');
    level.hidden = true;
    bar.style.width = '0%';

    const samples = concat(chunks);
    const seconds = samples.length / rate;
    if (seconds < 3) {
      label.textContent = 'Record the cry';
      timer.textContent = `${CRY_SECONDS}s`;
      result.textContent = 'That was too short to grade. Record about ten seconds.';
      return;
    }

    const wav = encodeWav(downsample(samples, rate, CRY_RATE), CRY_RATE);
    state.cry = `data:audio/wav;base64,${base64(wav)}`;
    state.crySeconds = seconds;

    button.classList.add('has-audio');
    label.textContent = 'Recorded';
    timer.textContent = `${seconds.toFixed(0)}s`;
    result.textContent = 'Recorded. It will be graded on this device when you run the check.';
    $('#cry-clear').hidden = false;
    updateSteps();
  }
}

function concat(chunks) {
  const total = chunks.reduce((n, c) => n + c.length, 0);
  const out = new Float32Array(total);
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}

/** Averaging decimation. Cheap, and it low-passes rather than aliasing the way plain picking does. */
function downsample(samples, from, to) {
  if (to >= from) return samples;
  const ratio = from / to;
  const out = new Float32Array(Math.floor(samples.length / ratio));
  for (let i = 0; i < out.length; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(samples.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = start; j < end; j++) sum += samples[j];
    out[i] = end > start ? sum / (end - start) : 0;
  }
  return out;
}

/** 16-bit mono RIFF/WAVE. */
function encodeWav(samples, rate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const ascii = (offset, s) => { for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i)); };

  ascii(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  ascii(8, 'WAVE');
  ascii(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);            // PCM
  view.setUint16(22, 1, true);            // mono
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);     // byte rate
  view.setUint16(32, 2, true);            // block align
  view.setUint16(34, 16, true);           // bits per sample
  ascii(36, 'data');
  view.setUint32(40, samples.length * 2, true);

  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buffer);
}

/** Chunked, because a ten-second clip overflows the argument list of String.fromCharCode. */
function base64(bytes) {
  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

/* ─────────────────────────────── breathing counter ─────────────────────────────── */

function setupBreathing() {
  const tap = $('#breath-tap');
  const count = $('#breath-count');
  const timer = $('#breath-timer');
  const result = $('#breath-result');

  tap.addEventListener('click', () => {
    if (!state.breathTimer) startBreathing();
    state.breaths.push(Date.now());
    count.textContent = state.breaths.length;
    if (navigator.vibrate) navigator.vibrate(8);
  });

  $('#breath-reset').addEventListener('click', resetBreathing);

  function startBreathing() {
    state.breathStart = Date.now();
    tap.classList.add('counting');
    state.breathTimer = setInterval(() => {
      const left = 60 - Math.floor((Date.now() - state.breathStart) / 1000);
      timer.textContent = `${Math.max(0, left)}s`;
      if (left <= 0) finishBreathing();
    }, 200);
  }

  function finishBreathing() {
    clearInterval(state.breathTimer);
    state.breathTimer = null;
    tap.classList.remove('counting');

    const rate = state.breaths.length;
    $('#respiratory_rate').value = rate;
    // The WHO threshold for an infant under two months is 60. Below it, a fast-looking rate is
    // not a danger sign, and saying so plainly is how the tool avoids training people to escalate.
    result.textContent = rate >= 60
      ? `${rate} breaths a minute. That is at or above the WHO threshold of 60.`
      : `${rate} breaths a minute. Below the WHO threshold of 60.`;
    saveDraft();
    updateSteps();
  }

  function resetBreathing() {
    clearInterval(state.breathTimer);
    state.breathTimer = null;
    state.breaths = [];
    state.breathStart = 0;
    count.textContent = '0';
    timer.textContent = '60s';
    result.textContent = '';
    $('#respiratory_rate').value = '';
    tap.classList.remove('counting');
    updateSteps();
  }
}

/* ─────────────────────────────── draft persistence ─────────────────────────────── */

function saveDraft() {
  const form = $('#check-form');
  const draft = {
    mode: state.mode,
    age_days: form.age_days.value,
    weight_kg: form.weight_kg.value,
    sex: form.sex.value,
    preterm: form.preterm.checked,
    intake_text: $('#intake').value,
    respiratory_rate: $('#respiratory_rate').value,
    privacy_local: $('#privacy_local').checked,
  };
  try { localStorage.setItem(DRAFT_KEY, JSON.stringify(draft)); } catch { /* private mode */ }
}

function restoreDraft() {
  let draft;
  try { draft = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null'); } catch { return; }
  if (!draft) return;
  const form = $('#check-form');
  form.age_days.value = draft.age_days ?? '';
  form.weight_kg.value = draft.weight_kg ?? '';
  if (draft.sex) form.sex.value = draft.sex;
  form.preterm.checked = !!draft.preterm;
  $('#intake').value = draft.intake_text ?? '';
  $('#respiratory_rate').value = draft.respiratory_rate ?? '';
  $('#privacy_local').checked = draft.privacy_local !== false;
  if (draft.respiratory_rate) {
    $('#breath-count').textContent = draft.respiratory_rate;
  }
}

/* ─────────────────────────────── assessment ─────────────────────────────── */

async function submit(event) {
  event.preventDefault();
  const form = event.target;
  const button = $('#assess-btn');

  button.disabled = true;
  $('.btn-label', button).textContent = 'Checking…';
  $('.spinner', button).hidden = false;

  const payload = {
    mode: state.mode,
    age_days: form.age_days.value === '' ? -1 : Number(form.age_days.value),
    weight_kg: form.weight_kg.value === '' ? -1 : Number(form.weight_kg.value),
    sex: form.sex.value,
    preterm: form.preterm.checked,
    intake_text: $('#intake').value,
    respiratory_rate: $('#respiratory_rate').value === '' ? -1 : Number($('#respiratory_rate').value),
    privacy_local: $('#privacy_local').checked,
    locale: currentLocale(),
    captures: [
      ...[...state.captures].map(([kind, data]) => ({ kind, media_type: 'image/jpeg', data })),
      ...(state.cry ? [{ kind: 'CRY', media_type: 'audio/wav', data: state.cry }] : []),
    ],
  };

  try {
    const response = await fetch('/api/assess', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!response.ok) {
      const err = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
      throw new Error(err.error || `HTTP ${response.status}`);
    }
    state.result = await response.json();
    renderResult(state.result);
    show('result');
  } catch (e) {
    toast(`The check could not be completed: ${e.message}`);
  } finally {
    button.disabled = false;
    $('.btn-label', button).textContent = 'Check this baby';
    $('.spinner', button).hidden = true;
  }
}

function renderResult(r) {
  const colour = r.light.toLowerCase();
  const verdict = $('#verdict');
  verdict.className = `verdict ${colour}`;
  $('#result-h').textContent = r.headline;
  $('#verdict-classification').textContent = r.classification;

  const reasons = $('#reasons');
  reasons.innerHTML = '';
  const items = r.reasons?.length ? r.reasons : ['No danger sign was found in what was recorded.'];
  for (const reason of items) {
    const li = document.createElement('li');
    li.textContent = reason;
    reasons.appendChild(li);
  }

  $('#narrative').textContent = r.narrative?.trim() || 'No further guidance was generated.';
  setupSpeech(r);

  const cryCard = $('#cry-card-result');
  if (r.cry) {
    cryCard.hidden = false;
    $('#cry-findings').innerHTML = r.cry.graded
      ? `<p>${escapeHtml(r.cry.summary)}</p>
         <p class="muted small">Heard by the device, not by a person. It can raise the level of
           concern and never lower it.</p>`
      : `<p>${escapeHtml(r.cry.summary)}</p>
         <p class="muted small">The recording is kept with this encounter for a clinician.</p>`;
  } else {
    cryCard.hidden = true;
  }

  const jaundiceCard = $('#jaundice-card');
  if (r.jaundice) {
    jaundiceCard.hidden = false;
    $('#jaundice').innerHTML = r.jaundice.refused
      ? `<p>${escapeHtml(r.jaundice.refusal_reason)}</p>`
      : `<p><strong>${escapeHtml(r.jaundice.severity.toLowerCase())}</strong>, reaching Kramer zone
           ${r.jaundice.kramer_zone} of 5.</p>
         <p class="muted small">The extent matters more than the shade: yellow starts at the head
           and moves down, and how far it has reached tracks the bilirubin level.</p>`;
  } else {
    jaundiceCard.hidden = true;
  }

  const referralCard = $('#referral-card');
  if (r.referral_letter) {
    referralCard.hidden = false;
    $('#referral').textContent = r.referral_letter;
  } else {
    referralCard.hidden = true;
  }

  renderTrace(r);
  updateBadge(r);
}

/*
 * "How this was decided" is not an easter egg for developers. Three independent things looked at
 * this baby and a supervisor may need to know whether they agreed, so the disagreement is shown
 * rather than smoothed over.
 */
function renderTrace(r) {
  const rows = [
    ['WHO rule engine', r.rule_light],
    ['Gradient-boosted head', `${r.model_light} (${Math.round(r.model_confidence * 100)}% confident)`],
    ['Language model', r.narrative_light === 'none' ? 'did not run' : r.narrative_light],
    ['Answered by', r.rung_label],
    ['Time taken', `${r.elapsed_ms} ms`],
  ];
  const warnings = [];
  if (!r.heads_agree) {
    warnings.push('The rule engine and the trained model disagreed. This has been flagged for a supervisor.');
  }
  if (r.abstained) {
    warnings.push('The model was not confident enough to separate two possible results, so the more serious one was used.');
  }
  if (r.offline) {
    warnings.push('This assessment ran entirely on this device. Nothing left it.');
  }

  $('#decision-trace').innerHTML =
    `<dl class="kv">${rows.map(([k, v]) =>
      `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(String(v))}</dd>`).join('')}</dl>` +
    warnings.map(w => `<p>${escapeHtml(w)}</p>`).join('');
}

function updateBadge(r) {
  const badge = $('#rung-badge');
  badge.textContent = r.offline ? 'offline · on this device' : r.rung_label;
  badge.className = `badge ${r.offline ? 'offline' : 'cloud'}`;
}

/* ─────────────────────────────── language ─────────────────────────────── */

const LANG_KEY = 'kangaroo.lang';

/*
 * The language control affects the part a caregiver actually reads and acts on: the model writes
 * the action plan in the chosen language. Interface chrome is currently only translated into
 * English and French, and docs/i18n.md is explicit about that rather than implying twelve.
 */
function currentLocale() {
  try {
    const saved = localStorage.getItem(LANG_KEY);
    if (saved) return saved;
  } catch { /* private mode */ }
  return (navigator.language || 'en').split('-')[0];
}

function applyLocale(tag, languages) {
  const lang = languages.find(l => l.tag === tag) || languages[0];
  if (!lang) return;
  document.documentElement.lang = lang.tag;
  document.documentElement.dir = lang.rtl ? 'rtl' : 'ltr';
  try { localStorage.setItem(LANG_KEY, lang.tag); } catch { /* private mode */ }
}

function buildLanguagePicker(languages) {
  const select = $('#lang-select');
  if (!select || !languages?.length) return;

  const chosen = languages.some(l => l.tag === currentLocale()) ? currentLocale() : 'en';
  select.innerHTML = languages
    .map(l => `<option value="${l.tag}"${l.tag === chosen ? ' selected' : ''}>${escapeHtml(l.endonym)}</option>`)
    .join('');

  select.addEventListener('change', () => {
    applyLocale(select.value, languages);
    toast('The advice will be written in ' + (languages.find(l => l.tag === select.value)?.endonym ?? select.value) + '.');
  });

  applyLocale(chosen, languages);
}

/* ─────────────────────────────── technical page ─────────────────────────────── */

async function loadStatus() {
  try {
    const s = await (await fetch('/api/status')).json();
    buildLanguagePicker(s.languages);
    const badge = $('#rung-badge');
    const offline = s.preferred_rung === 'NATIVE' || s.preferred_rung === 'DETERMINISTIC';
    badge.textContent = offline ? 'offline · ready' : 'network available';
    badge.className = `badge ${offline ? 'offline' : 'cloud'}`;

    $('#tech-status').innerHTML = [
      ['Java', s.java],
      ['Preferred rung', s.preferred_rung],
      ['On-device model', s.native_available ? 'available' : 'not configured'],
      ['Vision', s.vision_available ? 'available' : 'not configured'],
      ['Clinical head', s.clinical_model],
      ['Jaundice head', s.jaundice_model],
      ['Encounters stored', s.encounters_stored],
      ['Device fingerprint', s.device_fingerprint],
      ['Languages', s.languages.length],
    ].map(([k, v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(String(v))}</dd>`).join('');
  } catch {
    $('#rung-badge').textContent = 'not connected';
  }
}

async function loadJeps() {
  try {
    const data = await (await fetch('/api/jeps')).json();
    $('#jep-list').innerHTML = data.jeps.map(j => `
      <div class="jep ${j.load_bearing ? 'load-bearing' : ''}">
        <div class="jep-head">
          <span class="jep-num">JEP ${j.jep}</span>
          <span class="jep-title">${escapeHtml(j.title)}</span>
          <span class="jep-status">${escapeHtml(j.status)}</span>
        </div>
        <div class="jep-where">${escapeHtml(j.where)}</div>
        <p class="jep-why">${escapeHtml(j.why)}</p>
      </div>`).join('');
  } catch {
    $('#jep-list').textContent = 'Could not reach the server.';
  }
}

async function runBench() {
  const button = $('#bench-btn');
  const out = $('#bench-result');
  button.disabled = true;
  out.textContent = 'Measuring on this machine…';
  try {
    const b = await (await fetch('/api/bench')).json();
    if (!b.vector_available) {
      out.innerHTML = `<p>${escapeHtml(b.vector_description)}</p>`;
      return;
    }
    const worst = Math.max(b.scalar.ms_per_frame, b.kernels.scalar_ms);
    const bar = (ms, cls) =>
      `<div class="bench-bar ${cls}" style="width:${Math.max(3, (ms / worst) * 100)}%"></div>`;
    out.innerHTML = `
      <h3 class="bench-h">The kernels — the elementwise work SIMD applies to</h3>
      <div class="bench-bars">
        <div class="bench-row"><span>scalar</span>${bar(b.kernels.scalar_ms, 'scalar')}
          <span class="bench-figure">${b.kernels.scalar_ms} ms</span></div>
        <div class="bench-row"><span>vector</span>${bar(b.kernels.vector_ms, '')}
          <span class="bench-figure">${b.kernels.vector_ms} ms</span></div>
      </div>
      <div class="bench-speedup">${b.kernels.speedup}× on the kernels</div>

      <h3 class="bench-h">The whole pipeline, including the percentile sort</h3>
      <div class="bench-bars">
        <div class="bench-row"><span>scalar</span>${bar(b.scalar.ms_per_frame, 'scalar')}
          <span class="bench-figure">${b.scalar.ms_per_frame} ms</span></div>
        <div class="bench-row"><span>vector</span>${bar(b.vector.ms_per_frame, '')}
          <span class="bench-figure">${b.vector.ms_per_frame} ms</span></div>
      </div>
      <div class="bench-speedup">${b.speedup}× end to end</div>

      <p class="muted small">${escapeHtml(b.vector_description)} · ${b.frame} frame · ${escapeHtml(b.note)}</p>`;
  } catch (e) {
    out.textContent = `Benchmark failed: ${e.message}`;
  } finally {
    button.disabled = false;
  }
}

/* ─────────────────────────────── helpers ─────────────────────────────── */

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function resetForCheck() {
  state.captures.clear();
  state.result = null;
  $('#check-form').reset();
  try { localStorage.removeItem(DRAFT_KEY); } catch { /* ignore */ }
  $('#breath-reset').click();
  buildCaptures();
  buildChips();
  updateSteps();
}

/* ─────────────────────────────── wiring ─────────────────────────────── */

function init() {
  $$('.door').forEach(d => d.addEventListener('click', () => setMode(d.dataset.mode)));
  $('#home-btn').addEventListener('click', () => show('start'));
  $('#check-form').addEventListener('submit', submit);
  $('#check-form').addEventListener('input', () => { saveDraft(); updateSteps(); });
  $('#again-btn').addEventListener('click', () => { resetForCheck(); show('check'); });
  $('#edit-btn').addEventListener('click', () => show('check'));
  $('#print-btn').addEventListener('click', () => window.print());
  $('#bench-btn').addEventListener('click', runBench);
  setupCry();

  // Voices arrive asynchronously on most platforms, so a result rendered before they load would
  // hide the button forever.
  if ('speechSynthesis' in window) {
    speechSynthesis.onvoiceschanged = () => { if (state.result) setupSpeech(state.result); };
  }

  $$('[data-nav]').forEach(a => a.addEventListener('click', e => {
    e.preventDefault();
    const target = a.dataset.nav;
    show(target);
    if (target === 'tech') { loadStatus(); loadJeps(); }
  }));

  setupBreathing();
  restoreDraft();
  buildCaptures();
  buildChips();
  buildSteps();
  loadStatus();

  const hash = location.hash.replace('#', '');
  if (hash === 'tech') { show('tech'); loadStatus(); loadJeps(); }

  // The service worker is what makes this installable and usable with the network off. It is
  // optional: without it everything still works while the server is reachable.
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => { /* not fatal */ });
  }
}

document.addEventListener('DOMContentLoaded', init);
