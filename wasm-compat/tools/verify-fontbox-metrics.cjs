// Opt-in FontBox component: physical fonts only, translated composites only, no engine integration.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),http=require('node:http'),crypto=require('node:crypto'),zlib=require('node:zlib');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]),fixtures=path.resolve(process.argv[3]);
const fixture=JSON.parse(fs.readFileSync(path.join(fixtures,'jvm-reference.json'))),sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const fonts=fixture.faces.map(face=>{const bytes=fs.readFileSync(path.join(fixtures,'fonts',face.file));assert.equal(sha(bytes),face.sha256);return bytes;});
const rint=value=>{const floor=Math.floor(value),part=value-floor;return part===0.5?(floor%2===0?floor:floor+1):Math.round(value);};
function collect(api,fixture,fonts){
    const values=[];
    for(let i=0;i<fixture.faces.length;i++){
        api.face(new Int8Array(fonts[i]));
        for(const item of fixture.measurements.filter(item=>item.face===fixture.faces[i].id)){
            values.push([0,1,2,3,4].map(field=>api.measure(item.size,item.text,field)));
        }
    }return values;
}
let checks=0;
function verify(values){
    assert.equal(values.length,fixture.measurements.length);
    values.forEach((actual,index)=>{
        const item=fixture.measurements[index],bounds=item.bounds;
        assert.deepEqual(actual,[bounds.x,bounds.y,bounds.width,bounds.height,item.advance],item.face+'/'+item.size+'/'+item.text);
        const ratio=Math.fround((item.targetWidth>=item.targetHeight?item.targetWidth:item.targetHeight)/Math.fround(item.targetWidth>=item.targetHeight?actual[2]:actual[3]));
        assert.equal(rint(Math.fround(ratio*item.size)),item.fittedSize);checks++;
    });
}
(async()=>{
    vm.runInThisContext(fs.readFileSync(path.join(root,'fontbox-metrics.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    for(let instance=0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(root,'fontbox-metrics.wasm'))).exports;verify(collect(api,fixture,fonts));
    }
    const worker=`importScripts('/runtime.js');onmessage=async()=>{try{
        const fixture=await(await fetch('/fixture')).json(),fonts=[];
        for(let i=0;i<fixture.faces.length;i++){fonts.push(new Uint8Array(await(await fetch('/fonts/'+i)).arrayBuffer()));}
        const api=(await TeaVM.wasmGC.load('/module.wasm')).exports,collect=${collect.toString()};postMessage({values:collect(api,fixture,fonts),dom:typeof document});
    }catch(error){postMessage({error:String(error.stack||error)});}};`;
    const server=http.createServer((req,res)=>{
        const url=new URL(req.url,'http://127.0.0.1').pathname;
        if(url==='/'){return res.end('<!doctype html><title>FontBox physical metrics</title>');}
        if(url==='/fixture'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(fixture));}
        if(url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        if(url.startsWith('/fonts/')){const bytes=fonts[Number(url.slice(7))];if(bytes){return res.end(bytes);}}
        const file=url==='/module.wasm'?'fontbox-metrics.wasm':url==='/runtime.js'?'fontbox-metrics.wasm-runtime.js':null;
        if(!file){res.statusCode=404;return res.end();}res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,file)).pipe(res);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,args:['--js-flags=--max-old-space-size=384']});
        const context=await browser.newContext(),origin='http://127.0.0.1:'+server.address().port;
        await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
        const page=await context.newPage();await page.goto(origin+'/');
        for(let instance=0;instance<2;instance++){
            const result=await page.evaluate(()=>new Promise((resolve,reject)=>{
                const worker=new Worker('/worker.js'),timer=setTimeout(()=>{worker.terminate();reject(new Error('FontBox timeout'));},120000);
                worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};
                worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(null);
            }));assert.equal(result.dom,'undefined');verify(result.values);
        }
        const bytes=fs.readFileSync(path.join(root,'fontbox-metrics.wasm')),report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,
            cases:fixture.measurements.length,checks,exactPhysicalMetrics:true,wasmBytes:bytes.length,gzipBytes:zlib.gzipSync(bytes).length,wasmSha256:sha(bytes),
            referenceSha256:sha(fs.readFileSync(path.join(fixtures,'jvm-reference.json'))),faces:fixture.faces,
            limitation:'Physical TTF glyph metrics and translated composites only; not general font selection, shaping, music fonts, rasterization or engine integration'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
