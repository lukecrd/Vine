const $ = id => document.getElementById(id);
let currentState = null;
let lastEventCount = null;

function playBeep() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    gain.gain.setValueAtTime(0.25, ctx.currentTime);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.35);
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
  if (lastEventCount !== null && s.events && s.events.length > lastEventCount && (s.notifMode || 'sound') === 'sound') {
    playBeep();
  }
  lastEventCount = s.events ? s.events.length : 0;
  currentState = s;
  $('status').textContent = s.status;
  if ($('scanMode').value !== (s.scanMode || 'fixed')) $('scanMode').value = s.scanMode || 'fixed';
  if ($('notifMode').value !== (s.notifMode || 'sound')) $('notifMode').value = s.notifMode || 'sound';
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

async function send(type, extra = {}) {
  try {
    const r = await chrome.runtime.sendMessage({type, ...extra});
    if (!r.ok) throw Error(r.error);
    render(r.state);
  } catch (e) {
    $('status').textContent = e.message;
  }
}

$('start').onclick = () => send('start', {scanMode: $('scanMode').value, notifMode: $('notifMode').value});
$('stop').onclick = () => send('stop');
$('scanMode').onchange = () => send('config', {scanMode: $('scanMode').value, notifMode: $('notifMode').value});
$('notifMode').onchange = () => send('config', {scanMode: $('scanMode').value, notifMode: $('notifMode').value});

if (typeof chrome !== 'undefined' && chrome.storage) {
  chrome.storage.onChanged.addListener(c => { if (c.state) render(c.state.newValue); });
}
setInterval(updateDetails, 1000);
send('status');