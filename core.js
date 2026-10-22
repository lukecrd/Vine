/* Pure logic shared by browser and offline tests. No network or page actions. */
(function (root) {
  const TARGET = 'https://www.amazon.it/vine/vine-items?queue=potluck';
  function validURL(value) {
    try { const u = new URL(value); return u.origin === 'https://www.amazon.it' && u.pathname === '/vine/vine-items' && u.searchParams.get('queue') === 'potluck' && [...u.searchParams.keys()].every(k => ['queue','page'].includes(k)); } catch { return false; }
  }
  function asin(value) { return /^[A-Z0-9]{10}$/.test(value || '') ? value : null; }
  function read(doc, url) {
    if (!validURL(url)) throw Error('Sessione scaduta o pagina diversa da potluck.');
    if (doc.querySelector('input[type="password"], #captchacharacters, form[action*="validateCaptcha"], iframe[src*="captcha"]')) throw Error('Accesso o CAPTCHA richiesto. Intervento manuale necessario.');
    const selected = doc.querySelector('#vvp-items-button--recommended [aria-checked="true"]');
    const box = doc.querySelector('#vvp-items-grid-container');
    if (!selected || !box) throw Error('Pagina non riconosciuta: sessione, blocco o struttura cambiata.');
    const summary = [...box.querySelectorAll('p')].map(p => p.textContent).join(' ');
    const count = summary.match(/Visualizzazione\s+di\s+([\d.]+)\s*[–-]\s*([\d.]+)\s+di\s+([\d.]+)\s+risultati/i);
    // Empty-state wording is intentionally NOT guessed. Unknown empty layouts pause safely.
    if (!count) throw Error('Conteggio risultati non verificabile (anche pagina vuota): monitor sospeso.');
    const [start,end,total] = count.slice(1).map(n => Number(n.replaceAll('.', '')));
    const tiles = [...box.querySelectorAll('.vvp-item-tile')];
    const items = tiles.map(tile => {
      const id = asin(tile.querySelector('[data-asin]')?.getAttribute('data-asin'));
      const link = tile.querySelector('.vvp-item-product-title-container a[href]');
      const href = new URL(link?.getAttribute('href') || '', TARGET);
      const linkID = href.pathname.match(/^\/dp\/([A-Z0-9]{10})(?:\/|$)/)?.[1];
      const title = (link?.querySelector('.a-truncate-full')?.textContent || link?.textContent || '').trim();
      if (!id || id !== linkID || href.origin !== 'https://www.amazon.it' || !title) throw Error('Prodotto incompleto o identificatore incoerente.');
      return {id, title, url:'https://www.amazon.it/dp/' + id};
    });
    if (!Number.isSafeInteger(total) || total < 0 || (total === 0 ? items.length !== 0 : end-start+1 !== items.length) || new Set(items.map(x=>x.id)).size !== items.length) throw Error('Conteggio prodotti incoerente.');
    if (total && (start < 1 || end < start || end > total)) throw Error('Intervallo risultati incoerente.');
    return {items,start,end,total};
  }
  function reconcile(state, items, now) {
    const seen = {...(state.seen || {})};
    const fresh = [];
    for (const item of items) { if (!seen[item.id]) { seen[item.id] = now; if(state.baseline) fresh.push({...item,at:now}); } }
    return {...state, baseline:true, seen, last:now, count:items.length, events:[...(state.events || []),...fresh], fresh};
  }
  const api = {TARGET, validURL, asin, read, reconcile};
  root.VineCore = api;
  if (typeof module !== 'undefined') module.exports = api;
})(globalThis);
