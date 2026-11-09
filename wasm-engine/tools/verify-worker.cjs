/* SPDX-License-Identifier: AGPL-3.0-or-later */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Worker } = require('node:worker_threads');
const { performance } = require('node:perf_hooks');
const { brotliCompressSync, constants } = require('node:zlib');

const root = path.resolve(__dirname, '..');
const fixtures = path.resolve(process.argv[2] ?? path.join(root, 'build/sample-reference'));
const manifest = JSON.parse(fs.readFileSync(path.join(fixtures, 'manifest.json'), 'utf8'));
const workerFile = path.join(root, 'build/dist/engine-worker.js');

async function createWorker() {
    const before = performance.now();
    const worker = new Worker(workerFile, { resourceLimits: { maxOldGenerationSizeMb: 256 } });
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Worker initialization timed out')), 60_000);
        worker.once('error', error => { clearTimeout(timer); reject(error); });
        worker.once('message', message => {
            clearTimeout(timer);
            if (message.type !== 'ready') { reject(new Error(JSON.stringify(message))); }
            else { resolve(); }
        });
    }).catch(async error => { await worker.terminate(); throw error; });
    return { worker, initializationMs: performance.now() - before };
}

function run(worker, sample, generation = 1, cancel = false, targetStage = 'SCALE') {
    const jobId = `job-${sample.id}`;
    const bytes = fs.readFileSync(path.join(fixtures, sample.id + '.gray'));
    const pixels = Uint8Array.from(bytes).buffer;
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => finish(new Error('Page processing timed out')), 120_000);
        const start = performance.now();
        let cancelSent = false;
        const onError = error => finish(error);
        const handler = message => {
            if (message.jobId !== jobId || message.generation !== generation) { return; }
            if (message.type === 'progress') {
                if (cancel && !cancelSent) {
                    cancelSent = true;
                    worker.postMessage({ type: 'cancel', jobId, pageId: sample.id, generation });
                }
            } else if (['done', 'cancelled', 'failed'].includes(message.type)) {
                finish(null, { ...message, workerMs: performance.now() - start });
            }
        };
        function finish(error, result) {
            clearTimeout(timer);
            worker.off('message', handler);
            worker.off('error', onError);
            if (error) { reject(error); } else { resolve(result); }
        }
        worker.on('message', handler);
        worker.once('error', onError);
        worker.postMessage({ type: 'start', jobId, pageId: sample.id, generation,
            width: sample.width, height: sample.height, targetStage, pixels }, [pixels]);
    });
}

function verifyBinary(sample, result) {
    assert.equal(result.type, 'done', JSON.stringify(result.error));
    const actual = Buffer.from(result.binary);
    const expected = fs.readFileSync(path.join(fixtures, sample.id + '.binary'));
    assert.equal(actual.length, expected.length, `${sample.name}: binary length differs`);
    assert.equal(actual.equals(expected), true, `${sample.name}: Wasm binary pixels differ from upstream JVM`);
}

function verifyScale(sample, result) {
    if (sample.scaleError) {
        assert.equal(result.type, 'failed');
        assert.equal(result.error.stage, 'SCALE');
        assert.equal(result.error.message, sample.scaleError);
    } else {
        verifyBinary(sample, result);
        assert.deepEqual(result.scale, sample.portableScale, `${sample.name}: Wasm scale differs from portable JVM`);
    }
}

(async () => {
    const first = await createWorker();
    const results = [];
    try {
        for (const sample of manifest) {
            const result = await run(first.worker, sample, 1, false, 'BINARY');
            verifyBinary(sample, result);
            const scaleResult = await run(first.worker, sample);
            verifyScale(sample, scaleResult);
            let runCounts = null;
            let sectionCounts = null;
            const horizontalReference = path.join(fixtures, sample.id + '.horizontal.runs');
            if (fs.existsSync(horizontalReference)) {
                const runs = await run(first.worker, sample, 1, false, 'RUNS');
                verifyBinary(sample, runs);
                runCounts = {};
                for (const orientation of ['horizontal', 'vertical']) {
                    const actual = Buffer.from(runs[orientation + 'Runs']);
                    const expected = fs.readFileSync(path.join(fixtures, sample.id + '.' + orientation + '.runs'));
                    assert.equal(actual.length, expected.length, `${sample.id}: ${orientation} run data length differs`);
                    assert.equal(actual.equals(expected), true, `${sample.id}: ${orientation} runs differ from upstream`);
                    const laneCount = actual.readInt32LE(0);
                    runCounts[orientation] = actual.readInt32LE(4 * (laneCount + 1));
                }
            }
            if (fs.existsSync(path.join(fixtures, sample.id + '.horizontal.sections'))) {
                const sections = await run(first.worker, sample, 1, false, 'SECTIONS');
                verifyBinary(sample, sections);
                sectionCounts = {};
                for (const orientation of ['horizontal', 'vertical']) {
                    const actual = Buffer.from(sections[orientation + 'Sections']);
                    const expected = fs.readFileSync(path.join(fixtures, sample.id + '.' + orientation + '.sections'));
                    assert.equal(actual.length, expected.length, `${sample.id}: ${orientation} section data length differs`);
                    assert.equal(actual.equals(expected), true, `${sample.id}: ${orientation} sections differ from upstream`);
                    const geometry = Buffer.from(sections[orientation + 'Geometry']);
                    const expectedGeometry = fs.readFileSync(path.join(fixtures, sample.id + '.' + orientation + '.geometry'));
                    assert.equal(geometry.length, expectedGeometry.length, `${sample.id}: ${orientation} geometry length differs`);
                    assert.equal(geometry.equals(expectedGeometry), true, `${sample.id}: ${orientation} geometry differs from upstream`);
                    sectionCounts[orientation] = actual.readInt32LE(0);
                }
            }
            results.push({ name: sample.name, width: sample.width, height: sample.height,
                upstreamJvmMs: sample.upstreamJvmMs, portableJvmMs: sample.portableJvmMs,
                wasmWorkerMs: result.workerMs, scalePipelineMs: scaleResult.workerMs,
                scale: scaleResult.scale ?? null, scaleError: scaleResult.error ?? null, runCounts, sectionCounts });
            console.log(`${sample.name}: exact binary match; Wasm worker ${result.workerMs.toFixed(1)} ms`);
        }
        const cancelled = await run(first.worker, manifest[0], 2, true);
        assert.equal(cancelled.type, 'cancelled');
        verifyScale(manifest[0], await run(first.worker, manifest[0], 3));

        const second = await createWorker();
        try {
            const concurrent = await Promise.all([run(first.worker, manifest[0], 4), run(second.worker, manifest[0], 4)]);
            concurrent.forEach(result => verifyScale(manifest[0], result));
        } finally { await second.worker.terminate(); }
        const wasm = fs.readFileSync(path.join(root, 'build/dist/wasm-gc/engine.wasm'));
        const runtime = fs.readFileSync(path.join(root, 'build/dist/wasm-gc/engine.wasm-runtime.js'));
        const compress = bytes => brotliCompressSync(bytes, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length;
        const report = { node: process.version, initializationMs: first.initializationMs,
            verificationProcessPeakRssKiB: process.resourceUsage().maxRSS,
            sizes: { wasm: wasm.length, runtime: runtime.length, wasmBrotli: compress(wasm), runtimeBrotli: compress(runtime) },
            results, cancelledAndReused: true, concurrentWorkers: 2 };
        fs.writeFileSync(path.join(root, 'build/verification.json'), JSON.stringify(report, null, 2));
        console.log(JSON.stringify({ initializationMs: report.initializationMs, sizes: report.sizes,
            cancelledAndReused: true, concurrentWorkers: 2 }));
    } finally { await first.worker.terminate(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
