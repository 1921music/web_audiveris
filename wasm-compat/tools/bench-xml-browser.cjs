const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

// Runs entirely in a dedicated Worker, including module loading and all timed calls.
async function measure() {
    const begin = performance.now();
    const api = (await TeaVM.wasmGC.load('/xml.wasm')).exports;
    const loadMs = performance.now() - begin, cases = [];
    for (let document = 0; document < 2; document++) {
        const cold = performance.now(), checksum = api.parse(1, document);
        const firstParseMs = performance.now() - cold;
        const iterations = document === 0 ? 100 : 10;
        api.parse(20, document);
        const samplesMs = [];
        for (let sample = 0; sample < 7; sample++) {
            await new Promise(resolve => setTimeout(resolve, 0));
            const start = performance.now(), result = api.parse(iterations, document);
            samplesMs.push((performance.now() - start) / iterations);
            if (result !== Math.imul(checksum, iterations)) { throw new Error('Repeated XML checksum differs'); }
        }
        const sorted = samplesMs.toSorted((a, b) => a - b);
        cases.push({ name: document === 0 ? 'ISO639-3.xml' : 'synthetic-2000-entries',
            bytes: api.documentBytes(document), checksum, firstParseMs, medianParseMs: sorted[3],
            minParseMs: sorted[0], maxParseMs: sorted[6], samplesMs, iterationsPerSample: iterations });
    }
    return { loadMs, domParserAvailable: typeof DOMParser !== 'undefined', cases };
}

async function one(root) {
    const files = new Map(['xml.wasm', 'xml.wasm-runtime.js'].map(name => ['/' + name, path.join(root, name)]));
    const worker = `importScripts('/xml.wasm-runtime.js'); (${measure.toString()})()
        .then(result => postMessage({result})).catch(error => postMessage({error: String(error.stack || error)}));`;
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>XML component benchmark</title>'); return; }
        if (request.url === '/worker.js') { response.setHeader('Content-Type', 'text/javascript'); response.end(worker); return; }
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
        const result = await page.evaluate(() => new Promise((resolve, reject) => {
            const worker = new Worker('/worker.js');
            const timer = setTimeout(() => { worker.terminate(); reject(new Error('XML benchmark timed out')); }, 60000);
            worker.onmessage = event => {
                clearTimeout(timer); worker.terminate();
                event.data.error ? reject(new Error(event.data.error)) : resolve(event.data.result);
            };
            worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(event.message)); };
        }));
        assert.equal(result.domParserAvailable, false);
        const bytes = fs.readFileSync(path.join(root, 'xml.wasm'));
        return { backend: path.basename(root).replace('xml-bench-', ''), browser: browser.version(),
            wasmBytes: bytes.length, wasmGzipBytes: zlib.gzipSync(bytes, { level: 9 }).length,
            wasmSha256: crypto.createHash('sha256').update(bytes).digest('hex'), ...result };
    } finally {
        if (browser) { await browser.close(); }
        await new Promise(resolve => server.close(resolve));
    }
}

(async () => {
    assert.equal(process.argv.length, 4, 'Supply JDK and kxml2 benchmark directories');
    const reports = [];
    // Fresh browser per backend; sequential execution avoids competing benchmark loads.
    for (const directory of process.argv.slice(2)) { reports.push(await one(path.resolve(directory))); }
    reports[0].cases.forEach((item, i) => {
        assert.equal(item.checksum, reports[1].cases[i].checksum, 'Cross-backend XML checksum');
        assert.equal(item.bytes, reports[1].cases[i].bytes, 'Identical input bytes');
    });
    const result = { scope: 'XML component only; local Chromium dedicated Worker; fresh browsers, sequential backends; no engine throughput or allocation claim',
        reports };
    const output = path.join(path.dirname(path.resolve(process.argv[3])), 'xml-browser-benchmark.json');
    fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
})().catch(error => { console.error(error); process.exitCode = 1; });
