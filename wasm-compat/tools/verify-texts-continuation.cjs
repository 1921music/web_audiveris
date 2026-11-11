// Original native cleanup/OCR/font inputs, with actual TEXTS recognition and phase suspension in Wasm.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('playwright');const live=process.env.AUDIVERIS_LIVE_TEXTS==='1',compat=path.resolve(__dirname,'..');
const root=path.resolve(process.argv[2]),native=path.join(root,live?'live-ocr/jvm':'native');
const ttf=process.env.AUDIVERIS_TEXTS_TTF==='1';assert.ok(!ttf||live);
const cleanup=process.env.AUDIVERIS_TEXTS_CLEANUP==='1';assert.ok(!cleanup||ttf);
const moduleName=cleanup?'texts-cleanup':ttf?'texts-font-backed':'texts-continuation',moduleRoot=cleanup?path.resolve(root,'../texts-cleanup-probe'):ttf?path.resolve(root,'../texts-font-backed-probe'):root;
const textFontRoot=path.resolve(root,'../recorded-text-fonts');
const launchTimeout=Number(process.env.AUDIVERIS_BROWSER_LAUNCH_TIMEOUT_MS||600000),workerTimeout=Number(process.env.AUDIVERIS_TEXTS_WORKER_TIMEOUT_MS||600000);
assert.ok(Number.isSafeInteger(launchTimeout)&&launchTimeout>=0&&Number.isSafeInteger(workerTimeout)&&workerTimeout>0);
const modules=path.join(compat,'node_modules');let coreName;
const inputSha256={},sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const inputBytes=file=>{const bytes=fs.readFileSync(file);inputSha256[path.relative(root,path.resolve(file))]=sha(bytes);return bytes;};
const read=file=>JSON.parse(inputBytes(file));
const references=read(path.join(native,'jvm-reference.json')),samples=path.resolve(root,'../pixel-probe/samples');
const coverage=read(path.join(native,'coverage.json'));assert.equal(coverage.acceptedPages,references.length);
assert.equal(read(path.join(native,'jvm-rejections.json')).length,coverage.rejectedBeforeTexts);
const model=inputBytes(process.argv[3]);let numbers=0,maxDifference=0;
function compare(a,b,key='$'){
    if(typeof b==='number'){assert.ok(Number.isFinite(a),key);const error=Math.abs(a-b);numbers++;maxDifference=Math.max(maxDifference,error);
        const tolerance=key.endsWith('.grade')?2e-13:key.endsWith('.slope')?1e-12:1e-8;assert.ok(error<=tolerance,key+': '+a+' != '+b);}
    else if(Array.isArray(b)){assert.ok(Array.isArray(a),key);assert.equal(a.length,b.length,key);b.forEach((v,i)=>compare(a[i],v,key+'['+i+']'));}
    else if(b&&typeof b==='object'){assert.deepEqual(Object.keys(a).sort(),Object.keys(b).sort(),key);for(const k of Object.keys(b)){compare(a[k],b[k],key+'.'+k);}}
    else{assert.equal(a,b,key);}
}
function install(api,data,templates,model,faces=[]){
    api.configure(new Int8Array(model.buffer,model.byteOffset,model.byteLength));
    for(const m of data.serifs){api.configureSerifs(m.family,m.interline,...m.dimensions);}
    for(const m of data.geometry){api.configureGeometry(m.kind,m.family,m.shape,m.interline,m.num,m.den,...m.values);}
    for(const m of data.widths){api.configurePointWidth(m.family,m.shape,m.pointSize,m.width);}
    api.defaultTextFont(data.defaultFont);
    for(const face of faces){api.installTextFace(face.id,new Int8Array(face.bytes.buffer,face.bytes.byteOffset,face.bytes.byteLength));}
    for(const style of data.textStyles||[]){api.bindTextFace(style.flags,style.face,style.name,style.syntheticItalic);}
    for(const m of data.fonts){api.configureTextFont(m.kind,m.flags,m.size,m.value,m.family,m.name,m.x,m.y,m.width,m.height,m.advance);}
    for(const bytes of templates){api.installTemplates(new Int8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength));}
}
function checkCleanup(request,cleaned,width,height,margin){
    const stride=width+2*margin,rows=height+2*margin;
    if(request.width!==stride||request.height!==rows||request.pixels.length!==stride*rows){throw new Error('Cleanup request dimensions');}
    for(let y=0;y<rows;y++){for(let x=0;x<stride;x++){
        const expected=y>=margin&&y<height+margin&&x>=margin&&x<width+margin?(cleaned[(y-margin)*width+x-margin]&255):255;
        if((request.pixels[y*stride+x]&255)!==expected){throw new Error('Cleanup pixels differ at '+x+','+y);}
    }}
}
(async()=>{
    if(live){const features=require('wasm-feature-detect');const simd=await features.relaxedSimd()?'relaxedsimd':await features.simd()?'simd':'';coreName='tesseract-core'+(simd?'-'+simd:'');}
    const adapterFactory=async()=>{
        const {TesseractJsOcr}=await import(require('node:url').pathToFileURL(path.join(compat,'host/tesseract-js-ocr.mjs')));
        return new TesseractJsOcr({oem:0,availableLanguages:['eng'],createWorker:require('tesseract.js').createWorker,
            workerOptions:{workerPath:path.join(compat,'host/tesseract-word-metadata-node.cjs'),langPath:path.join(modules,'@tesseract.js-data/eng/4.0.0'),cacheMethod:'none',gzip:true}});
    };
    const data={serifs:read(path.resolve(root,'../cue-beams-default-portable-probe/font-measurements.json')),
        geometry:read(path.join(native,'symbol-geometry.json')),widths:read(path.join(native,'point-widths.json')),
        fonts:read(ttf?path.join(textFontRoot,'music-measurements.json'):path.join(native,'font-measurements.json')),defaultFont:inputBytes(path.join(native,'default-font.txt')).toString('utf8')};
    const textResources=ttf?read(path.join(textFontRoot,'font-resources.json')):null;
    const faces=textResources?textResources.faces.map(face=>{const bytes=inputBytes(path.join(textFontRoot,'fonts',face.file));assert.equal(sha(bytes),face.sha256);return {...face,bytes};}):[];
    if(ttf){data.textStyles=textResources.styles;assert.ok(data.fonts.every(entry=>entry.kind>=2));}
    const required=new Set(references.flatMap(row=>row.result.templatesUsed||[]));
    const templateRoot=path.resolve(root,'../template-original-probe'),catalog=read(path.join(templateRoot,'reference.json')).catalogs.filter(row=>required.has(row.family+':'+row.pointSize));
    assert.equal(catalog.length,required.size);const templates=catalog.map(row=>{const bytes=inputBytes(path.join(templateRoot,row.file+'.atpl'));assert.equal(sha(bytes),row.sha256);return bytes;});
    vm.runInThisContext(inputBytes(path.join(moduleRoot,moduleName+'.wasm-runtime.js')).toString('utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    inputBytes(path.join(moduleRoot,moduleName+'.wasm'));
    if(live){for(const file of [path.join(modules,'@tesseract.js-data/eng/4.0.0/eng.traineddata.gz'),
        path.join(modules,'tesseract.js-core',coreName+'.js'),path.join(modules,'tesseract.js-core',coreName+'.wasm'),
        ...['tesseract-js-ocr.mjs','gray8-png.mjs','tesseract-word-metadata.mjs','tesseract-word-metadata-node.cjs'].map(name=>path.join(compat,'host',name))]){inputBytes(file);}}
    const verifierHash=sha(fs.readFileSync(__filename,'utf8').replace(/\r\n/g,'\n')),checkpointPath=path.join(moduleRoot,'node-verification.json');
    const resumeNode=process.env.AUDIVERIS_RESUME_TEXTS_NODE==='1';
    let checks=0;const fontCalls=[];
    if(resumeNode){
        const saved=JSON.parse(fs.readFileSync(checkpointPath));
        assert.equal(saved.verifierHash,verifierHash);assert.equal(saved.node,process.version);assert.equal(saved.live,live);assert.equal(saved.ttf,ttf);
        assert.equal(saved.cleanup,cleanup);
        assert.equal(saved.checks,references.length*2);assert.ok(Number.isSafeInteger(saved.numbers)&&saved.numbers>0&&Number.isFinite(saved.maxDifference)&&saved.maxDifference<=1e-8);
        for(const [name,digest] of Object.entries(inputSha256)){assert.equal(saved.inputSha256[name],digest,'Node checkpoint input mismatch: '+name);}
        const project=path.resolve(root,'../../..')+path.sep;
        for(const [name,digest] of Object.entries(saved.inputSha256)){const file=path.resolve(root,name);assert.ok(file.startsWith(project));assert.equal(sha(fs.readFileSync(file)),digest,name);}
        Object.assign(inputSha256,saved.inputSha256);checks=saved.checks;numbers=saved.numbers;maxDifference=saved.maxDifference;
        if(ttf){assert.equal(saved.fontCalls.length,2);assert.ok(saved.fontCalls.every(value=>value>0));fontCalls.push(...saved.fontCalls);}
        console.log('Reusing Node phase with identical verifier, module and input hashes');
    }
    for(let instance=resumeNode?2:0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(moduleRoot,moduleName+'.wasm'))).exports;install(api,data,templates,model,faces);let generation=0;
        const adapter=live?await adapterFactory():null;try{
        for(const row of references){const meta=read(path.join(native,row.id,'metadata.json')),pixels=inputBytes(path.join(samples,row.id+'.binary')),cleaned=inputBytes(path.join(native,row.id,'cleaned.gray'));
            if(!live){assert.deepEqual(read(path.join(native,row.id,'original-lines.json')),read(path.join(native,row.id,'imported-lines.json')));}
            const pixelHash=sha(pixels),cleanHash=sha(cleaned),request=cleanup
                ?api.beginPage(meta.width,meta.height,new Int8Array(pixels),row.id,meta.margin,meta.languages,++generation)
                :api.beginPage(meta.width,meta.height,new Int8Array(pixels),new Int8Array(cleaned),row.id,meta.margin,meta.languages,++generation);
            if(cleanup){checkCleanup(request,cleaned,meta.width,meta.height,meta.margin);}
            if(ttf&&generation===1){assert.throws(()=>api.installTextFace('late-font',new Int8Array(faces[0].bytes)));}
            assert.equal(request.width,meta.width+2*meta.margin);assert.equal(request.height,meta.height+2*meta.margin);
            const stale={token:{...request.token,generation:request.token.generation-1}};Object.defineProperty(stale,'result',{get(){throw new Error('Late OCR payload decoded');}});assert.equal(api.receive(stale),false);
            await new Promise(resolve=>setImmediate(resolve));const expectedOcr=read(path.join(native,row.id,'ocr-result.json'));
            const reply=live?await adapter.recognize(request):{token:request.token,result:expectedOcr};
            assert.ok(!reply.failure,JSON.stringify(reply.failure));assert.deepEqual(reply.result,expectedOcr);
            assert.ok(api.receive(reply));assert.equal(api.receive(reply),false);const actual=JSON.parse(api.resume());
            try{compare(actual,row.result,'node'+instance+'.'+row.id);}catch(error){fs.writeFileSync(path.join(root,'failed-node.json'),JSON.stringify({actual,expected:row.result}));throw error;}
            assert.equal(sha(pixels),pixelHash);assert.equal(sha(cleaned),cleanHash);checks++;await new Promise(resolve=>setImmediate(resolve));
        }
        if(ttf){assert.ok(api.textMeasurements()>0);fontCalls.push(api.textMeasurements());}
        }finally{if(adapter){await adapter.close();}}
    }
    if(!resumeNode){fs.writeFileSync(checkpointPath,JSON.stringify({verifierHash,node:process.version,live,ttf,cleanup,inputSha256,checks,numbers,maxDifference,fontCalls})+'\n');}
    const nodePhase=JSON.parse(fs.readFileSync(checkpointPath));
    const files=new Map([['/module.wasm',path.join(moduleRoot,moduleName+'.wasm')],['/runtime.js',path.join(moduleRoot,moduleName+'.wasm-runtime.js')]]);
    if(ttf){for(let i=0;i<faces.length;i++){files.set('/text-fonts/'+i,path.join(textFontRoot,'fonts',faces[i].file));}}
    if(live){
        for(const name of ['tesseract-js-ocr.mjs','gray8-png.mjs','tesseract-word-metadata.mjs','tesseract-core-word-metadata.js']){files.set('/host/'+name,path.join(compat,'host',name));}
        files.set('/ocr-assets/tesseract.min.js',path.join(modules,'tesseract.js/dist/tesseract.min.js'));
        files.set('/ocr-assets/worker.min.js',path.join(modules,'tesseract.js/dist/worker.min.js'));
        files.set('/core-assets/'+coreName+'.js',path.join(modules,'tesseract.js-core',coreName+'.js'));
        files.set('/core-assets/'+coreName+'.wasm',path.join(modules,'tesseract.js-core',coreName+'.wasm'));
        files.set('/models/eng.traineddata.gz',path.join(modules,'@tesseract.js-data/eng/4.0.0/eng.traineddata.gz'));
    }
    for(let i=0;i<catalog.length;i++){files.set('/templates/'+i,path.join(templateRoot,catalog[i].file+'.atpl'));}
    for(const row of references){files.set('/pixels/'+row.id,path.join(samples,row.id+'.binary'));files.set('/cleaned/'+row.id,path.join(native,row.id,'cleaned.gray'));}
    const fixture={data,pages:references.map(row=>({meta:read(path.join(native,row.id,'metadata.json')),result:read(path.join(native,row.id,'ocr-result.json'))})),templates:catalog.map((_,i)=>'/templates/'+i),
        textFaces:faces.map((face,index)=>({id:face.id,url:'/text-fonts/'+index}))};
    const worker=`importScripts('/runtime.js');${live?"importScripts('/ocr-assets/tesseract.min.js');":""}onmessage=async()=>{let adapter;try{
        const fixture=await(await fetch('/fixture')).json(),model=new Uint8Array(await(await fetch('/model')).arrayBuffer()),templates=[];
        for(const url of fixture.templates){templates.push(new Uint8Array(await(await fetch(url)).arrayBuffer()));}
        const api=(await TeaVM.wasmGC.load('/module.wasm')).exports,faces=[];
        for(const face of fixture.textFaces){faces.push({id:face.id,bytes:new Uint8Array(await(await fetch(face.url)).arrayBuffer())});}
        const install=${install.toString()},checkCleanup=${checkCleanup.toString()};install(api,fixture.data,templates,model,faces);const rows=[];let generation=0;
        ${live?`const {TesseractJsOcr}=await import('/host/tesseract-js-ocr.mjs'),{browserWordMetadataOptions}=await import('/host/tesseract-word-metadata.mjs');
        adapter=new TesseractJsOcr({oem:0,availableLanguages:['eng'],createWorker:Tesseract.createWorker,
            workerOptions:browserWordMetadataOptions({workerPath:location.origin+'/ocr-assets/worker.min.js',corePath:location.origin+'/core-assets/${coreName}.js',langPath:location.origin+'/models',cacheMethod:'none',gzip:true},
                {loaderPath:location.origin+'/host/tesseract-core-word-metadata.js',metadataModulePath:location.origin+'/host/tesseract-word-metadata.mjs'})});`:''}
        for(const page of fixture.pages){const m=page.meta,pixels=new Int8Array(await(await fetch('/pixels/'+m.id)).arrayBuffer()),cleaned=new Int8Array(await(await fetch('/cleaned/'+m.id)).arrayBuffer());
            const request=${cleanup?'api.beginPage(m.width,m.height,pixels,m.id,m.margin,m.languages,++generation)':'api.beginPage(m.width,m.height,pixels,cleaned,m.id,m.margin,m.languages,++generation)'};
            ${cleanup?'checkCleanup(request,cleaned,m.width,m.height,m.margin);':''}
            ${ttf?`if(generation===1){let rejected=false;try{api.installTextFace('late-font',new Int8Array(faces[0].bytes));}catch(error){rejected=true;}if(!rejected){throw new Error('Late font configuration accepted');}}`:''}
            await new Promise(resolve=>setTimeout(resolve,0));const reply=adapter?await adapter.recognize(request):{token:request.token,result:page.result};
            if(reply.failure){throw new Error(JSON.stringify(reply.failure));}
            if(JSON.stringify(reply.result)!==JSON.stringify(page.result)){throw new Error('Live OCR differs from JVM oracle input');}
            if(!api.receive(reply)||api.receive(reply)){throw new Error('TEXTS reply admission');}rows.push({id:m.id,result:JSON.parse(api.resume())});await new Promise(resolve=>setTimeout(resolve,0));
        }postMessage({rows,dom:typeof document,fontCalls:${ttf?'api.textMeasurements()':'0'}});
    }catch(error){postMessage({error:String(error.stack||error)});}finally{if(adapter){await adapter.close();}}};`;
    const server=http.createServer((req,res)=>{
        if(req.url==='/'){return res.end('<!doctype html><title>TEXTS continuation</title>');}
        if(req.url==='/fixture'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(fixture));}
        if(req.url==='/model'){return res.end(model);}
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        const file=files.get(new URL(req.url,'http://127.0.0.1').pathname);if(!file){res.statusCode=404;return res.end();}res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':/\.(m?js)$/.test(file)?'text/javascript':'application/octet-stream');fs.createReadStream(file).pipe(res);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{browser=await chromium.launch({headless:true,timeout:launchTimeout,args:['--js-flags=--max-old-space-size=768']});const context=await browser.newContext(),origin='http://127.0.0.1:'+server.address().port;
        await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());const page=await context.newPage();await page.goto(origin+'/');
        // Sequential worker runs keep peak heap bounded while proving independent module resources.
        for(let instance=0;instance<2;instance++){
            const row=await page.evaluate(timeout=>new Promise((resolve,reject)=>{const worker=new Worker('/worker.js');const timer=setTimeout(()=>{worker.terminate();reject(new Error('TEXTS timeout'));},timeout);
                worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(null);}),workerTimeout);
            assert.equal(row.dom,'undefined');if(ttf){assert.ok(row.fontCalls>0);fontCalls.push(row.fontCalls);}for(let i=0;i<references.length;i++){compare(row.rows[i].result,references[i].result,'worker'+instance+'.'+references[i].id);checks++;}
        }
        assert.equal(sha(fs.readFileSync(__filename,'utf8').replace(/\r\n/g,'\n')),verifierHash);
        for(const [name,digest] of Object.entries(nodePhase.inputSha256)){assert.equal(sha(fs.readFileSync(path.resolve(root,name))),digest,'Node phase input changed: '+name);}
        const bytes=inputBytes(path.join(moduleRoot,moduleName+'.wasm')),report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,pages:references.length,inputSha256,
            examinedNativePages:coverage.examinedPages,rejectedBeforeTexts:coverage.rejectedBeforeTexts,
            checks,numericValues:numbers,maxDifference,originalTextWords:references.reduce((sum,row)=>sum+row.result.words,0),fontMeasurements:data.fonts.length,
            originalSystemProcessing:true,asynchronousReplyBoundary:true,duplicateAndLateRepliesRejected:true,wasmBytes:bytes.length,wasmSha256:sha(bytes),
            liveOcr:live,ordinaryFontsFromFiles:ttf,
            originalCleanupInWasm:cleanup,suppliedCleanupRasters:cleanup?0:references.length,cleanupReferencePixelsExact:cleanup,
            resumedNodeValidation:resumeNode,nodeCheckpointSha256:sha(fs.readFileSync(checkpointPath)),verifierSha256:verifierHash,
            limitation:cleanup?'Original TEXTS cleanup, actual OCR and TEXTS resume with physical text fonts; two music-font measurements remain supplied; thin-stroke drawing, music-symbol painting, general shaping/fallback and native OCR parity are not claimed':ttf?'Actual OCR and TEXTS resume with ordinary text measured from physical TTF files; native cleanup and two music-font measurements remain supplied; general shaping/fallback and native OCR parity are not claimed':live?'Actual Tesseract.js OCR and TEXTS resume; original native-cleaned rasters and exact font measurements remain supplied; native OCR parity is not claimed'
                :'Original native-cleaned rasters, OCR replies and exact font measurements are supplied as fixtures; browser-native cleanup/fonts and live OCR on score pages are not yet established'};
        if(live){report.ocrAssets=Object.fromEntries([...files].filter(([url])=>url.startsWith('/host/')||url.startsWith('/ocr-assets/')||url.startsWith('/core-assets/')||url.startsWith('/models/')).map(([url,file])=>{const bytes=inputBytes(file);return [url,{bytes:bytes.length,sha256:sha(bytes)}];}));}
        if(ttf){report.textFontFiles=faces.map(({bytes,...face})=>({...face,bytes:bytes.length}));report.textFontMeasurementCalls=fontCalls;report.fontResourcesSealed=true;report.suppliedOrdinaryFontMeasurements=0;}
        fs.writeFileSync(ttf?path.join(moduleRoot,'verification.json'):path.join(root,live?'live-ocr/verification.json':'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
