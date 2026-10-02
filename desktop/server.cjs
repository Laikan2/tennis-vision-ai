const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const proxy = require('http-proxy');

const mime = { '.html':'text/html; charset=utf-8', '.js':'text/javascript; charset=utf-8',
  '.css':'text/css; charset=utf-8', '.json':'application/json', '.svg':'image/svg+xml',
  '.png':'image/png', '.jpg':'image/jpeg', '.woff2':'font/woff2', '.mp3':'audio/mpeg', '.wav':'audio/wav' };

function safeFile(root, url) {
  let requested;
  try { requested = decodeURIComponent(new URL(url, 'http://localhost').pathname); }
  catch { return null; }
  if (requested.includes('\0') || requested.includes('\\')) return null;
  const candidate = path.resolve(root, '.' + (requested === '/' ? '/index.html' : requested));
  const relative = path.relative(root, candidate);
  if (relative.startsWith('..') || path.isAbsolute(relative)) return null;
  return candidate;
}

async function createStudioServer(root, getBackend) {
  const relay = proxy.createProxyServer({ ws:true, changeOrigin:true });
  const upgrades = new Set();
  relay.on('error', (_error, _req, response) => {
    if (response?.writeHead && !response.headersSent) {
      response.writeHead(503, { 'Content-Type':'application/json' });
      response.end(JSON.stringify({detail:'O motor de análise está iniciando. Aguarde alguns segundos.'}));
    } else response?.destroy?.();
  });
  const server = http.createServer((request, response) => {
    if (request.headers.host !== `127.0.0.1:${server.address().port}`) { response.writeHead(403); response.end(); return; }
    if (request.url.startsWith('/api/')) {
      const target = getBackend();
      if (!target) { response.writeHead(503); response.end(); return; }
      relay.web(request, response, {target}); return;
    }
    if (!['GET','HEAD'].includes(request.method)) { response.writeHead(405); response.end(); return; }
    const file = safeFile(root, request.url);
    if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) { response.writeHead(404); response.end(); return; }
    response.writeHead(200, { 'Content-Type':mime[path.extname(file)] || 'application/octet-stream',
      'X-Content-Type-Options':'nosniff', 'Cache-Control':file.includes(`${path.sep}_next${path.sep}`) ? 'public, max-age=31536000, immutable' : 'no-cache' });
    if (request.method === 'HEAD') { response.end(); return; }
    fs.createReadStream(file).on('error',()=>response.destroy()).pipe(response);
  });
  server.on('upgrade', (request, socket, head) => {
    if (request.headers.host !== `127.0.0.1:${server.address().port}` || !request.url.startsWith('/api/') || !getBackend()) {
      socket.destroy(); return;
    }
    upgrades.add(socket); socket.on('close',()=>upgrades.delete(socket));
    relay.ws(request, socket, head, {target:getBackend()});
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  return { url:`http://127.0.0.1:${server.address().port}`, server,
    close:async()=>{ for(const socket of upgrades) socket.destroy(); relay.close(); server.closeAllConnections(); await new Promise(resolve=>server.close(resolve)); } };
}
module.exports = { safeFile, createStudioServer };
