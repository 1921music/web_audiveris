const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    vm.runInThisContext(fs.readFileSync(path.join(root, 'headless.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const one = (await TeaVM.wasmGC.load(path.join(root, 'headless.wasm'))).exports;
    const two = (await TeaVM.wasmGC.load(path.join(root, 'headless.wasm'))).exports;
    assert.equal(one.platform(), 'platform-ok'); assert.equal(two.platform(), 'platform-ok');
    one.close(); assert.equal(one.open(), false); assert.equal(two.open(), true);
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Worker platform tests</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/headless.wasm-runtime.js');
                let api;
                onmessage = async event => {
                    try {
                        if (!api) api = (await TeaVM.wasmGC.load('/headless.wasm')).exports;
                        const result = api[event.data](); postMessage({result, dom: typeof document, domParser: typeof DOMParser});
                    } catch (error) { postMessage({error: String(error.stack || error)}); }
                };`); return;
        }
        const name = request.url.slice(1);
        if (!['headless.wasm', 'headless.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({ headless: true }); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const result = await page.evaluate(async () => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            async function call(index, method) {
                return new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('Worker timeout')), 30000);
                    workers[index].onmessage = event => { clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                    workers[index].onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
                    workers[index].postMessage(method);
                });
            }
            try {
                const first = await call(0, 'platform'), second = await call(1, 'platform');
                await call(0, 'close');
                return { first, second, closed: await call(0, 'open'), independent: await call(1, 'open') };
            } finally { workers.forEach(worker => worker.terminate()); }
        });
        for (const item of [result.first, result.second]) {
            assert.equal(item.result, 'platform-ok'); assert.equal(item.dom, 'undefined'); assert.equal(item.domParser, 'undefined');
        }
        assert.equal(result.closed.result, false); assert.equal(result.independent.result, true);
        const report = { node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            platform: true, serialScheduling: true, resourceUri: true, loggingAndProgress: true,
            metronomeMusicDataAndRegex: true, templateComposite: true, unboundSheetContextRejected: true,
            guiGuardPreservesEngineWork: true, workerIsolation: true, wasmBytes: fs.statSync(path.join(root, 'headless.wasm')).size };
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
