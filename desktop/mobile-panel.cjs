// A paired phone reaches the same UI/API as the desktop. The engine stays loopback-only.
const http = require('node:http');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const proxy = require('http-proxy');

function loadPanelToken(state) {
  const file = path.join(state, 'mobile-panel.key');
  if (!fs.existsSync(file)) fs.writeFileSync(file, crypto.randomBytes(32).toString('hex'), {mode:0o600, flag:'wx'});
  const token = fs.readFileSync(file, 'utf8').trim();
  if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('Pareamento do painel inválido. Preserve mobile-panel.key e confira os registros.');
  return token;
}
async function createMobilePanel({target, token, port=8894, host='0.0.0.0'}) {
  if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('Invalid panel token');
  const upstream = new URL(target);
  if (upstream.hostname !== '127.0.0.1' || upstream.protocol !== 'http:') throw new Error('Invalid local target');
  const relay = proxy.createProxyServer({target, ws:true, changeOrigin:true});
  const sockets = new Set();
  const cookieName = 'tv_panel';
  function same(value) {
    return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) &&
      crypto.timingSafeEqual(Buffer.from(value), Buffer.from(token));
  }
  function session(req) {
    return (req.headers.cookie || '').split(';').some(item => {
      const [name, value] = item.trim().split('='); return name === cookieName && same(value);
    });
  }
  function validOrigin(req) {
    const allowed = new Set(['127.0.0.1', 'localhost']);
    for (const items of Object.values(os.networkInterfaces())) for (const item of items || []) if (item.family === 'IPv4') allowed.add(item.address);
    let address;
    try { address = new URL('http://' + req.headers.host); } catch { return false; }
    return allowed.has(address.hostname) && address.port === String(server.address().port) &&
      !address.username && !address.password && address.pathname === '/' &&
      (!req.headers.origin || req.headers.origin === address.origin) && req.headers['sec-fetch-site'] !== 'cross-site';
  }
  function forward(req) {
    // Only relative paths can reach the fixed loopback upstream.
    if (!req.url.startsWith('/') || req.url.startsWith('//') || /[\\\r\n]/.test(req.url)) return false;
    req.headers.host = upstream.host;
    req.headers.origin = upstream.origin;
    req.headers['sec-fetch-site'] = 'same-origin';
    delete req.headers.authorization;
    delete req.headers.cookie;
    return true;
  }
  relay.on('error', (_error, _req, res) => {
    if (res?.writeHead && !res.headersSent) { res.writeHead(503, {'Content-Type':'application/json'}); res.end('{"detail":"O PC está reconectando. Tente novamente."}'); }
    else res?.destroy();
  });
  const server = http.createServer((req, res) => {
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    if (!validOrigin(req)) { res.writeHead(403); res.end(); return; }
    if (req.url === '/mobile/pair' && req.method === 'POST') {
      if (!same((req.headers.authorization || '').replace(/^Bearer /, ''))) { res.writeHead(401); res.end(); return; }
      res.writeHead(303, {'Set-Cookie':`${cookieName}=${token}; HttpOnly; SameSite=Strict; Path=/`, Location:'/?phone=1'});
      res.end(); return;
    }
    if (!session(req)) { res.writeHead(401, {'Content-Type':'text/plain; charset=utf-8'}); res.end('Leia o QR do Windows no aplicativo Tennis Vision para conectar o painel.'); return; }
    if (!forward(req)) { res.writeHead(400); res.end(); return; }
    relay.web(req, res);
  });
  server.on('connection', socket => { sockets.add(socket); socket.on('close',()=>sockets.delete(socket)); });
  server.on('upgrade', (req, socket, head) => {
    if (!validOrigin(req) || !session(req) || !req.url.startsWith('/api/') || !forward(req)) { socket.destroy(); return; }
    relay.ws(req, socket, head);
  });
  server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error',reject); server.listen(port, host, resolve); });
  return {port:server.address().port, close:async()=>{
    for (const socket of sockets) socket.destroy();
    relay.close();
    await new Promise(resolve=>server.close(resolve));
  }};
}
module.exports = {createMobilePanel, loadPanelToken};
