// SPDX-License-Identifier: AGPL-3.0-or-later
// The single-file build resolves assets in memory. HTTP deployments use original modules.
(() => {
    const base = 'https://audiveris-offline.invalid/';
    const mime = name => name.endsWith('.wasm') ? 'application/wasm'
        : /\.[mc]?js$/.test(name) ? 'text/javascript'
        : name.endsWith('.json') ? 'application/json' : 'application/octet-stream';

    /** file:// Workers cannot fetch a parent's Blob URLs. Share raw Blobs by
     * structured clone and create URLs in the realm that consumes them.
     */
    async function materialize(files, assetBase) {
        const assetUrls = {}, moduleUrls = {}, code = {}, building = new Set();
        for (const [name, blob] of Object.entries(files)) {
            if (/\.[mc]?js$/.test(name)) code[name] = await blob.text();
            else assetUrls[name] = URL.createObjectURL(blob);
        }
        const transform = (name, text) => text
            .replace(/\bimport\.meta\.url\b/g, JSON.stringify(assetBase + name))
            .replace(/\bself\.location\.href\b/g, 'globalThis.__AUDIVERIS_WORKER_URL__')
            .replace(/\bimport\s*\(/g, `globalThis.__AUDIVERIS_IMPORT__(${JSON.stringify(assetBase + name)},`);
        function module(name) {
            if (moduleUrls[name]) return moduleUrls[name];
            if (!code[name]) throw new Error('同梱モジュールがありません: ' + name);
            if (building.has(name)) throw new Error('循環するモジュール依存: ' + name);
            building.add(name);
            const text = transform(name, code[name]).replace(
                /(^|\n)(\s*import\s+(?:[^;\n]*?\s+from\s*)?)(['"])(\.[^'"\n]+)\3/g,
                (_match, line, prefix, quote, relative) => line + prefix + quote
                    + module(new URL(relative, assetBase + name).pathname.slice(1)) + quote
            );
            const url = URL.createObjectURL(new Blob([text], {type: 'text/javascript'}));
            moduleUrls[name] = url; assetUrls[name] = url; building.delete(name); return url;
        }
        for (const name of Object.keys(code)) if (name.endsWith('.mjs') || ['ocr/tesseract.esm.min.js', 'ocr/lib.js'].includes(name)) module(name);
        for (const [name, text] of Object.entries(code)) {
            if (!moduleUrls[name]) assetUrls[name] = URL.createObjectURL(new Blob([transform(name, text)], {type: 'text/javascript'}));
        }
        return {assetUrls, moduleUrls};
    }

    /** Synthetic HTTPS addresses are identifiers only. Actual reads use local Blob URLs. */
    function install(files, assetUrls, moduleUrls, assetBase, sourceUrl, prepareSource) {
        const nativeFetch = globalThis.fetch.bind(globalThis);
        const keyOf = value => {
            const url = new URL(String(value), sourceUrl);
            if (!url.href.startsWith(assetBase)) return null;
            return decodeURIComponent(url.pathname.slice(1));
        };
        const asset = value => {
            const key = keyOf(value);
            if (key !== null && assetUrls[key]) return assetUrls[key];
            if (String(value).startsWith('blob:') || String(value).startsWith('data:')) return String(value);
            throw new Error('同梱データが見つかりません: ' + value);
        };
        globalThis.__AUDIVERIS_WORKER_URL__ = sourceUrl;
        globalThis.__AUDIVERIS_IMPORT__ = (referrer, value) => {
            const resolved = new URL(String(value), referrer).href, key = keyOf(resolved);
            if (key !== null && moduleUrls[key]) return import(moduleUrls[key]);
            if (resolved.startsWith('blob:') || resolved.startsWith('data:')) return import(resolved);
            return Promise.reject(new Error('同梱モジュールが見つかりません: ' + resolved));
        };
        globalThis.fetch = (value, options) => {
            try { return nativeFetch(asset(value instanceof Request ? value.url : value), options); }
            catch (error) { return Promise.reject(error); }
        };
        if (typeof globalThis.importScripts === 'function') {
            const nativeImportScripts = globalThis.importScripts.bind(globalThis);
            globalThis.importScripts = (...values) => nativeImportScripts(...values.map(asset));
        }
        const NativeWorker = globalThis.Worker;
        if (NativeWorker) {
            globalThis.Worker = class extends NativeWorker {
                constructor(value, options) {
                    const resolved = new URL(String(value), sourceUrl).href, key = keyOf(resolved);
                    if (key === null || !files[key]) throw new Error('同梱Workerが見つかりません: ' + resolved);
                    const prelude = `self.onmessage=event=>{
                        const packet=event.data;
                        if(!packet.__audiverisBootstrap)return;
                        (${prepareSource})(packet.files,packet.base).then(runtime=>{
                            (${install.toString()})(packet.files,runtime.assetUrls,runtime.moduleUrls,packet.base,packet.source,${JSON.stringify(prepareSource)});
                            importScripts(runtime.assetUrls[packet.key]);
                            postMessage({__audiverisReady:true});
                        }).catch(error=>postMessage({__audiverisBootstrapError:String(error.stack||error)}));
                    };`;
                    const url = URL.createObjectURL(new Blob([prelude], {type: 'text/javascript'}));
                    try { super(url, options); }
                    catch (error) { URL.revokeObjectURL(url); throw error; }
                    this.offlineScriptUrl = url; this.offlineReady = false; this.offlineQueue = [];
                    this.addEventListener('message', event => {
                        if (event.data?.__audiverisReady) {
                            event.stopImmediatePropagation(); this.offlineReady = true;
                            for (const packet of this.offlineQueue) NativeWorker.prototype.postMessage.call(this, packet.message, packet.transferList);
                            this.offlineQueue = [];
                        } else if (event.data?.__audiverisBootstrapError) {
                            event.stopImmediatePropagation();
                            this.dispatchEvent(new ErrorEvent('error', {message: event.data.__audiverisBootstrapError}));
                            this.terminate();
                        }
                    });
                    NativeWorker.prototype.postMessage.call(this, {__audiverisBootstrap: true, files, base: assetBase, source: resolved, key});
                }
                postMessage(message, options) {
                    if (this.offlineReady) return super.postMessage(message, options);
                    const transfer = Array.isArray(options) ? options : options?.transfer ?? [];
                    this.offlineQueue.push(structuredClone({message, transferList: transfer}, {transfer}));
                }
                terminate() { super.terminate(); this.offlineQueue = []; URL.revokeObjectURL(this.offlineScriptUrl); }
            };
        }
    }

    async function start() {
        const status = document.getElementById('status'), input = document.getElementById('image-file');
        input.disabled = true; status.textContent = '同梱の認識データを準備しています…';
        const element = document.getElementById('audiveris-embedded-assets');
        const data = JSON.parse(element.textContent); element.remove();
        const files = {};
        for (const [id, payload] of Object.entries(data.payloads)) {
            const decoded = atob(payload.data), bytes = new Uint8Array(decoded.length);
            for (let i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
            const content = payload.encoding === 'gzip'
                ? await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()
                : bytes;
            const names = data.payloadNames[id], blob = new Blob([content], {type: mime(names[0])});
            for (const name of names) files[name] = blob;
            delete data.payloads[id];
        }
        const runtime = await materialize(files, base);
        install(files, runtime.assetUrls, runtime.moduleUrls, base, base + 'index.html', materialize.toString());
        await import(runtime.moduleUrls['app.mjs']);
        input.disabled = false; status.textContent = '楽譜画像を選択してください';
    }
    start().catch(error => {
        document.getElementById('status-card').dataset.state = 'error';
        document.getElementById('status').textContent = 'オフライン版を起動できませんでした。ブラウザを変更してお試しください。';
        document.getElementById('error-details').hidden = false;
        document.getElementById('error-message').textContent = String(error.stack || error);
        console.error(error);
    });
})();
