const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const C=require('./core.js');
const A={id:'B013LEIETU',title:'Prodotto A',url:'https://www.amazon.it/dp/B013LEIETU'};
const B={id:'B012345678',title:'Prodotto B',url:'https://www.amazon.it/dp/B012345678'};
// DOM contract doubles: not a claim of a complete browser rendering test.
function documentFixture({items=[A],count='Visualizzazione di 1–1 di 1 risultati',selected=true,blocked=false,badLink=false}={}) {
 const tiles=items.map(i=>({querySelector(s){if(s==='[data-asin]')return {getAttribute:()=>i.id};return {getAttribute:()=>'/dp/'+(badLink?'B099999999':i.id),textContent:i.title,querySelector:()=>({textContent:i.title})};}}));
 const box={querySelectorAll:s=>s==='p'?[{textContent:count}]:tiles};
 return {querySelector(s){if(s.includes('password'))return blocked?{}:null;if(s.includes('recommended'))return selected?{}:null;return box;}};
}
test('URL: solo potluck senza filtri, ASIN valido',()=>{assert(C.validURL(C.TARGET));assert(C.validURL(C.TARGET+'&page=2'));for(const url of [C.TARGET+'&cn=123',C.TARGET.replace('potluck','encore'),'https://evil.test/vine/vine-items?queue=potluck'])assert(!C.validURL(url));assert.equal(C.asin('no'),null);});
test('parser: struttura osservata, titolo e link canonico',()=>assert.deepEqual(C.read(documentFixture(),C.TARGET).items,[A]));
test('parser: login, CAPTCHA, coda errata, conteggio incompleto non validi',()=>{for(const options of [{blocked:true},{selected:false},{count:'Errore'},{count:'Visualizzazione di 1–2 di 2 risultati'},{badLink:true}])assert.throws(()=>C.read(documentFixture(options),C.TARGET));assert.throws(()=>C.read(documentFixture(),'https://www.amazon.it/ap/signin'));});
test('baseline silenziosa e un avviso per nuovo ASIN',()=>{let s=C.reconcile({},[A],1);assert.equal(s.fresh.length,0);s=C.reconcile(s,[A,B,B],2);assert.deepEqual(s.fresh.map(x=>x.id),[B.id]);});
test('riavvio, sparizione e ricomparsa non duplicano',()=>{let s=C.reconcile({},[A],1);s=C.reconcile(s,[B],2);s=JSON.parse(JSON.stringify(s));s=C.reconcile(s,[],3);s=C.reconcile(s,[A,B],4);assert.equal(s.fresh.length,0);assert.equal(s.events.length,1);});
test('pagina vuota con conteggio zero esplicito supportata',()=>assert.equal(C.read(documentFixture({items:[],count:'Visualizzazione di 0–0 di 0 risultati'}),C.TARGET).total,0));
test('test integrazione simulata: alarm 60s, persistenza, notifica unica, errore sospende, stop',async()=>{
 let saved,reply={ok:true,items:[A]},notifications=[],alarm,alarmsCleared=0;
 const listeners={};const event=name=>({addListener:fn=>listeners[name]=fn});
 const chrome={storage:{local:{get:async()=>({state:saved}),set:async x=>{saved=structuredClone(x.state);}}},action:{setBadgeText:async()=>{},setBadgeBackgroundColor:async()=>{}},alarms:{create:async(n,a)=>{alarm=a;},clear:async()=>{alarmsCleared++;},onAlarm:event('alarm')},tabs:{query:async()=>[{id:1,url:C.TARGET}],sendMessage:async()=>reply,onRemoved:event('removed')},runtime:{id:'local',onStartup:event('startup'),onMessage:event('message')},notifications:{create:async(id,n)=>notifications.push({id,n}),onClicked:event('clicked')}};
 const context=vm.createContext({chrome,URL,console,Date,importScripts:()=>{},VineCore:C});vm.runInContext(fs.readFileSync(__dirname+'/background.js','utf8'),context);
 const send=type=>new Promise(resolve=>listeners.message({type},{id:'local'},resolve));
 assert((await send('start')).ok);assert(saved.baseline);assert.equal(notifications.length,0);assert.equal(alarm.periodInMinutes,1);
 reply={ok:true,items:[A,B]};listeners.alarm({name:'vine-60s'});await send('status');assert.equal(notifications.length,1);assert.equal(saved.events.length,1);
 listeners.alarm({name:'vine-60s'});await send('status');assert.equal(notifications.length,1);
 reply={ok:false,error:'HTTP 503'};listeners.alarm({name:'vine-60s'});await send('status');assert.equal(saved.running,false);assert.equal(Object.keys(saved.seen).length,2);assert(alarmsCleared>0);
 await send('stop');assert.equal(saved.running,false);
});
