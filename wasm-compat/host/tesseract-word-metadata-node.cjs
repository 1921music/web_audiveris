// SPDX-License-Identifier: AGPL-3.0-or-later
// Node's Tesseract.js worker selects its core internally, so wrap the cached exported factories.
(async()=>{
    const {attachNativeWordMetadata}=await import('./tesseract-word-metadata.mjs');
    for(const suffix of ['', '-lstm', '-simd', '-simd-lstm', '-relaxedsimd', '-relaxedsimd-lstm']){
        const filename=require.resolve('tesseract.js-core/tesseract-core'+suffix);
        const factory=require(filename);
        require.cache[filename].exports=async(...args)=>attachNativeWordMetadata(await factory(...args));
    }
    require('tesseract.js/src/worker-script/node');
})().catch(error=>{throw error;});
