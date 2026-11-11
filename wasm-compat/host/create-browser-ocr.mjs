// SPDX-License-Identifier: AGPL-3.0-or-later
import {TesseractJsOcr} from './tesseract-js-ocr.mjs';
import {browserWordMetadataOptions} from './tesseract-word-metadata.mjs';

/** Optional bundled Tesseract.js backend; alternative OCR implementations need only the transport. */
export async function createBrowserOcr({base=new URL('../ocr/',import.meta.url)}={}){
    base=new URL(base);
    const response=await fetch(new URL('assets.json',base));
    if(!response.ok)throw new Error('OCR assets are unavailable: '+response.status);
    const assets=await response.json(),{default:Tesseract}=await import(new URL(assets.library,base).href);
    const url=name=>new URL(name,base).href;
    return new TesseractJsOcr({oem:0,availableLanguages:assets.languages,createWorker:Tesseract.createWorker,
        workerOptions:browserWordMetadataOptions({workerPath:url(assets.worker),corePath:url(assets.core),langPath:url(assets.models),cacheMethod:'none',gzip:true},
            {loaderPath:new URL('./tesseract-core-word-metadata.js',import.meta.url).href,
                metadataModulePath:new URL('./tesseract-word-metadata.mjs',import.meta.url).href})});
}
