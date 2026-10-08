const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const crypto=require('node:crypto');
const {createMobilePanel}=require('../mobile-panel.cjs');
const token='a'.repeat(64);
async function setup(t) {
  const upstream=http.createServer((req,res)=>{
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify({method:req.method,path:req.url,origin:req.headers.origin,cookie:req.headers.cookie || null}));
  });
  const sockets=new Set();
  upstream.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});
  upstream.on('upgrade',(req,socket)=>{
    const accept=crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n');
    const data=Buffer.from(JSON.stringify({path:req.url,cookie:req.headers.cookie || null}));
    socket.write(Buffer.concat([Buffer.from([0x81,data.length]),data]));
  });
  await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));
  const target='http://127.0.0.1:'+upstream.address().port;
  const panel=await createMobilePanel({target,token,port:0,host:'127.0.0.1'});
  t.after(async()=>{await panel.close();for(const s of sockets)s.destroy();await new Promise(resolve=>upstream.close(resolve));});
  return {url:'http://127.0.0.1:'+panel.port,target,panel};
}
test('phone is denied until it pairs; secret is exchanged for an HttpOnly cookie without URL leakage',async t=>{
  const {url}=await setup(t);
  assert.equal((await fetch(url+'/api/cameras')).status,401);
  assert.equal((await fetch(url+'/mobile/pair?token='+token)).status,401);
  assert.equal((await fetch(url+'/mobile/pair',{method:'POST',headers:{Authorization:'Bearer wrong'}})).status,401);
  const paired=await fetch(url+'/mobile/pair',{method:'POST',headers:{Authorization:'Bearer '+token},redirect:'manual'});
  assert.equal(paired.status,303);
  assert.equal(paired.headers.get('location'),'/?phone=1');
  assert.match(paired.headers.get('set-cookie'),/HttpOnly; SameSite=Strict/);
});
test('paired area edits and video use the desktop relay, stripping credentials and normalizing origin',async t=>{
  const {url,target}=await setup(t);
  const response=await fetch(url+'/api/cameras/one/capture-region',{method:'POST',headers:{Cookie:'tv_panel='+token,Origin:url,'Content-Type':'application/json'},body:'{}'});
  assert.equal(response.status,200);
  assert.deepEqual(await response.json(),{method:'POST',path:'/api/cameras/one/capture-region',origin:target,cookie:null});
  assert.equal((await fetch(url+'/api/preview?cameraId=one',{headers:{Cookie:'tv_panel='+token}})).status,200);
});
test('host spoofing, cross-origin requests and malformed credentials never reach the PC',async t=>{
  const {url}=await setup(t);
  for(const headers of [
    {Cookie:'tv_panel='+token,Origin:'https://foreign.example'},
    {Cookie:'tv_panel='+token,Host:'foreign.example'},
    {Cookie:'tv_panel='+token,'Sec-Fetch-Site':'cross-site'},
  ]) {
    const status=await new Promise((resolve,reject)=>http.get(url+'/api/cameras',{headers},response=>{response.resume();resolve(response.statusCode);}).on('error',reject));
    assert.equal(status,403,JSON.stringify(headers));
  }
  assert.equal((await fetch(url+'/api/cameras',{headers:{Cookie:'tv_panel='+'z'.repeat(64)}})).status,401);
});
test('paired live websocket forwards metadata and the bridge closes connected clients',async t=>{
  const {url,panel}=await setup(t);
  const response=await new Promise((resolve,reject)=>{
    const req=http.request(url+'/api/live',{headers:{Connection:'Upgrade',Upgrade:'websocket','Sec-WebSocket-Key':crypto.randomBytes(16).toString('base64'),'Sec-WebSocket-Version':'13',Cookie:'tv_panel='+token,Origin:url}});
    req.on('upgrade',(res,socket,head)=>{if(head.length)resolve({socket,head});else socket.once('data',head=>resolve({socket,head}));});
    req.on('error',reject);req.end();
  });
  assert.match(response.head.toString(),/"path":"\/api\/live","cookie":null/);
  response.socket.destroy();
});
test('an unauthenticated websocket is closed before upgrading',async t=>{
  const {url}=await setup(t);
  const outcome=await new Promise(resolve=>{
    const req=http.request(url+'/api/live',{headers:{Connection:'Upgrade',Upgrade:'websocket','Sec-WebSocket-Key':crypto.randomBytes(16).toString('base64'),'Sec-WebSocket-Version':'13'}});
    req.on('upgrade',(_r,s)=>{s.destroy();resolve('upgraded');});req.on('error',()=>resolve('denied'));req.end();
  });
  assert.equal(outcome,'denied');
});

