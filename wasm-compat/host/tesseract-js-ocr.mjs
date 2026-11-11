// SPDX-License-Identifier: AGPL-3.0-or-later
import {encodeGray8Png} from './gray8-png.mjs';

class BackendError extends Error{
    constructor(code,message){super(message);this.code=code;}
}
const unsupported=message=>{throw new BackendError('UNSUPPORTED',message);};
function box(value){
    if(!value||!['x0','y0','x1','y1'].every(key=>Number.isInteger(value[key])&&value[key]>=-2147483648&&value[key]<=2147483647)
            ||value.x1<value.x0||value.y1<value.y0||value.x1-value.x0>2147483647||value.y1-value.y0>2147483647){unsupported('Invalid OCR bounding box');}
    return {x:value.x0,y:value.y0,width:value.x1-value.x0,height:value.y1-value.y0};
}
function baseline(value){
    if(value==null){return null;}
    if(!['x0','y0','x1','y1'].every(key=>Number.isFinite(value[key]))){unsupported('Invalid OCR baseline');}
    return {x1:value.x0,y1:value.y0,x2:value.x1,y2:value.y1};
}
function font(value){
    if(value==null){return null;}
    if(!['bold','italic','underlined','monospace','serif','smallcaps'].every(key=>typeof value[key]==='boolean')
            ||!Number.isInteger(value.pointSize)||value.pointSize<=0||value.pointSize>2147483647){unsupported('Invalid native OCR font metadata');}
    return {bold:value.bold,italic:value.italic,underlined:value.underlined,monospace:value.monospace,
        serif:value.serif,smallcaps:value.smallcaps,pointSize:value.pointSize,name:null};
}
/** Retain real character boxes and line baselines. Missing font/word-baseline metadata stays absent. */
export function normalizeTesseractResult(data,legacyNativeFontMetadata=false){
    const capabilities={characters:true,baselines:true,fontAttributes:false},lines=[];
    if(!Array.isArray(data?.blocks)){
        if(data?.blocks==null&&typeof data?.text==='string'&&data.text.trim()===''){return {capabilities,lines};}
        unsupported('Structured OCR blocks were not returned');
    }
    for(const block of data.blocks){
        if(!Array.isArray(block.paragraphs)){unsupported('Missing OCR paragraphs');}
        for(const paragraph of block.paragraphs){
            if(!Array.isArray(paragraph.lines)){unsupported('Missing OCR lines');}
            for(const line of paragraph.lines){
                if(!Array.isArray(line.words)){unsupported('Missing OCR words');}
                const words=[];
                for(const word of line.words){
                    // Capability describes the backend, even if this image's words all have unknown fonts.
                    if(legacyNativeFontMetadata&&Object.hasOwn(word,'audiveris_font')){capabilities.fontAttributes=true;}
                    if(word.text===''&&Array.isArray(word.symbols)&&word.symbols.length===0){continue;}
                    if(typeof word.text!=='string'||word.text.length===0||!Array.isArray(word.symbols)||word.symbols.length===0){unsupported('Actual OCR character boxes are required');}
                    if(!Number.isFinite(word.confidence)||word.confidence<0||word.confidence>100){unsupported('Invalid OCR confidence');}
                    const characters=word.symbols.map(symbol=>{
                        if(typeof symbol.text!=='string'||symbol.text.length===0){unsupported('Missing OCR character text');}
                        return {text:symbol.text,bounds:box(symbol.bbox)};
                    });
                    const nativeFont=font(word.audiveris_font);if(nativeFont){capabilities.fontAttributes=true;}
                    words.push({text:word.text,bounds:box(word.bbox),confidence:word.confidence/100,
                        baseline:baseline(word.baseline),font:nativeFont,characters});
                }
                lines.push({words,baseline:baseline(line.baseline)});
            }
        }
    }
    return {capabilities,lines};
}

/** One reusable OCR worker per adapter. Engine Workers own their sessions and reject late replies.
 * createWorker and asset locations are injected; this module imports no particular OCR package/CDN.
 */
export class TesseractJsOcr{
    #createWorker;#available;#options;#encode;#oem;#worker;#languages;#tail=Promise.resolve();#closed=false;
    constructor({createWorker,availableLanguages,workerOptions={},encodeImage=encodeGray8Png,oem=0}){
        if(typeof createWorker!=='function'||typeof encodeImage!=='function'){throw new TypeError('OCR worker factory and image encoder required');}
        if(!availableLanguages||typeof availableLanguages==='string'){throw new TypeError('OCR languages must be an explicit collection');}
        if(![0,1].includes(oem)){throw new TypeError('OCR engine mode must be legacy (0) or LSTM-only (1)');}
        this.#oem=oem;
        this.#createWorker=createWorker;this.#available=new Set(availableLanguages);this.#options={...workerOptions};this.#encode=encodeImage;
        if(this.#available.size===0||![...this.#available].every(lang=>typeof lang==='string'&&lang.length>0&&!lang.includes('+'))){throw new TypeError('Explicit available OCR languages required');}
    }
    recognize(request){
        const token=request?.token;
        if(!token||typeof token.job!=='string'||!token.job||typeof token.page!=='string'||!token.page
                ||!Number.isInteger(token.generation)||token.generation<0||token.generation>2147483647
                ||!Number.isInteger(token.request)||token.request<=0||token.request>2147483647){throw new TypeError('Invalid OCR request token');}
        const ownedToken={job:token.job,page:token.page,generation:token.generation,request:token.request};
        // Snapshot callers' data before queueing; transferred engine buffers remain independent.
        const owned={width:request.width,height:request.height,languages:request.languages,layout:request.layout,
            pixels:request.pixels instanceof Uint8Array?request.pixels.slice():null};
        const operation=this.#tail.then(async()=>{
            try{
                if(this.#closed){throw new BackendError('EXECUTION_FAILED','OCR adapter is closed');}
                if(typeof owned.languages!=='string'||!owned.languages.split('+').every(lang=>this.#available.has(lang))){throw new BackendError('MODEL_MISSING','Requested OCR language model is not configured');}
                const mode=owned.layout==='MULTI_BLOCK'?'3':owned.layout==='SINGLE_BLOCK'?'6':null;if(!mode){unsupported('Unsupported OCR layout');}
                if(!Number.isInteger(owned.width)||!Number.isInteger(owned.height)||owned.width<=0||owned.height<=0
                        ||!owned.pixels||owned.width*owned.height!==owned.pixels.length){unsupported('Invalid packed GRAY8 OCR request');}
                const image=await this.#encode(owned);
                if(!this.#worker){this.#worker=await this.#createWorker(owned.languages,this.#oem,this.#options);this.#languages=owned.languages;}
                else if(this.#languages!==owned.languages){await this.#worker.reinitialize(owned.languages,this.#oem);this.#languages=owned.languages;}
                await this.#worker.setParameters({tessedit_pageseg_mode:mode});
                const {data}=await this.#worker.recognize(image,{}, {text:true,blocks:true});
                return {token:ownedToken,result:normalizeTesseractResult(data,this.#oem===0)};
            }catch(error){
                let message=String(error?.message||error);
                if(!(error instanceof BackendError)){
                    const failed=this.#worker;this.#worker=null;this.#languages=null;
                    if(failed){try{await failed.terminate();}catch(cleanup){message+='; OCR cleanup failed: '+String(cleanup?.message||cleanup);}}
                }
                return {token:ownedToken,failure:{code:error instanceof BackendError?error.code:'EXECUTION_FAILED',message}};
            }
        });
        this.#tail=operation.then(()=>undefined,()=>undefined);return operation;
    }
    /** Graceful shutdown. Cancelling one engine session does not terminate another session's OCR. */
    async close(){this.#closed=true;await this.#tail;if(this.#worker){await this.#worker.terminate();this.#worker=null;}}
}
