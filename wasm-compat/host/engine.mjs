// SPDX-License-Identifier: AGPL-3.0-or-later
import {createFreeTypeMusicSymbols} from './freetype-music-symbols.mjs';
import {createSymbolGeometry} from './font-symbol-geometry.mjs';

/** Initialize immutable assets once per Worker. readBytes also allows memory-only Node hosts. */
export async function createEngine({base,loadWasm,readBytes=async url=>{
    const response=await fetch(url);if(!response.ok)throw new Error(`Asset ${url}: ${response.status}`);
    return new Uint8Array(await response.arrayBuffer());
},fontFactory}={}) {
    base=new URL(base);
    const url=file=>new URL(file,base).href;
    const json=async file=>JSON.parse(new TextDecoder().decode(await readBytes(url(file))));
    const manifest=await json('assets.json');
    const [recipes,intrinsics,textFonts]=await Promise.all([
        json(manifest.recipes),json(manifest.intrinsics),json(manifest.textFonts)
    ]);
    const fonts={};
    for(const [name,file] of Object.entries(manifest.musicFonts))fonts[name]=await readBytes(url(file));
    const factory=fontFactory??(await import(url(manifest.fontFactory))).default;
    const music=await createFreeTypeMusicSymbols(factory,await readBytes(url(manifest.fontWasm)),fonts,recipes,
        {textFamilies:manifest.textFamilies});
    try{
        const loaded=await loadWasm(url(manifest.engine));
        const api=loaded.exports;
        api.configure(await readBytes(url(manifest.classifier)),textFonts.defaultFont,
            createSymbolGeometry(music,intrinsics),music.renderTemplate,
            (_kind,family,size,text)=>music.measure(family,size,text),music.paintSymbol);
        for(const face of textFonts.faces)api.installTextFace(face.id,await readBytes(url('text-fonts/fonts/'+face.file)));
        for(const style of textFonts.styles)api.bindTextFace(style.flags,style.face,style.name,style.syntheticItalic);
        let generation=0,closed=false,busy=false;
        const close=()=>{if(!closed){closed=true;api.cancel();music.dispose();}};
        return Object.freeze({
            /** Input is owned GRAY8 (0 black, 255 white); OCR accepts/returns the documented transport. */
            async recognize({width,height,pixels,id='page',family='Bravura',smallHeads=false,
                margin=20,languages='eng',adaptiveMean=.7,adaptiveDeviation=.9,
                ocr,signal,onProgress=()=>{},yieldControl=()=>new Promise(resolve=>setTimeout(resolve,0))}){
                if(closed||busy)throw new Error(closed?'Engine is closed':'Engine is busy');
                if(typeof ocr!=='function')throw new TypeError('An OCR adapter is required');
                if(!(pixels instanceof Uint8Array)||pixels.length!==width*height)throw new TypeError('Expected packed GRAY8');
                busy=true;
                const aborted=()=>{if(signal?.aborted)throw signal.reason??new DOMException('Aborted','AbortError');};
                try{
                    aborted();
                    api.beginGray(width,height,pixels,id,++generation,family,smallHeads,margin,languages,adaptiveMean,adaptiveDeviation);
                    onProgress({stage:api.stage()});await yieldControl();
                    while(!api.complete()){
                        aborted();const request=api.ocrRequest();
                        if(request){
                            onProgress({stage:api.stage(),waitingForOcr:true,token:request.token});
                            const reply=await ocr(request,{signal});aborted();
                            if(!api.receive(reply))throw new Error('OCR reply token does not match the active request');
                            api.resume();
                        }else api.advance();
                        onProgress({stage:api.stage()});await yieldControl();
                    }
                    const scores=[];
                    for(let index=0;index<api.scoreCount();index++){
                        aborted();scores.push({index,musicXml:api.musicXml(index)});await yieldControl();
                    }
                    return {id,stage:api.stage(),scores};
                }catch(error){api.cancel();throw error;}finally{busy=false;}
            },
            close,
            stats:()=>({music:music.stats(),symbols:music.symbolStats()})
        });
    }catch(error){music.dispose();throw error;}
}

/** Browser-native decoding; composite transparency onto white before converting to GRAY8. */
export async function decodeImage(blob,{maxPixels=32*1024*1024}={}){
    if(!(blob instanceof Blob))throw new TypeError('Expected an image Blob');
    const bitmap=await createImageBitmap(blob);
    try{
        const {width,height}=bitmap;
        if(!width||!height||width>32768||height>32768||width*height>maxPixels)throw new RangeError('Image exceeds page budget');
        const canvas=new OffscreenCanvas(width,height),context=canvas.getContext('2d',{willReadFrequently:true});
        context.fillStyle='#fff';context.fillRect(0,0,width,height);context.drawImage(bitmap,0,0);
        const rgba=context.getImageData(0,0,width,height).data,pixels=new Uint8Array(width*height);
        for(let i=0;i<pixels.length;i++)pixels[i]=Math.floor((299*rgba[i*4]+587*rgba[i*4+1]+114*rgba[i*4+2]+500)/1000);
        return {width,height,pixels};
    }finally{bitmap.close();}
}
