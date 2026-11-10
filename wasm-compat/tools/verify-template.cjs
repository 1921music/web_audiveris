const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('playwright');const root=path.resolve(process.argv[2]);
const original=path.resolve(root,'../template-original-probe'),portable=path.resolve(root,'../template-portable-probe');
const reference=JSON.parse(fs.readFileSync(path.join(original,'reference.json'),'utf8'));
assert.deepEqual(JSON.parse(fs.readFileSync(path.join(portable,'reference.json'),'utf8')),reference);
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');let comparedBytes=0;
for(const row of reference.catalogs){for(const extension of ['atpl','match']){
    assert.deepEqual(fs.readFileSync(path.join(original,row.file+'.'+extension)),fs.readFileSync(path.join(portable,row.file+'.'+extension)));
}}
(async()=>{
    vm.runInThisContext(fs.readFileSync(path.join(root,'templates.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    const load=async()=> (await TeaVM.wasmGC.load(path.join(root,'templates.wasm'))).exports;
    for(const explicit of [false,true]){
        const api=await load();assert.equal(api.missing(),true);if(explicit){api.explicitSource();}
        for(const [i,row] of reference.catalogs.entries()){
            const expected=fs.readFileSync(path.join(original,row.file+'.atpl')),matching=fs.readFileSync(path.join(original,row.file+'.match'));
            assert.equal(sha(expected),row.sha256);assert.equal(sha(matching),row.matchingSha256);
            if(i===0){
                assert.equal(api.rejects(expected.subarray(0,expected.length-1)),true);
                assert.equal(api.rejects(Buffer.concat([expected,Buffer.of(0)])),true);
                const bad=Buffer.from(expected);bad[0]=0;assert.equal(api.rejects(bad),true);
            }
            const input=Buffer.from(expected);api.install(input);input.fill(0);
            assert.equal(api.rejects(expected),true);
            assert.deepEqual(Buffer.from(api.catalog(row.family,row.pointSize)),expected,`${row.file} data`);
            assert.deepEqual(Buffer.from(api.match(row.family,row.pointSize)),matching,`${row.file} matching`);
            assert.equal(api.copyContract(row.family,row.pointSize),true);comparedBytes+=expected.length+matching.length;
        }
        assert.equal(api.frozen(),true);
    }
    const first=reference.catalogs[0],sample=fs.readFileSync(path.join(original,first.file+'.atpl'));
    const mismatch=await load();mismatch.configure('org.audiveris.omr.image.TemplateFactory.stemDx','-0.33');mismatch.install(sample);
    assert.throws(()=>mismatch.catalog(first.family,first.pointSize),/parameters differ/);
    const preview=await load();preview.configure('org.audiveris.omr.image.TemplateFactory.saveTemplates','true');preview.install(sample);
    assert.throws(()=>preview.catalog(first.family,first.pointSize),/host image backend/);
    const assets=new Set(reference.catalogs.map(r=>r.file+'.atpl'));
    const server=http.createServer((request,response)=>{
        if(request.url==='/'){response.end('<!doctype html><title>Template resource verification</title>');return;}
        if(request.url==='/worker.js'){
            response.setHeader('Content-Type','text/javascript');response.end(`importScripts('/templates.wasm-runtime.js');let api;
                const hash=async a=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',a.buffer))].map(b=>b.toString(16).padStart(2,'0')).join('');
                onmessage=async e=>{try{
                    const p=e.data;if(!api){api=(await TeaVM.wasmGC.load('/templates.wasm')).exports;if(p.explicit){api.explicitSource();}}
                    api.install(new Int8Array(p.buffer));
                    const catalog=api.catalog(p.family,p.pointSize),matching=api.match(p.family,p.pointSize);
                    postMessage({catalogHash:await hash(catalog),matchingHash:await hash(matching),copies:api.copyContract(p.family,p.pointSize),frozen:api.frozen(),dom:typeof document});
                }catch(error){postMessage({error:String(error.stack||error)});}};`);return;
        }
        const name=request.url.slice(1);
        if(['templates.wasm','templates.wasm-runtime.js'].includes(name)){
            response.setHeader('Content-Type',name.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,name)).pipe(response);return;
        }
        if(assets.has(name)){fs.createReadStream(path.join(original,name)).pipe(response);return;}
        response.writeHead(404).end();
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true});const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results=await page.evaluate(async rows=>{
            const workers=[new Worker('/worker.js'),new Worker('/worker.js')];
            try{
                const results=[];
                for(let i=0;i<2;i++){
                    const outputs=[];
                    for(const row of rows){
                        const buffer=await (await fetch('/'+row.file+'.atpl')).arrayBuffer();
                        outputs.push(await new Promise((resolve,reject)=>{
                            const timer=setTimeout(()=>reject(new Error('Template timeout')),60000);
                            workers[i].onmessage=e=>{clearTimeout(timer);e.data.error?reject(new Error(e.data.error)):resolve(e.data);};
                            workers[i].onerror=e=>{clearTimeout(timer);reject(new Error(e.message));};
                            workers[i].postMessage({...row,explicit:Boolean(i),buffer},[buffer]);if(buffer.byteLength){reject(new Error('Resource transfer'));}
                        }));
                    }
                    results.push(outputs);
                }
                return results;
            }finally{workers.forEach(w=>w.terminate());}
        },reference.catalogs);
        for(const outputs of results){outputs.forEach((row,i)=>{
            const expected=reference.catalogs[i];assert.equal(row.catalogHash,expected.sha256);assert.equal(row.matchingHash,expected.matchingSha256);
            assert.equal(row.copies,true);assert.equal(row.frozen,true);assert.equal(row.dom,'undefined');comparedBytes+=expected.bytes+expected.matchingBytes;
        });}
        const report={node:process.version,chromium:browser.version(),nodeInstances:4,workers:2,catalogs:reference.catalogs.length,
            supportedTemplates:reference.catalogs.reduce((n,r)=>n+r.supported,0),keyPoints:reference.keyPoints,comparedBytes,
            originalAndPortableJvmExact:true,templateDataExact:true,matchingAndForegroundPixelsExact:true,immutableRecords:true,
            malformedAndDuplicateResourcesRejected:true,atomicInstallation:true,parameterMismatchRejected:true,previewOptionRejected:true,
            explicitSourceInjection:true,hostTransfer:true,workerIsolation:true,headPointSizeChecks:reference.headPointSizeChecks,dilationCases:reference.dilationCases,dilationPixels:reference.dilationPixels,
            wasmBytes:fs.statSync(path.join(root,'templates.wasm')).size,
            limitation:'Font-derived template resources and matching component; full HEADS recognition still requires real-page comparison'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
