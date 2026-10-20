'use strict';
const {test}=require('node:test'), assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const root=path.join(__dirname,'../..'),html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const js=html.slice(html.lastIndexOf('<script>')+8,html.lastIndexOf('</script>'));
const model=js.slice(js.indexOf('const DEFAULT='),js.indexOf('function getAlert('));
const bridge=fs.readFileSync(path.join(root,'android/bridge.js'),'utf8');
function setup(events){
 const c={localStorage:{getItem:()=>null,setItem(){},removeItem(){}},window:{NativeFuel:{}},setTimeout,console,Date,Number,document:{querySelector:()=>({addEventListener(){}})}};
 vm.runInNewContext(model+'\n'+bridge+`\nglobalThis.api={init:e=>data.events=e,read:()=>data,base:replay,project:androidProjection,set:s=>androidState=s,normalize,isDataValid};`,c);
 c.api.init(events);return c.api;
}
const init={kind:'init',odometer:26000,at:'2026-10-20T08:00:00Z'};
const ride=odo=>({kind:'mileage',odometer:odo,at:'2026-10-20T09:00:00Z'});
const fill=(odo,liters,full=false,mode='liters')=>({kind:'refuel',odometer:odo,liters,full,mode,at:'2026-10-20T10:00:00Z'});
function state(a,km,unknown=false){return {km,unknown,events:JSON.stringify(a.read().events),at:'2026-10-20T09:00:00Z'}}
test('no GPS delta equals original model exactly',()=>{const a=setup([init,ride(26100),fill(26100,3)]);a.set(state(a,0));assert.deepEqual(a.project(a.base()),a.base())});
test('GPS transient replay equals real mileage input but never writes events or learning samples',()=>{
 const a=setup([init]);a.set(state(a,128));const before=JSON.stringify(a.read());const projected=a.project(a.base());const b=setup([init,ride(26128)]);
 assert.equal(projected.remaining,b.base().remaining);assert.equal(projected.safeKm,b.base().safeKm);assert.equal(projected.learning.count,0);assert.equal(JSON.stringify(a.read()),before);
});
test('trusted ODO reconciles underread without duplicate consumption',()=>{
 const a=setup([init]);a.set(state(a,128));a.project(a.base());a.init([init,ride(26132)]);a.set(state(a,0));
 const b=setup([init,ride(26132)]);assert.equal(a.project(a.base()).remaining,b.base().remaining);
});
test('trusted ODO reconciles overread and next interval starts fresh',()=>{
 const a=setup([init]);a.set(state(a,128));a.project(a.base());a.init([init,ride(26124)]);a.set(state(a,8));
 const b=setup([init,ride(26124),ride(26132)]);assert.equal(a.project(a.base()).remaining,b.base().remaining);
});
test('refuel during tracking clears overlap; actual full interval alone teaches model',()=>{
 const a=setup([init]);a.set(state(a,100));assert.equal(a.project(a.base()).learning.count,0);
 a.init([init,fill(26102,4.5,true)]);a.set(state(a,0));assert.equal(a.project(a.base()).remaining,12.5);assert.equal(a.base().learning.count,1);
 a.set(state(a,10));const b=setup([init,fill(26102,4.5,true),ride(26112)]);assert.equal(a.project(a.base()).remaining,b.base().remaining);
});
test('unknown gap or unconfirmed bus trip never reports precise remaining fuel or range',()=>{
 const a=setup([init]);a.set(state(a,24,true));const c=a.project(a.base());assert.ok(c.uncertain);assert.ok(Number.isNaN(c.remaining));assert.ok(Number.isNaN(c.safeKm));
});
test('stale projection from another fuel revision fails closed',()=>{
 const a=setup([init]);a.set(state(a,10));a.init([init,ride(26005)]);assert.ok(Number.isNaN(a.project(a.base()).remaining));
});
test('restart and backup roundtrip are deterministic and never export GPS events',()=>{
 const a=setup([init,fill(26030,2)]),s=state(a,10);a.set(s);const value=a.project(a.base()).remaining;
 const backup=JSON.parse(JSON.stringify(a.read()));assert.equal(backup.events.length,2);assert.ok(a.isDataValid(backup));
 const b=setup(backup.events);b.set(s);assert.equal(b.project(b.base()).remaining,value);
});
test('money partial fill stays excluded from trusted full-tank learning with GPS',()=>{
 const a=setup([init,fill(26100,3,false,'money'),fill(26250,7,true)]);a.set(state(a,10));assert.equal(a.project(a.base()).learning.count,0);
});
test('invalid delta and absent native state fail closed',()=>{
 const a=setup([init]);for(const km of [-1,Infinity,NaN]){a.set(state(a,km));assert.ok(Number.isNaN(a.project(a.base()).safeKm))}
 a.set(null);assert.ok(Number.isNaN(a.project(a.base()).safeKm));
});
