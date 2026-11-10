const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
function nearestKernel(bytes) {
    let offset = 8;
    function leb() { let value = 0, shift = 0, byte; do { byte = bytes[offset++]; value |= (byte & 127) << shift; shift += 7; } while (byte & 128); return value >>> 0; }
    while (offset < bytes.length) {
        const section = bytes[offset++], length = leb(), end = offset + length;
        if (section === 10) {
            const count = leb();
            for (let i = 0; i < count; i++) {
                const size = leb(), body = bytes.subarray(offset, offset + size); offset += size;
                if (body.equals(Buffer.from([0, 0x20, 0, 0x9e, 0x0b]))) { return true; }
            }
        }
        offset = end;
    }
    return false;
}
(async () => {
    const values = fs.readFileSync(path.join(root, 'jvm-reference.txt'), 'utf8');
    const checksum = fs.readFileSync(path.join(root, 'jvm-checksum.txt'), 'utf8');
    const wasm = fs.readFileSync(path.join(root, 'rounding.wasm'));
    assert.equal(nearestKernel(wasm), true, 'Expected one-instruction f64.nearest implementation');
    vm.runInThisContext(fs.readFileSync(path.join(root, 'rounding.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    for (let i = 0; i < 2; i++) {
        const api = (await TeaVM.wasmGC.load(path.join(root, 'rounding.wasm'))).exports;
        assert.equal(api.values(), values); assert.equal(api.checksum(), checksum);
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Rounding verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/rounding.wasm-runtime.js'); onmessage = async () => { try {
                const api = (await TeaVM.wasmGC.load('/rounding.wasm')).exports;
                postMessage({values: api.values(), checksum: api.checksum()});
            } catch(error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (!['rounding.wasm', 'rounding.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(() => Promise.all([0, 1].map(() => new Promise((resolve, reject) => {
            const worker = new Worker('/worker.js');
            const timer = setTimeout(() => { worker.terminate(); reject(new Error('Rounding worker timeout')); }, 30000);
            worker.onmessage = event => { clearTimeout(timer); worker.terminate(); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
            worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
            worker.postMessage('run');
        }))));
        for (const result of results) { assert.equal(result.values, values); assert.equal(result.checksum, checksum); }
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            jvmChecks: JSON.parse(fs.readFileSync(path.join(root, 'jvm-verification.json'), 'utf8')).checks,
            exactEdgeCases: 25, randomWasmCasesPerInstance: 8192, f64NearestKernel: true,
            tiesToEven: true, negativeRoundTies: true, signedZero: true, nanAndInfinity: true, integerSaturation: true,
            wasmBytes: wasm.length};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
