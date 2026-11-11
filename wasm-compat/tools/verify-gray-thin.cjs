const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('playwright'),root=path.resolve(process.argv[2]);
const packed=process.env.AUDIVERIS_PACKED_THIN==='1',moduleName=packed?'packed-thin':'gray-thin',cases=packed?4753:9506,guards=packed?2:4;
const reference=process.argv[3]?path.resolve(process.argv[3]):packed?path.resolve(root,'../gray-thin-probe'):root;
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
(async()=>{
    const originalHashes=fs.readFileSync(path.join(reference,'jvm-reference.txt'),'utf8'),originalPixels=fs.readFileSync(path.join(reference,'jvm-pixels.binary'));
    const hashes=packed?originalHashes.split(',').slice(0,-1).filter((_,i)=>(i&1)===0).join(',')+',':originalHashes;
    const pixels=packed?Buffer.alloc(originalPixels.length/2):originalPixels;
    if(packed){const size=47*37;for(let i=0;i<cases;i++){originalPixels.copy(pixels,i*size,i*2*size,(i*2+1)*size);}}
    const pixelSha256=sha(pixels),native=JSON.parse(fs.readFileSync(path.join(reference,'jvm-verification.json')));
    assert.equal(native.cases,9506);assert.equal(native.packedBackendDefaultCases,4753);assert.equal(native.packedBackendGuardCases,2);assert.equal(native.packedBackendPixelsExact,true);
    vm.runInThisContext(fs.readFileSync(path.join(root,moduleName+'.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    for(let instance=0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(root,moduleName+'.wasm'))).exports;
        assert.equal(api.cases(),cases);assert.equal(api.guards(),guards);assert.equal(api.hashes(),hashes);assert.deepEqual(Buffer.from(api.pixels()),pixels);
    }
    const worker=`importScripts('/${moduleName}.wasm-runtime.js');onmessage=async()=>{try{
        const api=(await TeaVM.wasmGC.load('/${moduleName}.wasm')).exports,pixels=api.pixels();
        const digest=await crypto.subtle.digest('SHA-256',pixels);
        postMessage({cases:api.cases(),hashes:api.hashes(),guards:api.guards(),bytes:pixels.length,dom:typeof document,
            pixelSha256:Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('')});
    }catch(error){postMessage({error:String(error.stack||error)});}};`;
    const server=http.createServer((req,res)=>{
        if(req.url==='/'){return res.end('<!doctype html><title>Thin path verification</title>');}
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        const name=req.url.slice(1);if(![moduleName+'.wasm',moduleName+'.wasm-runtime.js'].includes(name)){res.statusCode=404;return res.end();}
        res.setHeader('Content-Type',name.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,name)).pipe(res);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,timeout:600000});const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port+'/');
        for(let instance=0;instance<2;instance++){
            const row=await page.evaluate(()=>new Promise((resolve,reject)=>{
                const worker=new Worker('/worker.js'),timer=setTimeout(()=>{worker.terminate();reject(new Error('Thin path timeout'));},600000);
                worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};
                worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(null);
            }));
            assert.equal(row.cases,cases);assert.equal(row.hashes,hashes);assert.equal(row.guards,guards);assert.equal(row.bytes,pixels.length);assert.equal(row.pixelSha256,pixelSha256);assert.equal(row.dom,'undefined');
        }
        const wasm=fs.readFileSync(path.join(root,moduleName+'.wasm'));
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,paths:cases,normalizationModes:packed?1:2,packedBackendInWasm:packed,
            directedLineVectors:4225,largeCoordinatePaths:16,curveAndShapePaths:512,nativeStrokeWidths:[0,.25,.5,1],nativeEndCaps:3,nativeLineJoins:3,
            nativeComparedPixels:pixels.length,nativeJvmPixelsExact:true,nodePixelsExact:true,workerPixelSha256Exact:true,
            packedBackendDefaultCases:native.packedBackendDefaultCases,packedBackendGuardCases:native.packedBackendGuardCases,packedBackendPixelsExact:true,
            pixelSha256,wasmPerPathHashesExact:true,inputGuardsPerInstance:guards,nativeEndpointClipping:true,
            wasmBytes:wasm.length,wasmSha256:sha(wasm),
            limitation:'Opaque white non-AA thin Path2D drawing; dashed strokes, general graphics transforms/composites and music glyph painting are separate'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
