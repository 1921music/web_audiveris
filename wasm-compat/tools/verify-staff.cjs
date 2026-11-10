const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]), samples = path.resolve(root, '../pixel-probe/samples');
let numbers = 0, maxDifference = 0;
function compare(actual, expected, label = 'result') {
    if (typeof expected === 'number') {
        assert.equal(typeof actual, 'number', label); assert.ok(Number.isFinite(actual), label);
        const difference = Math.abs(actual - expected), tolerance = label.endsWith('.slope') ? 1e-12 : 1e-8;
        numbers++; maxDifference = Math.max(maxDifference, difference);
        assert.ok(difference <= tolerance, `${label}: ${actual} != ${expected}`); return;
    }
    if (Array.isArray(expected)) {
        assert.ok(Array.isArray(actual), label); assert.equal(actual.length, expected.length, label);
        expected.forEach((value, i) => compare(actual[i], value, label + '[' + i + ']')); return;
    }
    if (expected !== null && typeof expected === 'object') {
        assert.deepEqual(Object.keys(actual).sort(), Object.keys(expected).sort(), label);
        for (const key of Object.keys(expected)) { compare(actual[key], expected[key], label + '.' + key); } return;
    }
    assert.equal(actual, expected, label);
}
(async () => {
    const oracle = JSON.parse(fs.readFileSync(path.join(root, 'jvm-reference.json'), 'utf8'));
    const manifest = JSON.parse(fs.readFileSync(path.join(samples, 'manifest.json'), 'utf8'));
    const suppliers = fs.readFileSync(path.join(root, 'supplier-reference.txt'), 'utf8');
    vm.runInThisContext(fs.readFileSync(path.join(root, 'staff.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const api = (await TeaVM.wasmGC.load(path.join(root, 'staff.wasm'))).exports; api.configure();
    assert.equal(api.suppliers(), suppliers);
    const nodeResults = {fixture: JSON.parse(api.fixture()), pages: [], grayPages: []}; compare(nodeResults.fixture, oracle.fixture);
    for (const page of manifest.pages) {
        const buffer = fs.readFileSync(path.join(samples, page.id + '.binary'));
        assert.equal(crypto.createHash('sha256').update(buffer).digest('hex'), page.binarySha256);
        const copy = Buffer.from(buffer);
        const result = JSON.parse(api.staffPage(page.width, page.height, new Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength), page.id));
        compare(result, oracle.pages.find(p => p.id === page.id).result, page.id); assert.deepEqual(buffer, copy);
        nodeResults.pages.push({id: page.id, result});
        const gray = fs.readFileSync(path.join(samples, page.id + '.gray')), grayCopy = Buffer.from(gray);
        assert.equal(crypto.createHash('sha256').update(gray).digest('hex'), page.graySha256);
        const grayResult = JSON.parse(api.staffGrayPage(page.width, page.height, new Int8Array(gray.buffer, gray.byteOffset, gray.byteLength),
            page.id, manifest.meanCoefficient, manifest.deviationCoefficient));
        compare(grayResult, oracle.pages.find(p => p.id === page.id).result, page.id + '-gray'); assert.deepEqual(gray, grayCopy);
        nodeResults.grayPages.push({id: page.id, result: grayResult});
    }
    fs.writeFileSync(path.join(root, 'wasm-node-results.json'), JSON.stringify(nodeResults, null, 2) + '\n');
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Staff geometry verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/staff.wasm-runtime.js'); let api;
                onmessage = async event => { try {
                    if (!api) { api = (await TeaVM.wasmGC.load('/staff.wasm')).exports; api.configure(); }
                    const p = event.data;
                    const value = !p.buffer ? api.fixture() : p.gray ? api.staffGrayPage(p.width,p.height,new Int8Array(p.buffer),p.id,p.mean,p.deviation)
                        : api.staffPage(p.width,p.height,new Int8Array(p.buffer),p.id);
                    postMessage({result: JSON.parse(value), suppliers: api.suppliers(), dom: typeof document});
                } catch(error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (['staff.wasm', 'staff.wasm-runtime.js'].includes(name)) {
            response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
            fs.createReadStream(path.join(root, name)).pipe(response); return;
        }
        if (/^[0-9]{2}\.(binary|gray)$/.test(name) && manifest.pages.some(p => name === p.id + '.binary' || name === p.id + '.gray')) {
            fs.createReadStream(path.join(samples, name)).pipe(response); return;
        }
        response.writeHead(404).end();
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const result = await page.evaluate(async ({pages, mean, deviation}) => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            function call(index, message, transfer = []) {
                return new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('Staff worker timeout')), 60000);
                    workers[index].onmessage = event => { clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                    workers[index].onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
                    workers[index].postMessage(message, transfer);
                });
            }
            try {
                const fixtures = [await call(0, {}), await call(1, {})], outputs = [], grayOutputs = [];
                for (const p of pages) {
                    for (const gray of [false, true]) {
                        const buffer = await (await fetch('/' + p.id + (gray ? '.gray' : '.binary'))).arrayBuffer();
                        const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', buffer))].map(b => b.toString(16).padStart(2, '0')).join('');
                        if (hash !== (gray ? p.graySha256 : p.binarySha256)) { throw new Error('Wrong sample pixels'); }
                        const output = await call(0, {...p, gray, mean, deviation, buffer}, [buffer]);
                        (gray ? grayOutputs : outputs).push(output);
                        if (buffer.byteLength !== 0) { throw new Error('Host buffer not transferred'); }
                    }
                }
                return {fixtures, outputs, grayOutputs};
            } finally { workers.forEach(worker => worker.terminate()); }
        }, {pages: manifest.pages.map(({id, width, height, binarySha256, graySha256}) => ({id, width, height, binarySha256, graySha256})),
            mean: manifest.meanCoefficient, deviation: manifest.deviationCoefficient});
        for (const fixture of result.fixtures) { compare(fixture.result, oracle.fixture); assert.equal(fixture.dom, 'undefined'); assert.equal(fixture.suppliers, suppliers); }
        result.outputs.forEach((row, i) => { compare(row.result, oracle.pages.find(p => p.id === manifest.pages[i].id).result); assert.equal(row.dom, 'undefined'); });
        result.grayOutputs.forEach((row, i) => { compare(row.result, oracle.pages.find(p => p.id === manifest.pages[i].id).result); assert.equal(row.dom, 'undefined'); });
        fs.writeFileSync(path.join(root, 'wasm-worker-results.json'), JSON.stringify(result, null, 2) + '\n');
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 1, workers: 2,
            realPages: manifest.pages.length, pixels: manifest.pages.reduce((sum, p) => sum + p.width * p.height, 0),
            horizontalStaffRetrieval: true, originalImageAndRawPixelParityJvm: true, pinnedScaleReference: true,
            comparedNumbers: numbers, maxCoordinateDifference: maxDifference, pixelTolerance: 1e-8, slopeTolerance: 1e-12,
            sampleStaves: oracle.pages.map(p => ({id: p.id, staves: p.result.staves?.length || 0, rejected: Boolean(p.result.rejected)})),
            binaryInputSha256Verified: true, hostTransfer: true,
            jgraphtConstructorSuppliers: true,
            grayThroughAdaptiveScaleAndStaff: true, grayBufferOwnership: true,
            limitation: 'Horizontal staff retrieval only; barline/system completion and later recognition remain unverified',
            wasmBytes: fs.statSync(path.join(root, 'staff.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
