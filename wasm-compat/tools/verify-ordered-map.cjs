const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    const expected = fs.readFileSync(path.join(root, 'jvm-reference.txt'), 'utf8');
    const indexExpected = fs.readFileSync(path.join(root, 'index-reference.txt'), 'utf8');
    const setExpected = fs.readFileSync(path.join(root, 'set-reference.txt'), 'utf8');
    vm.runInThisContext(fs.readFileSync(path.join(root, 'ordered-map.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    for (let i = 0; i < 2; i++) {
        const api = (await TeaVM.wasmGC.load(path.join(root, 'ordered-map.wasm'))).exports;
        assert.equal(api.exercise(), expected);
        assert.equal(api.index(), indexExpected);
        assert.equal(api.sets(), setExpected);
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Ordered map verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/ordered-map.wasm-runtime.js');
                onmessage = async () => {
                    try { const api = (await TeaVM.wasmGC.load('/ordered-map.wasm')).exports;
                        postMessage({result: api.exercise(), index: api.index(), sets: api.sets()});
                    } catch(error) { postMessage({error: String(error.stack || error)}); }
                };`); return;
        }
        const name = request.url.slice(1);
        if (!['ordered-map.wasm', 'ordered-map.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(async () => {
            return Promise.all([0, 1].map(() => new Promise((resolve, reject) => {
                const worker = new Worker('/worker.js');
                const timer = setTimeout(() => { worker.terminate(); reject(new Error('Map worker timeout')); }, 30000);
                worker.onmessage = event => {
                    clearTimeout(timer); worker.terminate();
                    event.data.error ? reject(new Error(event.data.error)) : resolve(event.data);
                };
                worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
                worker.postMessage('run');
            })));
        });
        for (const result of results) {
            assert.equal(result.result, expected); assert.equal(result.index, indexExpected); assert.equal(result.sets, setExpected);
        }
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            randomizedOperationsPerInstance: 24000, rangeBoundaryCasesPerInstance: 440, liveViews: true, mutableIteration: true,
            nullAndRangeExceptions: true, standardApiCaller: true, originalBasicIndex: true,
            orderedSetOperationsPerInstance: 10000, orderedSetStreams: true,
            wasmBytes: fs.statSync(path.join(root, 'ordered-map.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
