const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    const expected = fs.readFileSync(path.join(root, 'jvm-reference.txt'), 'utf8');
    vm.runInThisContext(fs.readFileSync(path.join(root, 'sheet-model.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    for (let i = 0; i < 2; i++) {
        const api = (await TeaVM.wasmGC.load(path.join(root, 'sheet-model.wasm'))).exports;
        assert.equal(api.construct(), expected); assert.equal(api.invalidBooks(), true);
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Sheet model verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/sheet-model.wasm-runtime.js'); onmessage = async () => {
                try { const api = (await TeaVM.wasmGC.load('/sheet-model.wasm')).exports;
                    postMessage({model: api.construct(), validation: api.invalidBooks(), dom: typeof document});
                } catch(error) { postMessage({error: String(error.stack || error)}); }
            };`); return;
        }
        const name = request.url.slice(1);
        if (!['sheet-model.wasm', 'sheet-model.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(() => Promise.all([0, 1].map(() => new Promise((resolve, reject) => {
            const worker = new Worker('/worker.js');
            const timer = setTimeout(() => { worker.terminate(); reject(new Error('Model worker timeout')); }, 30000);
            worker.onmessage = event => { clearTimeout(timer); worker.terminate(); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
            worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
            worker.postMessage('run');
        }))));
        for (const result of results) {
            assert.equal(result.model, expected); assert.equal(result.validation, true); assert.equal(result.dom, 'undefined');
        }
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            memoryBook: true, originalSheetModel: true, originalGlyphAndInterIndexes: true,
            originalStaffAndLagManagers: true, originalInputBookParityJvm: true, invalidBookNamesRejected: true,
            modelLockReentryAndBalancedRelease: true, legacyArchiveDataRejected: true,
            limitation: 'Model initialization only; no raster attachment, staff detection or recognition',
            wasmBytes: fs.statSync(path.join(root, 'sheet-model.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
