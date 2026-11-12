// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url'),{chromium}=require('playwright');
const root=path.resolve(process.argv[2]),assets=path.join(root,'build/wasm-engine/package'),output=path.join(root,'build/wasm-engine/ui');
(async()=>{
    const limits='/sys/fs/cgroup/wasm_audiveris_dev/';
    assert.equal(fs.readFileSync(limits+'cpu.max','utf8').trim(),'800000 100000');
    assert.equal(fs.readFileSync(limits+'memory.max','utf8').trim(),'8589934592');
    assert.ok(fs.readFileSync('/proc/self/cgroup','utf8').includes('/wasm_audiveris_dev/'));
    fs.mkdirSync(output,{recursive:true});
    const {encodeGray8Png}=await import(pathToFileURL(path.join(root,'wasm-compat/host/gray8-png.mjs')));
    const metadata=JSON.parse(fs.readFileSync(path.join(root,'build/wasm-compat/texts-continuation-probe/live-ocr/jvm/00/metadata.json')));
    const png=await encodeGray8Png({...metadata,pixels:new Uint8Array(fs.readFileSync(path.join(root,'build/wasm-compat/pixel-probe/samples/00.gray')))});
    const blank=await encodeGray8Png({width:40,height:40,pixels:new Uint8Array(1600).fill(255)});
    const server=http.createServer((request,response)=>{
        const name=decodeURIComponent(new URL(request.url,'http://localhost').pathname),file=path.resolve(assets,'.'+(name==='/'?'/index.html':name));
        if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){response.statusCode=404;return response.end();}
        const ext=path.extname(file);response.setHeader('Content-Type',ext==='.wasm'?'application/wasm':ext==='.html'?'text/html; charset=utf-8':ext==='.css'?'text/css':/\.[mc]?js$/.test(ext)?'text/javascript':'application/octet-stream');
        fs.createReadStream(file).pipe(response);
    });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
    try{
        browser=await chromium.launch({headless:true,timeout:1200000,args:['--js-flags=--max-old-space-size=768']});
        const context=await browser.newContext({viewport:{width:1200,height:950},acceptDownloads:true}),origin='http://127.0.0.1:'+server.address().port;
        const errors=[];await context.route('**/*',route=>route.request().url().startsWith(origin+'/')?route.continue():route.abort());
        const page=await context.newPage();page.on('pageerror',error=>errors.push(String(error)));page.on('console',message=>{if(message.type()==='error')console.log('Browser: '+message.text());});
        await page.goto(origin+'/');await page.waitForFunction(()=>document.getElementById('recognize').disabled);
        assert.equal(await page.locator('html').getAttribute('lang'),'en');
        assert.ok((await page.locator('#recognize').textContent()).startsWith('Start recognition'));
        assert.equal(await page.locator('#recognize [lang=ja]').textContent(),'認識を開始');
        assert.ok(await page.locator('#recognize [lang=ja]').evaluate(element=>parseFloat(getComputedStyle(element).fontSize)<parseFloat(getComputedStyle(element.parentElement).fontSize)));
        await page.locator('#image-file').setInputFiles({name:'not-a-score.txt',mimeType:'text/plain',buffer:Buffer.from('invalid')});
        await page.waitForFunction(()=>document.getElementById('status-card').dataset.state==='error');assert.ok(await page.locator('#recognize').isDisabled());
        assert.match(await page.locator('#status .bilingual-en').textContent(),/Choose a PNG/);
        assert.match(await page.locator('#status [lang=ja]').textContent(),/画像を選択/);
        await page.locator('#image-file').setInputFiles({name:'MerryGoRound_p1.png',mimeType:'image/png',buffer:Buffer.from(png)});
        await page.waitForFunction(()=>!document.getElementById('recognize').disabled);assert.ok(await page.locator('#preview').isVisible());
        assert.equal(await page.locator('#status .bilingual-en').textContent(),'Ready to recognize');
        await page.screenshot({path:path.join(output,'ready-desktop.png'),fullPage:true});
        await page.locator('#recognize').click();await page.locator('#cancel').click();
        await page.waitForFunction(()=>document.getElementById('status').textContent.includes('キャンセルしました')&&!document.getElementById('recognize').disabled,{},{timeout:60000});
        assert.match(await page.locator('#status .bilingual-en').textContent(),/Recognition cancelled/);
        assert.ok(await page.locator('#downloads').isHidden());console.log('UI cancellation and retry readiness passed');
        const recognitionStarted=performance.now();await page.locator('#recognize').click();
        assert.ok(await page.locator('#image-file').isDisabled());assert.ok(await page.locator('#recognize').isDisabled());
        await page.waitForFunction(()=>['success','error'].includes(document.getElementById('status-card').dataset.state),{},{timeout:1200000});
        assert.equal(await page.locator('#status-card').getAttribute('data-state'),'success',await page.locator('#error-message').textContent());
        assert.match(await page.locator('#status .bilingual-en').textContent(),/Recognition complete/);
        assert.match(await page.locator('#status [lang=ja]').textContent(),/認識が完了/);
        assert.match(await page.locator('#elapsed .bilingual-en').textContent(),/^Elapsed:/);
        const recognitionMs=performance.now()-recognitionStarted;
        await page.waitForFunction(()=>!document.getElementById('recognize').disabled);assert.equal(await page.locator('#downloads a').count(),1);
        assert.equal(await page.locator('#downloads a .bilingual-en').textContent(),'Download MusicXML');
        assert.equal(await page.locator('#downloads a [lang=ja]').textContent(),'MusicXMLをダウンロード');
        const received=page.waitForEvent('download');await page.locator('#downloads a').click();const download=await received;
        assert.equal(download.suggestedFilename(),'MerryGoRound_p1.musicxml');await download.saveAs(path.join(output,'00-0.musicxml'));
        const xml=fs.readFileSync(path.join(output,'00-0.musicxml'),'utf8');assert.match(xml,/<score-partwise[^>]*version="4.0"/);assert.match(xml,/<note(?:\s|>)/);
        await page.screenshot({path:path.join(output,'completed-desktop.png'),fullPage:true});
        await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
        await page.screenshot({path:path.join(output,'completed-mobile.png'),fullPage:true});
        await page.locator('#image-file').setInputFiles({name:'blank.png',mimeType:'image/png',buffer:Buffer.from(blank)});
        await page.waitForFunction(()=>!document.getElementById('recognize').disabled);assert.ok(await page.locator('#downloads').isHidden());
        await page.locator('#recognize').click();
        await page.waitForFunction(()=>document.getElementById('status-card').dataset.state==='error'&&!document.getElementById('recognize').disabled,{},{timeout:120000});
        assert.ok(await page.locator('#error-details').isVisible());assert.ok(await page.locator('#downloads').isHidden());
        assert.match(await page.locator('#status .bilingual-en').textContent(),/Recognition failed/);
        assert.deepEqual(errors,[]);
        const report={backend:JSON.parse(fs.readFileSync(path.join(assets,'ocr/assets.json'))).backend,
            recognitionMs,chromium:browser.version(),imageSelection:true,invalidFileRejected:true,preview:true,cancelAndRetry:true,progress:true,musicXmlDownloaded:true,
            suggestedFilename:download.suggestedFilename(),responsiveNoHorizontalOverflow:true,recognitionFailureRecoverable:true,staleDownloadsCleared:true,
            bilingualEnglishPrimary:true,japaneseSecondarySmaller:true,bilingualDynamicStatusAndDownloads:true,
            pageErrors:errors,cpuMax:fs.readFileSync(limits+'cpu.max','utf8').trim(),memoryMaxBytes:Number(fs.readFileSync(limits+'memory.max','utf8').trim())};
        fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
    }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
