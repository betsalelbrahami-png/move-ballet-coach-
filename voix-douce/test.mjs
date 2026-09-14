
import { chromium } from 'playwright';
import { readFileSync,writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
const html=readFileSync('voix-douce/index.html','utf8');
const server=createServer((req,res)=>{res.setHeader('Content-Type','text/html');res.end(html)}).listen(8765,'127.0.0.1');
const browser=await chromium.launch({headless:true,args:['--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream']});
const context=await browser.newContext({permissions:['microphone'],viewport:{width:390,height:844}});
const page=await context.newPage();
const errors=[];page.on('pageerror',e=>errors.push(e.message));
page.on('requestfailed',r=>console.log('NETWORK_FAILURE',r.url(),r.failure()?.errorText));
page.on('console',m=>{if(m.type()==='error')console.log('BROWSER_ERROR',m.text());});
const report={};
for(const url of ['https://swc2-target-speaker-extraction.hf.space/config','https://huggingface.co/api/spaces/swc2/Target-speaker-extraction','https://betsalelbrahami-png.github.io/move-ballet-coach-/voix-douce/']){try{const r=await fetch(url,{signal:AbortSignal.timeout(20000)});console.log('HTTP_CHECK',url,r.status,(await r.text()).slice(0,150));}catch(e){console.log('HTTP_CHECK_FAILED',url,e.message);}}
try{
 await page.goto('http://127.0.0.1:8765');
 report.ui=await page.evaluate(()=>({title:document.title,overflow:document.documentElement.scrollWidth>innerWidth,initialDisabled:document.getElementById('process').disabled}));
 assert.equal(report.ui.overflow,false);assert.equal(report.ui.initialDisabled,true);
 report.dsp=await page.evaluate(()=>{
  const n=32000,m=new Float32Array(n),t=new Float32Array(n),b=new Float32Array(n);
  for(let i=0;i<n;i++){t[i]=.2*Math.sin(2*Math.PI*317*i/16000);b[i]=.14*Math.sin(2*Math.PI*739*i/16000);m[i]=t[i]+b[i];}
  const e=VoixDSP.estimate(m,Float32Array.from(t,x=>2.8*x)),o=VoixDSP.subtract(m,e.target,1);
  const error=Math.sqrt(o.reduce((s,x,i)=>s+(x-b[i])**2,0)/n);
  const delayed=new Float32Array(n),randomMix=new Float32Array(n);let seed=42;
  for(let i=0;i<n;i++){seed=(1664525*seed+1013904223)>>>0;randomMix[i]=(seed/4294967296-.5)*.2;}
  for(let i=0;i<n-37;i++)delayed[i+37]=randomMix[i]*-2;
  const d=VoixDSP.estimate(randomMix,delayed);
  return {error,gain:e.gain,lag:d.lag,delayedGain:d.gain,zeroExact:VoixDSP.subtract(m,e.target,0).every((x,i)=>x===m[i])};
 });
 assert(report.dsp.error<1e-6);assert(report.dsp.zeroExact);assert.equal(report.dsp.lag,37);
 // Verify actual microphone capture and automatic ten-second stop.
 await page.click('#recordRef');
 await page.waitForFunction(()=>document.getElementById('refInfo').textContent.includes('prête'),{},{timeout:20000});
 report.microphone=await page.locator('#refInfo').textContent();
 assert(await page.locator('#recordMix').isEnabled());
 // Deterministic local output import through the same UI as previous HF test files.
 const make=await page.evaluate(()=>{
  const n=16000*3,t=Float32Array.from({length:n},(_,i)=>.2*Math.sin(2*Math.PI*317*i/16000)),m=Float32Array.from(t,(x,i)=>x+.15*Math.sin(2*Math.PI*739*i/16000));
  return {m:Array.from(new Uint8Array(VoixDSP.wavBytes(m))),v:Array.from(new Uint8Array(VoixDSP.wavBytes(Float32Array.from(t,x=>x*2.7))))};
 });
 await page.locator('summary').filter({hasText:'déjà les deux fichiers'}).click();
 await page.setInputFiles('#processedFile',{name:'deal_input.wav',mimeType:'audio/wav',buffer:Buffer.from(make.m)});
 await page.setInputFiles('#extractedFile',{name:'temp_extracted.wav',mimeType:'audio/wav',buffer:Buffer.from(make.v)});
 await page.click('#localTest');
 await page.waitForFunction(()=>!document.getElementById('results').hidden);
 assert((await page.locator('#download').getAttribute('href')).startsWith('blob:'));
 report.localImport='passed';
 // Generate distinct, known speech sources for a real model call; no user audio.
 execFileSync('espeak',['-v','fr-fr','-s','140','-w','/tmp/ref.wav','Bonjour, je parle ici tout seul pour enregistrer ma voix. Je continue pendant quelques secondes. Ceci est une référence pour reconnaître ma voix dans une conversation. Je raconte une histoire et je parle normalement.']);
 execFileSync('espeak',['-v','fr-fr','-s','145','-w','/tmp/target.wav','Demain matin nous irons ensemble au marché pour acheter des fruits et des légumes. Ensuite nous préparerons un bon repas et nous inviterons nos amis.']);
 execFileSync('espeak',['-v','en-us+f3','-s','160','-w','/tmp/other.wav','I am the second person in this conversation. My voice should stay in the recording. Today the weather is nice and I would like to go for a walk in the garden.']);
 for(const f of ['ref','target','other'])execFileSync('ffmpeg',['-y','-loglevel','error','-i','/tmp/'+f+'.wav','-ar','16000','-ac','1','/tmp/'+f+'16.wav']);
 const sources=await page.evaluate(async({t,b})=>{
  const c=new AudioContext({sampleRate:16000});
  const ta=(await c.decodeAudioData(Uint8Array.from(t).buffer)).getChannelData(0),ba=(await c.decodeAudioData(Uint8Array.from(b).buffer)).getChannelData(0);
  const n=Math.max(ta.length,ba.length)+32000,target=new Float32Array(n),other=new Float32Array(n);
  target.set(ta);other.set(ba,32000);
  let peak=0;for(let i=0;i<n;i++)peak=Math.max(peak,Math.abs(target[i]+other[i]));
  const scale=.7/(peak||1);
  for(let i=0;i<n;i++){target[i]*=scale;other[i]*=scale;}
  window.testTarget=target;window.testOther=other;
  const mix=Float32Array.from(target,(x,i)=>x+other[i]);
  return Array.from(new Uint8Array(VoixDSP.wavBytes(mix)));
 },{t:[...readFileSync('/tmp/target16.wav')],b:[...readFileSync('/tmp/other16.wav')]});
 await page.setInputFiles('#refFile','/tmp/ref16.wav');
 await page.waitForFunction(()=>!document.getElementById('mixFile').disabled);
 await page.setInputFiles('#mixFile',{name:'mixture.wav',mimeType:'audio/wav',buffer:Buffer.from(sources)});
 await page.waitForFunction(()=>!document.getElementById('results').hidden||document.getElementById('status').classList.contains('error'),{},{timeout:320000});
 report.remoteStatus=await page.locator('#status').textContent();
 if(await page.locator('#results').isVisible()){
  report.model=await page.evaluate(async()=>{
   const c=new AudioContext({sampleRate:16000}),o=(await c.decodeAudioData(await(await fetch(document.getElementById('after').src)).arrayBuffer())).getChannelData(0);
   let tt=0,bb=0,tb=0,ot=0,ob=0;
   for(let i=0;i<o.length;i++){const t=testTarget[i]||0,b=testOther[i]||0;tt+=t*t;bb+=b*b;tb+=t*b;ot+=o[i]*t;ob+=o[i]*b;}
   const det=tt*bb-tb*tb;
   const targetGain=(ot*bb-ob*tb)/det,otherGain=(ob*tt-ot*tb)/det;
   return {targetGain,otherGain,targetAttenuationDB:-20*Math.log10(Math.max(Math.abs(targetGain),1e-10)),otherLevelDB:20*Math.log10(Math.max(Math.abs(otherGain),1e-10))};
  });
  report.realModelCall='passed';
  report.selectiveSuppression=report.model.targetAttenuationDB>=6&&Math.abs(report.model.otherLevelDB)<=3?'passed':'not demonstrated';
 }else{report.realModelCall='failed';}
 report.javascriptErrors=errors;
 await page.screenshot({path:'/tmp/voix-douce-test.png',fullPage:true});
 console.log(JSON.stringify(report,null,2));
 writeFileSync('/tmp/voix-douce-report.json',JSON.stringify(report,null,2));
 assert.equal(errors.length,0);
 assert.equal(report.realModelCall,'passed');
}finally{await browser.close();server.close();}
