// SPDX-License-Identifier: AGPL-3.0-or-later
const assert=require('node:assert/strict'),path=require('node:path');
const {pathToFileURL}=require('node:url');
(async()=>{
    const {TesseractJsOcr}=await import(pathToFileURL(path.resolve(__dirname,'../host/tesseract-js-ocr.mjs')));
    const tick=()=>new Promise(resolve=>setImmediate(resolve));
    const request=n=>({token:{job:'cancel',page:'page',generation:n,request:1},width:1,height:1,pixels:new Uint8Array([255]),languages:'eng',layout:'SINGLE_BLOCK'});
    let created=0,terminated=0,finish;
    const worker=()=>({setParameters:async()=>{},recognize:()=>new Promise(resolve=>{finish=()=>resolve({data:{text:'',blocks:[]}});}),terminate:async()=>{terminated++;}});
    const adapter=new TesseractJsOcr({availableLanguages:['eng'],encodeImage:async()=>new Uint8Array(),createWorker:async()=>{created++;return worker();}});
    const first=adapter.recognize(request(1));await tick();assert.equal(created,1);
    const queuedAbort=new AbortController(),queued=adapter.recognize(request(2),{signal:queuedAbort.signal});
    queuedAbort.abort();await assert.rejects(queued,{name:'AbortError'});assert.equal(terminated,0,'Queued cancellation stopped another active request');
    finish();assert.ok((await first).result);await tick();
    const activeAbort=new AbortController(),active=adapter.recognize(request(3),{signal:activeAbort.signal});await tick();
    const before=terminated;activeAbort.abort();await assert.rejects(active,{name:'AbortError'});await tick();assert.equal(terminated,before+1,'Active OCR was not terminated');
    const retry=adapter.recognize(request(4));await tick();finish();assert.ok((await retry).result);await adapter.close();
    let resolveCreation;const startingAbort=new AbortController();
    const starting=new TesseractJsOcr({availableLanguages:['eng'],encodeImage:async()=>new Uint8Array(),createWorker:()=>new Promise(resolve=>{resolveCreation=resolve;})});
    const initializing=starting.recognize(request(5),{signal:startingAbort.signal});await tick();startingAbort.abort();await assert.rejects(initializing,{name:'AbortError'});
    const prior=terminated;resolveCreation(worker());await tick();assert.equal(terminated,prior+1,'Late-created OCR Worker leaked');await starting.close();
    console.log(JSON.stringify({queuedAbortIsolated:true,activeAbortTerminated:true,retrySucceeded:true,initializationAbortCleaned:true,created,terminated}));
})().catch(error=>{console.error(error);process.exitCode=1;});
