// Research only: compare original OmrFont visual bounds/font fitting with explicit Worker fonts.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]),fixture=JSON.parse(fs.readFileSync(path.join(root,'jvm-reference.json')));
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
(async()=>{
    const worker=`(async()=>{
        const fixture=await(await fetch('/fixture')).json();
        for(const face of fixture.faces){const font=new FontFace(face.id,await(await fetch('/fonts/'+face.file)).arrayBuffer());await font.load();self.fonts.add(font);}
        const context=new OffscreenCanvas(1,1).getContext('2d');context.fontKerning='none';context.textRendering='geometricPrecision';
        const rint=value=>{const floor=Math.floor(value),fraction=value-floor;return fraction===0.5?(floor%2===0?floor:floor+1):Math.round(value);};
        const result=fixture.measurements.map(test=>[1,16,64,1024].map(scale=>{
            context.font=(test.size*scale)+'px "'+test.face+'"';
            const metrics=context.measureText(test.text),bounds={x:-metrics.actualBoundingBoxLeft,y:-metrics.actualBoundingBoxAscent,
                width:metrics.actualBoundingBoxLeft+metrics.actualBoundingBoxRight,height:metrics.actualBoundingBoxAscent+metrics.actualBoundingBoxDescent};
            for(const key of Object.keys(bounds)){bounds[key]/=scale;}
            const ratio=Math.fround((test.targetWidth>=test.targetHeight?test.targetWidth:test.targetHeight)/Math.fround(test.targetWidth>=test.targetHeight?bounds.width:bounds.height));
            return {bounds,advance:metrics.width/scale,fittedSize:rint(Math.fround(ratio*test.size))};
        }));postMessage({result});
    })().catch(error=>postMessage({error:String(error.stack||error)}));`;
    const files=new Map(fixture.faces.map(face=>['/fonts/'+face.file,path.join(root,'fonts',face.file)]));
    for(const face of fixture.faces){assert.equal(sha(fs.readFileSync(files.get('/fonts/'+face.file))),face.sha256);}
    const server=http.createServer((request,response)=>{
        if(request.url==='/'){response.setHeader('Content-Type','text/html');return response.end('<!doctype html><title>Text metrics comparison</title>');}
        if(request.url==='/worker.js'){response.setHeader('Content-Type','text/javascript');return response.end(worker);}
        if(request.url==='/fixture'){response.setHeader('Content-Type','application/json');return response.end(JSON.stringify(fixture));}
        const file=files.get(request.url);if(!file){response.statusCode=404;return response.end();}fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,args:['--no-sandbox','--disable-gpu','--disable-dev-shm-usage']});
        const page=await browser.newPage(),origin='http://127.0.0.1:'+server.address().port;
        await page.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
        await page.goto(origin);
        const results=await page.evaluate(()=>Promise.all([0,1].map(()=>new Promise((resolve,reject)=>{
            const worker=new Worker('/worker.js');worker.onmessage=({data})=>{worker.terminate();data.error?reject(new Error(data.error)):resolve(data.result);};worker.onerror=reject;
        }))));
        assert.deepEqual(results[0],results[1]);assert.equal(results[0].length,fixture.measurements.length);
        const modes=[1,16,64,1024].map((scale,mode)=>{
        let differentBounds=0,differentSizes=0,maxBoundsError=0,maxAdvanceError=0,maxSizeError=0;const examples=[];
        for(let index=0;index<results[0].length;index++){
            const expected=fixture.measurements[index],actual=results[0][index][mode];
            const error=Math.max(...['x','y','width','height'].map(key=>Math.abs(expected.bounds[key]-actual.bounds[key])));
            assert.ok(Object.values(actual.bounds).every(Number.isFinite));assert.ok(Number.isInteger(actual.fittedSize));
            if(error!==0){differentBounds++;}maxBoundsError=Math.max(maxBoundsError,error);
            maxAdvanceError=Math.max(maxAdvanceError,Math.abs(expected.advance-actual.advance));
            if(expected.fittedSize!==actual.fittedSize){differentSizes++;maxSizeError=Math.max(maxSizeError,Math.abs(expected.fittedSize-actual.fittedSize));
                if(examples.length<12){examples.push({face:expected.face,size:expected.size,text:expected.text,native:expected.fittedSize,canvas:actual.fittedSize,boundsError:error});}}
        }
        return {scale,differentBounds,differentSizes,maxBoundsError,maxAdvanceError,maxSizeError,examples};
        });
        const sourceFiles=['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/TextFontMetricsOracle.java','wasm-compat/tools/compare-text-fonts.cjs',
            'app/src/main/java/org/audiveris/omr/ui/symbol/OmrFont.java'];
        const repository=path.resolve(__dirname,'../..');
        const report={chromium:browser.version(),workers:2,workerResultsExact:true,cases:results[0].length,faces:fixture.faces,
            ...modes[0],modes,
            sourceSha256:Object.fromEntries(sourceFiles.map(file=>[file,sha(fs.readFileSync(path.join(repository,file),'utf8').replace(/\r\n/g,'\n'))])),
            referenceSha256:sha(fs.readFileSync(path.join(root,'jvm-reference.json'))),
            limitation:'Physical-font diagnostic only; no engine font replacement, fallback shaping or glyph raster equivalence'};
        fs.writeFileSync(path.join(root,'worker-results.json'),JSON.stringify(results[0]));
        fs.writeFileSync(path.join(root,'comparison.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
