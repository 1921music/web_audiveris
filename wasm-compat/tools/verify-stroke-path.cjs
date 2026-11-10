const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http');
const crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]);
function compareCbrts(actual,strict,math){
    if(actual!==strict){const a=actual.split(','),e=strict.split(',');const index=a.findIndex((v,i)=>v!==e[i]);throw new Error('StrictMath cbrt '+index+': '+a[index]+' vs '+e[index]);}
    const a=actual.split(',').filter(Boolean),e=math.split(',').filter(Boolean);let maxUlps=0n;
    if(a.length!==4096||e.length!==4096){throw new Error('Cbrt sample count');}
    for(let i=0;i<a.length;i++){const x=BigInt('0x'+a[i]),y=BigInt('0x'+e[i]),d=x>y?x-y:y-x;if(d>1n){throw new Error('Math cbrt ULP bound '+i);}if(d>maxUlps){maxUlps=d;}}
    return Number(maxUlps);
}
function compareGeometry(actual,expected,index){
    if(index%2&&actual!==expected){throw new Error('Normalized stroke coordinate bits '+index);}
    const split=value=>{const colon=value.indexOf(':');return [value.slice(0,colon),value.slice(colon+1).split(';').filter(Boolean)];};
    const [aw,a]=split(actual),[ew,e]=split(expected);
    if(aw!==ew||a.length!==e.length){throw new Error(`Stroke topology ${index}`);}
    const view=new DataView(new ArrayBuffer(8));
    const number=hex=>{view.setBigUint64(0,BigInt('0x'+hex));return view.getFloat64(0);};
    let numbers=0,maxDifference=0;
    for(let op=0;op<a.length;op++){
        const [ak,ac]=a[op].split('/'),[ek,ec]=e[op].split('/');
        const av=ac.split(',').filter(Boolean),ev=ec.split(',').filter(Boolean);
        if(ak!==ek||av.length!==ev.length){throw new Error(`Stroke segment ${index}/${op}`);}
        for(let i=0;i<av.length;i++){
            const x=number(av[i]),y=number(ev[i]),difference=Math.abs(x-y);
            if(!Number.isFinite(x)||!Number.isFinite(y)||difference>1e-7){throw new Error(`Stroke coordinate ${index}/${op}/${i}: ${x} vs ${y}`);}
            numbers++;maxDifference=Math.max(maxDifference,difference);
        }
    }
    return {numbers,maxDifference};
}
(async()=>{
    const expected=fs.readFileSync(path.join(root,'jvm-reference.txt'),'utf8').trimEnd().split('\n');
    const cbrtReference=fs.readFileSync(path.join(root,'cbrt-reference.txt'),'utf8');
    const strictCbrtReference=fs.readFileSync(path.join(root,'strict-cbrt-reference.txt'),'utf8');
    const acosReference=fs.readFileSync(path.join(root,'acos-reference.txt'),'utf8');
    const ulpReference=fs.readFileSync(path.join(root,'ulp-reference.txt'),'utf8');
    const nativePixels=fs.readFileSync(path.join(root,'jvm-pixels.binary'));
    assert.equal(nativePixels.length,512*47*37);
    const pixelSha256=crypto.createHash('sha256').update(nativePixels).digest('hex');
    assert.equal(expected.length,512);
    let numbers=0,maxDifference=0;
    function compare(actual,index){const row=compareGeometry(actual,expected[index],index);numbers+=row.numbers;maxDifference=Math.max(maxDifference,row.maxDifference);}
    vm.runInThisContext(fs.readFileSync(path.join(root,'stroke-path.wasm-runtime.js'),'utf8'),{
        importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    for(let instance=0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(root,'stroke-path.wasm'))).exports;
        assert.equal(api.nativeRasterRejected(),true);
        compareCbrts(api.cbrts(),strictCbrtReference,cbrtReference);
        if(api.acosValues()!==acosReference){throw new Error('Node acos differs');}
        if(api.ulps()!==ulpReference){throw new Error('Node ULP differs');}
        for(let test=0;test<256;test++){
            compare(api.geometry(test,false),test*2);compare(api.geometry(test,true),test*2+1);
            for(const normalize of [false,true]){
                const offset=(test*2+Number(normalize))*47*37;
                assert.deepEqual(Buffer.from(api.strokePixels(test,normalize)),nativePixels.subarray(offset,offset+47*37));
            }
        }
    }
    const referenceSha=crypto.createHash('sha256').update(expected.join('\n')+'\n').digest('hex');
    const server=http.createServer((request,response)=>{
        if(request.url==='/'){response.end('<!doctype html><title>Stroke geometry verification</title>');return;}
        if(request.url==='/worker.js'){
            response.setHeader('Content-Type','text/javascript');
            response.end(`importScripts('/stroke-path.wasm-runtime.js');const compareGeometry=${compareGeometry.toString()},compareCbrts=${compareCbrts.toString()};onmessage=async event=>{
                try{const api=(await TeaVM.wasmGC.load('/stroke-path.wasm')).exports;let numbers=0,maxDifference=0;
                    const pixels=new Uint8Array(512*47*37);
                    compareCbrts(api.cbrts(),event.data.strictCbrtReference,event.data.cbrtReference);
                    if(api.acosValues()!==event.data.acosReference){throw new Error('Worker acos differs');}
                    if(api.ulps()!==event.data.ulpReference){throw new Error('Worker ULP differs');}
                    for(let test=0;test<256;test++){for(const normalize of [false,true]){
                        const index=test*2+Number(normalize),row=compareGeometry(api.geometry(test,normalize),event.data.expected[index],index);
                        numbers+=row.numbers;maxDifference=Math.max(maxDifference,row.maxDifference);
                        pixels.set(new Uint8Array(api.strokePixels(test,normalize).buffer),index*47*37);
                    }}
                    const digest=await crypto.subtle.digest('SHA-256',pixels);
                    const pixelSha256=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
                    postMessage({nativeRasterRejected:api.nativeRasterRejected(),numbers,maxDifference,pixelSha256,dom:typeof document});
                }catch(error){postMessage({error:String(error.stack||error)});}
            };`);return;
        }
        const name=request.url.slice(1);
        if(!['stroke-path.wasm','stroke-path.wasm-runtime.js'].includes(name)){response.writeHead(404).end();return;}
        response.setHeader('Content-Type',name.endsWith('.wasm')?'application/wasm':'text/javascript');
        fs.createReadStream(path.join(root,name)).pipe(response);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true});const page=await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const rows=await page.evaluate(data=>Promise.all([0,1].map(()=>new Promise((resolve,reject)=>{
            const worker=new Worker('/worker.js');const timer=setTimeout(()=>{worker.terminate();reject(new Error('Stroke timeout'));},60000);
            worker.onmessage=event=>{clearTimeout(timer);worker.terminate();event.data.error?reject(new Error(event.data.error)):resolve(event.data);};
            worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(data);
        }))),{expected,cbrtReference,strictCbrtReference,acosReference,ulpReference});
        for(const row of rows){assert.equal(row.nativeRasterRejected,true);assert.equal(row.dom,'undefined');assert.equal(row.pixelSha256,pixelSha256);numbers+=row.numbers;maxDifference=Math.max(maxDifference,row.maxDifference);}
        const wasm=fs.readFileSync(path.join(root,'stroke-path.wasm'));
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,
            paths:256,normalizedPaths:256,originalBasicStrokeOutlinesExact:true,topologyAndWindingExact:true,
            comparedNumbers:numbers,maxDifference,coordinateTolerance:1e-7,normalizedCoordinateBitsExact:true,
            rasterPaths:512,nativeComparedPixels:nativePixels.length,nodePixelsExact:true,workerPixelSha256Exact:true,pixelSha256,
            nativeGeometrySha256:referenceSha,wasmBytes:wasm.length,
            nativeRasterRejectedExplicitly:true,
            pinnedCbrtCases:4096,
            strictCbrtBitsExact:true,mathCbrtUlpLimit:1,pinnedAcosCases:4096,acosBitsExact:true,
            ulpCases:4096,ulpBitsExact:true,
            wasmSha256:crypto.createHash('sha256').update(wasm).digest('hex'),
            limitation:'Stroke geometry plus thin-stroke Path2D fill of its outlines; native Region clipping, wide-stroke span generation and TEXTS cleanup are not implemented'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
