// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const {pathToFileURL,fileURLToPath}=require('node:url');
const root=path.resolve(process.argv[2]),packageRoot=path.join(root,'build/wasm-engine/package');
(async()=>{
    vm.runInThisContext(fs.readFileSync(path.join(packageRoot,'engine.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    const {createEngine}=await import(pathToFileURL(path.join(packageRoot,'host/engine.mjs'))),{TesseractJsOcr}=await import(pathToFileURL(path.join(root,'wasm-compat/host/tesseract-js-ocr.mjs')));
    let api;const engine=await createEngine({base:pathToFileURL(packageRoot+path.sep),readBytes:async url=>new Uint8Array(fs.readFileSync(fileURLToPath(url))),
        loadWasm:async url=>{const value=await TeaVM.wasmGC.load(fileURLToPath(url));api=value.exports;return value;}});
    const adapter=new TesseractJsOcr({oem:0,availableLanguages:['eng'],createWorker:require('tesseract.js').createWorker,
        workerOptions:{workerPath:path.join(root,'wasm-compat/host/tesseract-word-metadata-node.cjs'),langPath:path.join(root,'wasm-compat/node_modules/@tesseract.js-data/eng/4.0.0'),cacheMethod:'none',gzip:true}});
    const meta=JSON.parse(fs.readFileSync(path.join(root,'build/wasm-compat/texts-continuation-probe/live-ocr/jvm/00/metadata.json')));
    const pixels=new Uint8Array(fs.readFileSync(path.join(root,'build/wasm-compat/pixel-probe/samples/00.binary')));
    // Add two deterministic boxed rehearsal letters in the blank area above the first staff.
    function rectangle(x,y,w,h){for(let row=y;row<y+h;row++)pixels.fill(0,row*meta.width+x,row*meta.width+x+w);}
    const letters=['0011100','0110110','1100011','1100011','1111111','1100011','1100011','1100011','1100011'];
    for(const x of [1750,1900]){
        rectangle(x,740,66,3);rectangle(x,803,66,3);rectangle(x,740,3,66);rectangle(x+63,740,3,66);
        letters.forEach((row,y)=>[...row].forEach((bit,c)=>{if(bit==='1')rectangle(x+19+c*4,755+y*4,4,4);}));
    }
    const output=path.join(root,'build/wasm-engine/local-ocr');fs.mkdirSync(output,{recursive:true});fs.writeFileSync(path.join(output,'fixture.gray'),pixels);
    let staleChecks=0,duplicateChecks=0;const requests=[];
    try{
        const result=await engine.recognize({...meta,id:'rehearsals',pixels,ocr:async request=>{
            const stale={token:{...request.token,generation:request.token.generation-1}};
            Object.defineProperty(stale,'result',{get(){throw new Error('Stale OCR payload was decoded');}});
            assert.equal(api.receive(stale),false);staleChecks++;
            const reply=await adapter.recognize(request);assert.ok(!reply.failure,JSON.stringify(reply.failure));
            requests.push({token:request.token,layout:request.layout,width:request.width,height:request.height,lines:reply.result.lines});
            console.log('OCR '+request.layout+' '+JSON.stringify(request.token));
            return reply;
        },onProgress:({stage})=>console.log('Rehearsals '+stage)});
        assert.equal(result.stage,'PAGE');assert.ok(requests.filter(row=>row.layout==='SINGLE_BLOCK').length>=2,'Expected two real local OCR suspension points');
        assert.ok(result.scores.some(score=>/<rehearsal(?:\s|>)/.test(score.musicXml)),'No rehearsal exported');
        for(const request of requests){assert.equal(api.receive({token:request.token}),false);duplicateChecks++;}
        for(const score of result.scores)fs.writeFileSync(path.join(output,score.index+'.musicxml'),score.musicXml);
        fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({staleChecks,duplicateChecks,requests,stage:result.stage},null,2)+'\n');
        console.log('Positive local OCR and stale reply guards passed');
    }finally{engine.close();await adapter.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
