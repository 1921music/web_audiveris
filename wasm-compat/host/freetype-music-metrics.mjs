/** Optional memory-only music metrics. Initialize once before starting synchronous recognition. */
export async function createFreeTypeMusicMetrics(factory, wasm, fonts, options = {}) {
    const cacheLimit = options.maxCacheEntries ?? 4096;
    if (!Number.isInteger(cacheLimit) || cacheLimit < 1 || cacheLimit > 65536) throw new RangeError('Invalid music cache limit');
    const api = await factory({wasmBinary: wasm});
    const allocate = size => {const pointer = api._malloc(size); if (!pointer) throw new Error('Font allocation failed'); return pointer;};
    const values = allocate(1024), faces = new Map(), glyphs = new Map(), metrics = new Map(), strings = new Map();
    const owned = []; let closed = false, requests = 0;
    const normalize = name => name == null || String(name).toLowerCase() === 'generic' ? 'bravura' : String(name).replaceAll(' ', '').toLowerCase();
    function put(cache, key, value) {
        if (!cache.has(key) && cache.size >= cacheLimit) cache.delete(cache.keys().next().value);
        cache.set(key, value);
    }
    function dispose() {
        if (closed) return;
        closed = true;
        for (const {face, pointer} of owned) {api._music_mask_close(face); api._free(pointer);}
        api._free(values); faces.clear(); glyphs.clear(); metrics.clear(); strings.clear();
    }
    try {
        if (api._music_mask_version(values) !== 0 || [0,1,2].some(i => api.HEAP32[(values >> 2) + i] !== [2,14,3][i])) throw new Error('Unexpected FreeType version');
        for (const [file, bytes] of Object.entries(fonts)) {
            if (!(bytes instanceof Uint8Array) || !bytes.length || bytes.length > 16 * 1024 * 1024) throw new RangeError('Invalid font bytes');
            const pointer = allocate(bytes.length); api.HEAPU8.set(bytes, pointer);
            const face = api._music_mask_memory_open(pointer, bytes.length);
            if (!face) {api._free(pointer); throw new Error('Invalid music font: ' + file);}
            owned.push({face, pointer});
            const namePointer = api._music_metrics_family(face);
            if (!namePointer) throw new Error('Missing physical font name');
            const family = api.UTF8ToString(namePointer), key = normalize(family);
            const nameLength = api._music_metrics_full_name(face, values, 1024);
            if (nameLength <= 0) throw new Error('Missing full music font name');
            const name = api.UTF8ToString(values);
            if (faces.has(key)) throw new Error('Duplicate physical music family: ' + name);
            const record = {face, name}; faces.set(key, record);
            const fullKey = normalize(name);
            if (fullKey !== key && faces.has(fullKey)) throw new Error('Conflicting full music font name');
            faces.set(fullKey, record);
        }
        if (!faces.size) throw new Error('Configure a physical music font');
    } catch (error) {dispose(); throw error;}
    function measure(family, size, value) {
        if (closed) throw new Error('Music font backend is closed');
        if (!Number.isInteger(size) || size < 1 || size > 16384 || typeof value !== 'string' || !value.length || value.length > 64) throw new RangeError('Invalid music measurement');
        for (const character of value) {
            const code = character.codePointAt(0);
            if (code !== 32 && (code < 0xe000 || code > 0xf8ff)) throw new RangeError('Music shaping backend required for this text');
        }
        const font = faces.get(normalize(family)); if (!font) throw new Error('Music family is not configured: ' + family);
        requests++;
        const stringKey = JSON.stringify([font.name, size, value]);
        const cached = strings.get(stringKey); if (cached) return cached;
        let position = 0, minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (const character of value) {
            const codepoint = character.codePointAt(0), glyphKey = JSON.stringify([font.name, codepoint]);
            let glyph = glyphs.get(glyphKey);
            if (glyph === undefined) {
                glyph = api._music_metrics_glyph(font.face, codepoint); put(glyphs, glyphKey, glyph);
            }
            if (glyph <= 0) throw new Error('Music glyph fallback required: ' + font.name + '/U+' + codepoint.toString(16));
            const key = JSON.stringify([font.name, size, glyph]); let box = metrics.get(key);
            if (!box) {
                const error = api._music_metrics_measure(font.face, glyph, size, values);
                if (error) throw new Error('Music measurement error: ' + error);
                box = api.HEAPF64.slice(values >> 3, (values >> 3) + 5);
                if (!Array.from(box).every(Number.isFinite)) throw new Error('Invalid physical font metrics');
                put(metrics, key, box);
            }
            if (box[2] > 0 && box[3] > 0) {
                const x = Math.fround(position + box[0]), y = box[1];
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, Math.fround(x + box[2])); maxY = Math.max(maxY, Math.fround(y + box[3]));
            }
            position = Math.fround(position + box[4]);
        }
        const result = Object.freeze(minX === Infinity ? {fontName: font.name, x: 0, y: 0, width: 0, height: 0, advance: position}
            : {fontName: font.name, x: minX, y: minY, width: Math.fround(maxX - minX), height: Math.fround(maxY - minY), advance: position});
        put(strings, stringKey, result); return result;
    }
    return Object.freeze({measure, dispose, stats: () => ({requests, cacheLimit, cachedMeasurements: strings.size, cachedGlyphMetrics: metrics.size, linearMemoryBytes: api.HEAPU8.byteLength})});
}
