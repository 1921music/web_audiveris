// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]),packageRoot=path.join(root,'build/wasm-engine/package'),modules=path.join(root,'wasm-compat/node_modules');
const bundledOcr=process.env.AUDIVERIS_BUNDLED_OCR==='1';
const localFixture=process.env.AUDIVERIS_LOCAL_OCR_FIXTURE==='1';
const backend=bundledOcr?JSON.parse(fs.readFileSync(path.join(packageRoot,'ocr/assets.json'))).backend:'tesseract-js';
(async()=>{
    const features=require('wasm-feature-detect'),simd=await features.relaxedSimd()?'relaxedsimd':await features.simd()?'simd':'',core='tesseract-core'+(simd?'-'+simd:'');
    const files=new Map();
    function add(directory,prefix){for(const item of fs.readdirSync(directory,{withFileTypes:true})){const file=path.join(directory,item.name),url=prefix+'/'+item.name;if(item.isDirectory())add(file,url);else files.set(url,file);}}
    add(packageRoot,'/engine');
    files.set('/ocr/tesseract.js',path.join(modules,'tesseract.js/dist/tesseract.min.js'));
    files.set('/ocr/worker.js',path.join(modules,'tesseract.js/dist/worker.min.js'));
    for(const extension of ['js','wasm'])files.set('/core/'+core+'.'+extension,path.join(modules,'tesseract.js-core',core+'.'+extension));
    files.set('/models/eng.traineddata.gz',path.join(modules,'@tesseract.js-data/eng/4.0.0/eng.traineddata.gz'));
    const reference=path.join(root,'build/wasm-compat/texts-continuation-probe/live-ocr/jvm');
    const pages=(localFixture?['00']:['00','04']).map(id=>JSON.parse(fs.readFileSync(path.join(reference,id,'metadata.json'))));
    for(const page of pages)files.set('/pixels/'+page.id,path.join(root,'build/wasm-compat/pixel-probe/samples',page.id+(localFixture?'.binary':'.gray')));
    const server=http.createServer((request,response)=>{
        const url=new URL(request.url,'http://localhost').pathname;
        if(url==='/')return response.end('<!doctype html><title>Standalone engine Workers</title>');
        const file=files.get(url);if(!file){response.statusCode=404;return response.end();}
        response.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':/\.(m?js|cjs)$/.test(file)?'text/javascript':'application/octet-stream');
        fs.createReadStream(file).pipe(response);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,timeout:1200000,args:['--js-flags=--max-old-space-size=768']});
        const context=await browser.newContext(),origin='http://127.0.0.1:'+server.address().port;
        await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
        const page=await context.newPage();page.on('console',message=>console.log(message.text()));page.on('pageerror',error=>console.log('PAGE ERROR '+error.stack));
        await page.goto(origin+'/');if(!bundledOcr)await page.addScriptTag({url:origin+'/ocr/tesseract.js'});
        const result=await page.evaluate(async({pages,core,bundledOcr,localFixture})=>{
            const {EngineWorker}=await import('/engine/host/worker-client.mjs'),{encodeGray8Png}=await import('/engine/host/gray8-png.mjs');
            const {TesseractJsOcr}=await import('/engine/host/tesseract-js-ocr.mjs'),{browserWordMetadataOptions}=await import('/engine/host/tesseract-word-metadata.mjs');
            const adapter=bundledOcr?await(await import('/engine/host/create-browser-ocr.mjs')).createBrowserOcr():new TesseractJsOcr({oem:0,availableLanguages:['eng'],createWorker:Tesseract.createWorker,
                workerOptions:browserWordMetadataOptions({workerPath:location.origin+'/ocr/worker.js',corePath:location.origin+'/core/'+core+'.js',langPath:location.origin+'/models',cacheMethod:'none',gzip:true},
                    {loaderPath:location.origin+'/engine/host/tesseract-core-word-metadata.js',metadataModulePath:location.origin+'/engine/host/tesseract-word-metadata.mjs'})});
            const clients=pages.map(()=>new EngineWorker());let abortDuringInit=false,abortDuringRecognition=false,rejectedBusy=false;
            try{
                const controller=new AbortController(),cancelled=clients[0].recognize(new Blob(),{ocr:()=>{},signal:controller.signal}).catch(error=>error.name);
                controller.abort();abortDuringInit=await cancelled==='AbortError';
                const abort=new AbortController();
                const cancelledJob=clients[0].recognize({width:32,height:32,pixels:new Uint8Array(1024).fill(255)},
                    {ocr:()=>{},signal:abort.signal,onProgress:()=>abort.abort()}).catch(error=>error.name);
                abortDuringRecognition=await cancelledJob==='AbortError';
                const results=await Promise.all(pages.map(async(meta,index)=>{
                    const pixels=new Uint8Array(await fetch('/pixels/'+meta.id).then(r=>r.arrayBuffer()));
                    if(localFixture){
                        function rectangle(x,y,w,h){for(let row=y;row<y+h;row++)pixels.fill(0,row*meta.width+x,row*meta.width+x+w);}
                        const letters=['0011100','0110110','1100011','1100011','1111111','1100011','1100011','1100011','1100011'];
                        for(const x of [1750,1900]){
                            rectangle(x,740,66,3);rectangle(x,803,66,3);rectangle(x,740,3,66);rectangle(x+63,740,3,66);
                            letters.forEach((row,y)=>[...row].forEach((bit,c)=>{if(bit==='1')rectangle(x+19+c*4,755+y*4,4,4);}));
                        }
                    }
                    const png=await encodeGray8Png({width:meta.width,height:meta.height,pixels});
                    const image=new Blob([png],{type:'image/png'}),stages=[],ocrLayouts=[];let requests=0;
                    const job=clients[index].recognize(image,{id:meta.id,margin:meta.margin,languages:meta.languages,
                        ocr:async(request,{signal})=>{requests++;ocrLayouts.push(request.layout);const reply=await adapter.recognize(request,{signal});if(reply.failure)throw new Error('OCR '+meta.id+': '+JSON.stringify(reply.failure));console.log('OCR '+meta.id+' '+request.layout+' '+JSON.stringify(reply.result.lines.map(line=>line.words.map(word=>word.text))));return reply;},
                        onProgress:({stage})=>{if(stages.at(-1)!==stage){stages.push(stage);console.log('Worker '+meta.id+': '+stage);}}});
                    if(index===0){try{await clients[index].recognize(image,{ocr:()=>{}});}catch(error){rejectedBusy=error.message.includes('busy');}}
                    return {...await job,stages,ocrRequests:requests,ocrLayouts};
                }));
                return {results,abortDuringInit,abortDuringRecognition,rejectedBusy};
            }finally{clients.forEach(client=>client.close());await adapter.close();}
        },{pages,core,bundledOcr,localFixture});
        assert.ok(result.abortDuringInit&&result.abortDuringRecognition&&result.rejectedBusy);
        const output=path.join(root,'build/wasm-engine',localFixture?'local-ocr-workers':'workers');fs.mkdirSync(output,{recursive:true});
        if(localFixture){
            assert.ok(result.results[0].ocrLayouts.filter(layout=>layout==='SINGLE_BLOCK').length>=1,'No local OCR resume exercised');
            if(backend==='tesseract-js'){assert.ok(result.results[0].ocrLayouts.filter(layout=>layout==='SINGLE_BLOCK').length>=2);assert.ok(result.results[0].scores.some(score=>/<rehearsal(?:\s|>)/.test(score.musicXml)));}
        }
        for(const row of result.results){assert.equal(row.stage,'PAGE');assert.equal(row.stages.length,19);assert.ok(row.ocrRequests>=1);for(const score of row.scores){assert.match(score.musicXml,/<note(?:\s|>)/);fs.writeFileSync(path.join(output,row.id+'-'+score.index+'.musicxml'),score.musicXml);}}
        fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({backend,chromium:browser.version(),concurrentWorkers:pages.length,localFixture,decodedPng:true,bundledOcr,abortDuringInit:result.abortDuringInit,abortDuringRecognition:result.abortDuringRecognition,rejectedBusy:result.rejectedBusy,
            results:result.results.map(({scores,...row})=>({...row,scores:scores.length,rehearsals:scores.reduce((n,score)=>n+(score.musicXml.match(/<rehearsal(?:\s|>)/g)||[]).length,0)}))},null,2)+'\n');
        console.log('Concurrent standalone image Workers passed');
    }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
