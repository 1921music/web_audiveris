const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
const samples = path.resolve(root, '../pixel-probe/samples');
(async () => {
    const oracle = JSON.parse(fs.readFileSync(path.join(root, 'jvm-reference.json'), 'utf8'));
    const manifest = JSON.parse(fs.readFileSync(path.join(samples, 'manifest.json'), 'utf8'));
    vm.runInThisContext(fs.readFileSync(path.join(root, 'pixel-sheet.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const api = (await TeaVM.wasmGC.load(path.join(root, 'pixel-sheet.wasm'))).exports; api.configure();
    assert.equal(api.validation(), true); assert.equal(api.fixture(), oracle.fixture);
    for (const page of manifest.pages) {
        const buffer = fs.readFileSync(path.join(samples, page.id + '.binary'));
        assert.equal(buffer.length, page.width * page.height);
        assert.equal(crypto.createHash('sha256').update(buffer).digest('hex'), page.binarySha256);
        const data = new Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength), copy = Buffer.from(buffer);
        assert.equal(api.scalePage(page.width, page.height, data, page.id), oracle.pages.find(p => p.id === page.id).result);
        assert.deepEqual(buffer, copy);
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Pixel sheet verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/pixel-sheet.wasm-runtime.js'); let api;
                onmessage = async event => { try {
                    if (!api) { api = (await TeaVM.wasmGC.load('/pixel-sheet.wasm')).exports; api.configure(); }
                    const p = event.data;
                    postMessage({result: p.buffer ? api.scalePage(p.width, p.height, new Int8Array(p.buffer), p.id)
                        : {fixture: api.fixture(), valid: api.validation()}, dom: typeof document});
                } catch(error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (['pixel-sheet.wasm', 'pixel-sheet.wasm-runtime.js'].includes(name)) {
            response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
            fs.createReadStream(path.join(root, name)).pipe(response); return;
        }
        if (/^[0-9]{2}\.binary$/.test(name) && manifest.pages.some(p => p.id + '.binary' === name)) {
            fs.createReadStream(path.join(samples, name)).pipe(response); return;
        }
        response.writeHead(404).end();
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const result = await page.evaluate(async pages => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            function call(index, message, transfer = []) {
                return new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('Pixel sheet timeout')), 30000);
                    workers[index].onmessage = event => { clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                    workers[index].onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
                    workers[index].postMessage(message, transfer);
                });
            }
            try {
                const fixtures = [await call(0, {}), await call(1, {})], outputs = [];
                for (const p of pages) {
                    const buffer = await (await fetch('/' + p.id + '.binary')).arrayBuffer();
                    const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', buffer))]
                        .map(byte => byte.toString(16).padStart(2, '0')).join('');
                    if (digest !== p.binarySha256) { throw new Error('Wrong sample pixels'); }
                    outputs.push(await call(0, {...p, buffer}, [buffer]));
                    if (buffer.byteLength !== 0) { throw new Error('Host buffer not transferred'); }
                }
                return {fixtures, outputs};
            } finally { workers.forEach(worker => worker.terminate()); }
        }, manifest.pages.map(({id, width, height, binarySha256}) => ({id, width, height, binarySha256})));
        for (const fixture of result.fixtures) {
            assert.equal(fixture.result.fixture, oracle.fixture); assert.equal(fixture.result.valid, true); assert.equal(fixture.dom, 'undefined');
        }
        result.outputs.forEach((row, index) => {
            assert.equal(row.result, oracle.pages.find(p => p.id === manifest.pages[index].id).result); assert.equal(row.dom, 'undefined');
        });
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 1, workers: 2,
            realPages: manifest.pages.length, pixels: manifest.pages.reduce((sum, p) => sum + p.width * p.height, 0),
            originalSheetScalePath: true, comparedToUpstreamJvm: true, bufferOwnership: true, hostTransfer: true,
            binaryInputSha256Verified: true,
            validatedInitialization: true, pageResults: oracle.pages, wasmBytes: fs.statSync(path.join(root, 'pixel-sheet.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
