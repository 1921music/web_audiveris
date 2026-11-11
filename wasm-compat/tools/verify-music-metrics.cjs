const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const http=require('node:http'),zlib=require('node:zlib'),{pathToFileURL}=require('node:url'),{chromium}=require('playwright');
const root=path.resolve(process.argv[2]),sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
async function check(service,rows){
    let checked=0,unsupported=0;const values=[];
    for(const row of rows){
        if(!row.portable){assert.throws(()=>service.measure(row.family,row.size,row.value),/fallback required/);unsupported++;continue;}
        const result=service.measure(row.family,row.size,row.value),bounds=[result.x,result.y,result.width,result.height];
        assert.equal(result.fontName,row.name);assert.deepEqual(bounds,row.visual);assert.deepEqual(bounds,row.layout);assert.equal(result.advance,row.advance);
        values.push([result.fontName,...bounds,result.advance]);checked++;
    }
    let guards=0;
    for(const args of [['Bravura',0,'\ue1d5'],['Bravura',16385,'\ue1d5'],['MissingFamily',12,'\ue1d5'],['Bravura',12,'abc']]){
        try{service.measure(...args);}catch{guards++;}
    }
    assert.equal(guards,4);const stats=service.stats();service.dispose();assert.throws(()=>service.measure('Bravura',12,'\ue1d5'),/closed/);
    return {checked,unsupported,guards:guards+1,stats,valueSha256:sha(JSON.stringify(values))};
}
(async()=>{
    const bytes=fs.readFileSync(path.join(root,'jvm-reference.json')),rows=JSON.parse(bytes),native=JSON.parse(fs.readFileSync(path.join(root,'comparison.json')));
    assert.equal(rows.length,6966);assert.equal(native.portableDifferences,0);
    const fonts={};for(const[name,digest]of Object.entries(native.fontSha256)){fonts[name]=fs.readFileSync(path.join(root,'fonts',name));assert.equal(sha(fonts[name]),digest);}
    const wasm=fs.readFileSync(path.join(root,'music-metrics.wasm')),glue=fs.readFileSync(path.join(root,'music-metrics.mjs'));
    const {default:factory}=await import(pathToFileURL(path.join(root,'music-metrics.mjs')));
    const host=path.resolve(__dirname,'../host/freetype-music-metrics.mjs'),{createFreeTypeMusicMetrics}=await import(pathToFileURL(host));
    const nodeRows=[];for(let i=0;i<2;i++)nodeRows.push(await check(await createFreeTypeMusicMetrics(factory,wasm,fonts),rows));
    assert.equal(nodeRows[0].checked,native.portableCases);assert.equal(nodeRows[0].checked+nodeRows[0].unsupported,rows.length);assert.deepEqual(nodeRows[0],nodeRows[1]);
    const eviction=await check(await createFreeTypeMusicMetrics(factory,wasm,fonts,{maxCacheEntries:1}),rows.filter(row=>row.family==='Bravura'&&(row.size===52||row.size===84)));
    assert.equal(eviction.checked,18);assert.equal(eviction.stats.cachedMeasurements,1);assert.equal(eviction.stats.cachedGlyphMetrics,1);
    await assert.rejects(createFreeTypeMusicMetrics(factory,wasm,fonts,{maxCacheEntries:0}),/cache limit/);
    const worker=`import factory from '/music-metrics.mjs';import{createFreeTypeMusicMetrics}from'/host.mjs';
    onmessage=async()=>{try{
        const rows=await fetch('/jvm-reference.json').then(r=>r.json()),native=await fetch('/comparison.json').then(r=>r.json()),fonts={};
        for(const name of Object.keys(native.fontSha256))fonts[name]=new Uint8Array(await fetch('/fonts/'+name).then(r=>r.arrayBuffer()));
        const service=await createFreeTypeMusicMetrics(factory,new Uint8Array(await fetch('/music-metrics.wasm').then(r=>r.arrayBuffer())),fonts);
        let checked=0,unsupported=0,guards=0;const values=[];
        for(const row of rows){let result;try{result=service.measure(row.family,row.size,row.value);}catch(error){if(row.portable||!String(error).includes('fallback required'))throw error;unsupported++;continue;}
            if(!row.portable)throw new Error('Missing-glyph fallback was silently accepted');
            const bounds=[result.x,result.y,result.width,result.height];
            if(result.fontName!==row.name||bounds.some((v,i)=>v!==row.visual[i]||v!==row.layout[i])||result.advance!==row.advance)throw new Error('Music metric mismatch: '+JSON.stringify(row));
            values.push([result.fontName,...bounds,result.advance]);checked++;
        }
        for(const args of [['Bravura',0,'\\ue1d5'],['Bravura',16385,'\\ue1d5'],['MissingFamily',12,'\\ue1d5'],['Bravura',12,'abc']]){try{service.measure(...args);}catch{guards++;}}
        const stats=service.stats();service.dispose();try{service.measure('Bravura',12,'\\ue1d5');}catch{guards++;}
        const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(values)));
        postMessage({checked,unsupported,guards,stats,valueSha256:Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join(''),dom:typeof document});
    }catch(error){postMessage({error:String(error.stack||error)});}};`;
    const allowed=new Map(['music-metrics.mjs','music-metrics.wasm','jvm-reference.json','comparison.json',...Object.keys(fonts).map(n=>'fonts/'+n)].map(n=>['/'+n,path.join(root,n)]));
    const server=http.createServer((req,res)=>{
        if(req.url==='/')return res.end('<!doctype html><title>Music font metrics</title>');
        if(req.url==='/worker.mjs'||req.url==='/host.mjs'){res.setHeader('Content-Type','text/javascript');return res.end(req.url==='/worker.mjs'?worker:fs.readFileSync(host));}
        const file=allowed.get(req.url);if(!file){res.statusCode=404;return res.end();}
        res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':file.endsWith('.mjs')?'text/javascript':'application/octet-stream');fs.createReadStream(file).pipe(res);
    });
    await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;const workerRows=[];
    try{
        browser=await chromium.launch({headless:true,timeout:600000});const page=await browser.newPage();await page.goto('http://127.0.0.1:'+server.address().port+'/');
        for(let i=0;i<2;i++){
            const result=await page.evaluate(()=>new Promise((resolve,reject)=>{const w=new Worker('/worker.mjs',{type:'module'}),timer=setTimeout(()=>{w.terminate();reject(new Error('Music metrics timeout'));},600000);
                w.onmessage=({data})=>{clearTimeout(timer);w.terminate();data.error?reject(new Error(data.error)):resolve(data);};w.onerror=e=>{clearTimeout(timer);w.terminate();reject(new Error(e.message));};w.postMessage(null);}));
            assert.equal(result.dom,'undefined');const{dom,...comparison}=result;assert.deepEqual(comparison,nodeRows[0]);workerRows.push(result);
        }
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,examinedCases:rows.length,supportedCases:native.portableCases,
            unsupportedCases:rows.length-native.portableCases,exactValues:true,inputGuardsPerInstance:5,nodeRows,workerRows,nativeReferenceSha256:sha(bytes),
            wasmBytes:wasm.length,wasmGzipBytes:zlib.gzipSync(wasm).length,wasmSha256:sha(wasm),glueBytes:glue.length,glueSha256:sha(glue),hostSha256:sha(fs.readFileSync(host)),fontSha256:native.fontSha256,
            evictionNodeCheck:eviction,cacheLimitGuard:true,
            limitation:'Unshaped music strings whose glyphs are present in explicit physical fonts; missing glyphs require an explicit fallback. No TeaVM TEXTS integration is established by this component.'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser)await browser.close();await new Promise(r=>server.close(r));}
})().catch(error=>{console.error(error);process.exitCode=1;});
