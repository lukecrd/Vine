const $=id=>document.getElementById(id);
let currentState=null;
function formatCountdown(nextScan,running){
  if(!running||!nextScan)return '—';
  const diff=Math.max(0,Math.ceil((nextScan-Date.now())/1000));
  return diff>0?`tra ${diff}s`:'in corso…';
}
function updateDetails(){
  if(!currentState)return;
  const s=currentState;
  $('details').textContent=`Già visti: ${Object.keys(s.seen||{}).length} · Ultimo controllo: ${s.last?new Date(s.last).toLocaleString('it-IT'):'mai'} · Prossima verifica: ${formatCountdown(s.nextScan,s.running)}`;
}
function render(s){
  currentState=s;
  $('status').textContent=s.status;
  updateDetails();
  $('start').disabled=s.running; $('stop').disabled=!s.running;
  $('events').replaceChildren(); $('empty').hidden=!!s.events.length;
  for(const item of [...s.events].reverse()){
    const li=document.createElement('li'),a=document.createElement('a'),time=document.createElement('small');
    a.textContent=item.title;a.href=item.url;a.target='_blank';a.rel='noopener';
    time.textContent=new Date(item.at).toLocaleString('it-IT');
    li.append(a,time);$('events').append(li);
  }
}
async function send(type){try{const r=await chrome.runtime.sendMessage({type});if(!r.ok)throw Error(r.error);render(r.state);}catch(e){$('status').textContent=e.message;}}
$('start').onclick=()=>send('start');$('stop').onclick=()=>send('stop');
if (typeof chrome !== 'undefined' && chrome.storage) {
  chrome.storage.onChanged.addListener(c=>{if(c.state)render(c.state.newValue);});
}
setInterval(updateDetails,1000);
send('status');
