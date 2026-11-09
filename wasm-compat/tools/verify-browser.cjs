const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    const files = new Map(['compat.wasm', 'compat.wasm-runtime.js'].map(name => ['/' + name, path.join(root, name)]));
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Compatibility tests</title>'); return; }
        if (request.url === '/worker-probe.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/compat.wasm-runtime.js');
                TeaVM.wasmGC.load('/compat.wasm').then(loaded => {
                    const api = loaded.exports;
                    postMessage({standard: api.standard(), utilities: api.utilities(), properties: api.properties(), resources: api.resources(), modules: api.modules(), loggers: api.loggers(),
                        fence: api.fenceProbe(), fenceRootReleased: api.audiveris_probe_fence_root === null,
                        domParserUnavailable: typeof DOMParser === 'undefined'});
                }).catch(error => postMessage({error: String(error.stack || error)}));`);
            return;
        }
        const file = files.get(request.url);
        if (!file) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', file.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({ headless: true });
        const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        await page.addScriptTag({ url: '/compat.wasm-runtime.js' });
        const result = await page.evaluate(async () => {
            const api = (await TeaVM.wasmGC.load('/compat.wasm')).exports;
            const traces = [], previousError = console.error;
            try { console.error = (...args) => traces.push(args.join(' ')); api.dumpStack(); }
            finally { console.error = previousError; }
            return { values: Array.from(api.values()), collections: Array.from(api.collections()),
                strings: Array.from(api.strings()), pathDiagnostic: api.pathDiagnostic(), paths: Array.from(api.paths()),
                standard: api.standard(), bytes: api.bytes(), numbers: api.numbers(), trace: traces.join('\n'),
                utilities: api.utilities(), properties: api.properties(), resources: api.resources(), modules: api.modules(), loggers: api.loggers(),
                fence: api.fenceProbe(), fenceRootReleased: api.audiveris_probe_fence_root === null };
        });
        const expected = JSON.parse(fs.readFileSync(path.join(root, 'oracle.json'), 'utf8'));
        assert.equal(result.values.length, expected.length);
        result.values.forEach((value, i) => assert.equal(value, expected[i], `Browser oracle value ${i}`));
        for (const kind of ['collections', 'strings', 'paths']) {
            assert.deepEqual(result[kind], JSON.parse(fs.readFileSync(path.join(root, kind + '.json'), 'utf8')));
        }
        assert.equal(result.pathDiagnostic, 'ok');
        assert.ok(result.trace.includes('java.lang.Exception: Stack trace'), 'Thread.dumpStack output');
        assert.equal(result.fence, JSON.parse(fs.readFileSync(path.join(root, 'fence.json'), 'utf8')));
        assert.equal(result.fenceRootReleased, true);
        assert.equal(result.standard, fs.readFileSync(path.join(root, 'standard.txt'), 'utf8'), 'Standard API behavior');
        assert.equal(result.utilities, fs.readFileSync(path.join(root, 'utilities.txt'), 'utf8'), 'Utility APIs');
        for (const kind of ['properties', 'resources', 'modules', 'loggers']) { assert.equal(result[kind], fs.readFileSync(path.join(root, kind + '.txt'), 'utf8'), kind); }
        for (const kind of ['bytes', 'numbers']) { assert.equal(result[kind], fs.readFileSync(path.join(root, kind + '.txt'), 'utf8'), kind); }
        const workerResult = await page.evaluate(() => new Promise((resolve, reject) => {
            const worker = new Worker('/worker-probe.js');
            const timer = setTimeout(() => { worker.terminate(); reject(new Error('Worker probe timed out')); }, 30000);
            worker.onmessage = event => { clearTimeout(timer); worker.terminate(); resolve(event.data); };
            worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
        }));
        assert.equal(workerResult.error, undefined, 'Worker execution');
        assert.equal(workerResult.domParserUnavailable, true);
        for (const kind of ['standard', 'utilities', 'properties', 'resources', 'modules', 'loggers']) {
            assert.equal(workerResult[kind], fs.readFileSync(path.join(root, kind + '.txt'), 'utf8'), 'Worker ' + kind);
        }
        assert.equal(workerResult.fence, result.fence); assert.equal(workerResult.fenceRootReleased, true);
        const report = { browser: browser.version(), matchedValues: result.values.length,
            matchedStringValues: result.strings.length, matchedPathValues: result.paths.length,
            matchedStandardCases: result.standard.trimEnd().split('\n').length,
            matchedByteCases: result.bytes.trimEnd().split('\n').length,
            matchedNumberCases: result.numbers.trimEnd().split('\n').length, dumpStack: true, collections: true,
            utilities: true, properties: true, resources: true, modules: true, loggers: true, gcFence: true, webWorker: true };
        fs.writeFileSync(path.join(root, 'browser-verification.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report));
    } finally {
        if (browser) { await browser.close(); }
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
