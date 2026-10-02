const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const http=require('node:http');
const crypto=require('node:crypto');
const {safeFile,createStudioServer}=require('../server.cjs');
test('static file paths stay in the installed UI folder',()=>{
  const root=path.resolve('ui');
  assert.equal(safeFile(root,'/'),path.join(root,'index.html'));
  assert.equal(safeFile(root,'/%2e%2e%2fsecret.env'),null);
  assert.equal(safeFile(root,'/%5csecret'),null);
  assert.equal(safeFile(root,'/%00'),null);
});

test('live analysis websocket reaches the packaged renderer and closes cleanly',async()=>{
  const backend=http.createServer();
  const sockets=new Set();
  backend.on('upgrade',(request,socket)=>{
    sockets.add(socket);socket.on('close',()=>sockets.delete(socket));
    const accept=crypto.createHash('sha1').update(request.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n');
    const message=Buffer.from('{"cameraStatus":"online"}');
    socket.write(Buffer.concat([Buffer.from([0x81,message.length]),message]));
  });
  await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve));
  const app=await createStudioServer(path.resolve('ui'),()=>`http://127.0.0.1:${backend.address().port}`);
  try {
    const client=new WebSocket(app.url.replace('http:','ws:')+'/api/live');
    const data=await new Promise((resolve,reject)=>{
      client.addEventListener('message',event=>resolve(JSON.parse(event.data)),{once:true});
      client.addEventListener('error',reject,{once:true});
    });
    assert.equal(data.cameraStatus,'online');
    const closed=new Promise(resolve=>client.addEventListener('close',resolve,{once:true}));
    await app.close();await closed;
  } finally {
    for(const socket of sockets)socket.destroy();
    await new Promise(resolve=>backend.close(resolve));
  }
});
test('packaged UI, API relay and shutdown work without a Next server',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'tennis-studio-'));
  fs.writeFileSync(path.join(root,'index.html'),'<h1>Tennis Vision Studio</h1>');
  const backend=http.createServer((_req,res)=>{res.setHeader('Content-Type','application/json');res.end('{"status":"ok"}');});
  await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve));
  const app=await createStudioServer(root,()=>`http://127.0.0.1:${backend.address().port}`);
  try {
    assert.match(await fetch(app.url).then(r=>r.text()),/Tennis Vision Studio/);
    assert.deepEqual(await fetch(app.url+'/api/health').then(r=>r.json()),{status:'ok'});
    assert.equal((await fetch(app.url+'/%2e%2e%2fsecret.env')).status,404);
    assert.equal((await fetch(app.url,{method:'POST'})).status,405);
    const foreignHost = await new Promise((resolve,reject)=>{
      http.get(app.url,{headers:{host:'evil.example'}},response=>{
        response.resume();resolve(response.statusCode);
      }).on('error',reject);
    });
    assert.equal(foreignHost,403);
  } finally {await app.close();await new Promise(resolve=>backend.close(resolve));fs.rmSync(root,{recursive:true});}
});

test('MJPEG fallback streams immediately through the random-port desktop relay',async()=>{
  let received;
  const backend=http.createServer((req,res)=>{
    received=req.url;
    res.writeHead(200,{'Content-Type':'multipart/x-mixed-replace; boundary=frame'});
    res.write('--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\nABC\r\n');
  });
  const sockets=new Set();
  backend.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
  await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve));
  const app=await createStudioServer(path.resolve('ui'),()=>`http://127.0.0.1:${backend.address().port}`);
  const abort=new AbortController();
  try {
    const response=await fetch(app.url+'/api/preview?cameraId=second',{signal:abort.signal});
    assert.equal(response.status,200);
    assert.match(response.headers.get('content-type'),/multipart/);
    const chunk=await response.body.getReader().read();
    assert.match(Buffer.from(chunk.value).toString(),/ABC/);
    assert.equal(received,'/api/preview?cameraId=second');
  } finally {
    abort.abort();await app.close();
    for(const socket of sockets)socket.destroy();
    await new Promise(resolve=>backend.close(resolve));
  }
});
