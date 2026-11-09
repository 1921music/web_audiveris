/* SPDX-License-Identifier: AGPL-3.0-or-later */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { createHash } = require('node:crypto');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const fixtures = path.join(root, 'build/sample-reference');
const manifest = JSON.parse(fs.readFileSync(path.join(fixtures, 'manifest.json'), 'utf8'));

(async () => {
    // Serve only explicitly listed fixtures and distribution files, without a general path resolver.
    const files = new Map();
    for (const name of ['engine-worker.js', 'wasm-gc/engine.wasm', 'wasm-gc/engine.wasm-runtime.js']) {
        files.set('/' + name, path.join(root, 'build/dist', name));
    }
    for (const sample of manifest) { files.set('/' + sample.id + '.gray', path.join(fixtures, sample.id + '.gray')); }
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><title>Engine verification</title>'); return; }
        const file = files.get(request.url);
        if (!file) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', file.endsWith('.wasm') ? 'application/wasm' : file.endsWith('.js') ? 'text/javascript' : 'application/octet-stream');
        fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({ headless: true });
        const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(async samples => {
            const worker = new Worker('/engine-worker.js');
            const start = performance.now();
            try {
                await new Promise((resolve, reject) => {
                    worker.onerror = reject;
                    worker.onmessage = ({ data }) => data.type === 'ready' ? resolve() : reject(new Error(JSON.stringify(data)));
                });
                const initializationMs = performance.now() - start;
                const hash = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
                    b => b.toString(16).padStart(2, '0')).join('');
                const execute = async (sample, targetStage, generation, cancelAtSections = false) => {
                    const pixels = await (await fetch('/' + sample.id + '.gray')).arrayBuffer();
                    return new Promise((resolve, reject) => {
                        const timeout = setTimeout(() => reject(new Error('Browser worker timed out')), 60_000);
                        let cancelSent = false;
                        worker.onerror = error => { clearTimeout(timeout); reject(error); };
                        worker.onmessage = ({ data }) => {
                            if (cancelAtSections && !cancelSent && data.type === 'progress' && data.stage === 'SECTIONS') {
                                cancelSent = true;
                                worker.postMessage({ type: 'cancel', jobId: sample.id, pageId: sample.id, generation });
                            }
                            if (data.type === 'done' || data.type === 'cancelled') { clearTimeout(timeout); resolve(data); }
                            else if (data.type === 'failed') { clearTimeout(timeout); reject(new Error(JSON.stringify(data))); }
                        };
                        worker.postMessage({ type: 'start', jobId: sample.id, pageId: sample.id, generation,
                            width: sample.width, height: sample.height, targetStage, pixels }, [pixels]);
                    });
                };
                const output = [];
                for (const sample of samples) {
                    const before = performance.now();
                    const result = await execute(sample, 'BINARY', 1);
                    const elapsedMs = performance.now() - before;
                    const structures = await execute(sample, 'SECTIONS', 2);
                    const hashes = {};
                    for (const key of ['horizontalRuns', 'verticalRuns', 'horizontalSections', 'verticalSections']) {
                        hashes[key] = await hash(structures[key]);
                    }
                    output.push({ id: sample.id, elapsedMs, sha256: await hash(result.binary), structures: hashes });
                }
                const cancelled = await execute(samples[0], 'SECTIONS', 3, true);
                if (cancelled.type !== 'cancelled') { throw new Error('Section cancellation did not take effect'); }
                const reused = await execute(samples[0], 'BINARY', 4);
                return { initializationMs, output, cancelledAndReused: await hash(reused.binary) === output[0].sha256 };
            } finally { worker.terminate(); }
        }, manifest);
        for (const result of results.output) {
            const expected = createHash('sha256').update(fs.readFileSync(path.join(fixtures, result.id + '.binary'))).digest('hex');
            assert.equal(result.sha256, expected, result.id + ': browser differs from upstream JVM');
            for (const orientation of ['horizontal', 'vertical']) {
                for (const kind of ['runs', 'sections']) {
                    const file = path.join(fixtures, result.id + '.' + orientation + '.' + kind);
                    const expectedHash = createHash('sha256').update(fs.readFileSync(file)).digest('hex');
                    const key = orientation + kind[0].toUpperCase() + kind.slice(1);
                    assert.equal(result.structures[key], expectedHash, `${result.id}: browser ${key} differs from upstream`);
                }
            }
        }
        assert.equal(results.cancelledAndReused, true);
        const report = { browser: browser.version(), ...results };
        fs.writeFileSync(path.join(root, 'build/browser-verification.json'), JSON.stringify(report, null, 2));
        console.log(JSON.stringify({ browser: report.browser, matchedPages: report.output.length,
            binaryRunsAndSectionsMatch: true, cancelledAndReused: report.cancelledAndReused }));
    } finally {
        if (browser) { await browser.close(); }
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
