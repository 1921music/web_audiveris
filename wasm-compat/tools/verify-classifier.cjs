const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
const archivePath = process.argv[3] || path.resolve(root, '../../../app/res/basic-classifier.zip');
const pixelsRoot = path.resolve(root, '../clean-grid-probe/no-staff');
let comparedNumbers = 0, maxDifference = 0;
function compare(actual, expected, label = 'result') {
    if (typeof expected === 'number') {
        assert.equal(typeof actual, 'number', label); assert.ok(Number.isFinite(actual), label);
        const difference = Math.abs(actual - expected);
        const tolerance = label.includes('.features[') ? 1e-12 * Math.max(1, Math.abs(expected)) : 2e-13;
        comparedNumbers++; maxDifference = Math.max(maxDifference, difference);
        assert.ok(difference <= tolerance, `${label}: ${actual} != ${expected}; error ${difference}`); return;
    }
    if (Array.isArray(expected)) {
        assert.ok(Array.isArray(actual), label); assert.equal(actual.length, expected.length, label);
        expected.forEach((value, i) => compare(actual[i], value, `${label}[${i}]`)); return;
    }
    if (expected && typeof expected === 'object') {
        assert.deepEqual(Object.keys(actual).sort(), Object.keys(expected).sort(), label);
        for (const key of Object.keys(expected)) { compare(actual[key], expected[key], `${label}.${key}`); } return;
    }
    assert.equal(actual, expected, label);
}
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
(async () => {
    const oracle = JSON.parse(fs.readFileSync(path.join(root, 'jvm-reference.json'), 'utf8'));
    const portable = JSON.parse(fs.readFileSync(path.join(root, 'jvm-portable.json'), 'utf8'));
    assert.equal(portable.metadata, oracle.metadata); assert.equal(portable.weightSha256, oracle.weightSha256);
    assert.equal(portable.normSha256, oracle.normSha256);
    compare(portable.kernel, oracle.kernel, 'portable.kernel'); compare(portable.pages, oracle.pages, 'portable.pages');
    const portableJvmComparisons = comparedNumbers;
    const archive = fs.readFileSync(archivePath), archiveHash = sha256(archive);
    vm.runInThisContext(fs.readFileSync(path.join(root, 'classifier.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const nodeResults = []; const timings = [];
    for (let instance = 0; instance < 2; instance++) {
        const started = performance.now(); const api = (await TeaVM.wasmGC.load(path.join(root, 'classifier.wasm'))).exports;
        const instantiated = performance.now();
        assert.throws(() => api.metadata()); assert.throws(() => api.reinstall(new Int8Array([1, 2, 3])));
        api.configure(new Int8Array(archive.buffer, archive.byteOffset, archive.byteLength));
        const configured = performance.now();
        assert.equal(api.factoryContract(), true);
        for (const name of ["stds", "dimensions"]) {
            const bad = fs.readFileSync(path.join(root, "bad-" + name + ".zip"));
            assert.throws(() => api.reinstall(new Int8Array(bad.buffer, bad.byteOffset, bad.byteLength)));
        }
        assert.equal(api.metadata(), oracle.metadata);
        assert.throws(() => api.reinstall(new Int8Array(archive.buffer, archive.byteOffset, archive.byteLength)));
        const kernelStart = performance.now();
        const kernel = JSON.parse(api.kernel()); compare(kernel, oracle.kernel, `node${instance}.kernel`);
        const kernelDone = performance.now(); const pages = [];
        if (instance === 0) { for (const page of oracle.pages) {
            const pixels = fs.readFileSync(path.join(pixelsRoot, page.id + '.binary')); assert.equal(sha256(pixels), page.sha256);
            const before = Buffer.from(pixels);
            const result = JSON.parse(api.page(page.width, page.height, new Int8Array(pixels.buffer, pixels.byteOffset, pixels.byteLength), page.interline, page.glyphLimit));
            compare(result, page.result, `node.${page.id}`); assert.deepEqual(pixels, before); pages.push({id: page.id, result});
        } }
        nodeResults.push({metadata: api.metadata(), kernel, pages});
        timings.push({instantiateMs: instantiated - started, configureMs: configured - instantiated,
            validationMs: kernelStart - configured, kernelMs: kernelDone - kernelStart, pagesMs: performance.now() - kernelDone});
    }
    assert.equal(sha256(archive), archiveHash);
    fs.writeFileSync(path.join(root, 'wasm-node-results.json'), JSON.stringify(nodeResults) + '\n');
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Classifier verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/classifier.wasm-runtime.js'); let api;
                onmessage = async event => { try {
                    const p = event.data; const started = performance.now();
                    if (p.archive) {
                        api = (await TeaVM.wasmGC.load('/classifier.wasm')).exports; api.configure(new Int8Array(p.archive)); if (!api.factoryContract()) { throw new Error("Factory contract"); }
                        postMessage({metadata: api.metadata(), kernel: JSON.parse(api.kernel()), ms: performance.now() - started, dom: typeof document});
                    } else {
                        const result = JSON.parse(api.page(p.width,p.height,new Int8Array(p.buffer),p.interline,p.glyphLimit));
                        postMessage({result, ms: performance.now() - started, dom: typeof document});
                    }
                } catch(error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (['classifier.wasm', 'classifier.wasm-runtime.js'].includes(name)) {
            response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
            fs.createReadStream(path.join(root, name)).pipe(response); return;
        }
        if (name === 'model.zip') { fs.createReadStream(archivePath).pipe(response); return; }
        const input = oracle.pages.find(p => name === 'pixels/' + p.id + '.binary');
        if (input) { fs.createReadStream(path.join(pixelsRoot, input.id + '.binary')).pipe(response); return; }
        response.writeHead(404).end();
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const workerResults = await page.evaluate(async inputs => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            const call = (worker, message, transfer) => new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(new Error('Classifier timeout')), 60000);
                worker.onmessage = event => { clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                worker.onerror = event => { clearTimeout(timer); reject(new Error(event.message)); }; worker.postMessage(message, transfer);
            });
            try {
                const initial = [];
                for (const worker of workers) {
                    const archive = await (await fetch('/model.zip')).arrayBuffer(); initial.push(await call(worker, {archive}, [archive]));
                    if (archive.byteLength !== 0) { throw new Error('Model transfer failed'); }
                }
                const pages = [];
                for (let i = 0; i < inputs.length; i++) {
                    const p = inputs[i], buffer = await (await fetch('/pixels/' + p.id + '.binary')).arrayBuffer();
                    const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', buffer))].map(b => b.toString(16).padStart(2, '0')).join('');
                    if (hash !== p.sha256) { throw new Error('Wrong classifier pixels'); }
                    pages.push(await call(workers[i % 2], {...p, buffer}, [buffer]));
                    if (buffer.byteLength !== 0) { throw new Error('Pixel transfer failed'); }
                }
                return {initial, pages};
            } finally { workers.forEach(worker => worker.terminate()); }
        }, oracle.pages.map(({result, ...page}) => page));
        workerResults.initial.forEach((row, i) => { assert.equal(row.metadata, oracle.metadata); assert.equal(row.dom, 'undefined'); compare(row.kernel, oracle.kernel, `worker${i}.kernel`); });
        workerResults.pages.forEach((row, i) => { assert.equal(row.dom, 'undefined'); compare(row.result, oracle.pages[i].result, `worker.${oracle.pages[i].id}`); });
        fs.writeFileSync(path.join(root, 'wasm-worker-results.json'), JSON.stringify(workerResults) + '\n');
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            pages: oracle.pages.length, glyphs: oracle.pages.reduce((sum, page) => sum + page.result.length, 0),
            comparedNumbers, portableJvmComparisons, wasmComparisons: comparedNumbers - portableJvmComparisons, maxDifference, gradeTolerance: 2e-13, featureRelativeTolerance: 1e-12,
            modelBytes: archive.length, modelSha256: archiveHash, modelExternalToWasm: true,
            modelMetadata: oracle.metadata, portableWeightsExactJvm: true, portableNormsExactJvm: true, topClassesMatch: true,
            invalidModelRejected: true, invalidStdAndDimensionsRejected: true, modelArrayCopies: true, failedInstallPreservesModel: true, modelFrozen: true, independentInstances: true, hostTransfer: true,
            wasmBytes: fs.statSync(path.join(root, 'classifier.wasm')).size, nodeTimings: timings,
            limitation: 'Classifier component; full HEADERS and later recognition steps are unverified'};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
