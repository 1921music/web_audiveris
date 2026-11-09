const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const zlib = require('node:zlib');
const { performance } = require('node:perf_hooks');
const { spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');

async function one(root) {
    vm.runInThisContext(fs.readFileSync(path.join(root, 'xml.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const started = performance.now();
    const api = (await TeaVM.wasmGC.load(path.join(root, 'xml.wasm'))).exports;
    const loadMs = performance.now() - started, cases = [];
    for (let document = 0; document < 2; document++) {
        const first = performance.now(), checksum = api.parse(1, document), firstParseMs = performance.now() - first;
        const iterations = document === 0 ? 100 : 10;
        api.parse(20, document);
        const samples = [];
        for (let sample = 0; sample < 7; sample++) {
            await new Promise(resolve => setImmediate(resolve));
            const begin = performance.now(); api.parse(iterations, document);
            samples.push((performance.now() - begin) / iterations);
        }
        samples.sort((a, b) => a - b);
        cases.push({ name: document === 0 ? 'ISO639-3.xml' : 'synthetic-2000-entries', bytes: api.documentBytes(document),
            checksum, firstParseMs, medianParseMs: samples[3], minParseMs: samples[0], maxParseMs: samples[6],
            samples: 7, iterationsPerSample: iterations });
    }
    const bytes = fs.readFileSync(path.join(root, 'xml.wasm'));
    return { backend: path.basename(root).replace('xml-bench-', ''), node: process.version,
        wasmBytes: bytes.length, wasmGzipBytes: zlib.gzipSync(bytes, { level: 9 }).length, loadMs,
        peakProcessRssKiB: process.resourceUsage().maxRSS, cases };
}

(async () => {
    if (process.argv[2] === '--one') { console.log(JSON.stringify(await one(path.resolve(process.argv[3])))); return; }
    const reports = process.argv.slice(2).map(directory => {
        const child = spawnSync(process.execPath, ['--max-old-space-size=384', __filename, '--one', directory], { encoding: 'utf8' });
        if (child.status !== 0) { throw new Error(child.stderr + child.stdout); }
        return JSON.parse(child.stdout.trim());
    });
    assert.equal(reports.length, 2);
    reports[0].cases.forEach((item, i) => assert.equal(item.checksum, reports[1].cases[i].checksum, 'XML result checksum'));
    const output = { scope: 'XML component only; local Node Wasm GC, separate processes, sequential runs; no engine throughput claim', reports };
    const destination = path.join(path.dirname(path.resolve(process.argv[3])), 'xml-benchmark.json');
    fs.writeFileSync(destination, JSON.stringify(output, null, 2) + '\n');
    console.log(JSON.stringify(output));
})().catch(error => { console.error(error); process.exitCode = 1; });
