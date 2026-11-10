const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]);
const original=JSON.parse(fs.readFileSync(path.join(root,'jvm-original.json'),'utf8'));
assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'jvm-portable.json'),'utf8')),original);
const bytes=xml=>new TextEncoder().encode(xml);
const malformed=['<bad/>','<drum-set><staff>','<drum-set/><drum-set/>'];
(async()=>{
    vm.runInThisContext(fs.readFileSync(path.join(root,'drum.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    for(const overlay of [false,true]) {
        const api=(await TeaVM.wasmGC.load(path.join(root,'drum.wasm'))).exports;
        const input=bytes(overlay?original.overlay:''),before=Buffer.from(input);(overlay?api.configure:api.configureDefaults)(input);assert.deepEqual(Buffer.from(input),before);
        for(const row of original.cases) {assert.equal(api.parseOutcome(bytes(row.xml)),row.expected);}
        for(const xml of malformed) {assert.equal(api.rejects(bytes(xml)),true);}
        const expected=overlay?original.overlayMapping:original.defaultMapping;
        assert.equal(api.mapping(),expected);assert.equal(api.mapping(),expected);assert.equal(api.frozen(),true);
    }
    const server=http.createServer((request,response)=>{
        if(request.url==='/') {response.end('<!doctype html><title>Drum XML tests</title>');return;}
        if(request.url==='/worker.js') {
            response.setHeader('Content-Type','text/javascript');
            response.end(`importScripts('/drum.wasm-runtime.js');let api;
                onmessage=async e=>{try{
                    if(!api) {api=(await TeaVM.wasmGC.load('/drum.wasm')).exports;}
                    const {method,buffer}=e.data;
                    const result=buffer===undefined?api[method]():api[method](new Int8Array(buffer));
                    postMessage({result,dom:typeof document,domParser:typeof DOMParser});
                }catch(error){postMessage({error:String(error.stack||error)});}};`);return;
        }
        const name=request.url.slice(1);
        if(!['drum.wasm','drum.wasm-runtime.js'].includes(name)){response.writeHead(404).end();return;}
        response.setHeader('Content-Type',name.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,name)).pipe(response);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true});const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results=await page.evaluate(async({reference,malformed})=>{
            const workers=[new Worker('/worker.js'),new Worker('/worker.js')];
            const call=(worker,method,xml)=>new Promise((resolve,reject)=>{
                const timer=setTimeout(()=>reject(new Error('Drum Worker timeout')),30000);
                worker.onmessage=e=>{clearTimeout(timer);e.data.error?reject(new Error(e.data.error)):resolve(e.data);};
                worker.onerror=e=>{clearTimeout(timer);reject(new Error(e.message));};
                if(xml===undefined){worker.postMessage({method});}else{const buffer=new TextEncoder().encode(xml).buffer;worker.postMessage({method,buffer},[buffer]);if(buffer.byteLength){reject(new Error('Input was not transferred'));}}
            });
            try{
                const result=[];
                for(let i=0;i<2;i++){
                    await call(workers[i],i?'configure':'configureDefaults',i?reference.overlay:'');
                    const parsed=[];for(const row of reference.cases){parsed.push((await call(workers[i],'parseOutcome',row.xml)).result);}
                    const rejected=[];for(const xml of malformed){rejected.push((await call(workers[i],'rejects',xml)).result);}
                    result.push({parsed,rejected,mapping:await call(workers[i],'mapping'),frozen:(await call(workers[i],'frozen')).result});
                }
                return result;
            }finally{workers.forEach(w=>w.terminate());}
        },{reference:original,malformed});
        results.forEach((row,i)=>{
            assert.deepEqual(row.parsed,original.cases.map(c=>c.expected));assert.deepEqual(row.rejected,malformed.map(()=>true));
            assert.equal(row.mapping.result,i?original.overlayMapping:original.defaultMapping);assert.equal(row.frozen,true);
            assert.equal(row.mapping.dom,'undefined');assert.equal(row.mapping.domParser,'undefined');
        });
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,
            xmlCases:original.cases.length,matchedDecodings:4*original.cases.length,malformedCases:malformed.length,
            originalAndPortableJvmExact:true,defaultAndOverlayMapsExact:true,originalMergeAlgorithm:true,
            copiedOverlay:true,sourceDefaultAndOverrideOptions:true,freezeAfterFirstUse:true,workerIsolation:true,hostTransfer:true,
            wasmBytes:fs.statSync(path.join(root,'drum.wasm')).size,
            limitation:'Drum configuration component; font/template backend and full HEADS recognition remain unverified'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
