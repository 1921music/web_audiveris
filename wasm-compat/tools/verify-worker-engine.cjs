// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const {pathToFileURL,fileURLToPath}=require('node:url');
const root=path.resolve(process.argv[2]),assets=path.join(root,'build/wasm-engine/package');
(async()=>{
    vm.runInThisContext(fs.readFileSync(path.join(assets,'engine.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    const {createEngine}=await import(pathToFileURL(path.join(assets,'host/engine.mjs')));
    const {TesseractJsOcr}=await import(pathToFileURL(path.join(root,'wasm-compat/host/tesseract-js-ocr.mjs')));
    const adapter=new TesseractJsOcr({oem:0,availableLanguages:['eng'],createWorker:require('tesseract.js').createWorker,
        workerOptions:{workerPath:path.join(root,'wasm-compat/host/tesseract-word-metadata-node.cjs'),
            langPath:path.join(root,'wasm-compat/node_modules/@tesseract.js-data/eng/4.0.0'),cacheMethod:'none',gzip:true}});
    const engine=await createEngine({base:pathToFileURL(assets+path.sep),readBytes:async url=>new Uint8Array(fs.readFileSync(fileURLToPath(url))),
        loadWasm:url=>TeaVM.wasmGC.load(fileURLToPath(url))});
    const reference=path.join(root,'build/wasm-compat/texts-continuation-probe/live-ocr/jvm'),rows=JSON.parse(fs.readFileSync(path.join(reference,'jvm-reference.json')));
    const results=[];
    try{
        for(const row of rows){
            const meta=JSON.parse(fs.readFileSync(path.join(reference,row.id,'metadata.json'))),stages=[],started=performance.now();let requests=0;
            const result=await engine.recognize({...meta,id:row.id,
                pixels:new Uint8Array(fs.readFileSync(path.join(root,'build/wasm-compat/pixel-probe/samples',row.id+'.gray'))),
                ocr:async request=>{requests++;const reply=await adapter.recognize(request);assert.ok(!reply.failure,JSON.stringify(reply.failure));return reply;},
                onProgress:({stage})=>{if(stages.at(-1)!==stage){stages.push(stage);console.log(row.id+': '+stage);}}});
            assert.equal(result.stage,'PAGE');assert.ok(requests>=1);assert.equal(stages.length,19);
            assert.ok(result.scores.length>0);
            for(const score of result.scores){assert.match(score.musicXml,/<score-partwise[^>]*version="4.0"/);assert.match(score.musicXml,/<note(?:\s|>)/);fs.writeFileSync(path.join(root,'build/wasm-engine/engine',row.id+'-'+score.index+'.musicxml'),score.musicXml);}
            results.push({id:row.id,stages,ocrRequests:requests,milliseconds:performance.now()-started});
            console.log('Standalone page passed: '+row.id);
        }
        fs.writeFileSync(path.join(root,'build/wasm-engine/engine/verification.json'),JSON.stringify({node:process.version,grayscaleInput:true,pageSpecificResources:0,results,stats:engine.stats()},null,2)+'\n');
    }finally{engine.close();await adapter.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
