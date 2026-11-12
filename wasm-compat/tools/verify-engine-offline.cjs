// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url'),{chromium}=require('playwright');
const root=path.resolve(process.argv[2]),output=path.join(root,'build/wasm-engine/offline');
(async()=>{
    const limits='/sys/fs/cgroup/wasm_audiveris_dev/';
    assert.equal(fs.readFileSync(limits+'cpu.max','utf8').trim(),'800000 100000');
    assert.equal(fs.readFileSync(limits+'memory.max','utf8').trim(),'8589934592');
    assert.ok(fs.readFileSync('/proc/self/cgroup','utf8').includes('/wasm_audiveris_dev/'));
    const {encodeGray8Png}=await import(pathToFileURL(path.join(root,'wasm-compat/host/gray8-png.mjs')));
    const metadata=JSON.parse(fs.readFileSync(path.join(root,'build/wasm-compat/texts-continuation-probe/live-ocr/jvm/00/metadata.json')));
    const png=await encodeGray8Png({...metadata,pixels:new Uint8Array(fs.readFileSync(path.join(root,'build/wasm-compat/pixel-probe/samples/00.gray')))});
    const browser=await chromium.launch({headless:true,timeout:1200000,args:['--js-flags=--max-old-space-size=768']});
    const errors=[],requests=[];
    try{
        const context=await browser.newContext({acceptDownloads:true,viewport:{width:1200,height:950}});
        await context.setOffline(true);
        await context.route('**/*',route=>{
            if(/^https?:/.test(route.request().url())){requests.push(route.request().url());return route.abort();}
            return route.continue();
        });
        const page=await context.newPage();page.on('pageerror',error=>errors.push(String(error)));
        page.on('console',message=>{if(message.type()==='error')console.log('Browser: '+message.text());});
        await page.goto(pathToFileURL(path.join(output,'audiveris-offline.html')).href,{timeout:120000});
        await page.waitForFunction(()=>!document.getElementById('image-file').disabled||document.getElementById('status-card').dataset.state==='error',{},{timeout:120000});
        if(await page.locator('#image-file').isDisabled())throw new Error(await page.locator('#error-message').textContent());
        assert.equal(await page.locator('html').getAttribute('lang'),'en');
        assert.equal(await page.locator('#status .bilingual-en').textContent(),'Choose a score image to begin');
        assert.match(await page.locator('#status [lang=ja]').textContent(),/楽譜画像を選択/);
        assert.match(await page.locator('footer').textContent(),/Offline edition/);
        await page.locator('#image-file').setInputFiles({name:'offline-score.png',mimeType:'image/png',buffer:Buffer.from(png)});
        await page.waitForFunction(()=>!document.getElementById('recognize').disabled);
        await page.locator('#recognize').click();
        await page.waitForFunction(()=>document.getElementById('progress').value>0||document.getElementById('status-card').dataset.state==='error',{},{timeout:120000});
        if(await page.locator('#status-card').getAttribute('data-state')==='error')throw new Error(await page.locator('#error-message').textContent());
        await page.locator('#cancel').click();
        await page.waitForFunction(()=>document.getElementById('status').textContent.includes('キャンセルしました')&&!document.getElementById('recognize').disabled,{},{timeout:60000});
        const recognitionStarted=performance.now();await page.locator('#recognize').click();
        await page.waitForFunction(()=>['success','error'].includes(document.getElementById('status-card').dataset.state),{},{timeout:1200000});
        assert.equal(await page.locator('#status-card').getAttribute('data-state'),'success',await page.locator('#error-message').textContent());
        assert.match(await page.locator('#status .bilingual-en').textContent(),/Recognition complete/);
        assert.match(await page.locator('#status [lang=ja]').textContent(),/認識が完了/);
        const event=page.waitForEvent('download');await page.locator('#downloads a').click();const download=await event;
        assert.equal(download.suggestedFilename(),'offline-score.musicxml');await download.saveAs(path.join(output,'00-0.musicxml'));
        await page.screenshot({path:path.join(output,'completed-desktop.png'),fullPage:true});
        assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
        const report={backend:JSON.parse(fs.readFileSync(path.join(output,'build.json'))).ocrBackend,
            recognitionMs:performance.now()-recognitionStarted,chromium:browser.version(),protocol:new URL(page.url()).protocol,specialFileAccessFlags:false,
            serverUsed:false,browserNetworkDisabled:true,networkRequests:requests,initialization:true,cancelAndRetry:true,imageRecognition:true,musicXmlDownloaded:true,
            bilingualEnglishPrimary:true,bilingualBootstrapAndStatus:true,
            htmlBytes:fs.statSync(path.join(output,'audiveris-offline.html')).size,
            cpuMax:fs.readFileSync(limits+'cpu.max','utf8').trim(),memoryMaxBytes:Number(fs.readFileSync(limits+'memory.max','utf8').trim())};
        fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
