const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),{pathToFileURL}=require('node:url'),vm=require('node:vm'),http=require('node:http'),zlib=require('node:zlib');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]),sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function background(index,w,h){const p=new Uint8Array(w*h);for(let i=0;i<p.length;i++)p[i]=(i*37+index*53)&255;return p;}
function blend(p,w,h,masks){for(const m of masks)for(let y=0;y<m.height;y++)for(let x=0;x<m.width;x++){
    const px=m.x+x,py=m.y+y;if(px<0||py<0||px>=w||py>=h)continue;
    const a=m.pixels[y*m.width+x]&255,i=py*w+px;p[i]=a+Math.floor((p[i]*(255-a)+127)/255);
}}
(async()=>{
    const inv=JSON.parse(fs.readFileSync(path.join(root,'inventory.json'))),recipes=JSON.parse(fs.readFileSync(path.join(root,'recipes.json'))),rows=JSON.parse(fs.readFileSync(path.join(root,'jvm-reference.json')));
    const resources=JSON.parse(fs.readFileSync(path.join(root,'font-resources.json')));
    const fonts={};for(const file of fs.readdirSync(path.join(root,'fonts')))fonts[file]=fs.readFileSync(path.join(root,'fonts',file));
    const {default:factory}=await import(pathToFileURL(path.join(root,'music-symbols.mjs'))),wasm=fs.readFileSync(path.join(root,'music-symbols.wasm'));
    const host=path.resolve(__dirname,'../host/freetype-music-symbols.mjs'),{createFreeTypeMusicSymbols}=await import(pathToFileURL(host));
    const engine=path.resolve(root,'../music-symbol-probe');
    vm.runInThisContext(fs.readFileSync(path.join(engine,'music-symbol.wasm-runtime.js'),'utf8'),{importModuleDynamically:vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});
    const invalid=[{x:0,y:0,width:1,height:1,pixels:new Int8Array([255])},{x:0,y:0,width:1,height:1,pixels:new Int8Array()}];
    function protocolGuards(api){
        const valid={x:0,y:0,width:1,height:1,pixels:new Int8Array([255])};
        if(!api.invalidMaskIsAtomic([valid,{outline:new Float64Array([0,NaN,0]),windingRule:1}]))throw new Error('Outline validation is not atomic');
        if(!api.invalidMaskIsAtomic([valid,{outline:new Float64Array([0,1]),windingRule:1}]))throw new Error('Incomplete outline accepted');
        if(!api.invalidMaskIsAtomic([valid,{x:0,y:0,width:1,height:1,pixels:new Uint8Array([255])}]))throw new Error('Wrong pixel buffer accepted');
        const empty=api.paintOperations([],0);if(empty[0]!==0||empty[1]!==37)throw new Error('Empty valid mask operation rejected');
        const filled=api.paintOperations([valid],0);if((filled[0]&255)!==255||filled[1]!==37)throw new Error('Valid mask operation rejected');
        return 3;
    }
    const nodeRows=[];
    for(let instance=0;instance<2;instance++){
        const service=await createFreeTypeMusicSymbols(factory,wasm,fonts,recipes,{textFamilies:resources.textFamilies}),api=(await TeaVM.wasmGC.load(path.join(engine,'music-symbol.wasm'))).exports;
        assert.throws(()=>api.paint('Bravura','NOTEHEAD_BLACK',24,80,80,0));assert.throws(()=>api.install(null));
        api.install(service.paintSymbol);assert.throws(()=>api.install(service.paintSymbol));
        assert.equal(api.invalidMaskIsAtomic(invalid),true);assert.equal(api.invalidMaskIsAtomic(Promise.resolve([])),true);
        assert.equal(protocolGuards(api),3);
        const owned=service.paintSymbol('Bravura','NOTEHEAD_BLACK',24,80,80);owned[0].pixels.fill(0);
        let failures=0,stats;const groups={},examples=[];
        try{for(const row of rows){const split=row.key.indexOf(':'),family=row.key.slice(0,split),shape=row.key.slice(split+1);
            let p;try{p=api.paint(family,shape,row.size,row.x,row.y,row.background);}catch(error){console.log('Failed case',row);throw error;}const actual=sha(p);
            if(actual!==row.sha256){failures++;groups[row.key]=(groups[row.key]||0)+1;if(examples.length<8)examples.push({...row,actual});}
        }}finally{stats=service.symbolStats();service.dispose();}
        const row={cases:rows.length,failures,groups,examples};
        fs.writeFileSync(path.join(root,'node-comparison.json'),JSON.stringify(row,null,2)+'\n');
        if(failures)console.log(JSON.stringify({failures,examples,affectedSymbols:Object.keys(groups).length}));assert.equal(failures,0,'Packed symbol layout/pixels differ');assert.equal(api.calls(),rows.length);
        assert.ok(stats.cachedMaskBytes<=stats.maskCacheBudgetBytes);assert.ok(stats.cachedMasks<=stats.cacheLimit);
        nodeRows.push({calls:api.calls(),guards:8,stats,marlinBufferBytes:api.rasterMemoryBytes()});
    }
    const unbound=await createFreeTypeMusicSymbols(factory,wasm,fonts,recipes);try{assert.throws(()=>unbound.paintSymbol('Bravura','PLUCK_P',24,80,80));}finally{unbound.dispose();}
    const eviction=await createFreeTypeMusicSymbols(factory,wasm,fonts,recipes,{maxCacheEntries:1,maxMaskCacheBytes:128,textFamilies:resources.textFamilies});
    let evictionChecks=0;const extra=(await TeaVM.wasmGC.load(path.join(engine,'music-symbol.wasm'))).exports;
    const selects=new Set(['NOTEHEAD_BLACK:1','NOTEHEAD_BLACK:24','NOTEHEAD_BLACK:128','NOTEHEAD_BLACK_SMALL:1','REPEAT_TWO_BARS:128']);
    try{const baseline=eviction.measure('Bravura',24,'\ue1d5');
        for(const row of rows.filter(r=>r.key.startsWith('Bravura:')&&selects.has(r.key.slice(8)+':'+r.size))){
        eviction.measure('Bravura',25,'\ue1d5'); // Evict the metric result so shared face state is observed again.
        const split=row.key.indexOf(':'),ops=eviction.paintSymbol(row.key.slice(0,split),row.key.slice(split+1),row.size,row.x,row.y);
        assert.equal(sha(extra.paintOperations(ops,row.background)),row.sha256);
        assert.deepEqual(eviction.measure('Bravura',24,'\ue1d5'),baseline);evictionChecks++;}
    }finally{eviction.dispose();}
    const worker=`importScripts('/runtime.js');onmessage=async()=>{let service;try{
        const {default:factory}=await import('/factory.mjs'),{createFreeTypeMusicSymbols}=await import('/host/freetype-music-symbols.mjs');
        const [rows,recipes,files,wasm,resources]=await Promise.all([fetch('/refs').then(r=>r.json()),fetch('/recipes').then(r=>r.json()),fetch('/fonts').then(r=>r.json()),fetch('/font.wasm').then(r=>r.arrayBuffer()),fetch('/font-config').then(r=>r.json())]);
        const fonts={};for(const name of files)fonts[name]=new Uint8Array(await fetch('/font/'+name).then(r=>r.arrayBuffer()));
        service=await createFreeTypeMusicSymbols(factory,new Uint8Array(wasm),fonts,recipes,{textFamilies:resources.textFamilies});
        const api=(await TeaVM.wasmGC.load('/engine.wasm')).exports;let guards=0;
        try{api.paint('Bravura','NOTEHEAD_BLACK',24,80,80,0);}catch{guards++;}try{api.install(null);}catch{guards++;}
        api.install(service.paintSymbol);try{api.install(service.paintSymbol);}catch{guards++;}
        if(api.invalidMaskIsAtomic([{x:0,y:0,width:1,height:1,pixels:new Int8Array([255])},{x:0,y:0,width:1,height:1,pixels:new Int8Array()}]))guards++;
        if(api.invalidMaskIsAtomic(Promise.resolve([])))guards++;
        const protocolGuards=${protocolGuards.toString()};guards+=protocolGuards(api);
        const owned=service.paintSymbol('Bravura','NOTEHEAD_BLACK',24,80,80);owned[0].pixels.fill(0);
        for(const row of rows){const split=row.key.indexOf(':'),p=api.paint(row.key.slice(0,split),row.key.slice(split+1),row.size,row.x,row.y,row.background);
            const digest=await crypto.subtle.digest('SHA-256',p),actual=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
            if(actual!==row.sha256)throw new Error('Native symbol pixels differ: '+JSON.stringify(row));}
        postMessage({calls:api.calls(),guards,stats:service.symbolStats(),marlinBufferBytes:api.rasterMemoryBytes(),dom:typeof document});
    }catch(error){postMessage({error:String(error.stack||error)});}finally{if(service)service.dispose();}};`;
    const files=new Map([['/runtime.js',path.join(engine,'music-symbol.wasm-runtime.js')],['/engine.wasm',path.join(engine,'music-symbol.wasm')],['/font.wasm',path.join(root,'music-symbols.wasm')],['/factory.mjs',path.join(root,'music-symbols.mjs')],
        ['/host/freetype-music-symbols.mjs',host],['/host/freetype-music-metrics.mjs',path.resolve(__dirname,'../host/freetype-music-metrics.mjs')],...Object.keys(fonts).map(n=>['/font/'+n,path.join(root,'fonts',n)])]);
    const server=http.createServer((req,res)=>{
        if(req.url==='/')return res.end('<!doctype html><title>Music symbols</title>');
        if(req.url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        if(['/refs','/recipes','/fonts','/font-config'].includes(req.url)){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(req.url==='/refs'?rows:req.url==='/recipes'?recipes:req.url==='/font-config'?resources:Object.keys(fonts)));}
        const file=files.get(req.url);if(!file){res.statusCode=404;return res.end();}res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':/\.(m?js)$/.test(file)?'text/javascript':'application/octet-stream');fs.createReadStream(file).pipe(res);
    });await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;const workerRows=[];
    try{browser=await chromium.launch({headless:true,timeout:600000,args:['--js-flags=--max-old-space-size=768 --v8-pool-size=1']});const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port+'/');
        for(let i=0;i<2;i++){
            const row=await page.evaluate(()=>new Promise((resolve,reject)=>{const w=new Worker('/worker.js'),timer=setTimeout(()=>{w.terminate();reject(new Error('Symbol timeout'));},600000);w.onmessage=({data})=>{clearTimeout(timer);w.terminate();data.error?reject(new Error(data.error)):resolve(data);};w.onerror=e=>{clearTimeout(timer);w.terminate();reject(new Error(e.message));};w.postMessage(null);}));
            assert.equal(row.calls,rows.length);assert.equal(row.guards,8);assert.equal(row.dom,'undefined');assert.ok(row.stats.cachedMasks<=row.stats.cacheLimit);assert.ok(row.stats.cachedMaskBytes<=row.stats.maskCacheBudgetBytes);workerRows.push(row);
        }
        const binary=fs.readFileSync(path.join(engine,'music-symbol.wasm'));
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,recipes:Object.keys(recipes).length,unsupportedDefinitions:Object.keys(inv.unsupported).length,pointSizes:inv.pointSizes,cases:rows.length,pixelsPerInstance:rows.length*inv.width*inv.height,
            nativePixelHashesExact:true,originalPackedRasterInWasm:true,suppliedGlyphIds:0,suppliedGlyphPositions:0,evictionChecks,sharedFaceMetricRecoveryChecks:evictionChecks,nodeRows,workerRows,
            engineWasmBytes:binary.length,engineWasmSha256:sha(binary),fontWasmBytes:wasm.length,fontWasmGzipBytes:zlib.gzipSync(wasm).length,fontWasmSha256:sha(wasm),
            recipesSha256:sha(fs.readFileSync(path.join(root,'recipes.json'))),nativeReferenceSha256:sha(fs.readFileSync(path.join(root,'jvm-reference.json'))),
            fontResourcesSha256:sha(fs.readFileSync(path.join(root,'font-resources.json'))),missingTextRoleRejected:true,
            hostSha256:sha(fs.readFileSync(host)),metricsHostSha256:sha(fs.readFileSync(path.resolve(__dirname,'../host/freetype-music-metrics.mjs'))),verifierSha256:sha(fs.readFileSync(__filename)),
            fontSha256:Object.fromEntries(Object.entries(fonts).map(([name,bytes])=>[name,sha(bytes)])),
            limitation:'Source-derived centered glyphs, reviewed size transforms, centered repeat-bar layers and ASCII TextSymbol definitions with explicit physical-font bindings. Other composite/line definitions remain explicit unsupported; point sizes above 128 and general text shaping/fallback need separate validation.'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser)await browser.close();await new Promise(r=>server.close(r));}
})().catch(error=>{console.error(error);process.exitCode=1;});
