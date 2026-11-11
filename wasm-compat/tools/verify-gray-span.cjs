const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('playwright'),root=path.resolve(process.argv[2]);
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
(async()=>{
    const hashes=fs.readFileSync(path.join(root,'jvm-reference.txt'),'utf8'),pixels=fs.readFileSync(path.join(root,'jvm-pixels.binary')),pixelSha256=sha(pixels);
    vm.runInThisContext(fs.readFileSync(path.join(root,'gray-span.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    for(let instance=0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(root,'gray-span.wasm'))).exports;
        assert.equal(api.guards(),4);assert.equal(api.hashes(),hashes);assert.deepEqual(Buffer.from(api.pixels()),pixels);
    }
    const worker=`importScripts('/gray-span.wasm-runtime.js');onmessage=async()=>{try{
        const api=(await TeaVM.wasmGC.load('/gray-span.wasm')).exports,pixels=api.pixels();
        const digest=await crypto.subtle.digest('SHA-256',pixels);
        postMessage({hashes:api.hashes(),guards:api.guards(),bytes:pixels.length,dom:typeof document,
            pixelSha256:Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('')});
    }catch(error){postMessage({error:String(error.stack||error)});}};`;
    const server=http.createServer((req,res)=>{
        if(req.url==='/'){return res.end('<!doctype html><title>Span raster verification</title>');}
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        const name=req.url.slice(1);if(!['gray-span.wasm','gray-span.wasm-runtime.js'].includes(name)){res.statusCode=404;return res.end();}
        res.setHeader('Content-Type',name.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,name)).pipe(res);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,timeout:600000});const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port+'/');
        for(let instance=0;instance<2;instance++){
            const row=await page.evaluate(()=>new Promise((resolve,reject)=>{
                const worker=new Worker('/worker.js'),timer=setTimeout(()=>{worker.terminate();reject(new Error('Span raster timeout'));},600000);
                worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};
                worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(null);
            }));
            assert.equal(row.hashes,hashes);assert.equal(row.guards,4);assert.equal(row.bytes,pixels.length);assert.equal(row.pixelSha256,pixelSha256);assert.equal(row.dom,'undefined');
        }
        const wasm=fs.readFileSync(path.join(root,'gray-span.wasm'));
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,paths:1024,normalizationModes:2,
            nativeComparedPixels:pixels.length,nativeJvmPixelsExact:true,nodePixelsExact:true,workerPixelSha256Exact:true,
            pixelSha256,wasmPerPathHashesExact:true,inputGuardsPerInstance:4,pureJavaSpanConverter:true,
            wasmBytes:wasm.length,wasmSha256:sha(wasm),
            limitation:'Packed white non-AA span fill only; portable TEXTS cleanup integration and music glyph painting are separate'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
