// SPDX-License-Identifier: AGPL-3.0-or-later
// Classic Worker, so the generated TeaVM runtime can be loaded without a DOM.
let engine,active,initializing;
const pending=new Map();
const errorData=error=>({name:error?.name??'Error',message:String(error?.message??error),stack:error?.stack});
self.onmessage=event=>{
    const message=event.data;
    if(message?.type==='ocr-reply'){
        const waiter=pending.get(message.key);
        if(waiter){pending.delete(message.key);waiter.resolve(message.reply);}return;
    }
    if(message?.type==='cancel'){
        if(active?.id===message.id){active.controller.abort();for(const waiter of pending.values())waiter.reject(new DOMException('Aborted','AbortError'));pending.clear();}
        return;
    }
    (async()=>{
        if(message.type==='init'){
            if(initializing)throw new Error('Worker is already initialized');
            initializing=(async()=>{
                const base=new URL(message.base??'../',self.location.href);
                importScripts(new URL('engine.wasm-runtime.js',base).href);
                const {createEngine}=await import('./engine.mjs');
                engine=await createEngine({base,loadWasm:url=>TeaVM.wasmGC.load(url)});
            })();await initializing;
            self.postMessage({type:'ready',id:message.id});
        }else if(message.type==='recognize'){
            if(!engine||active)throw new Error(!engine?'Worker is not ready':'Worker is busy');
            const controller=new AbortController();active={id:message.id,controller};
            try{
                let input=message.input;
                if(input instanceof Blob){const {decodeImage}=await import('./engine.mjs');input=await decodeImage(input);}
                const result=await engine.recognize({...message.options,...input,signal:controller.signal,
                    onProgress:progress=>self.postMessage({type:'progress',id:message.id,progress}),
                    ocr:request=>new Promise((resolve,reject)=>{
                        const key=JSON.stringify(request.token);pending.set(key,{resolve,reject});
                        self.postMessage({type:'ocr',id:message.id,key,request},[request.pixels.buffer]);
                    })});
                self.postMessage({type:'result',id:message.id,result});
            }finally{active=null;}
        }else throw new Error('Unknown Worker command');
    })().catch(error=>self.postMessage({type:'error',id:message.id,error:errorData(error)}));
};
