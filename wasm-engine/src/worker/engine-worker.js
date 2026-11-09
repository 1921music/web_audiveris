/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Classic worker, also executable with Node worker_threads for headless verification. */
(async () => {
    let send, receive, schedule;
    if (typeof process !== 'undefined' && process.versions?.node) {
        const { parentPort } = require('node:worker_threads');
        const fs = require('node:fs');
        const path = require('node:path');
        const vm = require('node:vm');
        vm.runInThisContext(fs.readFileSync(path.join(__dirname, 'wasm-gc/engine.wasm-runtime.js'), 'utf8'), {
            importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
        });
        send = (message, transfer = []) => parentPort.postMessage(message, transfer);
        receive = (handler) => parentPort.on('message', handler);
        schedule = setImmediate;
    } else {
        importScripts('./wasm-gc/engine.wasm-runtime.js');
        send = (message, transfer = []) => postMessage(message, transfer);
        receive = (handler) => { onmessage = event => handler(event.data); };
        const channel = new MessageChannel();
        const tasks = [];
        channel.port1.onmessage = () => tasks.shift()();
        schedule = task => { tasks.push(task); channel.port2.postMessage(null); };
    }
    let active = null;
    let api;
    const identity = job => ({ jobId: job.jobId, pageId: job.pageId, generation: job.generation });
    const fail = (job, error) => send({ type: 'failed', ...identity(job),
        error: { code: 'ENGINE_FAILURE', stage: job.stage ?? 'BINARY', message: String(error?.message ?? error) } });
    // Register immediately so messages arriving during Wasm initialization are not lost.
    const waiting = [];
    receive(message => {
        if (!api) { waiting.push(message); return; }
        dispatch(message);
    });

    function dispatch(message) {
        if (message.type === 'start') {
            if (active) { fail(message, new Error('Worker is busy')); return; }
            try {
                const { width, height, stride = width, pixels } = message;
                if (!(pixels instanceof ArrayBuffer)) { throw new Error('pixels must be an ArrayBuffer'); }
                const targetStage = message.targetStage ?? 'SCALE';
                if (!['BINARY', 'SCALE', 'RUNS', 'SECTIONS'].includes(targetStage)) { throw new Error('Unsupported target stage'); }
                api.start(width, height, stride, new Int8Array(pixels), 18, .7, .9, targetStage === 'SCALE',
                    ['RUNS', 'SECTIONS'].includes(targetStage), targetStage === 'SECTIONS');
                const job = { ...identity(message), width, height, targetStage };
                active = job;
                schedule(() => tick(job));
            } catch (error) { api.dispose(); fail(message, error); }
        } else if (message.type === 'cancel' && active &&
                message.jobId === active.jobId && message.pageId === active.pageId && message.generation === active.generation) {
            const job = active;
            active = null;
            api.dispose();
            send({ type: 'cancelled', ...identity(job) });
        }
    }

    function tick(job) {
        if (!job || active !== job) { return; }
        try {
            job.stage = api.stage();
            if (api.advance(8)) {
                const binary = api.binaryImage();
                const scale = job.targetStage === 'SCALE' ? Array.from(api.scale()) : null;
                const horizontalRuns = ['RUNS', 'SECTIONS'].includes(job.targetStage) ? api.runTable(true).buffer : null;
                const verticalRuns = ['RUNS', 'SECTIONS'].includes(job.targetStage) ? api.runTable(false).buffer : null;
                const horizontalSections = job.targetStage === 'SECTIONS' ? api.sections(true).buffer : null;
                const verticalSections = job.targetStage === 'SECTIONS' ? api.sections(false).buffer : null;
                const horizontalGeometry = job.targetStage === 'SECTIONS' ? api.sectionGeometry(true).buffer : null;
                const verticalGeometry = job.targetStage === 'SECTIONS' ? api.sectionGeometry(false).buffer : null;
                api.dispose();
                active = null;
                send({ type: 'done', ...identity(job), width: job.width, height: job.height,
                    binary: binary.buffer, scale, horizontalRuns, verticalRuns, horizontalSections, verticalSections,
                    horizontalGeometry, verticalGeometry },
                    [binary.buffer, horizontalRuns, verticalRuns, horizontalSections, verticalSections,
                        horizontalGeometry, verticalGeometry].filter(Boolean));
            } else {
                send({ type: 'progress', ...identity(job), stage: api.stage(), completed: api.completedColumns(), total: api.totalUnits() });
                schedule(() => tick(job));
            }
        } catch (error) { api.dispose(); active = null; fail(job, error); }
    }

    try {
        const wasmPath = typeof __dirname === 'string' ? require('node:path').join(__dirname, 'wasm-gc/engine.wasm') : './wasm-gc/engine.wasm';
        api = (await TeaVM.wasmGC.load(wasmPath)).exports;
        send({ type: 'ready', protocolVersion: 1, stages: ['BINARY', 'SCALE', 'RUNS', 'SECTIONS'] });
        for (const message of waiting.splice(0)) { dispatch(message); }
    } catch (error) {
        send({ type: 'initializationFailed', error: { code: 'WASM_LOAD_FAILED', message: String(error?.message ?? error) } });
    }
})();
