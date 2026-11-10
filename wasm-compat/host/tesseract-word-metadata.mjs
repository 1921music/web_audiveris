// SPDX-License-Identifier: AGPL-3.0-or-later
const installed=new WeakSet();
const RIL_WORD=3; // Tesseract PageIteratorLevel, also used by the original JVM OCR bridge.
/** Augment the public JSON result using the same recognized page's public ResultIterator API. */
export function attachNativeWordMetadata(module){
    if(installed.has(module)){return module;}
    const prototype=module.TessBaseAPI?.prototype,iteratorPrototype=module.ResultIterator?.prototype;
    if(typeof prototype?.GetJSONText!=='function'||typeof prototype?.GetIterator!=='function'
            ||typeof iteratorPrototype?.getWordFontAttributes!=='function'||typeof iteratorPrototype?.getBaseline!=='function'
            ||typeof iteratorPrototype?.getBoundingBox!=='function'||typeof module.destroy!=='function'){
        throw new Error('Tesseract core does not expose the required word metadata API');
    }
    const original=prototype.GetJSONText;
    prototype.GetJSONText=function(...args){
        const json=original.apply(this,args),data=JSON.parse(json);
        const words=(data.blocks||[]).flatMap(block=>(block.paragraphs||[]).flatMap(paragraph=>(paragraph.lines||[]).flatMap(line=>line.words||[])));
        if(words.length===0){return json;}
        const iterator=this.GetIterator();let index=0;
        try{
            iterator.Begin();
            do{
                if(iterator.Empty(RIL_WORD)){continue;}
                const word=words[index++],bounds=iterator.getBoundingBox(RIL_WORD);
                if(!word||!['x0','y0','x1','y1'].every(key=>word.bbox[key]===bounds[key])){
                    throw new Error('Tesseract word metadata is not aligned with JSON words');
                }
                const baseline=iterator.getBaseline(RIL_WORD);
                word.baseline=baseline.has_baseline?{x0:baseline.x0,y0:baseline.y0,x1:baseline.x1,y1:baseline.y1}:null;
                const font=iterator.getWordFontAttributes();
                // As in TesseractOrder.getFont, the native font name is not used for font selection.
                word.audiveris_font=font.font_name&&font.font_id>=0&&font.pointsize>0?{
                    bold:font.is_bold,italic:font.is_italic,underlined:font.is_underlined,monospace:font.is_monospace,
                    serif:font.is_serif,smallcaps:font.is_smallcaps,pointSize:font.pointsize,name:null
                }:null;
            }while(iterator.Next(RIL_WORD));
            if(index!==words.length){throw new Error('Incomplete native OCR word metadata');}
        }finally{module.destroy(iterator);}
        return JSON.stringify(data);
    };
    installed.add(module);return module;
}

/** Configure a normal Tesseract.js browser Worker to load a metadata-enabled core factory. */
export function browserWordMetadataOptions(options,{loaderPath,metadataModulePath,baseUrl=globalThis.location?.href}){
    if(!options.workerPath||!options.corePath||!loaderPath||!metadataModulePath){throw new TypeError('Explicit OCR asset paths required');}
    const worker=new URL(options.workerPath,baseUrl);
    if(!new URL(options.corePath,baseUrl).pathname.endsWith('.js')){throw new TypeError('Select a JavaScript OCR core asset before enabling word metadata');}
    worker.searchParams.set('audiverisCore',new URL(options.corePath,baseUrl).href);
    worker.searchParams.set('audiverisMetadata',new URL(metadataModulePath,baseUrl).href);
    return {...options,workerPath:worker.href,workerBlobURL:false,corePath:new URL(loaderPath,baseUrl).href};
}
