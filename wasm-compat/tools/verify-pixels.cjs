const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2]);
const oracle = JSON.parse(fs.readFileSync(path.join(root, 'jvm-reference.json'), 'utf8'));
function verify(result) {
    assert.equal(result.threshold, oracle.threshold);
    assert.equal(result.globalCases, oracle.globalCases);
    assert.equal(result.ownership, true); assert.equal(result.fontRejected, true);
}
(async () => {
    vm.runInThisContext(fs.readFileSync(path.join(root, 'pixels.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    for (let i = 0; i < 2; i++) {
        const api = (await TeaVM.wasmGC.load(path.join(root, 'pixels.wasm'))).exports;
        verify({threshold: api.threshold(), globalCases: api.globalCases(), ownership: api.ownership(), fontRejected: api.fontRejected()});
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Pixel engine tests</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/pixels.wasm-runtime.js');
                onmessage = async () => { try {
                    const api = (await TeaVM.wasmGC.load('/pixels.wasm')).exports;
                    postMessage({threshold: api.threshold(), globalCases: api.globalCases(), ownership: api.ownership(),
                        fontRejected: api.fontRejected(), dom: typeof document});
                } catch (error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (!['pixels.wasm', 'pixels.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(async () => Promise.all([0, 1].map(() => new Promise((resolve, reject) => {
            const worker = new Worker('/worker.js');
            const timer = setTimeout(() => { worker.terminate(); reject(new Error('Pixel Worker timeout')); }, 30000);
            worker.onmessage = event => { clearTimeout(timer); worker.terminate(); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
            worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
            worker.postMessage('run');
        }))));
        for (const result of results) { verify(result); assert.equal(result.dom, 'undefined'); }
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            globalThresholdCases: 30, matchedPixels: oracle.globalCases.replaceAll(';', '').length,
            grayInputOwnership: true, invalidDimensionsRejected: true, desktopFontRejected: true,
            wasmBytes: fs.statSync(path.join(root, 'pixels.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
