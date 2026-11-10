const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),http=require('node:http');
const crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('playwright');const root=path.resolve(process.argv[2]);
async function exercise(api,fixture,host){
    let checks=0;const check=value=>{checks++;if(!value){throw new Error('OCR transport check '+checks);}};
    check(api.importFixture(fixture));
    const retained=structuredClone(fixture);api.retain(retained);
    retained.capabilities.characters=false;retained.lines[0].words[0].characters[0].text='changed';
    retained.lines[0].baseline.y1=-99;retained.lines.length=0;check(api.retainedMatches());
    const mutate=[
        x=>{x.capabilities=null;},x=>{x.capabilities.characters=1;},x=>{x.capabilities.baselines=false;},
        x=>{x.capabilities.fontAttributes=false;},x=>{x.lines=null;},x=>{x.lines={};},x=>{x.lines[0]=null;},
        x=>{x.lines[0].words={};},x=>{x.lines[0].words[0].text=4;},x=>{x.lines[0].words[0].confidence=NaN;},
        x=>{x.lines[0].words[0].confidence=Infinity;},x=>{x.lines[0].words[0].confidence=1.1;},
        x=>{x.lines[0].words[0].bounds.x=1.5;},x=>{x.lines[0].words[0].bounds.width=-1;},
        x=>{x.lines[0].words[0].bounds.x=2147483647;},x=>{x.lines[0].words[0].font.pointSize=0;},
        x=>{x.lines[0].words[0].font.bold='true';},x=>{x.lines[0].words[0].baseline.x1=NaN;},
        x=>{x.lines[0].words[0].characters=[];},x=>{x.lines[0].words[0].characters[0].text='';},
        x=>{delete x.lines[0].words[0].bounds;},x=>{delete x.capabilities.fontAttributes;}
    ];
    for(const change of mutate){const bad=structuredClone(fixture);change(bad);check(api.rejects(bad));}
    check(api.rejects(null));check(api.rejects(3));check(api.rejects([]));
    const request=api.begin(7);
    check(request.width===3&&request.height===2&&request.languages==='eng+deu'&&request.layout==='MULTI_BLOCK');
    check(request.pixels instanceof Uint8Array&&String(Array.from(request.pixels))==='0,10,20,128,200,255');
    const stale={token:{...request.token,generation:6}};
    Object.defineProperty(stale,'result',{get(){throw new Error('Stale payload was decoded');}});
    check(api.receive(stale)===false);
    const reply=await host(request);
    check(request.pixels.byteLength===0);check(api.originalPixelsOwned());
    check(api.receive(reply));check(!api.receive(reply));check(api.resume()==='fixture');
    const empty=api.begin(8);check(api.receive({token:empty.token,result:{capabilities:{characters:true,baselines:false,fontAttributes:false},lines:[]}}));
    check(api.resume()==='empty');
    for(const code of ['MODEL_MISSING','UNSUPPORTED','EXECUTION_FAILED']){
        const failed=api.begin(9);check(api.receive({token:failed.token,failure:{code,message:'backend failure'}}));
        check(api.resume()===code+':backend failure');
    }
    const cancelled=api.begin(10);api.cancel();check(!api.receive({token:cancelled.token,result:fixture}));
    api.begin(11);check(!api.receive({token:cancelled.token,result:fixture}));
    return {checks,invalidCases:mutate.length+3,pixelsTransferred:true,staleBeforeDecode:true,dom:typeof document};
}
(async()=>{
    const fixture=JSON.parse(fs.readFileSync(path.join(root,'jvm-reference.txt'),'utf8'));
    vm.runInThisContext(fs.readFileSync(path.join(root,'ocr-transport.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    const node=[];
    for(let i=0;i<2;i++){
        const api=(await TeaVM.wasmGC.load(path.join(root,'ocr-transport.wasm'))).exports;
        node.push(await exercise(api,fixture,async request=>{
            const transferred=structuredClone(request,{transfer:[request.pixels.buffer]});
            await new Promise(resolve=>setImmediate(resolve));
            return structuredClone({token:transferred.token,result:fixture});
        }));
    }
    const server=http.createServer((request,response)=>{
        if(request.url==='/'){response.end('<!doctype html><title>OCR structured-clone boundary</title>');return;}
        if(request.url==='/worker.js'){
            response.setHeader('Content-Type','text/javascript');response.end(`importScripts('/ocr-transport.wasm-runtime.js');
                const exercise=${exercise.toString()};let resolveHost;
                onmessage=async event=>{
                    if(event.data.kind==='reply'){resolveHost(event.data.envelope);return;}
                    try{const api=(await TeaVM.wasmGC.load('/ocr-transport.wasm')).exports;
                        const result=await exercise(api,event.data.fixture,request=>new Promise(resolve=>{
                            resolveHost=resolve;postMessage({kind:'ocr',request},[request.pixels.buffer]);
                        }));postMessage({kind:'done',result});
                    }catch(error){postMessage({error:String(error.stack||error)});}
                };`);return;
        }
        const name=request.url.slice(1);if(!['ocr-transport.wasm','ocr-transport.wasm-runtime.js'].includes(name)){response.writeHead(404).end();return;}
        response.setHeader('Content-Type',name.endsWith('.wasm')?'application/wasm':'text/javascript');fs.createReadStream(path.join(root,name)).pipe(response);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true});const page=await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const workers=await page.evaluate(fixture=>Promise.all([0,1].map(()=>new Promise((resolve,reject)=>{
            const worker=new Worker('/worker.js');const timer=setTimeout(()=>{worker.terminate();reject(new Error('OCR transport timeout'));},30000);
            worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};
            worker.onmessage=event=>{
                const data=event.data;
                if(data.kind==='ocr'){
                    if(String(Array.from(data.request.pixels))!=='0,10,20,128,200,255'){clearTimeout(timer);worker.terminate();reject(new Error('Transferred OCR pixels'));return;}
                    setTimeout(()=>worker.postMessage({kind:'reply',envelope:{token:data.request.token,result:fixture}}),0);return;
                }
                clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data.result);
            };
            worker.postMessage({kind:'start',fixture});
        }))),fixture);
        for(const row of [...node,...workers]){assert.deepEqual(row,node[0]);assert.equal(row.dom,'undefined');}
        const wasm=fs.readFileSync(path.join(root,'ocr-transport.wasm'));
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,
            ...node[0],nativeModelRoundTrip:true,unicodeAndLineBaseline:true,inboundSnapshotsOwned:true,
            plainDataAndTransferablePixels:true,wasmBytes:wasm.length,wasmSha256:crypto.createHash('sha256').update(wasm).digest('hex'),
            limitation:'Host transport component only; no actual OCR backend, TEXTS cleanup or TextLine ingestion is claimed'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
