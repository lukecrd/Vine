importScripts('core.js');
const ALARM = 'vine-60s';
let chain = Promise.resolve();
function serial(fn) { const task = chain.then(fn); chain = task.catch(()=>{}); return task; }
async function state() { return (await chrome.storage.local.get('state')).state || {running:false, baseline:false, seen:{}, events:[], nextScan:null, status:'Pronto. Avvia per creare la baseline silenziosa.'}; }
async function updateNextScan(s) {
  if (!s.running) { s.nextScan = null; return; }
  const alarm = await chrome.alarms.get(ALARM);
  s.nextScan = alarm ? alarm.scheduledTime : Date.now() + 60000;
}
async function save(s) {
  await updateNextScan(s);
  await chrome.storage.local.set({state:s});
  await chrome.action.setBadgeText({text:s.running ? 'ON' : 'OFF'});
  await chrome.action.setBadgeBackgroundColor({color:s.running ? '#146b48' : '#8d392b'});
}
function setKeepAwake(active) {
  if (!chrome.power) return;
  if (active) chrome.power.requestKeepAwake('system');
  else chrome.power.releaseKeepAwake();
}
async function stop(reason) {
  const s = await state(); s.running = false; s.nextScan = null; s.status = reason;
  setKeepAwake(false);
  await chrome.alarms.clear(ALARM);
  if (s.tabId) chrome.tabs.sendMessage(s.tabId, {type:'wake-lock', active:false}).catch(()=>{});
  await save(s);
}
function isFatalError(msg) { return /CAPTCHA|Accesso|Sessione scaduta|non è più la coda|Catalogo cambiato|struttura cambiata/i.test(msg || ''); }
async function scan() {
  let s = await state(); if (!s.running) return;
  setKeepAwake(true);
  try {
    const tab = await chrome.tabs.get(s.tabId).catch(() => null);
    if (!tab) { await stop('Fermo: scheda Vine chiusa.'); return; }
    if (tab.discarded) {
      await chrome.tabs.reload(s.tabId);
      s.status = 'Attivo — scheda risvegliata dallo standby, riprovo al prossimo ciclo.';
      await save(s); return;
    }
    const answer = await chrome.tabs.sendMessage(s.tabId, {type:'scan'});
    if (!answer?.ok) throw Error(answer?.error || 'Nessuna risposta dalla scheda.');
    const wasBaseline = s.baseline;
    s = VineCore.reconcile(s, answer.items, Date.now());
    const fresh = s.fresh; delete s.fresh;
    s.status = wasBaseline ? 'Attivo — ultima scansione completata.' : 'Attivo — baseline silenziosa salvata.';
    await save(s);
    for (const item of fresh) {
      try { await chrome.notifications.create('product-'+item.id, {type:'basic', iconUrl:'icon.png', title:'Nuovo prodotto Vine', message:item.title, contextMessage:'Clicca per aprire il prodotto', requireInteraction:true}); }
      catch { s.status = 'Attivo — avvisi di sistema non disponibili; nuovi prodotti nel registro.'; await save(s); }
    }
  } catch(e) {
    if (isFatalError(e.message)) await stop('Sospeso: ' + e.message + ' Apri Vine e premi Avvia per riprendere.');
    else { s.status = 'In attesa (standby/rete): ' + e.message + ' — Riprovo al prossimo ciclo.'; await save(s); }
  }
}
chrome.alarms.onAlarm.addListener(a => { if (a.name === ALARM) serial(scan); });
chrome.runtime.onStartup.addListener(() => serial(async () => {
  const s = await state();
  if (s.running) { setKeepAwake(true); await chrome.alarms.create(ALARM, {periodInMinutes:1, delayInMinutes:1}); await save(s); }
}));
chrome.tabs.onRemoved.addListener(id => serial(async () => { const s = await state(); if (s.running && s.tabId === id) await stop('Fermo: scheda Vine chiusa.'); }));
chrome.notifications.onClicked.addListener(id => { const asin = VineCore.asin(id.replace(/^product-/, '')); if (asin) chrome.tabs.create({url:'https://www.amazon.it/dp/'+asin}); });
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (sender.id !== chrome.runtime.id || !['start','stop','status'].includes(msg.type)) return;
  serial(async () => {
    if (msg.type === 'stop') await stop('Fermo — memoria dei prodotti conservata.');
    if (msg.type === 'start') {
      const tabs = await chrome.tabs.query({url:'https://www.amazon.it/vine/vine-items*'});
      const tab = tabs.find(t => VineCore.validURL(t.url));
      if (!tab) throw Error('Apri la pagina Vine potluck senza filtri e riprova.');
      const s = await state(); s.running = true; s.tabId = tab.id; s.status = 'Lettura in corso…';
      setKeepAwake(true);
      await chrome.alarms.create(ALARM, {periodInMinutes:1, delayInMinutes:1});
      await save(s);
      await scan();
    }
    const current = await state(); await updateNextScan(current); return current;
  }).then(s => reply({ok:true, state:s}), e => reply({ok:false, error:e.message})); return true;
});
