/* GET only. No clicks, submissions, cart/order APIs, or page script execution. */
let wakeLock = null;
async function keepPageAwake(active) {
  try {
    if (active && 'wakeLock' in navigator && !wakeLock) {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => { wakeLock = null; });
    } else if (!active && wakeLock) {
      await wakeLock.release(); wakeLock = null;
    }
  } catch {}
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.type === 'wake-lock') { keepPageAwake(message.active); respond({ok:true}); return; }
  if (message.type !== 'scan') return;
  keepPageAwake(true);
  (async () => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 40000);
    try {
      if (!VineCore.validURL(location.href)) throw Error('La scheda monitorata non è più la coda potluck senza filtri.');
      let all = [], total = null, expected = 1;
      for (let page = 1; page <= 100; page++) {
        const url = VineCore.TARGET + (page > 1 ? '&page=' + page : '');
        const response = await fetch(url, {credentials:'same-origin', cache:'no-store', signal:controller.signal});
        if (!response.ok) throw Error('Risposta HTTP ' + response.status + ': controlli sospesi.');
        const snapshot = VineCore.read(new DOMParser().parseFromString(await response.text(), 'text/html'), response.url);
        if (total !== null && snapshot.total !== total) throw Error('Catalogo cambiato durante la scansione: riprendere manualmente.');
        total = snapshot.total;
        if (total && snapshot.start !== expected) throw Error('Paginazione incompleta o non riconosciuta.');
        all.push(...snapshot.items); expected = snapshot.end + 1;
        if (all.length === total) {
          if (new Set(all.map(x=>x.id)).size !== total) throw Error('Duplicati tra pagine: scansione non affidabile.');
          return {ok:true, items:all};
        }
      }
      throw Error('Limite difensivo di 100 pagine raggiunto.');
    } finally { clearTimeout(timer); }
  })().then(respond, e => respond({ok:false, error:e.name === 'AbortError' ? 'Rete lenta: scansione interrotta dopo 40 secondi.' : e.message}));
  return true;
});
