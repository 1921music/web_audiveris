// SPDX-License-Identifier: AGPL-3.0-or-later
import {EngineWorker} from './host/worker-client.mjs';
import {createBrowserOcr} from './host/create-browser-ocr.mjs';
import {setBilingual} from './bilingual.mjs';

const $=id=>document.getElementById(id);
const input=$('image-file'),start=$('recognize'),cancel=$('cancel'),preview=$('image-preview');
const phases=['BINARY','SCALE','GRID','HEADERS','STEM_SEEDS','BEAMS','LEDGERS','HEADS','STEMS','REDUCTION','CUE_BEAMS','TEXTS','MEASURES','CHORDS','CURVES','SYMBOLS','LINKS','RHYTHMS','PAGE'];
let selected=null,previewUrl=null,downloadUrls=[],active=null,selection=0;
function status(message,state='idle'){
    setBilingual($('status'),...message);$('status-card').dataset.state=state;
    $('status-card').setAttribute('aria-busy',state==='working'?'true':'false');
}
function clearResults(){
    downloadUrls.forEach(url=>URL.revokeObjectURL(url));downloadUrls=[];
    $('downloads').replaceChildren();$('downloads').hidden=true;
    $('error-details').hidden=true;$('error-message').textContent='';
    $('progress').hidden=true;$('progress').value=0;$('elapsed').hidden=true;
}
async function choose(file){
    if(active||!file)return;
    const ticket=++selection;selected=null;start.disabled=true;clearResults();
    if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl=null;
    preview.removeAttribute('src');$('preview').hidden=true;$('image-size').textContent='';
    $('filename').textContent=file.name;
    if(!/\.(png|jpe?g|webp|bmp)$/i.test(file.name)){
        status(['Choose a PNG, JPEG, WebP or BMP image.','PNG・JPEG・WebP・BMP形式の画像を選択してください。'],'error');return;
    }
    if(file.size>64*1024*1024){status(['The file is too large. Choose an image no larger than 64 MiB.','画像ファイルが大きすぎます。64 MiB以下の画像を選択してください。'],'error');return;}
    status(['Loading image…','画像を読み込んでいます…']);
    const url=previewUrl=URL.createObjectURL(file);
    try{
        preview.src=url;await preview.decode();if(ticket!==selection)return;
        const width=preview.naturalWidth,height=preview.naturalHeight;
        if(!width||!height||width>32768||height>32768||width*height>32*1024*1024)
            throw new RangeError('Image exceeds the 32 Mi-pixel or 32,768-pixel dimension limit.');
        selected=file;$('preview').hidden=false;$('image-size').textContent=`${width.toLocaleString()} × ${height.toLocaleString()} px`;
        status(['Ready to recognize','認識を開始できます']);start.disabled=false;
    }catch(error){
        if(ticket!==selection)return;
        URL.revokeObjectURL(url);previewUrl=null;preview.removeAttribute('src');
        status(error instanceof RangeError?['Choose an image with at most 32 Mi pixels and 32,768 pixels per side.','画像は約3,300万画素以下、各辺32,768ピクセル以下にしてください。']
            :['Could not open the image. Choose another file.','画像を開けませんでした。別の画像を選択してください。'],'error');
    }
}
input.addEventListener('change',()=>{
    const file=input.files[0];input.value='';choose(file);
});
const drop=$('drop-zone');
drop.addEventListener('dragover',event=>{event.preventDefault();if(!active)drop.classList.add('dragover');});
drop.addEventListener('dragleave',()=>drop.classList.remove('dragover'));
drop.addEventListener('drop',event=>{event.preventDefault();drop.classList.remove('dragover');if(!active)choose(event.dataTransfer.files[0]);});
function phaseLabel(stage,waiting){
    if(waiting||stage==='TEXTS')return ['Recognizing text…','文字を認識しています…'];
    if(['BINARY','SCALE'].includes(stage))return ['Analyzing the image…','画像を解析しています…'];
    if(['GRID','HEADERS'].includes(stage))return ['Reading staves and score structure…','五線と楽譜の構造を読み取っています…'];
    if(phases.indexOf(stage)<=10)return ['Recognizing notes…','音符を認識しています…'];
    if(stage==='PAGE')return ['Creating MusicXML…','MusicXMLを作成しています…'];
    return ['Analyzing measures, symbols and rhythm…','小節・記号・リズムを解析しています…'];
}
function downloads(result,file){
    if(!result.scores?.length)throw new Error('The recognition result contains no score data.');
    const base=file.name.replace(/\.[^.]+$/,'').replace(/[<>:"/\\|?*\u0000-\u001f]/g,'-').replace(/[. ]+$/,'')||'score';
    for(const score of result.scores){
        const url=URL.createObjectURL(new Blob([score.musicXml],{type:'application/xml;charset=utf-8'}));downloadUrls.push(url);
        const link=document.createElement('a');link.className='download';link.href=url;
        link.download=result.scores.length===1?`${base}.musicxml`:`${base}-${score.index+1}.musicxml`;
        if(result.scores.length===1)setBilingual(link,'Download MusicXML','MusicXMLをダウンロード');
        else setBilingual(link,`Download MusicXML · Score ${score.index+1}`,`楽譜 ${score.index+1} のMusicXMLをダウンロード`);
        $('downloads').append(link);
    }
    $('downloads').hidden=false;
}
start.addEventListener('click',async()=>{
    if(active||!selected)return;
    const file=selected,controller=new AbortController(),engine=new EngineWorker();
    active={controller};let ocr;clearResults();start.disabled=true;input.disabled=true;cancel.hidden=false;cancel.disabled=false;
    status(['Preparing recognition data…','認識用データを準備しています…'],'working');$('progress').hidden=false;$('elapsed').hidden=false;
    const began=performance.now();
    const elapsed=()=>{const seconds=Math.floor((performance.now()-began)/1000),minutes=Math.floor(seconds/60),remainder=String(seconds%60).padStart(2,'0');
        setBilingual($('elapsed'),`Elapsed: ${minutes}m ${remainder}s`,`経過 ${minutes}分${remainder}秒`);};
    elapsed();const timer=setInterval(elapsed,1000);
    try{
        ocr=await createBrowserOcr();
        if(controller.signal.aborted)throw controller.signal.reason;
        const result=await engine.recognize(file,{id:file.name,signal:controller.signal,
            ocr:(request,{signal})=>ocr.recognize(request,{signal}),
            onProgress:({stage,waitingForOcr})=>{
                if(controller.signal.aborted)return;
                $('progress').value=phases.indexOf(stage)+1;status(phaseLabel(stage,waitingForOcr),'working');
            }});
        if(controller.signal.aborted)throw controller.signal.reason;
        downloads(result,file);$('progress').value=20;status(['Recognition complete. Your MusicXML is ready.','認識が完了しました。MusicXMLを保存できます。'],'success');
    }catch(error){
        if(controller.signal.aborted)status(['Recognition cancelled. You can start again.','認識をキャンセルしました。もう一度開始できます。']);
        else{
            status(['Recognition failed. Check the image resolution and staff clarity, then try again.','認識できませんでした。画像の解像度や五線が鮮明か確認して、もう一度お試しください。'],'error');
            $('error-message').textContent=String(error?.message??error);$('error-details').hidden=false;
        }
    }finally{
        clearInterval(timer);elapsed();engine.close();
        try{if(ocr)await ocr.close();}catch(error){console.error('OCR cleanup failed',error);}
        active=null;cancel.hidden=true;input.disabled=false;start.disabled=!selected;
    }
});
cancel.addEventListener('click',()=>{
    if(!active)return;cancel.disabled=true;status(['Cancelling recognition…','認識を中断しています…'],'working');active.controller.abort();
});
window.addEventListener('pagehide',()=>{
    active?.controller.abort();if(previewUrl)URL.revokeObjectURL(previewUrl);downloadUrls.forEach(url=>URL.revokeObjectURL(url));
});
