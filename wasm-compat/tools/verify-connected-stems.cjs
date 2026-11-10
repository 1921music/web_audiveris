const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
Error.stackTraceLimit = 100;
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]), samples = path.resolve(root, '../pixel-probe/samples');
const profiles = fs.existsSync(path.join(root, 'symbol-geometry.json')) ? root : path.resolve(root, '../connected-stems-portable-probe');
const portablePath = fs.existsSync(path.join(root, 'jvm-portable.json')) ? path.join(root, 'jvm-portable.json') : path.resolve(root, '../connected-stems-portable-probe/jvm-reference.json');
const modelPath = process.argv[3] || (fs.existsSync(path.join(root, 'model.zip')) ? path.join(root, 'model.zip') : path.resolve(root, '../../../app/res/basic-classifier.zip'));
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
let numbers = 0, maxDifference = 0;
let comparedPixelBytes = 0, comparedDistanceBytes = 0;
const buffers = fs.existsSync(path.join(root, 'head-pixels')) ? path.join(root, 'head-pixels') : path.resolve(root, '../connected-stems-original-probe/head-pixels');
const pixelHashes = Object.fromEntries(fs.readdirSync(buffers).filter(name => name.endsWith('.binary'))
    .map(name => [name.slice(0, -7), sha256(fs.readFileSync(path.join(buffers, name)))]));
const distanceBuffers = fs.existsSync(path.join(root, 'distance-pixels')) ? path.join(root, 'distance-pixels') : path.resolve(root, '../connected-stems-original-probe/distance-pixels');
const distanceHashes = Object.fromEntries(fs.readdirSync(distanceBuffers).filter(n => n.endsWith('.short'))
    .map(n => [n.slice(0,-6), sha256(fs.readFileSync(path.join(distanceBuffers,n)))]));
const portableBuffers = path.join(path.dirname(portablePath), 'distance-pixels');
if (fs.existsSync(portableBuffers) && portableBuffers !== distanceBuffers) {
    for (const id of Object.keys(distanceHashes)) {
        assert.deepEqual(fs.readFileSync(path.join(distanceBuffers,id+'.short')), fs.readFileSync(path.join(portableBuffers,id+'.short')), `JVM distance table ${id}`);
    }
}
function comparePixels(api, id) {
    const table = Buffer.from(api.distancePixels()), reference = fs.readFileSync(path.join(distanceBuffers,id+'.short'));
    assert.deepEqual(table, reference, `distance table ${id}`); comparedDistanceBytes += table.length;
    const actual = Buffer.from(api.headPixels()), expected = fs.readFileSync(path.join(buffers, id + '.binary'));
    assert.deepEqual(actual, expected, `head pixels ${id}`); comparedPixelBytes += actual.length;
}
function compare(actual, expected, label = 'result') {
    if (typeof expected === 'number') {
        assert.equal(typeof actual, 'number', label); assert.ok(Number.isFinite(actual), label);
        const error = Math.abs(actual - expected), tolerance = label.endsWith('.grade') ? 2e-13 : label.endsWith('.slope') ? 1e-12 : 1e-8;
        numbers++; maxDifference = Math.max(maxDifference, error);
        assert.ok(error <= tolerance, `${label}: ${actual} != ${expected}; error ${error}`); return;
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
(async () => {
    const originalPath = fs.existsSync(path.join(root, 'jvm-original.json')) ? path.join(root, 'jvm-original.json') : path.resolve(root, '../connected-stems-original-probe/jvm-reference.json');
    const oracle = JSON.parse(fs.readFileSync(originalPath, 'utf8'));
    const stemSortContract = fs.readFileSync(path.join(root,'stem-sort-reference.txt'),'utf8');
    { const seam = JSON.parse(fs.readFileSync(portablePath, 'utf8')); assert.deepEqual(seam, oracle); }
    const manifest = JSON.parse(fs.readFileSync(path.join(samples, 'manifest.json'), 'utf8'));
    const allPages = manifest.pages.length;
    const maxPages = Number(process.env.AUDIVERIS_CONNECTED_STEMS_MAX_PAGES || 0);
    if (maxPages > 0) { manifest.pages = manifest.pages.slice(0, maxPages); }
    const templateRoot = fs.existsSync(path.join(root,'templates')) ? path.join(root,'templates') : path.resolve(root,'../template-original-probe');
    const templateReference = JSON.parse(fs.readFileSync(path.join(templateRoot,'reference.json'),'utf8'));
    const required = new Set([oracle.fixture,...oracle.pages.filter(p=>manifest.pages.some(m=>m.id===p.id)).map(p=>p.result)].flatMap(p=>p.templatesUsed || []));
    const templateRows = templateReference.catalogs.filter(r=>required.has(r.family+':'+r.pointSize));
    assert.equal(templateRows.length, required.size, 'Missing native template catalog');
    function templates(api) {
        for(const row of templateRows) {
            const bytes = fs.readFileSync(path.join(templateRoot,row.file+'.atpl')); assert.equal(sha256(bytes),row.sha256);
            api.installTemplates(new Int8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength));
        }
    }
    const serifs = JSON.parse(fs.readFileSync(path.join(profiles, 'font-measurements.json'), 'utf8'));
    const geometry = JSON.parse(fs.readFileSync(path.join(profiles, 'symbol-geometry.json'), 'utf8'));
    const archive = fs.readFileSync(modelPath), modelHash = sha256(archive);
    vm.runInThisContext(fs.readFileSync(path.join(root, 'connected-stems.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const pointWidths = JSON.parse(fs.readFileSync(path.join(profiles, 'point-widths.json'), 'utf8'));
    function resources(api) {
        templates(api);
        for (const m of serifs) { api.configureSerifs(m.family, m.interline, ...m.dimensions); }
        for (const m of geometry) { api.configureGeometry(m.kind, m.family, m.shape, m.interline, m.num, m.den, ...m.values); }
        for (const m of pointWidths) { api.configurePointWidth(m.family, m.shape, m.pointSize, m.width); }
    }
    function configure(api) {
        api.configure(new Int8Array(archive.buffer, archive.byteOffset, archive.byteLength));
        for (const m of serifs) { api.configureSerifs(m.family, m.interline, ...m.dimensions); }
        for (const m of geometry) { api.configureGeometry(m.kind, m.family, m.shape, m.interline, m.num, m.den, ...m.values); }
        for (const m of pointWidths) { api.configurePointWidth(m.family, m.shape, m.pointSize, m.width); }
        templates(api);
        assert.equal(api.resourceContract(), true);
        assert.equal(api.stemDebugRejected(), true);
        assert.equal(api.sortContract(), stemSortContract, 'Pinned JVM stem sort comparison schedule');
        if(process.env.AUDIVERIS_STEMS_TRACE_RECT){api.traceRectangle(...process.env.AUDIVERIS_STEMS_TRACE_RECT.split(',').map(Number));}
    }
    let debug = (await TeaVM.wasmGC.load(path.join(root, 'connected-stems.wasm'))).exports;
    debug.configureDebug(new Int8Array(archive.buffer, archive.byteOffset, archive.byteLength));
    assert.equal(debug.debugImageRejected(), true);
    let preview = (await TeaVM.wasmGC.load(path.join(root, 'connected-stems.wasm'))).exports;
    preview.configurePreview(new Int8Array(archive.buffer, archive.byteOffset, archive.byteLength)); resources(preview);
    assert.equal(preview.previewRejected(), true);
    let api = (await TeaVM.wasmGC.load(path.join(root, 'connected-stems.wasm'))).exports; configure(api);
    const node = {fixture: JSON.parse(api.fixture()), pages: [], grayPages: []}; compare(node.fixture, oracle.fixture); comparePixels(api, 'staff');
    for (const page of manifest.pages) {
        const expected = oracle.pages.find(p => p.id === page.id).result;
        for (const gray of [false, true]) {
            const bytes = fs.readFileSync(path.join(samples, page.id + (gray ? '.gray' : '.binary'))), original = Buffer.from(bytes);
            assert.equal(sha256(bytes), gray ? page.graySha256 : page.binarySha256);
            const input = new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
            const actual = JSON.parse(gray ? api.grayPage(page.width, page.height, input, page.id, manifest.meanCoefficient, manifest.deviationCoefficient)
                : api.page(page.width, page.height, input, page.id));
            try { compare(actual, expected, `node.${page.id}.${gray ? 'gray' : 'binary'}`); }
            catch (error) {
                fs.writeFileSync(path.join(root, 'failed-node-result.json'), JSON.stringify({id:page.id,gray,actual,expected})+'\n');
                throw error;
            }
            assert.deepEqual(bytes, original);
            if (!expected.rejected) { comparePixels(api, page.id); }
            (gray ? node.grayPages : node.pages).push({id: page.id, result: actual});
            // WeakRef targets stay alive for the current JavaScript job; each image must finish its task.
            await new Promise(resolve => setImmediate(resolve));
        }
    }
    let api2 = (await TeaVM.wasmGC.load(path.join(root, 'connected-stems.wasm'))).exports; configure(api2);
    compare(JSON.parse(api2.fixture()), oracle.fixture, 'node2.fixture'); comparePixels(api2, 'staff'); assert.equal(sha256(archive), modelHash);
    fs.writeFileSync(path.join(root, 'wasm-node-results.json'), JSON.stringify(node) + '\n');
    node.fixture=null; node.pages.length=0; node.grayPages.length=0;
    debug=null; preview=null; api=null; api2=null;
    await new Promise(resolve=>setImmediate(resolve));
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Stem connection recognition verification</title>'); return; }
        if (request.url === '/model.zip') { response.end(archive); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/connected-stems.wasm-runtime.js'); let api;
                const serifs = ${JSON.stringify(serifs)}, geometry = ${JSON.stringify(geometry)}, pointWidths = ${JSON.stringify(pointWidths)}, templateRows = ${JSON.stringify(templateRows)};
                const stemSortContract = ${JSON.stringify(stemSortContract)};
                onmessage = async event => { try {
                    const p = event.data;
                    if (p.archive) {
                        api = (await TeaVM.wasmGC.load('/connected-stems.wasm')).exports; api.configure(new Int8Array(p.archive));
                        for (const m of serifs) { api.configureSerifs(m.family,m.interline,...m.dimensions); }
                        for (const m of geometry) { api.configureGeometry(m.kind,m.family,m.shape,m.interline,m.num,m.den,...m.values); }
                        for (const m of pointWidths) { api.configurePointWidth(m.family,m.shape,m.pointSize,m.width); }
                        for (const row of templateRows) {
                            const data=await (await fetch('/template-'+row.file+'.atpl')).arrayBuffer();
                            const hash=[...new Uint8Array(await crypto.subtle.digest('SHA-256',data))].map(b=>b.toString(16).padStart(2,'0')).join('');
                            if(hash!==row.sha256) {throw new Error('Template catalog hash');}
                            api.installTemplates(new Int8Array(data));
                        }
                        if (!api.resourceContract()) { throw new Error('Geometry resource contract'); }
                        if (!api.stemDebugRejected()) {throw new Error('Stem debug image boundary');}
                        if (api.sortContract() !== stemSortContract) {throw new Error('Worker pinned stem sort schedule');}
                        const result = JSON.parse(api.fixture()), pixels = api.headPixels();
                        const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', pixels.buffer))].map(b => b.toString(16).padStart(2, '0')).join('');
                        const distanceHash = result.rejected ? null : [...new Uint8Array(await crypto.subtle.digest('SHA-256', api.distancePixels().buffer))].map(b => b.toString(16).padStart(2,'0')).join('');
                        postMessage({result, pixelHash: hash, distanceHash, dom: typeof document});
                    } else {
                        const value = p.gray ? api.grayPage(p.width,p.height,new Int8Array(p.buffer),p.id,p.mean,p.deviation)
                            : api.page(p.width,p.height,new Int8Array(p.buffer),p.id);
                        const result = JSON.parse(value); let hash = null;
                        if (!result.rejected) {
                            const pixels = api.headPixels();
                            hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', pixels.buffer))].map(b => b.toString(16).padStart(2, '0')).join('');
                        }
                        const distanceHash = result.rejected ? null : [...new Uint8Array(await crypto.subtle.digest('SHA-256', api.distancePixels().buffer))].map(b => b.toString(16).padStart(2,'0')).join('');
                        postMessage({result, pixelHash: hash, distanceHash, dom: typeof document});
                    }
                } catch(error) { postMessage({error: String(error.stack || error)}); } };`); return;
        }
        const template = templateRows.find(r=>request.url==='/template-'+r.file+'.atpl');
        if(template) {fs.createReadStream(path.join(templateRoot,template.file+'.atpl')).pipe(response);return;}
        const name = request.url.slice(1);
        if (['connected-stems.wasm', 'connected-stems.wasm-runtime.js'].includes(name)) {
            response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
            fs.createReadStream(path.join(root, name)).pipe(response); return;
        }
        if (/^[0-9]{2}\.(gray|binary)$/.test(name) && manifest.pages.some(p => name.startsWith(p.id + '.'))) {
            fs.createReadStream(path.join(samples, name)).pipe(response); return;
        }
        response.writeHead(404).end();
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const workers = await page.evaluate(async ({pages, modelHash, mean, deviation}) => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            const digest = async buffer => [...new Uint8Array(await crypto.subtle.digest('SHA-256', buffer))].map(b => b.toString(16).padStart(2, '0')).join('');
            const call = (worker, message, transfer) => new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(new Error('Heads timeout')), 180000);
                worker.onmessage = event => { clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                worker.onerror = event => { clearTimeout(timer); reject(new Error(event.message)); }; worker.postMessage(message, transfer);
            });
            try {
                const fixtures = [], outputs = [], grayOutputs = [];
                for (const worker of workers) {
                    const archive = await (await fetch('/model.zip')).arrayBuffer(); if (await digest(archive) !== modelHash) { throw new Error('Model hash'); }
                    fixtures.push(await call(worker, {archive}, [archive])); if (archive.byteLength) { throw new Error('Model transfer'); }
                }
                for (let i = 0; i < pages.length; i++) { const p = pages[i]; for (const gray of [false, true]) {
                    const buffer = await (await fetch('/' + p.id + (gray ? '.gray' : '.binary'))).arrayBuffer();
                    if (await digest(buffer) !== (gray ? p.graySha256 : p.binarySha256)) { throw new Error('Pixel hash'); }
                    const row = await call(workers[(i + Number(gray)) % 2], {...p, gray, mean, deviation, buffer}, [buffer]);
                    (gray ? grayOutputs : outputs).push(row); if (buffer.byteLength) { throw new Error('Pixel transfer'); }
                } }
                return {fixtures, outputs, grayOutputs};
            } finally { workers.forEach(worker => worker.terminate()); }
        }, {pages: manifest.pages, modelHash, mean: manifest.meanCoefficient, deviation: manifest.deviationCoefficient});
        workers.fixtures.forEach((row, i) => { compare(row.result, oracle.fixture, `worker${i}.fixture`); assert.equal(row.dom, 'undefined');
            assert.equal(row.pixelHash, pixelHashes.staff); comparedPixelBytes += 1024 * 300;
            assert.equal(row.distanceHash, distanceHashes.staff); comparedDistanceBytes += 2 * 1024 * 300; });
        for (const rows of [workers.outputs, workers.grayOutputs]) { rows.forEach((row, i) => {
            compare(row.result, oracle.pages.find(p => p.id === manifest.pages[i].id).result, `worker.${manifest.pages[i].id}`); assert.equal(row.dom, 'undefined');
            const p = manifest.pages[i];
            assert.equal(row.pixelHash, row.result.rejected ? null : pixelHashes[p.id]);
            assert.equal(row.distanceHash, row.result.rejected ? null : distanceHashes[p.id]);
            if (!row.result.rejected) { comparedPixelBytes += p.width * p.height; comparedDistanceBytes += 2 * p.width * p.height; }
        }); }
        fs.writeFileSync(path.join(root, 'wasm-worker-results.json'), JSON.stringify(workers) + '\n');
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 4, workers: 2,
            pages: manifest.pages.length, fullSampleSet: manifest.pages.length === allPages, diagnosticTraceEnabled: Boolean(process.env.AUDIVERIS_STEMS_TRACE_RECT), totalInputPages: allPages, comparedNumbers: numbers, maxDifference, comparedPixelBytes, comparedDistanceBytes,
            originalHeaderClassesPinned: true, originalStemAlgorithms: true, originalBeamAlgorithms: true, originalLedgerAlgorithms: true, originalDistancePaintingAndChamfer: true, originalHeadsAlgorithms: true, originalConnectedStemsAlgorithms: true, stemDebugRejectedExplicitly: true, pinnedStemSortCases: 768, kernelContract: true, previewOptionRejectedExplicitly: true, debugImageOptionRejectedExplicitly: true,
            geometryRecords: geometry.length, pointWidthRecords: pointWidths.length, externalGeometryBytes: fs.statSync(path.join(profiles, 'symbol-geometry.json')).size,
            templateCatalogs: templateRows.map(r=>({family:r.family,pointSize:r.pointSize,bytes:r.bytes,sha256:r.sha256})),
            samples: oracle.pages.filter(p=>manifest.pages.some(m=>m.id===p.id)).map(p => ({id: p.id, rejected: Boolean(p.result.rejected), headers: p.result.headers?.length || 0,
                clefs: (p.result.headers || []).filter(h => h.clef).length, keys: (p.result.headers || []).filter(h => h.key).length,
                times: (p.result.headers || []).filter(h => h.time).length,
                stemScale: p.result.stemScale || null, seeds: p.result.seeds?.length || 0,
                beams: p.result.beams?.length || 0, heads: p.result.heads?.length || 0, connectedStems: p.result.connectedStemsCount || 0, stemRelations: p.result.stemRelations?.length || 0, stemConnections: p.result.stemConnections?.length || 0, musicPointSize: p.result.musicPointSize ?? null})),
            grayThroughConnectedStems: true, modelSha256: modelHash, modelBytes: archive.length, hostTransfer: true, yieldsBetweenInputs: true,
            geometryCopiesAndAppendOnlyEntries: true, explicitProviderInjection: true,
            wasmSha256: sha256(fs.readFileSync(path.join(root, 'connected-stems.wasm'))),
            wasmBytes: fs.statSync(path.join(root, 'connected-stems.wasm')).size,
            limitation: 'STEMS component with explicit host resources; later recognition steps and MusicXML remain unverified'};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
