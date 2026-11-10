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
    const measurements = JSON.parse(fs.readFileSync(path.join(root, 'font-measurements.json'), 'utf8'));
    const supplierPath = path.join(root, 'supplier-reference.txt');
    const suppliers = fs.readFileSync(fs.existsSync(supplierPath) ? supplierPath : path.join(root, '../staff-probe/supplier-reference.txt'), 'utf8');
    vm.runInThisContext(fs.readFileSync(path.join(root, 'grid.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const api = (await TeaVM.wasmGC.load(path.join(root, 'grid.wasm'))).exports; api.configure(); for (const m of measurements) { api.configureSerifs(m.family, m.interline, ...m.dimensions); }
    assert.equal(api.suppliers(), suppliers);
    assert.throws(() => api.configureSerifs("Invalid", 0, 1, 1, 1, 1));
    assert.throws(() => api.configureSerifs("Invalid", 12, NaN, 1, 1, 1));
    const firstMetric = measurements[0];
    assert.throws(() => api.configureSerifs(firstMetric.family, firstMetric.interline, ...firstMetric.dimensions));
    for (const m of measurements) { compare(JSON.parse(api.brackets(m.family, m.interline)), m.brackets, m.family); }
    assert.throws(() => api.configureSerifs("Late", 12, 1, 1, 1, 1));
    const api2 = (await TeaVM.wasmGC.load(path.join(root, 'grid.wasm'))).exports; api2.configure();
    api2.configureSerifs(firstMetric.family, firstMetric.interline, ...firstMetric.dimensions.map(value => value * 2));
    assert.notDeepEqual(JSON.parse(api2.brackets(firstMetric.family, firstMetric.interline)), firstMetric.brackets);
    assert.throws(() => api2.brackets(firstMetric.family, firstMetric.interline + 100));
    compare(JSON.parse(api.brackets(firstMetric.family, firstMetric.interline)), firstMetric.brackets, 'isolated-metrics');
    const nodeResults = {fixture: JSON.parse(api.fixture()), pages: [], grayPages: []}; compare(nodeResults.fixture, oracle.fixture);
    for (const page of manifest.pages) {
        const buffer = fs.readFileSync(path.join(samples, page.id + '.binary'));
        assert.equal(crypto.createHash('sha256').update(buffer).digest('hex'), page.binarySha256);
        const copy = Buffer.from(buffer);
        const result = JSON.parse(api.gridPage(page.width, page.height, new Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength), page.id));
        compare(result, oracle.pages.find(p => p.id === page.id).result, page.id); assert.deepEqual(buffer, copy);
        nodeResults.pages.push({id: page.id, result});
        const gray = fs.readFileSync(path.join(samples, page.id + '.gray')), grayCopy = Buffer.from(gray);
        assert.equal(crypto.createHash('sha256').update(gray).digest('hex'), page.graySha256);
        const grayResult = JSON.parse(api.gridGrayPage(page.width, page.height, new Int8Array(gray.buffer, gray.byteOffset, gray.byteLength),
            page.id, manifest.meanCoefficient, manifest.deviationCoefficient));
        compare(grayResult, oracle.pages.find(p => p.id === page.id).result, page.id + '-gray'); assert.deepEqual(gray, grayCopy);
        nodeResults.grayPages.push({id: page.id, result: grayResult});
    }
    fs.writeFileSync(path.join(root, 'wasm-node-results.json'), JSON.stringify(nodeResults, null, 2) + '\n');
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Grid geometry verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/grid.wasm-runtime.js'); let api; const measurements = ${JSON.stringify(measurements)};
                onmessage = async event => { try {
                    if (!api) { api = (await TeaVM.wasmGC.load('/grid.wasm')).exports; api.configure(); for (const m of measurements) { api.configureSerifs(m.family, m.interline, ...m.dimensions); } }
                    const p = event.data;
                    const value = p.fontCase ? api.brackets(p.family, p.interline) : !p.buffer ? api.fixture() : p.gray ? api.gridGrayPage(p.width,p.height,new Int8Array(p.buffer),p.id,p.mean,p.deviation)
                        : api.gridPage(p.width,p.height,new Int8Array(p.buffer),p.id);
                    postMessage({result: JSON.parse(value), suppliers: api.suppliers(), dom: typeof document});
                } catch(error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const name = request.url.slice(1);
        if (['grid.wasm', 'grid.wasm-runtime.js'].includes(name)) {
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
        const result = await page.evaluate(async ({pages, mean, deviation, measurements}) => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            function call(index, message, transfer = []) {
                return new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('Grid worker timeout')), 60000);
                    workers[index].onmessage = event => { clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                    workers[index].onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
                    workers[index].postMessage(message, transfer);
                });
            }
            try {
                const brackets = [];
                for (let i = 0; i < measurements.length; i++) { brackets.push(await call(i % 2, {...measurements[i], fontCase: true})); }
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
                return {fixtures, outputs, grayOutputs, brackets};
            } finally { workers.forEach(worker => worker.terminate()); }
        }, {pages: manifest.pages.map(({id, width, height, binarySha256, graySha256}) => ({id, width, height, binarySha256, graySha256})),
            mean: manifest.meanCoefficient, deviation: manifest.deviationCoefficient, measurements});
        result.brackets.forEach((row, i) => compare(row.result, measurements[i].brackets, measurements[i].family));
        for (const fixture of result.fixtures) { compare(fixture.result, oracle.fixture); assert.equal(fixture.dom, 'undefined'); assert.equal(fixture.suppliers, suppliers); }
        result.outputs.forEach((row, i) => { compare(row.result, oracle.pages.find(p => p.id === manifest.pages[i].id).result); assert.equal(row.dom, 'undefined'); });
        result.grayOutputs.forEach((row, i) => { compare(row.result, oracle.pages.find(p => p.id === manifest.pages[i].id).result); assert.equal(row.dom, 'undefined'); });
        fs.writeFileSync(path.join(root, 'wasm-worker-results.json'), JSON.stringify(result, null, 2) + '\n');
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            realPages: manifest.pages.length, pixels: manifest.pages.reduce((sum, p) => sum + p.width * p.height, 0),
            gridBuilderCompleted: true, bracketFontCases: measurements.length * 12, pinnedBracketReference: true, metricInputValidation: true, metricFreezeAndIsolation: true, originalImageAndRawPixelParityJvm: true, pinnedScaleReference: true,
            comparedNumbers: numbers, maxCoordinateDifference: maxDifference, pixelTolerance: 1e-8, slopeTolerance: 1e-12,
            sampleStaves: oracle.pages.map(p => ({id: p.id, staves: p.result.staves?.length || 0, systems: p.result.systems?.length || 0, bars: (p.result.systems || []).reduce((n, s) => n + s.bars.length, 0), rejected: Boolean(p.result.rejected)})),
            binaryInputSha256Verified: true, hostTransfer: true,
            jgraphtConstructorSuppliers: true,
            grayThroughAdaptiveScaleAndGrid: true, grayBufferOwnership: true,
            limitation: 'GridBuilder only; staff-line removal, score update, OCR, symbols and MusicXML remain unverified',
            wasmBytes: fs.statSync(path.join(root, 'grid.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
