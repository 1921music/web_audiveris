const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('playwright');const root=path.resolve(process.argv[2]);
const measurements=JSON.parse(fs.readFileSync(path.join(root,'font-measurements.json'))),cases=JSON.parse(fs.readFileSync(path.join(root,'cases.json')));
function check(api,measurements,cases,name){
    assert.ok(api.missingRejected());api.defaultFont(name);
    for(const m of measurements){api.register(m.kind,m.flags,m.size,m.value,m.family,m.name,m.x,m.y,m.width,m.height,m.advance);}
    for(const c of cases){assert.equal(api.fit(c.flags,c.size,c.text,c.width,c.height),c.pointSize+':'+c.name);}
    assert.ok(api.missingRejected());return cases.length;
}
(async()=>{
    const name=fs.readFileSync(path.join(root,'default-font.txt'),'utf8');
    vm.runInThisContext(fs.readFileSync(path.join(root,'text-geometry.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    for(let i=0;i<2;i++){check((await TeaVM.wasmGC.load(path.join(root,'text-geometry.wasm'))).exports,measurements,cases,name);}
    const server=http.createServer((req,res)=>{
        if(req.url==='/'){return res.end('<!doctype html><title>Text geometry</title>');}
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(`importScripts('/text-geometry.wasm-runtime.js');onmessage=async({data})=>{try{
            const api=(await TeaVM.wasmGC.load('/text-geometry.wasm')).exports;
            if(!api.missingRejected()){throw new Error('Missing measurements accepted');}api.defaultFont(data.name);
            for(const m of data.measurements){api.register(m.kind,m.flags,m.size,m.value,m.family,m.name,m.x,m.y,m.width,m.height,m.advance);}
            for(const c of data.cases){if(api.fit(c.flags,c.size,c.text,c.width,c.height)!==c.pointSize+':'+c.name){throw new Error('Text font fit mismatch');}}
            postMessage({checks:data.cases.length,missingRejected:api.missingRejected(),dom:typeof document});
        }catch(error){postMessage({error:String(error.stack||error)});}};`);}
        const file=req.url.slice(1);if(!['text-geometry.wasm','text-geometry.wasm-runtime.js'].includes(file)){res.statusCode=404;return res.end();}
        res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,file)).pipe(res);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{browser=await chromium.launch({headless:true});const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port+'/');
        const rows=await page.evaluate(data=>Promise.all([0,1].map(()=>new Promise((resolve,reject)=>{const worker=new Worker('/worker.js');
            const timer=setTimeout(()=>{worker.terminate();reject(new Error('Text geometry timeout'));},60000);
            worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};worker.onerror=reject;worker.postMessage(data);
        }))),{name,cases,measurements});
        for(const row of rows){assert.equal(row.checks,cases.length);assert.ok(row.missingRejected);assert.equal(row.dom,'undefined');}
        const bytes=fs.readFileSync(path.join(root,'text-geometry.wasm'));
        const result={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,cases:cases.length,
            exactOriginalFontFitting:true,missingMeasurementsRejected:true,wasmBytes:bytes.length,wasmSha256:crypto.createHash('sha256').update(bytes).digest('hex'),
            limitation:'Exact supplied metrics only; portable font outline measurement and TEXTS cleanup remain separate boundaries'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
