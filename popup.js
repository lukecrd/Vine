const $=id=>document.getElementById(id);
function render(s) {
  $('status').textContent=s.status;
  $('details').textContent=`Già visti: ${Object.keys(s.seen).length} · Ultimo controllo: ${s.last ? new Date(s.last).toLocaleString('it-IT') : 'mai'}`;
  $('start').disabled=s.running; $('stop').disabled=!s.running;
  $('events').replaceChildren();$('empty').hidden=!!s.events.length;
  for(const item of [...s.events].reverse()) { const li=document.createElement('li'),a=document.createElement('a'),time=document.createElement('small'); a.textContent=item.title;a.href=item.url;a.target='_blank';a.rel='noopener';time.textContent=new Date(item.at).toLocaleString('it-IT');li.append(a,time);$('events').append(li); }
}
async function send(type) { try {const r=await chrome.runtime.sendMessage({type});if(!r.ok) throw Error(r.error);render(r.state);}catch(e){$('status').textContent=e.message;} }
$('start').onclick=()=>send('start');$('stop').onclick=()=>send('stop');
chrome.storage.onChanged.addListener(c=>{if(c.state)render(c.state.newValue);});send('status');
