// SPDX-License-Identifier: AGPL-3.0-or-later
class BackendError extends Error {
    constructor(code, message) { super(message); this.code = code; }
}
const unsupported = message => { throw new BackendError('UNSUPPORTED', message); };
const aborted = signal => signal.reason ?? new DOMException('Aborted', 'AbortError');
function properties(element) {
    return Object.fromEntries((element.getAttribute('title') || '').split(';').map(item => {
        const [key, ...values] = item.trim().split(/\s+/); return [key, values];
    }));
}
function numbers(value, count) {
    const result = value?.map(Number);
    if (!result || result.length !== count || !result.every(Number.isFinite)) unsupported('Invalid hOCR geometry');
    return result;
}
function bounds(value) {
    const [x, y, right, bottom] = numbers(value, 4);
    if (![x, y, right, bottom].every(n => Number.isInteger(n) && Math.abs(n) <= 2147483647)
        || right < x || bottom < y || right - x > 2147483647 || bottom - y > 2147483647) unsupported('Invalid hOCR box');
    return {x, y, width: right - x, height: bottom - y};
}
/** Actual hOCR character boxes; word baselines interpolate the native line baseline.
 * LSTM style policy: unknown font family/styles become plain Sans. x_fsize is
 * native; if absent, initialize from word height. Original TEXTS refines size
 * against physical font metrics. These normalized styles are not native detections.
 * Words without a native baseline retain their geometry/text but have font=null:
 * original TEXTS skips these unusable detections instead of dereferencing null.
 * DOMParser is used only on the host, never on the recognition Worker.
 */
export function normalizeWasmHocr(hocr, parse = text => new DOMParser().parseFromString(text, 'text/html')) {
    const document = parse(hocr), lines = [];
    if (!document.querySelector('.ocr_page')) unsupported('Missing hOCR page');
    for (const element of document.querySelectorAll('.ocr_line, .ocr_header, .ocr_textfloat, .ocr_caption')) {
        const props = properties(element), rect = bounds(props.bbox);
        const [slope, intercept] = props.baseline ? numbers(props.baseline, 2) : [0, 0];
        // A missing native baseline remains absent; no synthetic baseline is reported.
        const baseline = box => props.baseline ? {x1: box.x, y1: rect.y + rect.height + intercept + slope * (box.x - rect.x),
            x2: box.x + box.width, y2: rect.y + rect.height + intercept + slope * (box.x + box.width - rect.x)} : null;
        const words = [];
        for (const word of element.querySelectorAll('.ocrx_word')) {
            const wp = properties(word), box = bounds(wp.bbox);
            const characters = [...word.querySelectorAll('.ocrx_cinfo')].map(char => ({text: char.textContent, bounds: bounds(properties(char).x_bboxes)}));
            if (!characters.length || characters.some(char => !char.text)) unsupported('Actual hOCR character boxes required');
            const confidence = numbers(wp.x_wconf, 1)[0] / 100;
            if (confidence < 0 || confidence > 1) unsupported('Invalid hOCR confidence');
            const pointSize = Math.round(wp.x_fsize ? numbers(wp.x_fsize, 1)[0] : Math.max(1, box.height));
            if (pointSize <= 0 || pointSize > 2147483647) unsupported('Invalid hOCR font size');
            words.push({text: characters.map(char => char.text).join(''), bounds: box, confidence, baseline: baseline(box), characters,
                font: props.baseline ? {bold: false, italic: false, underlined: false, monospace: false, serif: false, smallcaps: false, pointSize, name: null} : null});
        }
        lines.push({words, baseline: baseline(rect)});
    }
    return {capabilities: {characters: true, baselines: true, fontAttributes: true}, lines,
        metadataPolicy: 'lstm-plain-sans; native-characters; native-line-baseline; skip-missing-baseline'};
}

/** One cancellable, reusable single-threaded OCR Worker. Queued aborts do not
 * interrupt another job. Active aborts terminate synchronous native OCR outright.
 */
export class TesseractWasmOcr {
    #assets; #factory; #worker; #pending = new Map(); #serial = 0; #tail = Promise.resolve(); #closed = false;
    constructor({assets, createWorker = () => new Worker(new URL('./tesseract-wasm-worker.js', import.meta.url))}) {
        this.#assets = assets; this.#factory = createWorker;
    }
    #dispose(error) {
        this.#worker?.terminate(); this.#worker = null;
        for (const {reject} of this.#pending.values()) reject(error);
        this.#pending.clear();
    }
    #call(data, transfer = []) {
        const id = ++this.#serial;
        return new Promise((resolve, reject) => {
            this.#pending.set(id, {resolve, reject});
            try { this.#worker.postMessage({...data, id}, transfer); }
            catch (error) { this.#pending.delete(id); reject(error); }
        });
    }
    recognize(request, {signal} = {}) {
        const t = request?.token;
        if (!t || typeof t.job !== 'string' || !t.job || typeof t.page !== 'string' || !t.page
            || !Number.isInteger(t.generation) || t.generation < 0 || t.generation > 2147483647
            || !Number.isInteger(t.request) || t.request <= 0 || t.request > 2147483647) throw new TypeError('Invalid OCR token');
        const token = {...t}, owned = {...request, pixels: request.pixels instanceof Uint8Array ? request.pixels.slice() : null};
        let abort;
        const cancelled = new Promise((_, reject) => {
            abort = () => reject(aborted(signal));
            if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, {once: true});
        });
        cancelled.catch(() => {});
        const wait = promise => Promise.race([promise, cancelled]);
        const operation = this.#tail.then(async () => {
            let active = false;
            try {
                if (signal?.aborted) throw aborted(signal);
                if (this.#closed) throw new BackendError('EXECUTION_FAILED', 'OCR adapter is closed');
                if (owned.languages !== 'eng') throw new BackendError('MODEL_MISSING', 'This build contains only the English LSTM model');
                const mode = owned.layout === 'MULTI_BLOCK' ? '3' : owned.layout === 'SINGLE_BLOCK' ? '6' : null;
                if (!mode || !Number.isInteger(owned.width) || !Number.isInteger(owned.height) || owned.width <= 0 || owned.height <= 0
                    || !owned.pixels || owned.width * owned.height !== owned.pixels.length) unsupported('Invalid GRAY8 OCR request or layout');
                active = true;
                if (!this.#worker) {
                    const worker = this.#factory(); this.#worker = worker;
                    worker.onmessage = ({data}) => {
                        if (this.#worker !== worker) return;
                        const pending = this.#pending.get(data.id); if (!pending) return;
                        this.#pending.delete(data.id); data.error ? pending.reject(new Error(data.error)) : pending.resolve(data);
                    };
                    worker.onerror = event => { event.preventDefault(); if (this.#worker === worker) this.#dispose(new Error(event.message || 'OCR worker failed')); };
                    worker.onmessageerror = () => { if (this.#worker === worker) this.#dispose(new Error('Invalid OCR worker message')); };
                    await wait(this.#call({type: 'init', ...this.#assets}));
                }
                const {hocr} = await wait(this.#call({type: 'recognize', width: owned.width, height: owned.height, pixels: owned.pixels, mode}, [owned.pixels.buffer]));
                return {token, result: normalizeWasmHocr(hocr)};
            } catch (error) {
                if (active) this.#dispose(error);
                if (signal?.aborted) throw aborted(signal);
                return {token, failure: {code: error instanceof BackendError ? error.code : 'EXECUTION_FAILED', message: String(error?.message || error)}};
            } finally { signal?.removeEventListener('abort', abort); }
        });
        this.#tail = operation.catch(() => {});
        return signal ? Promise.race([operation, cancelled]) : operation;
    }
    async close() { this.#closed = true; await this.#tail; this.#dispose(new Error('OCR adapter closed')); }
}
