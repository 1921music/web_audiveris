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
                const output = [];
                for (const sample of samples) {
                    const pixels = await (await fetch('/' + sample.id + '.gray')).arrayBuffer();
                    const before = performance.now();
                    const result = await new Promise((resolve, reject) => {
                        worker.onerror = reject;
                        worker.onmessage = ({ data }) => {
                            if (data.type === 'done') { resolve(data); }
                            else if (data.type === 'failed') { reject(new Error(JSON.stringify(data))); }
                        };
                        worker.postMessage({ type: 'start', jobId: sample.id, pageId: sample.id, generation: 1,
                            width: sample.width, height: sample.height, targetStage: 'BINARY', pixels }, [pixels]);
                    });
                    const elapsedMs = performance.now() - before;
                    const hash = await crypto.subtle.digest('SHA-256', result.binary);
                    output.push({ id: sample.id, elapsedMs, sha256: Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, '0')).join('') });
                }
                return { initializationMs, output };
            } finally { worker.terminate(); }
        }, manifest);
        for (const result of results.output) {
            const expected = createHash('sha256').update(fs.readFileSync(path.join(fixtures, result.id + '.binary'))).digest('hex');
            assert.equal(result.sha256, expected, result.id + ': browser differs from upstream JVM');
        }
        const report = { browser: browser.version(), ...results };
        fs.writeFileSync(path.join(root, 'build/browser-verification.json'), JSON.stringify(report, null, 2));
        console.log(JSON.stringify(report, null, 2));
    } finally {
        if (browser) { await browser.close(); }
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
