const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const {chromium}=require(process.env.TV_PLAYWRIGHT || 'playwright');
(async()=>{
 const report=path.resolve(process.argv[2] || '');
 const folder=path.dirname(report);
 assert(path.basename(folder).startsWith('desktop-smoke-'),'Use an isolated smoke report');
 const startup=JSON.parse(fs.readFileSync(report,'utf8'));
 assert(!startup.projectPath && path.dirname(path.resolve(startup.dataPath))===folder);
 const info=await fetch(startup.url+'/api/mobile-camera?platform=ios').then(r=>r.json());
 const params=new URL(info.appPairingUri).searchParams;
 const panel='http://127.0.0.1:'+params.get('panelPort');
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 try {
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true});
 await context.request.post(panel+'/mobile/pair',{headers:{Authorization:'Bearer '+params.get('panelToken')}});
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(panel+'/?phone=1');await page.waitForSelector('.phone-panel');
 await page.getByRole('button',{name:'Arrastar chão',exact:true}).waitFor({timeout:15000});

 await page.screenshot({path:path.join(folder,'phone-panel-before.png'),fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,'Horizontal overflow');
 await page.getByRole('button',{name:'Arrastar chão',exact:true}).click();
 const area=page.locator('svg.target-overlay');await area.scrollIntoViewIfNeeded();
 const box=await area.boundingBox();assert(box && box.width>200);
 const client=await context.newCDPSession(page);
 const touch=(type,x,y)=>client.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'?[]:[{x,y,radiusX:2,radiusY:2,force:1,id:1}]});
 await touch('touchStart',box.x+box.width*.2,box.y+box.height*.25);
 for(let i=1;i<=6;i++)await touch('touchMove',box.x+box.width*(.2+.6*i/6),box.y+box.height*(.25+.6*i/6));
 await touch('touchEnd',0,0);
 await page.getByRole('button',{name:'Usar esta superfície',exact:true}).click();
 await page.getByRole('button',{name:'Arrastar chão',exact:true}).waitFor();
 const cameras=await fetch(startup.url+'/api/cameras').then(r=>r.json());
 const poly=cameras[0].capturePolygon;assert.equal(poly.length,4);
 assert(Math.abs(poly[0][0]-.2)<.03 && Math.abs(poly[2][1]-.85)<.03,'Touch rectangle matches image coordinates');
 await page.getByRole('button',{name:'Testar som OUT',exact:false}).click();
 await page.getByText('Som OUT testado. Mantenha o volume ligado.',{exact:true}).waitFor();
 await page.locator('.camera-wall').screenshot({path:path.join(folder,'phone-area-saved.png')});
 await page.setViewportSize({width:844,height:390});
 await page.screenshot({path:path.join(folder,'phone-landscape.png'),fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,'Landscape overflow');
 assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(folder,'ui-result.json'),JSON.stringify({touchAreaSaved:true,coordinates:poly,outAudioDecodedAndPlayed:true,noHorizontalOverflow:true,javascriptErrors:errors},null,2));
 console.log('Phone UI touch, area persistence, audio and responsive layout passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
