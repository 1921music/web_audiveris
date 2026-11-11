// Research diagnostic: actual browser-capable OCR on original native-cleaned score rasters.
// Native font measurements and cleanup remain supplied; differences are reported, never hidden.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),http=require('node:http');
const {pathToFileURL}=require('node:url');const {createWorker}=require('tesseract.js');
const {chromium}=require('playwright'),features=require('wasm-feature-detect');
const root=path.resolve(process.argv[2]),compat=path.resolve(__dirname,'..'),native=path.join(root,'native');
const inputSha256={},sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const bytes=file=>{const value=fs.readFileSync(file);inputSha256[path.relative(root,file)]=sha(value);return value;};
const read=file=>JSON.parse(bytes(file));
function differences(actual,expected){
    let count=0,maxNumericDifference=0;const examples=[];
    function walk(a,b,key='$'){
        if(a===b){return;}
        if(a&&b&&typeof a==='object'&&typeof b==='object'&&Array.isArray(a)===Array.isArray(b)){
            for(const field of new Set([...Object.keys(a),...Object.keys(b)])){walk(a[field],b[field],key+'.'+field);}return;
        }
        count++;if(typeof a==='number'&&typeof b==='number'){maxNumericDifference=Math.max(maxNumericDifference,Math.abs(a-b));}
        if(examples.length<20){examples.push({key,actual:a,expected:b});}
    }
    walk(actual,expected);return {exact:count===0,count,maxNumericDifference,examples};
}
(async()=>{
    const {TesseractJsOcr}=await import(pathToFileURL(path.join(compat,'host/tesseract-js-ocr.mjs')));
    const references=read(path.join(native,'jvm-reference.json')),coverage=read(path.join(native,'coverage.json'));
    assert.equal(references.length,coverage.acceptedPages);assert.ok(references.length>0);
    const modelDir=path.join(compat,'node_modules/@tesseract.js-data/eng/4.0.0');bytes(path.join(modelDir,'eng.traineddata.gz'));
    let created=0;const versions=new Set(),adapter=new TesseractJsOcr({oem:0,availableLanguages:['eng'],
        createWorker:async(...args)=>{created++;const worker=await createWorker(...args),recognize=worker.recognize.bind(worker);
            worker.recognize=async(...args)=>{const result=await recognize(...args);versions.add(result.data.version);return result;};return worker;},
        workerOptions:{workerPath:path.join(compat,'host/tesseract-word-metadata-node.cjs'),langPath:modelDir,cacheMethod:'none',gzip:true}});
    const pages=[],results=[],requests=[];
    try{
        for(const reference of references){
            const m=read(path.join(native,reference.id,'metadata.json')),cleaned=bytes(path.join(native,reference.id,'cleaned.gray'));
            assert.equal(cleaned.length,m.width*m.height);
            const width=m.width+2*m.margin,height=m.height+2*m.margin,pixels=new Uint8Array(width*height).fill(255);
            for(let y=0;y<m.height;y++){pixels.set(cleaned.subarray(y*m.width,(y+1)*m.width),(y+m.margin)*width+m.margin);}
            const request={token:{job:'score-ocr',page:reference.id,generation:1,request:pages.length+1},width,height,pixels,languages:m.languages,layout:'MULTI_BLOCK'};
            requests.push(request);
            const started=performance.now(),reply=await adapter.recognize(request),elapsedMs=performance.now()-started;
            assert.deepEqual(reply.token,request.token);assert.ok(!reply.failure,JSON.stringify(reply.failure));
            const expected=read(path.join(native,reference.id,'ocr-result.json')),comparison=differences(reply.result,expected);
            pages.push({id:reference.id,width,height,elapsedMs,words:reply.result.lines.reduce((n,line)=>n+line.words.length,0),comparison});
            results.push({id:reference.id,result:reply.result});console.log(JSON.stringify(pages.at(-1)));
        }
    }finally{await adapter.close();}
    assert.equal(created,1);
    const simd=await features.relaxedSimd()?'relaxedsimd':await features.simd()?'simd':'',coreName='tesseract-core'+(simd?'-'+simd:'');
    const modules=path.join(compat,'node_modules'),files=new Map([
        ['/ocr-assets/tesseract.min.js',path.join(modules,'tesseract.js/dist/tesseract.min.js')],
        ['/ocr-assets/worker.min.js',path.join(modules,'tesseract.js/dist/worker.min.js')],
        ['/core-assets/'+coreName+'.js',path.join(modules,'tesseract.js-core',coreName+'.js')],
        ['/core-assets/'+coreName+'.wasm',path.join(modules,'tesseract.js-core',coreName+'.wasm')],
        ['/models/eng.traineddata.gz',path.join(modelDir,'eng.traineddata.gz')]
    ]);
    for(const name of ['tesseract-js-ocr.mjs','gray8-png.mjs','tesseract-word-metadata.mjs','tesseract-core-word-metadata.js']){
        files.set('/host/'+name,path.join(compat,'host',name));
    }
    let origin;const requested=new Set();
    const worker=`importScripts('/ocr-assets/tesseract.min.js');onmessage=async()=>{
        let adapter;try{
            const {TesseractJsOcr}=await import('/host/tesseract-js-ocr.mjs'),{browserWordMetadataOptions}=await import('/host/tesseract-word-metadata.mjs');
            let created=0;adapter=new TesseractJsOcr({oem:0,availableLanguages:['eng'],createWorker:async(...args)=>{created++;return Tesseract.createWorker(...args);},
                workerOptions:browserWordMetadataOptions({workerPath:location.origin+'/ocr-assets/worker.min.js',corePath:location.origin+'/core-assets/${coreName}.js',langPath:location.origin+'/models',cacheMethod:'none',gzip:true},
                    {loaderPath:location.origin+'/host/tesseract-core-word-metadata.js',metadataModulePath:location.origin+'/host/tesseract-word-metadata.mjs'})});
            const pages=await(await fetch('/requests')).json(),rows=[];
            for(const page of pages){
                const pixels=new Uint8Array(await(await fetch('/pixels/'+page.token.page)).arrayBuffer());
                const reply=await adapter.recognize({...page,pixels});if(reply.failure){throw new Error(JSON.stringify(reply.failure));}
                rows.push({id:page.token.page,result:reply.result});
            }postMessage({rows,created,dom:typeof document});
        }catch(error){postMessage({error:String(error.stack||error)});}finally{if(adapter){await adapter.close();}}
    };`;
    const server=http.createServer((req,res)=>{
        const url=new URL(req.url,origin||'http://127.0.0.1').pathname;
        if(url==='/'){return res.end('<!doctype html><title>Score OCR diagnostic</title>');}
        if(url==='/worker.js'){res.setHeader('Content-Type','text/javascript');return res.end(worker);}
        if(url==='/requests'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(requests.map(({pixels,...request})=>request)));}
        if(url.startsWith('/pixels/')){const request=requests.find(request=>'/pixels/'+request.token.page===url);if(request){return res.end(request.pixels);}}
        const file=files.get(url);if(!file){res.statusCode=404;return res.end();}requested.add(url);
        res.setHeader('Content-Type',file.endsWith('.wasm')?'application/wasm':/\.(m?js)$/.test(file)?'text/javascript':'application/octet-stream');fs.createReadStream(file).pipe(res);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+server.address().port;
    let browser,chromiumVersion;const workerResults=[];
    try{
        browser=await chromium.launch({headless:true,args:['--js-flags=--max-old-space-size=384']});chromiumVersion=browser.version();
        const context=await browser.newContext(),external=[];
        await context.route('**/*',route=>{if(new URL(route.request().url()).origin!==origin){external.push(route.request().url());return route.abort();}return route.continue();});
        const page=await context.newPage();await page.goto(origin+'/');
        for(let instance=0;instance<2;instance++){
            const result=await page.evaluate(()=>new Promise((resolve,reject)=>{
                const worker=new Worker('/worker.js'),timer=setTimeout(()=>{worker.terminate();reject(new Error('Score OCR timeout'));},180000);
                worker.onmessage=({data})=>{clearTimeout(timer);worker.terminate();data.error?reject(new Error(data.error)):resolve(data);};
                worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};worker.postMessage(null);
            }));
            assert.equal(result.created,1);assert.equal(result.dom,'undefined');assert.deepEqual(result.rows,results);workerResults.push(result.rows);
        }
        assert.deepEqual(external,[]);
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
    const assets=Object.fromEntries([...files].map(([url,file])=>{const value=fs.readFileSync(file);return [url,{bytes:value.length,sha256:sha(value)}];}));
    const report={node:process.version,chromium:chromiumVersion,workers:2,workerResultsExact:true,allBrowserAssetsLocal:true,assets,requested:[...requested],
        backend:'tesseract.js',version:require('tesseract.js/package.json').version,
        oem:0,engineVersions:[...versions],pages,nodeWorkerReused:true,inputSha256,
        exactPages:pages.filter(page=>page.comparison.exact).length,
        limitation:'Live Node and Worker OCR on native-cleaned score images; native OCR parity is diagnostic; TEXTS resume and general font measurement are not established by this run'};
    const output=path.join(root,'live-ocr');fs.mkdirSync(output,{recursive:true});
    fs.writeFileSync(path.join(output,'node-results.json'),JSON.stringify(results)+'\n');
    fs.writeFileSync(path.join(output,'worker-results.json'),JSON.stringify(workerResults)+'\n');
    fs.writeFileSync(path.join(output,'comparison.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({pages:pages.length,exactPages:report.exactPages,engineVersions:report.engineVersions}));
})().catch(error=>{console.error(error);process.exitCode=1;});
