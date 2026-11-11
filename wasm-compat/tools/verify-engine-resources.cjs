// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),http=require('node:http'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url'),{chromium}=require('playwright');
const root=path.resolve(process.argv[2]),assets=path.join(root,'build/wasm-engine'),moduleRoot=path.join(assets,'resources-probe');
const read=file=>JSON.parse(fs.readFileSync(file)),sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
async function run(api,music,fixed,cases,templates,hash){
    if(typeof music.renderTemplate!=='function')throw new Error('Missing template raster callback');
    const {createSymbolGeometry}=await import(pathToFileURL(path.join(root,'wasm-compat/host/font-symbol-geometry.mjs')));
    api.configure(createSymbolGeometry(music,fixed),music.renderTemplate);
    const measurements=cases.map(row=>Array.from(api.measure(row.kind,row.family,row.shape,row.scale,row.num,row.den))),catalogs=[];
    for(const row of templates){catalogs.push({file:row.file,catalog:await hash(api.catalog(row.family,row.pointSize)),matching:await hash(api.matching(row.family,row.pointSize))});}
    let sealed=false;try{api.configure(()=>null,()=>null);}catch{sealed=true;}
    return {measurements,catalogs,sealed,renders:api.generatedRasters(),requests:api.measurements()};
}
(async()=>{
    const fontsRoot=path.join(assets,'fonts'),fixed=read(path.join(assets,'font-data/intrinsics.json')),cases=read(path.join(assets,'font-data/geometry-reference.json'));
    const templates=read(path.join(assets,'template-reference/reference.json')),recipes=read(path.join(fontsRoot,'recipes.json')),textFamilies=read(path.join(fontsRoot,'font-resources.json')).textFamilies;
    const expected=templates.catalogs.map(row=>({...row,catalog:sha(fs.readFileSync(path.join(assets,'template-reference',row.file+'.atpl'))),matching:sha(fs.readFileSync(path.join(assets,'template-reference',row.file+'.match')))}));
    const moduleBytes=fs.readFileSync(path.join(moduleRoot,'resources.wasm')),factory=(await import(pathToFileURL(path.join(fontsRoot,'music-symbols.mjs')))).default;
    const {createFreeTypeMusicSymbols}=await import(pathToFileURL(path.join(root,'wasm-compat/host/freetype-music-symbols.mjs'))),wasm=fs.readFileSync(path.join(fontsRoot,'music-symbols.wasm'));
    const fonts=Object.fromEntries(fs.readdirSync(path.join(fontsRoot,'fonts')).map(name=>[name,new Uint8Array(fs.readFileSync(path.join(fontsRoot,'fonts',name)))]));
    let maxDifference=0;const results=[];
    function check(result){
        assert.ok(result.sealed&&result.renders>0&&result.requests>0);assert.equal(result.measurements.length,cases.length);
        result.measurements.forEach((values,n)=>values.forEach((v,i)=>{const difference=Math.abs(v-cases[n].values[i]);maxDifference=Math.max(maxDifference,difference);assert.ok(Number.isFinite(v)&&difference<=1e-8,JSON.stringify({row:cases[n],actual:values}));}));
        assert.equal(result.catalogs.length,expected.length);result.catalogs.forEach((row,i)=>{assert.equal(row.catalog,expected[i].catalog,'Template data '+row.file);assert.equal(row.matching,expected[i].matching,'Template matching '+row.file);});
        results.push({renders:result.renders,measurements:result.requests});
    }
    vm.runInThisContext(fs.readFileSync(path.join(moduleRoot,'resources.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    for(let instance=0;instance<2;instance++){
        const api=(await TeaVM.wasmGC.load(path.join(moduleRoot,'resources.wasm'))).exports,music=await createFreeTypeMusicSymbols(factory,wasm,fonts,recipes,{textFamilies});
        try{check(await run(api,music,fixed,cases,templates.catalogs,sha));}finally{music.dispose();}
        console.log('Font-derived resources: Node '+instance+' passed');
    }
    const files=new Map([['/module.wasm',path.join(moduleRoot,'resources.wasm')],['/runtime.js',path.join(moduleRoot,'resources.wasm-runtime.js')],['/font/factory.mjs',path.join(fontsRoot,'music-symbols.mjs')],['/font/module.wasm',path.join(fontsRoot,'music-symbols.wasm')]]);
    for(const name of ['freetype-music-symbols.mjs','freetype-music-metrics.mjs','font-symbol-geometry.mjs'])files.set('/host/'+name,path.join(root,'wasm-compat/host',name));
    for(const name of Object.keys(fonts))files.set('/fonts/'+name,path.join(fontsRoot,'fonts',name));
    const fixture={fixed,recipes,textFamilies,fontNames:Object.keys(fonts),cases:cases.map(({values,...args})=>args),templates:templates.catalogs};
    const worker=`importScripts('/runtime.js');onmessage=async()=>{let music;try{
        const fixture=await fetch('/fixture').then(r=>r.json()),{default:factory}=await import('/font/factory.mjs'),{createFreeTypeMusicSymbols}=await import('/host/freetype-music-symbols.mjs'),fonts={};
        for(const name of fixture.fontNames)fonts[name]=new Uint8Array(await fetch('/fonts/'+name).then(r=>r.arrayBuffer()));
        music=await createFreeTypeMusicSymbols(factory,new Uint8Array(await fetch('/font/module.wasm').then(r=>r.arrayBuffer())),fonts,fixture.recipes,{textFamilies:fixture.textFamilies});
        const api=(await TeaVM.wasmGC.load('/module.wasm')).exports,root='',path={join:()=>'/host/font-symbol-geometry.mjs'},pathToFileURL=x=>x,run=${run.toString()};
        const hash=async bytes=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(b=>b.toString(16).padStart(2,'0')).join('');
        postMessage({...(await run(api,music,fixture.fixed,fixture.cases,fixture.templates,hash)),dom:typeof document});
        }catch(error){postMessage({error:String(error.stack||error)});}finally{if(music)music.dispose();}};`;
    const server=http.createServer((req,res)=>{
        if(req.url==='/')return res.end('<!doctype html><title>Engine resources</title>');
        if(req.url==='/fixture')return res.end(JSON.stringify(fixture));
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        const file=files.get(req.url);if(!file){res.statusCode=404;return res.end();}
        res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':file.endsWith('.js')||file.endsWith('.mjs')?'text/javascript':'application/octet-stream');fs.createReadStream(file).pipe(res);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,timeout:1200000,args:['--js-flags=--max-old-space-size=768']});const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port);
        for(let instance=0;instance<2;instance++){
            const result=await page.evaluate(()=>new Promise((resolve,reject)=>{const w=new Worker('/worker.js'),timer=setTimeout(()=>{w.terminate();reject(new Error('Resource timeout'));},1200000);w.onmessage=e=>{clearTimeout(timer);w.terminate();e.data.error?reject(new Error(e.data.error)):resolve(e.data);};w.onerror=e=>{clearTimeout(timer);w.terminate();reject(new Error(e.message));};w.postMessage(null);}));
            assert.equal(result.dom,'undefined');check(result);console.log('Font-derived resources: Worker '+instance+' passed');
        }
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,geometryCases:cases.length,geometryChecks:cases.length*4,templateCatalogs:expected.length,catalogChecks:expected.length*4,matchingChecks:expected.length*4,maxDifference,pageSpecificMeasurements:0,suppliedTemplateCatalogs:0,originalCatalogRegression:templates.originalCatalogChecks,instances:results,wasmBytes:moduleBytes.length,wasmSha256:sha(moduleBytes)};
        fs.writeFileSync(path.join(moduleRoot,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
