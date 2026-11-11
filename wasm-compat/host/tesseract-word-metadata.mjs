// SPDX-License-Identifier: AGPL-3.0-or-later
const installed=new WeakSet();
const RIL_WORD=3; // Tesseract PageIteratorLevel, also used by the original JVM OCR bridge.
const RIL_SYMBOL=4;
const RIL_TEXTLINE=2;
/** Augment the public JSON result using the same recognized page's public ResultIterator API. */
export function attachNativeWordMetadata(module){
    if(installed.has(module)){return module;}
    const prototype=module.TessBaseAPI?.prototype,iteratorPrototype=module.ResultIterator?.prototype;
    if(typeof prototype?.GetJSONText!=='function'||typeof prototype?.GetIterator!=='function'
            ||typeof iteratorPrototype?.getWordFontAttributes!=='function'||typeof iteratorPrototype?.getBaseline!=='function'
            ||typeof iteratorPrototype?.getBoundingBox!=='function'||typeof iteratorPrototype?.Confidence!=='function'
            ||typeof iteratorPrototype?.GetUTF8Text!=='function'||typeof iteratorPrototype?.IsAtBeginningOf!=='function'
            ||typeof module.destroy!=='function'){
        throw new Error('Tesseract core does not expose the required word metadata API');
    }
    const original=prototype.GetJSONText;
    prototype.GetJSONText=function(...args){
        const json=original.apply(this,args),data=JSON.parse(json);
        const words=(data.blocks||[]).flatMap(block=>(block.paragraphs||[]).flatMap(paragraph=>(paragraph.lines||[]).flatMap(line=>line.words||[])));
        if(words.length===0){return json;}
        const iterator=this.GetIterator();let index=0,line,word;const lines=[];
        try{
            iterator.Begin();
            do{
                if(iterator.Empty(RIL_SYMBOL)){continue;}
                if(iterator.IsAtBeginningOf(RIL_WORD)){
                    const jsonWord=words[index],bounds=iterator.getBoundingBox(RIL_WORD),text=iterator.GetUTF8Text(RIL_WORD);
                    const aligned=jsonWord&&['x0','y0','x1','y1'].every(key=>jsonWord.bbox[key]===bounds[key]);
                    // JSON omits some real whitespace-only words/lines. Retain their native
                    // symbols and boundaries without shifting metadata onto the next JSON word.
                    if(aligned){index++;}
                    else if(typeof text!=='string'||text.trim()!==''){
                        throw new Error('Tesseract word metadata is not aligned with JSON words: '
                            +JSON.stringify({index,text:jsonWord?.text,jsonBounds:jsonWord?.bbox,iteratorBounds:bounds,iteratorText:text}));
                    }
                    if(iterator.IsAtBeginningOf(RIL_TEXTLINE)){
                        const baseline=iterator.getBaseline(RIL_TEXTLINE);
                        line={words:[],baseline:baseline.has_baseline?{x0:baseline.x0,y0:baseline.y0,x1:baseline.x1,y1:baseline.y1}:null};lines.push(line);
                    }
                    if(!line){throw new Error('Missing native OCR line boundary');}
                    word={text,bbox:bounds,symbols:[]};line.words.push(word);
                    const baseline=iterator.getBaseline(RIL_WORD);
                    word.confidence=iterator.Confidence(RIL_WORD);
                    word.baseline=baseline.has_baseline?{x0:baseline.x0,y0:baseline.y0,x1:baseline.x1,y1:baseline.y1}:null;
                    const font=iterator.getWordFontAttributes();
                    // The original JVM bridge also ignores the native font name.
                    word.audiveris_font=font.font_name&&font.font_id>=0&&font.pointsize>0?{
                        bold:font.is_bold,italic:font.is_italic,underlined:font.is_underlined,monospace:font.is_monospace,
                        serif:font.is_serif,smallcaps:font.is_smallcaps,pointSize:font.pointsize,name:null
                    }:null;
                    if(aligned){Object.assign(jsonWord,{confidence:word.confidence,baseline:word.baseline,audiveris_font:word.audiveris_font});}
                }
                if(!word){throw new Error('Missing native OCR word boundary');}
                word.symbols.push({text:iterator.GetUTF8Text(RIL_SYMBOL),bbox:iterator.getBoundingBox(RIL_SYMBOL)});
            }while(iterator.Next(RIL_SYMBOL));
            if(index!==words.length){throw new Error('Incomplete native OCR word metadata');}
        }finally{module.destroy(iterator);}
        // Tesseract.js forwards only .blocks. Keep its public JSON, carrying complete
        // native lines alongside it for the recognition adapter.
        data.blocks[0].audiveris_native_lines=lines;
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
