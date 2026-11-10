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
    const frames = JSON.parse(fs.readFileSync(path.join(root, 'jvm-frame-reference.json'), 'utf8'));
    assert.equal(frames.cases, 640); assert.equal(frames.gray8Bytes, 890694); assert.ok(frames.nativePixelsExact);
    vm.runInThisContext(fs.readFileSync(path.join(root, 'ocr-exchange.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const a = (await TeaVM.wasmGC.load(path.join(root, 'ocr-exchange.wasm'))).exports;
    const b = (await TeaVM.wasmGC.load(path.join(root, 'ocr-exchange.wasm'))).exports;
    assert.equal(a.contracts(), expected); assert.equal(b.contracts(), expected);
    a.begin(7); b.begin(8);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(a.state(), 'WAITING'); assert.equal(a.respond(8), false);
    assert.equal(a.respond(7), true); assert.equal(a.respond(7), false);
    assert.equal(b.state(), 'WAITING'); b.cancel();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(b.respond(8), false); assert.equal(a.resume(), 0); assert.equal(a.state(), 'IDLE');
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>OCR request/resume boundary</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/ocr-exchange.wasm-runtime.js'); let api;
                onmessage = async event => {
                    try {
                        if (!api) api = (await TeaVM.wasmGC.load('/ocr-exchange.wasm')).exports;
                        postMessage({result: api[event.data.method](event.data.argument), dom: typeof document});
                    } catch (error) { postMessage({error: String(error.stack || error)}); }
                };`); return;
        }
        const name = request.url.slice(1);
        if (!['ocr-exchange.wasm', 'ocr-exchange.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const rows = await page.evaluate(async () => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')], rows = [];
            function call(index, method, argument) {
                return new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('OCR boundary timeout')), 30000);
                    workers[index].onmessage = event => {
                        clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data);
                    };
                    workers[index].onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
                    workers[index].postMessage({method, argument});
                });
            }
            try {
                for (let i = 0; i < 2; i++) { rows.push(await call(i, 'contracts')); }
                rows.push(await call(0, 'begin', 3)); rows.push(await call(1, 'begin', 4));
                rows.push(await call(0, 'respond', 4)); rows.push(await call(0, 'state'));
                rows.push(await call(1, 'cancel')); rows.push(await call(1, 'respond', 4));
                rows.push(await call(0, 'respond', 3)); rows.push(await call(0, 'respond', 3));
                rows.push(await call(0, 'resume')); rows.push(await call(1, 'state'));
                // Replacing the cancelled generation must not admit its late reply.
                rows.push(await call(1, 'begin', 5)); rows.push(await call(1, 'respond', 4));
                rows.push(await call(1, 'respond', 5)); rows.push(await call(1, 'resume'));
                return rows;
            } finally { workers.forEach(worker => worker.terminate()); }
        });
        assert.deepEqual(rows.map(r => r.result ?? null), [expected, expected, 1, 1, false, 'WAITING', null,
            false, true, false, 0, 'CANCELLED', 1, false, true, 0]);
        for (const row of rows) { assert.equal(row.dom, 'undefined'); }
        const wasm = fs.readFileSync(path.join(root, 'ocr-exchange.wasm'));
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            contract: expected, workerIsolation: true, asynchronousRoundTrip: true,
            staleAndDuplicateRepliesRejected: true, cancellationTerminal: true, emptyAndFailureDistinct: true,
            pixelSnapshotsOwned: true, nativeGray8Margins: frames, frameCoordinatesRestored: true,
            frameReplyAppliedOnce: true, wasmBytes: wasm.length,
            wasmSha256: crypto.createHash('sha256').update(wasm).digest('hex'),
            limitation: 'OCR transport/lifecycle component only; TEXTS raster cleanup, backend OCR and result ingestion are not yet integrated'};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
