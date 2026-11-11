const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto'),zlib=require('node:zlib'),vm=require('node:vm');
const assert=require('node:assert/strict');const {pathToFileURL}=require('node:url');
const {chromium}=require('playwright');const {createWorker}=require('tesseract.js');
const features=require('wasm-feature-detect');const root=path.resolve(process.argv[2]),compat=path.resolve(__dirname,'..');
const oem=Number(process.env.AUDIVERIS_OCR_OEM??0);assert.ok([0,1].includes(oem));
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
function withoutMargin(result,margin){
    const restored=structuredClone(result);
    const shiftBaseline=baseline=>{if(baseline){baseline.x1-=margin;baseline.x2-=margin;baseline.y1-=margin;baseline.y2-=margin;}};
    for(const line of restored.lines){shiftBaseline(line.baseline);for(const word of line.words){
        word.bounds.x-=margin;word.bounds.y-=margin;shiftBaseline(word.baseline);
        for(const character of word.characters){character.bounds.x-=margin;character.bounds.y-=margin;}
    }}return restored;
}
(async()=>{
    const {TesseractJsOcr,normalizeTesseractResult}=await import(pathToFileURL(path.join(compat,'host/tesseract-js-ocr.mjs')));
    const {encodeGray8Png}=await import(pathToFileURL(path.join(compat,'host/gray8-png.mjs')));
    const {attachNativeWordMetadata}=await import(pathToFileURL(path.join(compat,'host/tesseract-word-metadata.mjs')));
    const unknownFontData={blocks:[{paragraphs:[{lines:[{words:[{text:'x',confidence:90,bbox:{x0:0,y0:0,x1:1,y1:1},
        audiveris_font:null,symbols:[{text:'x',bbox:{x0:0,y0:0,x1:1,y1:1}}]}]}]}]}]};
    assert.equal(normalizeTesseractResult(unknownFontData,true).capabilities.fontAttributes,true);
    assert.equal(normalizeTesseractResult(unknownFontData,false).capabilities.fontAttributes,false);
    assert.throws(()=>attachNativeWordMetadata({}));
    let destroyed=0;const iterator={Begin(){},Empty(){return false;},getBoundingBox(){return {x0:1,y0:0,x1:1,y1:1};},Next(){return false;}};
    function MockApi(){}MockApi.prototype.GetJSONText=()=>JSON.stringify({blocks:[{paragraphs:[{lines:[{words:[{bbox:{x0:0,y0:0,x1:1,y1:1}}]}]}]}]});
    MockApi.prototype.GetIterator=()=>iterator;
    function MockIterator(){}Object.assign(MockIterator.prototype,{getWordFontAttributes(){},getBaseline(){},getBoundingBox(){},Confidence(){}});
    const mockModule={TessBaseAPI:MockApi,ResultIterator:MockIterator,destroy(value){assert.equal(value,iterator);destroyed++;}};
    attachNativeWordMetadata(mockModule);const wrapped=MockApi.prototype.GetJSONText;attachNativeWordMetadata(mockModule);
    assert.equal(MockApi.prototype.GetJSONText,wrapped);assert.throws(()=>new MockApi().GetJSONText(),/not aligned/);assert.equal(destroyed,1);
    const fixture=JSON.parse(fs.readFileSync(path.join(root,'fixture.json'),'utf8'));
    const original=new Uint8Array(fs.readFileSync(path.join(root,'input.gray')));
    const transportRoot=path.resolve(root,'../../ocr-transport-probe');
    vm.runInThisContext(fs.readFileSync(path.join(transportRoot,'ocr-transport.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    const engine=(await TeaVM.wasmGC.load(path.join(transportRoot,'ocr-transport.wasm'))).exports;
    const otherEngine=(await TeaVM.wasmGC.load(path.join(transportRoot,'ocr-transport.wasm'))).exports;
    const engineRequest=(api,generation,layout='SINGLE_BLOCK',pixels=original,languages='eng')=>api.imageRequest(fixture.width,fixture.height,new Int8Array(pixels.buffer,pixels.byteOffset,pixels.byteLength),languages,layout,generation);
    const png=await encodeGray8Png({...fixture,pixels:original});fs.writeFileSync(path.join(root,'host.png'),png);
    // Independent zlib decoding verifies the packed GRAY8 bytes, not just successful recognition.
    let at=8,idat=[];while(at<png.length){const length=new DataView(png.buffer,png.byteOffset+at,4).getUint32(0);
        const type=Buffer.from(png.subarray(at+4,at+8)).toString('ascii');if(type==='IDAT'){idat.push(png.subarray(at+8,at+8+length));}at+=length+12;}
    const decoded=zlib.inflateSync(Buffer.concat(idat));
    for(let y=0;y<fixture.height;y++){
        assert.equal(decoded[y*(fixture.width+1)],0);
        assert.deepEqual(decoded.subarray(y*(fixture.width+1)+1,(y+1)*(fixture.width+1)),Buffer.from(original.subarray(y*fixture.width,(y+1)*fixture.width)));
    }
    const simdFlavor=await features.relaxedSimd()?'relaxedsimd':await features.simd()?'simd':'';
    const coreFlavor=[simdFlavor,oem===1?'lstm':''].filter(Boolean).join('-');
    const coreName='tesseract-core'+(coreFlavor?'-'+coreFlavor:'');
    const modules=path.join(compat,'node_modules'),modelDir=path.join(modules,'@tesseract.js-data/eng',oem===0?'4.0.0':'4.0.0_best_int');
    let created=0,modes=[],versions=[];
    const trackedFactory=async(...args)=>{
        created++;assert.equal(args[1],oem);const worker=await createWorker(...args),set=worker.setParameters.bind(worker),recognize=worker.recognize.bind(worker);
        worker.setParameters=async params=>{modes.push(params.tessedit_pageseg_mode);return set(params);};
        worker.recognize=async(...args)=>{const result=await recognize(...args);versions.push(result.data.version);return result;};return worker;
    };
    const adapter=new TesseractJsOcr({oem,createWorker:trackedFactory,availableLanguages:['eng'],workerOptions:{
        workerPath:path.join(compat,'host/tesseract-word-metadata-node.cjs'),langPath:modelDir,cacheMethod:'none',gzip:true}});
    const request=(id,layout='SINGLE_BLOCK',pixels=original)=>({token:{job:'ocr-backend',page:'fixture',generation:1,request:id},width:fixture.width,height:fixture.height,pixels,languages:'eng',layout});
    function recognized(reply){
        if(reply.failure){throw new Error(reply.failure.code+': '+reply.failure.message);}
        const words=reply.result.lines.flatMap(line=>line.words),text=words.map(word=>word.text).join(' ');
        for(const expected of fixture.expected){assert.ok(text.includes(expected),text);}
        assert.ok(words.every(word=>word.characters.length>0));return reply.result;
    }
    let first,second,blank,padded;
    try{
        const pixels=original.slice(),one=engineRequest(engine,1,'SINGLE_BLOCK',pixels),two=engineRequest(otherEngine,2,'MULTI_BLOCK',pixels);
        const a=adapter.recognize(one),b=adapter.recognize(two);one.pixels.fill(0);two.pixels.fill(0);pixels.fill(0);
        [first,second]=await Promise.all([a,b]);recognized(first);recognized(second);
        assert.ok(first.result.lines.every(line=>line.words.every(word=>word.baseline!==null)));
        if(oem===0){
            assert.ok(first.result.capabilities.fontAttributes);
            const words=first.result.lines.flatMap(line=>line.words);
            assert.ok(words.every(word=>word.font&&word.font.pointSize>0&&word.font.name===null));
            assert.equal(words[0].font.bold,true);assert.equal(words[2].font.serif,true);
        }
        assert.ok(engine.receive(first));assert.ok(otherEngine.receive(second));assert.equal(engine.resumeSummary(),'2/4/23');assert.equal(otherEngine.resumeSummary(),'2/4/23');
        blank=await adapter.recognize(engineRequest(engine,3,'SINGLE_BLOCK',new Uint8Array(original.length).fill(255)));
        assert.ok(blank.result);assert.equal(blank.result.lines.flatMap(line=>line.words).length,0);
        assert.ok(engine.receive(blank));assert.equal(engine.resumeSummary(),'0/0/0');
        const paddedRequest=engine.paddedImageRequest(fixture.width,fixture.height,new Int8Array(original.buffer,original.byteOffset,original.byteLength),10,'eng','SINGLE_BLOCK',5);
        assert.equal(paddedRequest.width,fixture.width+20);assert.equal(paddedRequest.height,fixture.height+20);
        padded=await adapter.recognize(paddedRequest);recognized(padded);
        assert.ok(engine.receive(padded));assert.ok(!engine.receive(padded));assert.ok(engine.resumeMatches(withoutMargin(padded.result,10)));
        const missing=await adapter.recognize(engineRequest(engine,4,'SINGLE_BLOCK',original,'deu'));assert.equal(missing.failure.code,'MODEL_MISSING');
        assert.ok(engine.receive(missing));assert.equal(engine.resumeSummary(),'failure:MODEL_MISSING');
        assert.equal(created,1);assert.deepEqual(modes,['6','3','6','6']);
    }finally{await adapter.close();}
    assert.equal((await adapter.recognize(request(5))).failure.code,'EXECUTION_FAILED');
    assert.throws(()=>normalizeTesseractResult({text:'word',blocks:null}));
    assert.throws(()=>normalizeTesseractResult({blocks:[{paragraphs:[{lines:[{words:[{text:'word',confidence:90,bbox:{x0:0,y0:0,x1:20,y1:10},symbols:[]}]}]}]}]}));
    const broken=new TesseractJsOcr({availableLanguages:['eng'],createWorker:async()=>{throw new Error('core initialization failure');}});
    assert.equal((await broken.recognize(request(6))).failure.code,'EXECUTION_FAILED');await broken.close();
    assert.throws(()=>new TesseractJsOcr({availableLanguages:'eng',createWorker}));
    let mockCreated=0,mockTerminated=0,reinitialized=[],fault=true;
    const recovery=new TesseractJsOcr({availableLanguages:['eng','deu'],encodeImage:async()=>new Uint8Array(),createWorker:async()=>{
        mockCreated++;return {setParameters:async()=>{},reinitialize:async language=>{reinitialized.push(language);},
            recognize:async()=>{if(fault){fault=false;throw new Error('worker execution failure');}return {data:{text:'',blocks:[]}};},
            terminate:async()=>{mockTerminated++;}};
    }});
    assert.equal((await recovery.recognize(request(7))).failure.code,'EXECUTION_FAILED');
    assert.ok((await recovery.recognize(request(8))).result);
    assert.ok((await recovery.recognize({...request(9),languages:'deu'})).result);
    assert.ok((await recovery.recognize(request(10))).result);
    assert.equal(mockCreated,2);assert.deepEqual(reinitialized,['deu','eng']);await recovery.close();assert.equal(mockTerminated,2);
    const files={
        '/ocr-transport.wasm':path.join(transportRoot,'ocr-transport.wasm'),'/ocr-transport.wasm-runtime.js':path.join(transportRoot,'ocr-transport.wasm-runtime.js'),
        '/host/tesseract-js-ocr.mjs':path.join(compat,'host/tesseract-js-ocr.mjs'),'/host/gray8-png.mjs':path.join(compat,'host/gray8-png.mjs'),
        '/host/tesseract-word-metadata.mjs':path.join(compat,'host/tesseract-word-metadata.mjs'),
        '/host/tesseract-core-word-metadata.js':path.join(compat,'host/tesseract-core-word-metadata.js'),
        '/ocr-assets/tesseract.min.js':path.join(modules,'tesseract.js/dist/tesseract.min.js'),
        '/ocr-assets/worker.min.js':path.join(modules,'tesseract.js/dist/worker.min.js'),
        ['/core-assets/'+coreName+'.js']:path.join(modules,'tesseract.js-core',coreName+'.js'),
        ['/core-assets/'+coreName+'.wasm']:path.join(modules,'tesseract.js-core',coreName+'.wasm'),
        '/models/eng.traineddata.gz':path.join(modelDir,'eng.traineddata.gz')
    };
    let origin;const requested={};
    const server=http.createServer((req,res)=>{
        const url=new URL(req.url,origin||'http://127.0.0.1').pathname;
        if(url==='/'){res.end('<!doctype html><title>Local OCR backend verification</title>');return;}
        if(url==='/outer-worker.js'){
            res.setHeader('Content-Type','text/javascript');res.end(`importScripts('/ocr-assets/tesseract.min.js','/ocr-transport.wasm-runtime.js');onmessage=async event=>{
                let adapter;try{const {TesseractJsOcr}=await import('/host/tesseract-js-ocr.mjs');
                    const {browserWordMetadataOptions}=await import('/host/tesseract-word-metadata.mjs');let created=0,versions=[];
                    adapter=new TesseractJsOcr({oem:${oem},availableLanguages:['eng'],createWorker:async(...args)=>{
                        created++;const worker=await Tesseract.createWorker(...args),recognize=worker.recognize.bind(worker);
                        worker.recognize=async(...args)=>{const result=await recognize(...args);versions.push(result.data.version);return result;};return worker;
                    },workerOptions:browserWordMetadataOptions({workerPath:'${origin}/ocr-assets/worker.min.js',corePath:'${origin}/core-assets/${coreName}.js',langPath:'${origin}/models',cacheMethod:'none',gzip:true},
                        {loaderPath:'${origin}/host/tesseract-core-word-metadata.js',metadataModulePath:'${origin}/host/tesseract-word-metadata.mjs'})});
                    const api=(await TeaVM.wasmGC.load('/ocr-transport.wasm')).exports,input=event.data;
                    const request=generation=>api.imageRequest(input.width,input.height,new Int8Array(input.pixels.buffer),'eng','SINGLE_BLOCK',generation);
                    const started=performance.now(),first=await adapter.recognize(request(1)),firstRequestMs=performance.now()-started;
                    if(!api.receive(first)){throw new Error('First OCR reply token');}const summary=api.resumeSummary();
                    const warmStarted=performance.now(),second=await adapter.recognize(request(2)),warmRequestMs=performance.now()-warmStarted;
                    if(!api.receive(second)){throw new Error('Second OCR reply token');}const warmSummary=api.resumeSummary();
                    const padded=await adapter.recognize(api.paddedImageRequest(input.width,input.height,new Int8Array(input.pixels.buffer),10,'eng','SINGLE_BLOCK',3));
                    const withoutMargin=${withoutMargin.toString()};
                    if(!padded.result||!api.receive(padded)||api.receive(padded)||!api.resumeMatches(withoutMargin(padded.result,10))){throw new Error('Padded OCR coordinate restoration');}
                    await adapter.close();adapter=null;postMessage({first,second,padded,frameCoordinatesRestored:true,created,versions,summary,warmSummary,firstRequestMs,warmRequestMs,dom:typeof document});
                }catch(error){postMessage({error:String(error.stack||error)});}finally{if(adapter){await adapter.close();}}
            };`);return;
        }
        const file=files[url];if(!file){res.writeHead(404).end();return;}
        requested[url]=(requested[url]||0)+1;
        res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':file.endsWith('.gz')?'application/octet-stream':'text/javascript');
        fs.createReadStream(file).pipe(res);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin=`http://127.0.0.1:${server.address().port}`;let browser;
    try{
        browser=await chromium.launch({headless:true});const context=await browser.newContext(),external=[];
        await context.route('**/*',route=>{if(new URL(route.request().url()).origin!==origin){external.push(route.request().url());return route.abort();}return route.continue();});
        const page=await context.newPage();await page.goto(origin+'/');
        const workers=await page.evaluate(input=>Promise.all([0,1].map(()=>new Promise((resolve,reject)=>{
            const worker=new Worker('/outer-worker.js');const timer=setTimeout(()=>{worker.terminate();reject(new Error('Tesseract worker timeout'));},120000);
            worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};
            worker.onmessage=event=>{clearTimeout(timer);worker.terminate();event.data.error?reject(new Error(event.data.error)):resolve(event.data);};
            const pixels=new Uint8Array(input.pixels);worker.postMessage({...input,pixels},[pixels.buffer]);
        }))),{width:fixture.width,height:fixture.height,pixels:Array.from(original)});
        assert.deepEqual(external,[]);
        for(const row of workers){recognized(row.first);recognized(row.second);recognized(row.padded);assert.ok(row.frameCoordinatesRestored);assert.deepEqual(row.padded.result,padded.result);assert.deepEqual(row.first.result,first.result);assert.deepEqual(row.second.result,row.first.result);assert.equal(row.summary,'2/4/23');assert.equal(row.warmSummary,'2/4/23');assert.equal(row.created,1);assert.equal(row.dom,'undefined');}
        const assets=Object.fromEntries(Object.entries(files).map(([url,file])=>{const bytes=fs.readFileSync(file);return[url,{bytes:bytes.length,sha256:sha(bytes)}];}));
        const result={backend:'tesseract.js',version:require('tesseract.js/package.json').version,coreVersion:require('tesseract.js-core/package.json').version,
            modelPackage:require('@tesseract.js-data/eng/package.json').version,oem,upstreamEngineMode:oem===0,coreFlavor,node:process.version,chromium:browser.version(),workers:2,
            engineVersions:[...new Set([...versions,...workers.flatMap(row=>row.versions)])],nativeInputBytes:original.length,pngBytes:png.length,pngPixelsExact:true,
            nodeWorkerReused:true,workerResultsExact:true,queuedInputSnapshotsOwned:true,layoutParametersHonored:true,blankSuccessDistinct:true,missingModelDistinct:true,
            simulatedFailureRecovery:true,simulatedLanguageReinitialization:true,
            wasmRequestAndReplyIntegrated:true,wasmBlankAndModelFailureDistinct:true,paddedFrameCoordinatesRestored:true,configuredWhiteMargin:10,
            nativeWordMetadata:true,realCharacterBoxes:true,fontAttributesAvailable:first.result.lines.some(line=>line.words.some(word=>word.font!==null)),lineBaselineAvailable:first.result.lines.some(line=>line.baseline!==null),
            metadataAlignmentGuard:true,metadataIteratorReleasedOnFailure:true,metadataHookIdempotent:true,separateCoreAssetDirectory:true,
            wordBaselineAvailable:first.result.lines.some(line=>line.words.some(word=>word.baseline!==null)),
            workerTimings:workers.map(row=>({firstRequestMs:row.firstRequestMs,warmRequestMs:row.warmRequestMs})),
            words:first.result.lines.flatMap(line=>line.words).map(word=>word.text),characters:first.result.lines.flatMap(line=>line.words).reduce((sum,word)=>sum+word.characters.length,0),
            allBrowserAssetsLocal:true,assets,requested,limitation:'External OCR adapter fixture only; no upstream native OCR parity, font fallback, TEXTS cleanup or full recognition integration is claimed'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(result,null,2)+'\n');
        fs.writeFileSync(path.join(root,'results.json'),JSON.stringify({first,second,blank,padded,workers},null,2)+'\n');console.log(JSON.stringify(result));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
