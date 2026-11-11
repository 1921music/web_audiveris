// Original native cleanup/OCR/font inputs, with actual TEXTS recognition and phase suspension in Wasm.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('playwright');const root=path.resolve(process.argv[2]),native=path.join(root,'native');
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
function install(api,data,templates,model){
    api.configure(new Int8Array(model.buffer,model.byteOffset,model.byteLength));
    for(const m of data.serifs){api.configureSerifs(m.family,m.interline,...m.dimensions);}
    for(const m of data.geometry){api.configureGeometry(m.kind,m.family,m.shape,m.interline,m.num,m.den,...m.values);}
    for(const m of data.widths){api.configurePointWidth(m.family,m.shape,m.pointSize,m.width);}
    api.defaultTextFont(data.defaultFont);
    for(const m of data.fonts){api.configureTextFont(m.kind,m.flags,m.size,m.value,m.family,m.name,m.x,m.y,m.width,m.height,m.advance);}
    for(const bytes of templates){api.installTemplates(new Int8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength));}
}
(async()=>{
    const data={serifs:read(path.resolve(root,'../cue-beams-default-portable-probe/font-measurements.json')),
        geometry:read(path.join(native,'symbol-geometry.json')),widths:read(path.join(native,'point-widths.json')),
        fonts:read(path.join(native,'font-measurements.json')),defaultFont:inputBytes(path.join(native,'default-font.txt')).toString('utf8')};
    const required=new Set(references.flatMap(row=>row.result.templatesUsed||[]));
    const templateRoot=path.resolve(root,'../template-original-probe'),catalog=read(path.join(templateRoot,'reference.json')).catalogs.filter(row=>required.has(row.family+':'+row.pointSize));
    assert.equal(catalog.length,required.size);const templates=catalog.map(row=>{const bytes=inputBytes(path.join(templateRoot,row.file+'.atpl'));assert.equal(sha(bytes),row.sha256);return bytes;});
    vm.runInThisContext(inputBytes(path.join(root,'texts-continuation.wasm-runtime.js')).toString('utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    let checks=0;
    for(let instance=0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(root,'texts-continuation.wasm'))).exports;install(api,data,templates,model);let generation=0;
        for(const row of references){const meta=read(path.join(native,row.id,'metadata.json')),pixels=inputBytes(path.join(samples,row.id+'.binary')),cleaned=inputBytes(path.join(native,row.id,'cleaned.gray'));
            assert.deepEqual(read(path.join(native,row.id,'original-lines.json')),read(path.join(native,row.id,'imported-lines.json')));
            const pixelHash=sha(pixels),cleanHash=sha(cleaned),request=api.beginPage(meta.width,meta.height,new Int8Array(pixels),new Int8Array(cleaned),row.id,meta.margin,meta.languages,++generation);
            assert.equal(request.width,meta.width+2*meta.margin);assert.equal(request.height,meta.height+2*meta.margin);
            const stale={token:{...request.token,generation:request.token.generation-1}};Object.defineProperty(stale,'result',{get(){throw new Error('Late OCR payload decoded');}});assert.equal(api.receive(stale),false);
            await new Promise(resolve=>setImmediate(resolve));const reply={token:request.token,result:read(path.join(native,row.id,'ocr-result.json'))};
            assert.ok(api.receive(reply));assert.equal(api.receive(reply),false);const actual=JSON.parse(api.resume());
            try{compare(actual,row.result,'node'+instance+'.'+row.id);}catch(error){fs.writeFileSync(path.join(root,'failed-node.json'),JSON.stringify({actual,expected:row.result}));throw error;}
            assert.equal(sha(pixels),pixelHash);assert.equal(sha(cleaned),cleanHash);checks++;await new Promise(resolve=>setImmediate(resolve));
        }
    }
    const files=new Map([['/module.wasm',path.join(root,'texts-continuation.wasm')],['/runtime.js',path.join(root,'texts-continuation.wasm-runtime.js')]]);
    for(let i=0;i<catalog.length;i++){files.set('/templates/'+i,path.join(templateRoot,catalog[i].file+'.atpl'));}
    for(const row of references){files.set('/pixels/'+row.id,path.join(samples,row.id+'.binary'));files.set('/cleaned/'+row.id,path.join(native,row.id,'cleaned.gray'));}
    const fixture={data,pages:references.map(row=>({meta:read(path.join(native,row.id,'metadata.json')),result:read(path.join(native,row.id,'ocr-result.json'))})),templates:catalog.map((_,i)=>'/templates/'+i)};
    const worker=`importScripts('/runtime.js');onmessage=async()=>{try{
        const fixture=await(await fetch('/fixture')).json(),model=new Uint8Array(await(await fetch('/model')).arrayBuffer()),templates=[];
        for(const url of fixture.templates){templates.push(new Uint8Array(await(await fetch(url)).arrayBuffer()));}
        const api=(await TeaVM.wasmGC.load('/module.wasm')).exports;
        const install=${install.toString()};install(api,fixture.data,templates,model);const rows=[];let generation=0;
        for(const page of fixture.pages){const m=page.meta,pixels=new Int8Array(await(await fetch('/pixels/'+m.id)).arrayBuffer()),cleaned=new Int8Array(await(await fetch('/cleaned/'+m.id)).arrayBuffer());
            const request=api.beginPage(m.width,m.height,pixels,cleaned,m.id,m.margin,m.languages,++generation);
            await new Promise(resolve=>setTimeout(resolve,0));const reply={token:request.token,result:page.result};
            if(!api.receive(reply)||api.receive(reply)){throw new Error('TEXTS reply admission');}rows.push({id:m.id,result:JSON.parse(api.resume())});await new Promise(resolve=>setTimeout(resolve,0));
        }postMessage({rows,dom:typeof document});
    }catch(error){postMessage({error:String(error.stack||error)});}};`;
    const server=http.createServer((req,res)=>{
        if(req.url==='/'){return res.end('<!doctype html><title>TEXTS continuation</title>');}
        if(req.url==='/fixture'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(fixture));}
        if(req.url==='/model'){return res.end(model);}
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        const file=files.get(req.url);if(!file){res.statusCode=404;return res.end();}res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':file.endsWith('.js')?'text/javascript':'application/octet-stream');fs.createReadStream(file).pipe(res);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{browser=await chromium.launch({headless:true,args:['--js-flags=--max-old-space-size=768']});const context=await browser.newContext(),origin='http://127.0.0.1:'+server.address().port;
        await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());const page=await context.newPage();await page.goto(origin+'/');
        // Sequential worker runs keep peak heap bounded while proving independent module resources.
        for(let instance=0;instance<2;instance++){
            const row=await page.evaluate(()=>new Promise((resolve,reject)=>{const worker=new Worker('/worker.js');const timer=setTimeout(()=>{worker.terminate();reject(new Error('TEXTS timeout'));},240000);
                worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(null);}));
            assert.equal(row.dom,'undefined');for(let i=0;i<references.length;i++){compare(row.rows[i].result,references[i].result,'worker'+instance+'.'+references[i].id);checks++;}
        }
        const bytes=inputBytes(path.join(root,'texts-continuation.wasm')),report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,pages:references.length,inputSha256,
            examinedNativePages:coverage.examinedPages,rejectedBeforeTexts:coverage.rejectedBeforeTexts,
            checks,numericValues:numbers,maxDifference,originalTextWords:references.reduce((sum,row)=>sum+row.result.words,0),fontMeasurements:data.fonts.length,
            originalSystemProcessing:true,asynchronousReplyBoundary:true,duplicateAndLateRepliesRejected:true,wasmBytes:bytes.length,wasmSha256:sha(bytes),
            limitation:'Original native-cleaned rasters, OCR replies and exact font measurements are supplied as fixtures; browser-native cleanup/fonts and live OCR on score pages are not yet established'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
