// SPDX-License-Identifier: AGPL-3.0-or-later
// Classic Worker loader selected by Tesseract.js corePath; source packages remain unchanged.
(()=>{
    const parameters=new URL(self.location.href).searchParams;
    const core=parameters.get('audiverisCore'),metadata=parameters.get('audiverisMetadata');
    if(!core||!metadata){throw new Error('Missing native OCR metadata loader configuration');}
    importScripts(core);
    const factory=self.TesseractCore;
    if(typeof factory!=='function'){throw new Error('Tesseract core factory is unavailable');}
    self.TesseractCore=async(...args)=>{
        if(!args[0]?.locateFile){args[0]={...args[0],locateFile:filename=>new URL(filename,core).href};}
        const [module,adapter]=await Promise.all([factory(...args),import(metadata)]);
        return adapter.attachNativeWordMetadata(module);
    };
})();
