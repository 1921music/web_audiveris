const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    const expected = fs.readFileSync(path.join(root, 'jvm-reference.txt'), 'utf8');
    const pixels = fs.readFileSync(path.join(root, 'jvm-pixels.binary'));
    const pixelSha256 = crypto.createHash('sha256').update(pixels).digest('hex');
    vm.runInThisContext(fs.readFileSync(path.join(root, 'gray-path.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    for (let i = 0; i < 2; i++) {
        const api = (await TeaVM.wasmGC.load(path.join(root, 'gray-path.wasm'))).exports;
        assert.equal(api.hashes(), expected);
        assert.deepEqual(Buffer.from(api.pixels()), pixels);
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Path raster verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/gray-path.wasm-runtime.js'); onmessage = async () => {
                try {const api=(await TeaVM.wasmGC.load('/gray-path.wasm')).exports;
                    const pixels=api.pixels();
                    const digest=await crypto.subtle.digest('SHA-256',pixels);
                    postMessage({result:api.hashes(),bytes:pixels.length,
                        pixelSha256:Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join(''),dom:typeof document});}
                catch(error){postMessage({error:String(error.stack||error)});}
            };`); return;
        }
        const name = request.url.slice(1);
        if (!['gray-path.wasm', 'gray-path.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless:true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const rows = await page.evaluate(async () => Promise.all([0,1].map(() => new Promise((resolve,reject) => {
            const worker=new Worker('/worker.js');
            const timer=setTimeout(()=>{worker.terminate();reject(new Error('Path raster timeout'));},30000);
            worker.onmessage=event=>{clearTimeout(timer);worker.terminate();event.data.error?reject(new Error(event.data.error)):resolve(event.data);};
            worker.onerror=event=>{clearTimeout(timer);worker.terminate();reject(new Error(event.message));};
            worker.postMessage(null);
        }))));
        for (const row of rows) {
            assert.equal(row.result,expected);assert.equal(row.dom,'undefined');
            assert.equal(row.bytes,pixels.length);assert.equal(row.pixelSha256,pixelSha256);
        }
        const wasm=fs.readFileSync(path.join(root,'gray-path.wasm'));
        const report={node:process.version,chromium:browser.version(),nodeInstances:2,workers:2,
            paths:512,nativeComparedPixels:pixels.length,nativeJvmPixelsExact:true,nodePixelsExact:true,
            workerPixelSha256Exact:true,pixelSha256,wasmPerPathHashesExact:true,
            unchangedJdkProcessPath:true,wasmBytes:wasm.length,
            wasmSha256:crypto.createHash('sha256').update(wasm).digest('hex'),
            limitation:'Thin-stroke non-antialiased Path2D fill component only; rectangle fast paths, wide-stroke spans and TEXTS image cleanup are not yet covered'};
        fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser){await browser.close();}await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
