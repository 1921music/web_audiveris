import {createFreeTypeMusicMetrics} from './freetype-music-metrics.mjs';

/** Logical symbol recipes contain only source character codes, font fallback and size operations. */
export async function createFreeTypeMusicSymbols(factory, wasm, fonts, recipes, options = {}) {
    const definitions = structuredClone(recipes);
    const textFamilies = Object.freeze({...options.textFamilies});
    const maskBudget = options.maxMaskCacheBytes ?? 16 * 1024 * 1024;
    if (!Number.isInteger(maskBudget) || maskBudget < 0 || maskBudget > 128 * 1024 * 1024) throw new RangeError('Invalid mask cache budget');
    return createFreeTypeMusicMetrics(factory, wasm, fonts, options, (api, faces, limit) => {
        const info = api._malloc(40), output = api._malloc(512 * 512);
        if (!info || !output) {if (info) api._free(info); if (output) api._free(output); throw new Error('Symbol allocation failed');}
        const cache = new Map(); let closed = false, calls = 0, cachedBytes = 0;
        const normalize = value => String(value).replaceAll(' ', '').toLowerCase();
        function glyph(font, size, codepoint) {
            const key = JSON.stringify([font.name, size, codepoint]); let value = cache.get(key);
            if (value) return value;
            const id = api._music_symbol_glyph(font.face, codepoint);
            if (id < 0) throw new Error('Symbol glyph lookup failed');
            if (api._music_symbol_metrics(font.face, id, size, info)) throw new Error('Symbol metrics failed');
            const box = Array.from(api.HEAPF64.slice(info >> 3, (info >> 3) + 5));
            if (api._music_mask_render(font.face, id, size, 1, info, output, 512 * 512)) throw new Error('Symbol mask failed');
            const [left, top, width, height] = api.HEAP32.slice(info >> 2, (info >> 2) + 4);
            if (width < 0 || height < 0 || width > 512 || height > 512) throw new Error('Symbol mask dimensions');
            value = {id, box, left, top, width, height, pixels: new Int8Array(api.HEAPU8.slice(output, output + width * height).buffer)};
            if (value.pixels.byteLength <= maskBudget) {
                while (cache.size && (cache.size >= limit || cachedBytes + value.pixels.byteLength > maskBudget)) {
                    const oldest = cache.keys().next().value; cachedBytes -= cache.get(oldest).pixels.byteLength; cache.delete(oldest);
                }
                cache.set(key, value); cachedBytes += value.pixels.byteLength;
            }
            return value;
        }
        function paintSymbol(family, shape, pointSize, centerX, centerY) {
            if (closed) throw new Error('Symbol backend is closed');
            if (!Number.isInteger(pointSize) || pointSize < 1 || pointSize > 128 || !Number.isFinite(centerX) || !Number.isFinite(centerY) || Math.abs(centerX) > 4194304 || Math.abs(centerY) > 4194304) throw new RangeError('Symbol request outside verified bounds');
            const recipe = definitions[family + ':' + shape]; if (!recipe) throw new Error('Unsupported symbol definition: ' + family + ':' + shape);
            const result = render(recipe, pointSize, centerX, centerY, 0); calls++; return result;
        }
        function render(recipe, pointSize, centerX, centerY, depth) {
            if (depth > 12) throw new Error('Recursive symbol definition');
            if (recipe.layers) return recipe.layers.flatMap(layer => render(layer, pointSize, centerX, centerY, depth + 1));
            const family = recipe.textFamily ? textFamilies[recipe.textFamily] : recipe.family;
            if (!family) throw new Error('Text symbol role is not configured: ' + recipe.textFamily);
            const font = faces.get(normalize(family)); if (!font) throw new Error('Symbol font is not installed: ' + family);
            let size = pointSize;
            for (const op of recipe.sizeOps) size = op === 0 ? Math.floor(size + .5) : Math.fround(Math.fround(op) * size);
            if (recipe.textFamily) {
                if (!/^[\x20-\x7e]*$/.test(recipe.text)) throw new Error('Text symbol shaping/fallback backend required');
                const scaled = size * recipe.textScale, floor = Math.floor(scaled);
                size = scaled - floor === .5 ? floor + (floor % 2) : Math.floor(scaled + .5);
                if (size === 0) return [];
            }
            if (!Number.isFinite(size) || size <= 0 || size > 128) throw new RangeError('Derived symbol size');
            const positioned = []; let position = 0, minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
            for (const character of recipe.text) {
                const mask = glyph(font, size, character.codePointAt(0)), box = mask.box;
                positioned.push({position, mask});
                if (box[2] > 0 && box[3] > 0) {
                    const x = Math.fround(position + box[0]);
                    minX = Math.min(minX, x); minY = Math.min(minY, box[1]);
                    maxX = Math.max(maxX, Math.fround(x + box[2])); maxY = Math.max(maxY, Math.fround(box[1] + box[3]));
                }
                position = Math.fround(position + box[4]);
            }
            if (minX === Infinity) minX = minY = maxX = maxY = 0;
            const x = Math.fround(centerX - minX - Math.fround(maxX - minX) / 2);
            const y = Math.fround(centerY - minY - Math.fround(maxY - minY) / 2);
            if (Math.floor(size + .5) > 100) {
                const parts = []; let count = 0, windingRule = 1;
                for (const {position, mask} of positioned) {
                    if (api._music_symbol_outline(font.face, mask.id, size, Math.fround(x + position), y, info, output, 32768)) throw new Error('Symbol outline failed');
                    const [length, winding] = api.HEAP32.slice(info >> 2, (info >> 2) + 2);
                    if (length < 0 || length > 32768 || (count += length) > 65536) throw new Error('Symbol outline capacity');
                    parts.push(api.HEAPF64.slice(output >> 3, (output >> 3) + length)); windingRule = winding;
                }
                const outline = new Float64Array(count); let at = 0;
                for (const part of parts) {outline.set(part, at); at += part.length;}
                return [{outline, windingRule}];
            }
            return positioned.map(({position, mask}) => ({x: Math.floor(x + position + .5) + mask.left, y: Math.floor(y + .5) + mask.top,
                width: mask.width, height: mask.height, pixels: new Int8Array(mask.pixels)}));
        }
        return {methods: {paintSymbol, symbolStats: () => ({calls, cachedMasks: cache.size, cacheLimit: limit, cachedMaskBytes: cachedBytes, maskCacheBudgetBytes: maskBudget})},
            dispose() {closed = true; cache.clear(); cachedBytes = 0; api._free(info); api._free(output);}};
    });
}
