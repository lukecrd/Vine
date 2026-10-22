importScripts('core.js');
const ALARM = 'vine-60s';
let chain = Promise.resolve();
function serial(fn) { const task = chain.then(fn); chain = task.catch(()=>{}); return task; }
async function state() { return (await chrome.storage.local.get('state')).state || {running:false, baseline:false, seen:{},events:[],status:'Pronto. Avvia per creare la baseline silenziosa.'}; }
async function save(s) { await chrome.storage.local.set({state:s}); await chrome.action.setBadgeText({text:s.running ? 'ON' : 'OFF'}); await chrome.action.setBadgeBackgroundColor({color:s.running ? '#146b48' : '#8d392b'}); }
async function stop(reason) { const s=await state(); s.running=false; s.status=reason; await chrome.alarms.clear(ALARM); await save(s); }
async function scan() {
  let s = await state(); if(!s.running) return;
  try {
    const answer = await chrome.tabs.sendMessage(s.tabId,{type:'scan'});
    if (!answer?.ok) throw Error(answer?.error || 'Nessuna risposta dalla scheda.');
    const wasBaseline = s.baseline;
    s = VineCore.reconcile(s,answer.items,Date.now());
    const fresh=s.fresh; delete s.fresh;
    s.status=wasBaseline ? 'Attivo — ultima scansione completata.' : 'Attivo — baseline silenziosa salvata.';
    // Persist events AND seen atomically before system alerts. Alerts are never replayed on restart.
    await save(s);
    for(const item of fresh) {
      try { await chrome.notifications.create('product-'+item.id,{type:'basic',iconUrl:'icon.png',title:'Nuovo prodotto Vine',message:item.title,contextMessage:'Clicca per aprire il prodotto',requireInteraction:true}); }
      catch { s.status='Attivo — avvisi di sistema non disponibili; nuovi prodotti nel registro.'; await save(s); }
    }
  } catch(e) { await stop('Sospeso: '+e.message+' Apri Vine e premi Avvia per riprendere.'); }
}
chrome.alarms.onAlarm.addListener(a => { if(a.name===ALARM) serial(scan); });
chrome.runtime.onStartup.addListener(() => serial(()=>stop('Fermo dopo riavvio browser. Apri Vine e premi Avvia.')));
chrome.tabs.onRemoved.addListener(id => serial(async()=>{const s=await state();if(s.running && s.tabId===id) await stop('Fermo: scheda Vine chiusa.');}));
chrome.notifications.onClicked.addListener(id=>{const asin=VineCore.asin(id.replace(/^product-/,'')); if(asin) chrome.tabs.create({url:'https://www.amazon.it/dp/'+asin});});
chrome.runtime.onMessage.addListener((msg,sender,reply)=>{
  if(sender.id!==chrome.runtime.id || !['start','stop','status'].includes(msg.type)) return;
  serial(async()=>{
    if(msg.type==='stop') await stop('Fermo — memoria dei prodotti conservata.');
    if(msg.type==='start') {
      const tabs=await chrome.tabs.query({url:'https://www.amazon.it/vine/vine-items*'});
      const tab=tabs.find(t=>VineCore.validURL(t.url));
      if(!tab) throw Error('Apri la pagina Vine potluck senza filtri e riprova.');
      const s=await state(); s.running=true;s.tabId=tab.id;s.status='Lettura in corso…';await save(s);
      await chrome.alarms.create(ALARM,{periodInMinutes:1,delayInMinutes:1});
      await scan();
    }
    return await state();
  }).then(s=>reply({ok:true,state:s}),e=>reply({ok:false,error:e.message}));return true;
});
