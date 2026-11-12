// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]),assets=path.join(root,'build/wasm-engine/package');
(async()=>{
    const limits='/sys/fs/cgroup/wasm_audiveris_dev/';
    assert.equal(fs.readFileSync(limits+'cpu.max','utf8').trim(),'800000 100000');
    assert.equal(fs.readFileSync(limits+'memory.max','utf8').trim(),'8589934592');
    assert.ok(fs.readFileSync('/proc/self/cgroup','utf8').includes('/wasm_audiveris_dev/'));
    const manifest=JSON.parse(fs.readFileSync(path.join(assets,'ocr/assets.json')));
    const server=http.createServer((request,response)=>{
        const name=new URL(request.url,'http://localhost').pathname,file=path.resolve(assets,'.'+(name==='/'?'/index.html':name));
        if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)){response.statusCode=404;return response.end();}
        response.setHeader('Content-Type',file.endsWith('.html')?'text/html; charset=utf-8':file.endsWith('.wasm')?'application/wasm':/\.[mc]?js$/.test(file)?'text/javascript':'application/octet-stream');
        fs.createReadStream(file).pipe(response);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,timeout:120000,args:['--js-flags=--max-old-space-size=768']});
        const page=await browser.newPage(),origin='http://127.0.0.1:'+server.address().port,errors=[];
        await page.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
        page.on('pageerror',e=>errors.push(String(e)));await page.goto(origin+'/index.html');
        const report=await page.evaluate(async backend=>{
            const {createBrowserOcr}=await import('./host/create-browser-ocr.mjs');
            const canvas=document.createElement('canvas');canvas.width=650;canvas.height=110;
            const context=canvas.getContext('2d');context.fillStyle='white';context.fillRect(0,0,650,110);
            context.fillStyle='black';context.font='48px sans-serif';context.fillText('Hello OCR',30,72);
            const rgba=context.getImageData(0,0,650,110).data,pixels=new Uint8Array(650*110);
            for(let i=0;i<pixels.length;i++)pixels[i]=rgba[i*4];
            const request=n=>({token:{job:'ocr',page:'canvas',generation:n,request:1},width:650,height:110,pixels,languages:'eng',layout:'SINGLE_BLOCK'});
            const adapter=await createBrowserOcr(),times=[],texts=[];
            try{
                // Abort initialization while a second job is queued: both must remain recoverable.
                const startAbort=new AbortController(),first=adapter.recognize(request(1),{signal:startAbort.signal});
                await new Promise(resolve=>setTimeout(resolve,20));startAbort.abort();
                await first.then(()=>{throw new Error('Initialization abort ignored');},e=>{if(e.name!=='AbortError')throw e;});
                for(let n=2;n<=3;n++){
                    const start=performance.now(),reply=await adapter.recognize(request(n));times.push(performance.now()-start);
                    if(reply.failure)throw new Error(JSON.stringify(reply.failure));
                    const words=reply.result.lines.flatMap(line=>line.words),text=words.map(word=>word.text).join(' ');texts.push(text);
                    if(text!=='Hello OCR')throw new Error('Unexpected OCR text: '+text);
                    if(words.some(w=>!w.characters.length||!w.baseline||!w.font||w.characters.some(c=>c.bounds.width<=0)))throw new Error('Missing actual character/baseline/font data');
                }
                const unknown=await adapter.recognize({...request(4),languages:'missing'});
                if(unknown.failure?.code!=='MODEL_MISSING')throw new Error('Missing language not rejected');
                const queuedAbort=new AbortController(),active=adapter.recognize(request(5)),queued=adapter.recognize(request(6),{signal:queuedAbort.signal});
                queuedAbort.abort();await queued.then(()=>{throw new Error('Queued abort ignored');},e=>{if(e.name!=='AbortError')throw e;});
                if((await active).failure)throw new Error('Queued abort disrupted active OCR');
            }finally{await adapter.close();}
            let geometryChecks=false;
            if(backend==='tesseract-wasm'){
                const {normalizeWasmHocr,TesseractWasmOcr}=await import('./host/tesseract-wasm-ocr.mjs');
                const hocr=`<div class="ocr_page"><span class="ocr_line" title="bbox 10 20 100 60; baseline 0.1 -5"><span class="ocrx_word" title="bbox 30 25 70 55; x_wconf 85; x_fsize 24"><span class="ocrx_cinfo" title="x_bboxes 30 25 38 55">&amp;</span><span class="ocrx_cinfo" title="x_bboxes 40 25 70 55">W</span></span></span></div>`;
                const word=normalizeWasmHocr(hocr).lines[0].words[0];
                if(word.text!=='&W'||word.characters[0].bounds.width!==8||word.characters[1].bounds.width!==30||word.baseline.y1!==57||word.baseline.y2!==61)throw new Error('hOCR normalization changed real geometry');
                const noBaseline=normalizeWasmHocr(hocr.replace('; baseline 0.1 -5','')).lines[0].words[0];
                if(noBaseline.font!==null||noBaseline.baseline!==null||noBaseline.text!=='&W')throw new Error('Missing baseline was fabricated or admitted to TEXTS');
                let rejected=false;try{normalizeWasmHocr(hocr.replaceAll('ocrx_cinfo','missing'));}catch{rejected=true;}if(!rejected)throw new Error('Missing characters fabricated');
                let worker,retired,terminated=0,created=0;
                const mock=new TesseractWasmOcr({assets:{},createWorker:()=>{
                    const generation=++created;
                    worker={postMessage(packet){
                        if(packet.type==='init')queueMicrotask(()=>this.onmessage({data:{id:packet.id}}));
                        else if(generation===2){
                            retired.onerror({message:'late error from terminated worker',preventDefault(){}});
                            queueMicrotask(()=>this.onmessage({data:{id:packet.id,hocr}}));
                        }
                    },terminate(){terminated++;}};
                    if(generation===1)retired=worker;return worker;
                }});
                const abort=new AbortController(),pending=mock.recognize(request(7),{signal:abort.signal});
                await new Promise(resolve=>setTimeout(resolve,0));abort.abort();
                await pending.then(()=>{throw new Error('Active OCR abort ignored');},e=>{if(e.name!=='AbortError')throw e;});
                const retry=await mock.recognize(request(8));if(retry.failure||terminated!==1)throw new Error('Late retired-worker error disrupted retry');
                await mock.close();if(terminated!==2)throw new Error('Active native OCR worker leaked');geometryChecks=true;
            }
            return {texts,timesMs:times,initializationAbortAndRetry:true,queuedAbortIsolated:true,missingModelRejected:true,geometryChecks};
        },manifest.backend);
        assert.deepEqual(errors,[]);
        const output=path.join(root,'build/wasm-engine/ocr-checks');fs.mkdirSync(output,{recursive:true});
        const result={backend:manifest.backend,chromium:browser.version(),...report,pageErrors:errors,cpuMax:'800000 100000',memoryMaxBytes:8589934592};
        fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
    }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
