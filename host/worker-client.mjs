// SPDX-License-Identifier: AGPL-3.0-or-later
/** A single page-owning Worker. Create multiple clients to schedule independent pages concurrently. */
export class EngineWorker {
    #url;#base;#worker;#ready;#active;#closed=false;#serial=0;
    constructor({workerUrl=new URL('./engine-worker.js',import.meta.url),base=new URL('../',import.meta.url)}={}){
        this.#url=workerUrl;this.#base=String(base);
    }
    async #initialize(){
        if(this.#closed)throw new Error('Engine Worker is closed');
        if(this.#ready)return this.#ready;
        const worker=this.#worker=new Worker(this.#url);
        this.#ready=new Promise((resolve,reject)=>{
            worker.onerror=event=>{if(this.#worker!==worker)return;const error=new Error(event.message);reject(error);this.#active?.reject(error);this.#discard();};
            worker.onmessage=event=>{
                if(this.#worker!==worker)return;
                const message=event.data;
                if(message.type==='ready'){resolve();return;}
                if(message.type==='error'&&message.id===0){reject(Object.assign(new Error(message.error.message),{name:message.error.name}));this.#discard();return;}
                const active=this.#active;if(!active||active.id!==message.id)return;
                if(message.type==='progress')active.onProgress?.(message.progress);
                else if(message.type==='result')active.resolve(message.result);
                else if(message.type==='error')active.reject(Object.assign(new Error(message.error.message),{name:message.error.name}));
                else if(message.type==='ocr'){
                    Promise.resolve().then(()=>active.ocr(message.request,{signal:active.signal})).then(reply=>{
                        if(this.#active===active&&this.#worker===worker)worker.postMessage({type:'ocr-reply',key:message.key,reply});
                    },error=>{if(this.#active===active){active.reject(error);this.#discard();}});
                }
            };
            worker.postMessage({type:'init',id:0,base:this.#base});
        });
        return this.#ready;
    }
    #discard(){this.#worker?.terminate();this.#worker=null;this.#ready=null;}
    /** Blob decoding runs in the Worker. GRAY8 input is cloned, leaving caller-owned bytes intact.
     * Abort terminates the Worker, including a synchronous recognition stage; a subsequent call
     * starts a fresh isolated instance. The OCR adapter receives the same AbortSignal.
     */
    async recognize(input,{ocr,signal,onProgress,...options}={}){
        if(this.#active)throw new Error('Engine Worker is busy');
        if(typeof ocr!=='function')throw new TypeError('An OCR adapter is required');
        if(signal?.aborted)throw signal.reason??new DOMException('Aborted','AbortError');
        let abort;
        try{
            const ready=this.#initialize();
            return await new Promise((resolve,reject)=>{
                const active=this.#active={id:++this.#serial,ocr,signal,onProgress,resolve,reject};
                abort=()=>{reject(signal.reason??new DOMException('Aborted','AbortError'));this.#discard();};
                signal?.addEventListener('abort',abort,{once:true});
                ready.then(()=>{if(this.#active===active&&this.#worker)this.#worker.postMessage({type:'recognize',id:active.id,input,options});},reject);
            });
        }finally{signal?.removeEventListener('abort',abort);this.#active=null;}
    }
    close(){this.#closed=true;this.#active?.reject(new Error('Engine Worker closed'));this.#discard();}
}
