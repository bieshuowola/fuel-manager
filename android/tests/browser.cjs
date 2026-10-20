// Desktop Chromium integration, not Android WebView or device acceptance.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),generated=path.join(root,'android/app/build/generated/webAssets');
let state={events:'[]',km:0,unknown:false,at:new Date().toISOString()},fuel=null,exported;
const server=http.createServer((req,res)=>{
 const name=req.url==='/'?'index.html':req.url.slice(1).split('?')[0];
 if(!['index.html','icon.svg','manifest.webmanifest'].includes(name)){res.writeHead(404).end();return}
 res.setHeader('Content-Type',name.endsWith('html')?'text/html':name.endsWith('svg')?'image/svg+xml':'application/manifest+json');res.end(fs.readFileSync(path.join(generated,name)));
});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 try{
 const context=await browser.newContext({viewport:{width:412,height:900}}),page=await context.newPage();
 async function expectFuel(value){await page.waitForFunction(expected=>document.getElementById('remainingNumber').textContent===expected,value,{timeout:8000})}
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await context.route('https://bieshuowola.github.io/**',r=>r.abort()); // exercise offline oil-price fallback
 await page.exposeFunction('nativeMessage',msg=>{
  const m=JSON.parse(msg);
  if(m.type==='fuel'){fuel=m.fuel;state={...state,events:JSON.stringify(fuel.events)};return state}
  if(m.type==='export')exported=m.fuel;
  return state;
 });
 await page.addInitScript(()=>{window.NativeFuel={postMessage:message=>{window.nativeMessage(message).then(s=>window.androidReceive(s))}}});
 page.on('dialog',d=>d.accept());
 await page.goto(`http://127.0.0.1:${server.address().port}/`);
 await page.fill('#initOdo','26000');await page.click('#initBtn');
 await expectFuel('12.5');
 const bar=await page.locator('#fuelGauge>span').count();assert.equal(bar,20);
 await page.click('#mileageBtn');await page.fill('#entryOdo','26100');await page.click('#saveMileage');await expectFuel('8.6');
 await page.click('#refuelBtn');await page.fill('#quantity','2');await page.click('#saveRefuel');
 await expectFuel('10.6');
 // Adding GPS changes the original animated display, not the saved ledger.
 state={...state,km:20};await page.evaluate(s=>window.androidReceive(s),state);await expectFuel('9.8');assert.equal(fuel.events.length,3);
 // Full fill at real ODO reconciles transient consumption; learning uses only confirmed ODO.
 state.km=0;await page.click('#refuelBtn');await page.fill('#entryOdo','26250');await page.fill('#quantity','8');await page.click('#fullToggle');await page.click('#saveRefuel');await expectFuel('12.5');
 await page.click('#refuelBtn');await page.fill('#entryOdo','26270');await page.click('#switchMode');await page.fill('#quantity','40');await page.fill('#price','8');await page.click('#saveRefuel');await page.waitForTimeout(1100);
 // Over-capacity partial fill is flagged as unreliable by the original model.
 assert.ok((await page.textContent('#statusPill')).includes('校准'));
 await page.click('#settingsBtn');await page.click('#exportBtn');await page.waitForTimeout(100);assert.equal(exported.version,1);assert.equal(exported.events.length,5);
 const original=JSON.stringify(exported.events);await page.evaluate(o=>window.androidImport(o),exported);await page.waitForTimeout(100);
 assert.equal(JSON.stringify(fuel.events),original);
 state={...state,unknown:true};await page.evaluate(s=>window.androidReceive(s),state);await page.waitForTimeout(50);
 assert.equal(await page.textContent('#safeRangeNumber'),'--');assert.equal(await page.textContent('#remainingNumber'),'--');assert.ok((await page.textContent('#percentLabel')).includes('--'));
 state={...state,unknown:false,km:0};await page.evaluate(s=>window.androidReceive(s),state);
 await context.setOffline(true);await page.click('#historyBtn');assert.ok((await page.textContent('#modalHeading')).includes('历史'));await page.click('#closeModal');
 // APK AssetLoader cold offline launch is a device test; loaded-page calculations work without network.
 await context.setOffline(false);await page.goto(`http://127.0.0.1:${server.address().port}/`);await page.waitForTimeout(1100);
 assert.equal(JSON.stringify(fuel.events),original);
 assert.deepEqual(errors,[]);
 console.log('PASS: initialization, mileage, partial/full refuel, money entry, GPS animated update, uncertainty, legacy backup, persistence, offline oil-price fallback; 20 gauge bars retained');
 }finally{await browser.close()}
})().then(()=>server.close()).catch(e=>{console.error(e);server.close();process.exitCode=1});
