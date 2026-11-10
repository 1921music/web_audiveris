const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2]);
const oracle = JSON.parse(fs.readFileSync(path.join(root, 'jvm-reference.json'), 'utf8'));
const samples = process.argv.includes('--samples') ? JSON.parse(fs.readFileSync(path.join(root, 'samples/manifest.json'), 'utf8')) : null;
const scales = process.argv.includes('--scale') ? JSON.parse(fs.readFileSync(path.join(root, 'samples/scale-upstream.json'), 'utf8')) : null;
const expectedScale = entry => entry.result + ';invalid=' + entry.invalid;
function bulk(api) {
    const padded = new Int8Array(12); padded.set([0, 1, 127, -128, -127, -2, -1, 40], 2);
    const result = api.adaptivePage(4, 2, padded.subarray(2, 10), 0.7, 0.9);
    return Array.from(new Uint8Array(result.buffer, result.byteOffset, result.byteLength)).join(',');
}
function verify(result) {
    assert.equal(result.threshold, oracle.threshold);
    assert.equal(result.globalCases, oracle.globalCases);
    assert.equal(result.adaptiveCases, oracle.adaptiveCases); assert.equal(result.runCases, oracle.runCases);
    assert.equal(result.bulk, oracle.bulkOracle);
    assert.equal(result.scaleFixtures, oracle.scaleFixtures);
    assert.equal(result.ownership, true); assert.equal(result.fontRejected, true);
}
(async () => {
    vm.runInThisContext(fs.readFileSync(path.join(root, 'pixels.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    let sampleApi;
    const sampleScaleResults = [];
    for (let i = 0; i < 2; i++) {
        const api = (await TeaVM.wasmGC.load(path.join(root, 'pixels.wasm'))).exports;
        api.configure();
        verify({threshold: api.threshold(), globalCases: api.globalCases(), adaptiveCases: api.adaptiveCases(), runCases: api.runCases(), bulk: bulk(api), scaleFixtures: api.scaleFixtures(), ownership: api.ownership(), fontRejected: api.fontRejected()});
        sampleApi = api;
    }
    if (samples) { for (const page of samples.pages) {
        const gray = fs.readFileSync(path.join(root, 'samples', page.id + '.gray'));
        const expected = fs.readFileSync(path.join(root, 'samples', page.id + '.binary'));
        assert.equal(crypto.createHash('sha256').update(gray).digest('hex'), page.graySha256);
        assert.equal(crypto.createHash('sha256').update(expected).digest('hex'), page.binarySha256);
        const actual = sampleApi.adaptivePage(page.width, page.height, new Int8Array(gray.buffer, gray.byteOffset, gray.byteLength), samples.meanCoefficient, samples.deviationCoefficient);
        assert.ok(Buffer.from(actual.buffer, actual.byteOffset, actual.byteLength).equals(expected), 'Node sample mismatch ' + page.id);
        if (scales) {
            const expectedResult = scales.find(entry => entry.id === page.id);
            console.log('Estimating Node scale ' + page.id);
            const actualScale = sampleApi.scalePage(page.width, page.height, actual, expectedResult.sheetId);
            const composed = sampleApi.scaleGrayPage(page.width, page.height, new Int8Array(gray.buffer, gray.byteOffset, gray.byteLength), expectedResult.sheetId, samples.meanCoefficient, samples.deviationCoefficient);
            assert.equal(actualScale, expectedScale(expectedResult), 'Scale mismatch ' + page.id);
            assert.equal(composed, actualScale, 'Composed gray/scale mismatch ' + page.id);
            sampleScaleResults.push({id: page.id, result: actualScale});
        }
        assert.equal(crypto.createHash('sha256').update(gray).digest('hex'), page.graySha256);
        console.log('Matched Node sample ' + page.id + ' (' + page.pixels + ' pixels)');
    } }
    if (scales) {
        assert.equal(sampleApi.scaleFixtures(), ['staff', 'specified', 'blank', 'supplied'].map(id => expectedScale(scales.find(entry => entry.id === id))).join('|'));
    }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Pixel engine tests</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/pixels.wasm-runtime.js');
                onmessage = async event => { try {
                    const api = (await TeaVM.wasmGC.load('/pixels.wasm')).exports;
                    api.configure();
                    if (event.data === 'samples') {
                        const manifest = await (await fetch('/samples/manifest.json')).json(); const pages = [];
                        const scaleReferences = ${Boolean(scales)} ? await (await fetch('/samples/scale-upstream.json')).json() : null;
                        for (const page of manifest.pages) {
                            const gray = new Int8Array(await (await fetch('/samples/' + page.id + '.gray')).arrayBuffer());
                            const expected = new Uint8Array(await (await fetch('/samples/' + page.id + '.binary')).arrayBuffer());
                            const result = api.adaptivePage(page.width, page.height, gray, manifest.meanCoefficient, manifest.deviationCoefficient);
                            const actual = new Uint8Array(result.buffer, result.byteOffset, result.byteLength);
                            if (actual.length !== expected.length) throw new Error('Sample length mismatch');
                            for (let i = 0; i < expected.length; i++) if (actual[i] !== expected[i]) throw new Error('Sample mismatch ' + page.id + ' pixel ' + i);
                            let scaleResult;
                            if (scaleReferences) {
                                const entry = scaleReferences.find(value => value.id === page.id);
                                scaleResult = api.scalePage(page.width, page.height, result, entry.sheetId);
                                const composed = api.scaleGrayPage(page.width, page.height, gray, entry.sheetId, manifest.meanCoefficient, manifest.deviationCoefficient);
                                const expectedScale = entry.result + ';invalid=' + entry.invalid;
                                if (scaleResult !== expectedScale || composed !== expectedScale) throw new Error('Scale mismatch ' + page.id + ': ' + scaleResult);
                            }
                            pages.push({id: page.id, pixels: actual.length, scaleResult});
                        }
                        postMessage({pages}); return;
                    }
                    const padded = new Int8Array(12); padded.set([0,1,127,-128,-127,-2,-1,40], 2);
                    const converted = api.adaptivePage(4, 2, padded.subarray(2, 10), 0.7, 0.9);
                    postMessage({threshold: api.threshold(), globalCases: api.globalCases(), adaptiveCases: api.adaptiveCases(), runCases: api.runCases(),
                        bulk: Array.from(new Uint8Array(converted.buffer, converted.byteOffset, converted.byteLength)).join(','), ownership: api.ownership(),
                        scaleFixtures: api.scaleFixtures(),
                        fontRejected: api.fontRejected(), dom: typeof document});
                } catch (error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (samples && /^samples\/(manifest\.json|scale-upstream\.json|[0-9]{2}\.(gray|binary))$/.test(name)) {
            response.setHeader('Content-Type', name.endsWith('.json') ? 'application/json' : 'application/octet-stream');
            fs.createReadStream(path.join(root, name)).pipe(response); return;
        }
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
        let samplePages = [];
        if (samples) {
            const result = await page.evaluate(async () => new Promise((resolve, reject) => {
                const worker = new Worker('/worker.js');
                const timer = setTimeout(() => { worker.terminate(); reject(new Error('Sample Worker timeout')); }, 120000);
                worker.onmessage = event => { clearTimeout(timer); worker.terminate(); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
                worker.postMessage('samples');
            }));
            samplePages = result.pages;
            assert.deepEqual(samplePages.map(page => ({id: page.id, pixels: page.pixels})), samples.pages.map(page => ({id: page.id, pixels: page.pixels})));
            if (scales) { for (const page of samplePages) { assert.equal(page.scaleResult, expectedScale(scales.find(entry => entry.id === page.id))); } }
        }
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            globalThresholdCases: 30, matchedPixels: oracle.globalCases.replaceAll(';', '').length,
            adaptiveCases: 45, adaptivePixels: oracle.adaptiveCases.replaceAll(';', '').length, runTableCases: 30,
            bulkByteInterop: true, sampleWorkers: samples ? 1 : 0, samplePages,
            samplePixels: samplePages.reduce((sum, page) => sum + page.pixels, 0),
            scaleFixtureCases: 4, upstreamScaleCompared: Boolean(scales), sampleScaleResults,
            grayInputOwnership: true, invalidDimensionsRejected: true, desktopFontRejected: true,
            wasmBytes: fs.statSync(path.join(root, 'pixels.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
