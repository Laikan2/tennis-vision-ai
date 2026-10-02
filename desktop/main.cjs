const { app, BrowserWindow, ipcMain, Menu, dialog, shell, powerSaveBlocker } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const { createStudioServer } = require('./server.cjs');
const { createStudioUpdater, loadUpdateRepository } = require('./updater.cjs');

const option = name => process.argv.find(a=>a.startsWith(`--${name}=`))?.slice(name.length+3);
if (option('state-dir')) app.setPath('userData', path.resolve(option('state-dir')));
else app.setPath('userData', path.join(app.getPath('appData'),'TennisVisionStudio'));
const state = app.getPath('userData');
fs.mkdirSync(path.join(state,'logs'), {recursive:true});
const logFile = path.join(state,'logs','desktop.log');
function log(message) { fs.appendFileSync(logFile, `${new Date().toISOString()} ${message}\n`); }
let window, web, engine, receiver, backendUrl, shuttingDown=false, ready=false, starting;
let block, updates, shutdown;
const smokeStartedAt=Date.now();
let project = option('project-dir');
const configFile = path.join(state,'studio.json');
if (!project && fs.existsSync(configFile)) {
  try { project=JSON.parse(fs.readFileSync(configFile,'utf8')).project; } catch {}
}
if (project && !fs.existsSync(path.join(project,'backend','api.py'))) project=undefined;
if (project) fs.writeFileSync(configFile,JSON.stringify({project}));
const root = path.resolve(__dirname,'..');
const resources = app.isPackaged ? process.resourcesPath : path.join(__dirname,'build');
const ui = app.isPackaged ? path.join(resources,'ui') : path.join(root,'frontend','out');
let stage='Preparando o estúdio', issue='', receiverState='preparando';

function info() {
  return {version:app.getVersion(),ready,stage,issue,url:web?.url,
    backend:ready?'online':'iniciando',receiver:receiverState,
    dataPath:project?path.join(project,'data'):path.join(state,'data'),
    projectPath:project || null, logsPath:path.join(state,'logs'),
    videoAcceleration:app.getGPUFeatureStatus().video_decode || 'unavailable', updates:updates?.snapshot()};
}
function update(message) { stage=message; log(message); window?.webContents.send('studio:status',info()); }
function trusted(event) {
  const url=event.senderFrame?.url || '';
  if (event.sender!==window?.webContents ||
    !(web && new URL(url).origin===web.url || url===require('node:url').pathToFileURL(path.join(__dirname,'startup.html')).href))
    throw new Error('Origem inválida.');
}
function handle(channel,action) { ipcMain.handle(channel,async(event,...args)=>{trusted(event);return action(...args);}); }

async function availablePort() {
  const probe=net.createServer();
  await new Promise((resolve,reject)=>{probe.once('error',reject);probe.listen(0,'127.0.0.1',resolve);});
  const port=probe.address().port;
  await new Promise(resolve=>probe.close(resolve));
  return port;
}
async function listening(port) {
  return new Promise(resolve=>{const socket=net.connect({port,host:'127.0.0.1'});
    socket.setTimeout(750); const done=value=>{socket.destroy();resolve(value);};
    socket.once('connect',()=>done(true));socket.once('error',()=>done(false));socket.once('timeout',()=>done(false));});
}
async function health(url) {
  const response=await fetch(`${url}/api/health`,{signal:AbortSignal.timeout(1200)});
  const data=await response.json(); return response.ok && data.status==='ok' && data.credentialsStorageReady;
}
async function waitUntil(check,seconds=45) {
  const end=Date.now()+seconds*1000;
  while(Date.now()<end && !shuttingDown) {
    try { if(await check()) return; } catch {}
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  throw new Error('Não foi possível iniciar o serviço. Abra os registros para conferir o motivo.');
}
function startChild(executable,args,options={}) {
  const child=spawn(executable,args,{windowsHide:true,stdio:['pipe','pipe','pipe'],...options});
  child.on('error',error=>{log(`Serviço: ${error.message}`);issue='Não foi possível executar um serviço do programa.';});
  // Native decoders can print full RTSP credentials. Application state and
  // backend.log provide sanitized diagnostics; raw child output stays private.
  child.stdout?.on('data',()=>{}); child.stderr?.on('data',()=>{});
  return child;
}
async function stopEngine() {
  ready=false;
  if(!engine) return;
  const child=engine;engine=undefined;
  const finished=new Promise(resolve=>{if(child.exitCode!==null)resolve();else child.once('exit',resolve);});
  child.stdin?.on('error',()=>{});child.stdin?.end('shutdown\n');
  await Promise.race([finished,new Promise(resolve=>setTimeout(resolve,12000))]);
  if(child.exitCode===null) {
    child.kill();
    await Promise.race([finished,new Promise(resolve=>setTimeout(resolve,2500))]);
  }
}
async function sessionActive() {
  if (shuttingDown) return true;
  if (!ready) return Boolean(starting);
  try {
    const response = await fetch(`${backendUrl}/api/status`, {signal:AbortSignal.timeout(2500)});
    if (!response.ok) return true;
    return Boolean((await response.json()).running);
  } catch { return true; }
}
async function stopServices() {
  if (shutdown) return shutdown;
  shuttingDown = true;
  shutdown = (async () => {
    await stopEngine();
    await web?.close();
    receiver?.kill();
    if (block!==undefined && powerSaveBlocker.isStarted(block)) powerSaveBlocker.stop(block);
  })();
  return shutdown;
}
async function launch() {
  if(starting) return starting;
  starting=(async()=>{
    issue='';
    await stopEngine();
    if(!web) {web=await createStudioServer(ui,()=>backendUrl);}
    update('Preparando o receptor das câmeras');
    if(await listening(8554)) receiverState='compartilhado';
    else {
      const receiverRoot=app.isPackaged?path.join(resources,'receiver'):path.join(root,'tools','mediamtx');
      const receiverWork=path.join(state,'receiver');fs.mkdirSync(receiverWork,{recursive:true});
      receiver=startChild(path.join(receiverRoot,'mediamtx.exe'),[path.join(receiverRoot,'mediamtx.yml')],{cwd:receiverWork});
      await waitUntil(()=>listening(8554),15);receiverState='integrado';
    }
    update('Iniciando a IA e carregando seus dados');
    const port=await availablePort();backendUrl=`http://127.0.0.1:${port}`;
    const args=['--port',String(port),'--origin',web.url,'--state',state,'--resources',resources];
    if(project) args.push('--project',project);
    if(app.isPackaged) engine=startChild(path.join(resources,'engine','TennisVisionEngine.exe'),args);
    else engine=startChild(path.join(root,'.venv','Scripts','python.exe'),[path.join(__dirname,'engine.py'),...args],{cwd:root});
    engine.on('exit',code=>{
      if(!shuttingDown && ready) {ready=false;issue='O motor de análise foi interrompido. Reinicie o estúdio.';update('Motor desconectado');}
      log(`Motor encerrado: ${code}`);
    });
    await waitUntil(()=>health(backendUrl),60);
    ready=true;update('Estúdio pronto');
    await window.loadURL(web.url);
    if(option('smoke-report')) {
      const report=path.resolve(option('smoke-report'));
      const bridge=await window.webContents.executeJavaScript('window.tennisStudio?.getInfo()');
      fs.writeFileSync(report,JSON.stringify({...info(),enginePid:engine.pid,windowTitle:window.getTitle(),
        startupMs:Date.now()-smokeStartedAt,bridgeReady:Boolean(bridge?.ready)},null,2));
      setTimeout(async()=>{try {const picture=await window.webContents.capturePage();fs.writeFileSync(report+'.png',picture.toPNG());}catch{}},2500);
      if(option('smoke-stop-file')) {
        const stopFile=path.resolve(option('smoke-stop-file'));
        const stopTimer=setInterval(()=>{if(fs.existsSync(stopFile)){clearInterval(stopTimer);app.quit();}},300);
        stopTimer.unref();
      }
    }
  })().catch(async error=>{issue=error.message;ready=false;update('Precisamos conferir a inicialização');
    await window.loadFile(path.join(__dirname,'startup.html'));}).finally(()=>{starting=undefined;});
  return starting;
}

if(!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance',()=>{if(window?.isMinimized())window.restore();window?.focus();});
  app.whenReady().then(async()=>{
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      {label:'Estúdio',submenu:[{label:'Abrir pasta dos dados',click:()=>shell.openPath(info().dataPath)},
        {label:'Abrir registros',click:()=>shell.openPath(path.join(state,'logs'))},{type:'separator'},{role:'quit',label:'Sair'}]},
      {label:'Exibir',submenu:[{role:'reload',label:'Atualizar painel'},{role:'togglefullscreen',label:'Tela cheia'},{role:'resetZoom',label:'Tamanho original'},{role:'zoomIn',label:'Aumentar'},{role:'zoomOut',label:'Diminuir'}]},
      {label:'Ajuda',submenu:[{label:'Buscar atualização',click:async()=>{
        const result=await updates?.check();
        const detail=result?.message || (result?.status==='current' ? 'Você está usando a versão mais recente.' : result?.status==='downloaded' ? 'A atualização será instalada ao fechar o programa. Você também pode reiniciar em Configurações.' : 'Acompanhe a atualização em Configurações.');
        if(!window.isDestroyed())void dialog.showMessageBox(window,{type:'info',title:'Atualizações',message:'Atualizações do Tennis Vision Studio',detail});
      }},{type:'separator'},{label:'Sobre o Tennis Vision Studio',click:()=>dialog.showMessageBox(window,{type:'info',title:'Tennis Vision Studio',message:`Tennis Vision Studio ${app.getVersion()}`,detail:'Captura, análise e dados locais. O programa recebe o vídeo do celular e calcula a trajetória, os impactos e as estimativas no computador.'})}]}]));
    window=new BrowserWindow({width:1500,height:960,minWidth:960,minHeight:680,title:'Tennis Vision Studio',
      backgroundColor:'#0b1115',icon:path.join(__dirname,'assets','icon.png'),
      titleBarStyle:'hidden',titleBarOverlay:{color:'#0c1317',symbolColor:'#cad6d8',height:38},
      webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,sandbox:true,nodeIntegration:false,backgroundThrottling:true}});
    window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-navigate',(event,url)=>{if(web && new URL(url).origin!==web.url)event.preventDefault();});
    const canWriteClipboard = (contents, permission, origin) =>
      contents === window.webContents && permission === 'clipboard-sanitized-write' &&
      Boolean(web && origin?.startsWith(web.url + '/'));
    window.webContents.session.setPermissionRequestHandler((contents,permission,callback,details)=>
      callback(canWriteClipboard(contents,permission,details.requestingUrl)));
    window.webContents.session.setPermissionCheckHandler((contents,permission,origin)=>
      canWriteClipboard(contents,permission,origin.endsWith('/') ? origin : origin + '/'));
    window.webContents.session.on('will-download',async(_event,item)=>{
      const filename=path.basename(item.getFilename());
      item.setSavePath(path.join(app.getPath('downloads'),filename));
    });
    handle('studio:info',()=>info());
    let repository;
    if (app.isPackaged) {
      try {repository = loadUpdateRepository(resources);} catch {log('Atualizações: configuração indisponível.');}
    } else {
      const publish = require('./package.json').build?.publish;
      repository = Array.isArray(publish) ? publish[0] : publish;
    }
    updates = createStudioUpdater({updater:require('electron-updater').autoUpdater,
      isPackaged:app.isPackaged, version:app.getVersion(), repository,
      log, isSessionActive:sessionActive, prepareInstall:stopServices,
      onStatus:()=>{if(!window.isDestroyed())window.webContents.send('studio:status',info());}});
    handle('studio:updates-check',()=>updates.check());
    handle('studio:updates-install',()=>updates.install());
    handle('studio:open-data',()=>shell.openPath(info().dataPath));
    handle('studio:open-logs',()=>shell.openPath(path.join(state,'logs')));
    handle('studio:network',async()=>{
      const script=path.join(app.isPackaged?resources:__dirname,'setup','prepare-network.ps1');
      const receiverFile=path.join(app.isPackaged?path.join(resources,'receiver'):path.join(root,'tools','mediamtx'),'mediamtx.exe');
      const resultFile=path.join(state,'network-result.json');
      const startedAt=Date.now();
      if(!fs.existsSync(script)||!fs.existsSync(receiverFile))throw new Error('O arquivo de configuração da rede não está disponível.');
      const code=await new Promise((resolve,reject)=>{
        const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',script,
          '-ReceiverPath',receiverFile,'-ResultPath',resultFile],{windowsHide:true,stdio:'ignore'});
        child.on('error',reject);child.on('exit',resolve);
      });
      if(fs.existsSync(resultFile)&&fs.statSync(resultFile).mtimeMs>=startedAt-1000) {
        const result=JSON.parse(fs.readFileSync(resultFile,'utf8').replace(/^\uFEFF/,''));
        if(!result.ok)throw new Error(result.detail);return result;
      }
      throw new Error(code ? 'O Windows não autorizou a configuração. Você pode tentar novamente.' : 'Não foi possível confirmar a configuração da rede.');
    });
    handle('studio:fullscreen',()=>{window.setFullScreen(!window.isFullScreen());return window.isFullScreen();});
    handle('studio:retry',()=>launch());
    handle('studio:quit',()=>app.quit());
    handle('studio:project',async()=>{
      if(ready) {const status=await fetch(`${backendUrl}/api/status`).then(r=>r.json());if(status.running)throw new Error('Encerre a sessão antes de trocar a pasta de dados.');}
      const result=await dialog.showOpenDialog(window,{title:'Selecione a pasta tennis-vision-ai',properties:['openDirectory']});
      if(result.canceled)return {cancelled:true};
      const selected=result.filePaths[0];
      if(!fs.existsSync(path.join(selected,'backend','api.py')) || !fs.existsSync(path.join(selected,'data')))
        throw new Error('Selecione a pasta tennis-vision-ai que contém backend e data.');
      project=selected;fs.writeFileSync(configFile,JSON.stringify({project}));
      await window.loadFile(path.join(__dirname,'startup.html'));await launch();return info();
    });
    window.on('close',event=>{if(!shuttingDown){event.preventDefault();app.quit();}});
    block=powerSaveBlocker.start('prevent-app-suspension');
    await window.loadFile(path.join(__dirname,'startup.html'));await launch();
    if (!option('smoke-report')) updates.start();
  });
  app.on('before-quit',event=>{
    if(shuttingDown)return;
    event.preventDefault();
    void stopServices().then(()=>app.quit()).catch(error=>{log(`Encerramento: ${error.code || 'ERROR'}`);app.quit();});
  });
  app.on('window-all-closed',()=>app.quit());
}
