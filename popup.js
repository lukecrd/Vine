const $ = id => document.getElementById(id);
let currentState = null;
let lastEventCount = null;

function playCustomSound(type, volumePct) {
  const vol = Math.max(0.05, Math.min(1, (Number(volumePct) || 80) / 100));
  if (type === 'device' && typeof AndroidBridge !== 'undefined' && AndroidBridge.playSystemSound) {
    AndroidBridge.playSystemSound();
    return;
  }
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const notes = {
      chime:  [{f: 784, d: 0.18, t: 0, w: 'sine'}, {f: 1046.5, d: 0.35, t: 0.2, w: 'sine'}],
      beep:   [{f: 960, d: 0.12, t: 0, w: 'square'}, {f: 960, d: 0.12, t: 0.18, w: 'square'}],
      alarm:  [{f: 880, d: 0.15, t: 0, w: 'sawtooth'}, {f: 1174, d: 0.15, t: 0.17, w: 'sawtooth'}, {f: 1396, d: 0.25, t: 0.34, w: 'sawtooth'}],
      melody: [{f: 523.2, d: 0.12, t: 0, w: 'triangle'}, {f: 659.2, d: 0.12, t: 0.13, w: 'triangle'}, {f: 784, d: 0.12, t: 0.26, w: 'triangle'}, {f: 1046.5, d: 0.3, t: 0.39, w: 'triangle'}],
      device: [{f: 880, d: 0.3, t: 0, w: 'sine'}]
    }[type || 'chime'] || [{f: 880, d: 0.3, t: 0, w: 'sine'}];

    for (const n of notes) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = n.w;
      osc.frequency.setValueAtTime(n.f, ctx.currentTime + n.t);
      gain.gain.setValueAtTime(vol * 0.4, ctx.currentTime + n.t);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + n.t + n.d);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(ctx.currentTime + n.t);
      osc.stop(ctx.currentTime + n.t + n.d);
    }
  } catch {}
}

function formatCountdown(nextScan, running) {
  if (!running || !nextScan) return '—';
  const diff = Math.max(0, Math.ceil((nextScan - Date.now()) / 1000));
  if (diff <= 0) return 'in corso…';
  const m = Math.floor(diff / 60);
  const s = diff % 60;
  return m > 0 ? `tra ${m}m ${s}s (${diff}s)` : `tra ${diff}s`;
}

function updateDetails() {
  if (!currentState) return;
  const s = currentState;
  const modeLabel = s.scanMode === 'random' ? 'Casuale 1-3m' : 'Fissa 60s';
  $('details').textContent = `Già visti: ${Object.keys(s.seen || {}).length} · Modalità: ${modeLabel} · Ultimo: ${s.last ? new Date(s.last).toLocaleTimeString('it-IT') : 'mai'} · Prossima verifica: ${formatCountdown(s.nextScan, s.running)}`;
}

function render(s) {
  if (!s) return;
  const soundType = s.soundType || 'chime';
  const soundVolume = s.soundVolume || 80;
  if (lastEventCount !== null && s.events && s.events.length > lastEventCount && (s.notifMode || 'sound') === 'sound') {
    playCustomSound(soundType, soundVolume);
  }
  lastEventCount = s.events ? s.events.length : 0;
  currentState = s;
  $('status').textContent = s.status;
  if ($('scanMode').value !== (s.scanMode || 'fixed')) $('scanMode').value = s.scanMode || 'fixed';
  if ($('notifMode').value !== (s.notifMode || 'sound')) $('notifMode').value = s.notifMode || 'sound';
  if ($('soundType').value !== soundType) $('soundType').value = soundType;
  if (Number($('soundVolume').value) !== Number(soundVolume)) $('soundVolume').value = soundVolume;
  $('volLabel').textContent = `${soundVolume}%`;
  $('soundType').disabled = (s.notifMode === 'off' || s.notifMode === 'system');
  $('soundVolume').disabled = (s.notifMode === 'off' || s.notifMode === 'system');
  updateDetails();
  $('start').disabled = s.running;
  $('stop').disabled = !s.running;
  $('events').replaceChildren();
  $('empty').hidden = !!(s.events && s.events.length);
  for (const item of [...(s.events || [])].reverse()) {
    const li = document.createElement('li'), a = document.createElement('a'), time = document.createElement('small');
    a.textContent = item.title;
    a.href = item.url;
    a.target = '_blank';
    a.rel = 'noopener';
    time.textContent = new Date(item.at).toLocaleString('it-IT');
    li.append(a, time);
    $('events').append(li);
  }
}

function currentCfg() {
  return {
    scanMode: $('scanMode').value,
    notifMode: $('notifMode').value,
    soundType: $('soundType').value,
    soundVolume: Number($('soundVolume').value)
  };
}

async function send(type, extra = {}) {
  try {
    const r = await chrome.runtime.sendMessage({type, ...extra});
    if (!r.ok) throw Error(r.error);
    render(r.state);
  } catch (e) {
    $('status').textContent = e.message;
  }
}

$('start').onclick = () => send('start', currentCfg());
$('stop').onclick = () => send('stop');
$('scanMode').onchange = () => send('config', currentCfg());
$('notifMode').onchange = () => send('config', currentCfg());
$('soundType').onchange = () => { playCustomSound($('soundType').value, $('soundVolume').value); send('config', currentCfg()); };
$('soundVolume').oninput = () => { $('volLabel').textContent = `${$('soundVolume').value}%`; };
$('soundVolume').onchange = () => send('config', currentCfg());
$('testSound').onclick = () => playCustomSound($('soundType').value, $('soundVolume').value);

if (typeof chrome !== 'undefined' && chrome.storage) {
  chrome.storage.onChanged.addListener(c => { if (c.state) render(c.state.newValue); });
}
setInterval(updateDetails, 1000);
send('status');