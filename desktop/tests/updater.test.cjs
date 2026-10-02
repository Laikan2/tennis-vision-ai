const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { createStudioUpdater } = require('../updater.cjs');

function fixture(options = {}) {
  const events = [];
  const updater = new EventEmitter();
  updater.checkForUpdates = async () => { events.push('check'); updater.emit('checking-for-update'); updater.emit('update-available', {version:'0.7.0'}); };
  updater.downloadUpdate = async () => { events.push('download'); updater.emit('download-progress', {percent:50, transferred:500, total:1000}); updater.emit('update-downloaded', {version:'0.7.0'}); };
  updater.quitAndInstall = (silent, restart) => events.push(['install', silent, restart]);
  const updates = createStudioUpdater({updater, isPackaged:true, version:'0.6.0',
    repository:{owner:'example',repo:'releases'}, prepareInstall:async()=>events.push('shutdown'), ...options});
  return {updater, updates, events};
}
test('stable update is downloaded, verified and installed only after services stop', async () => {
  const {updater, updates, events} = fixture();
  const state = await updates.check();
  assert.equal(state.status, 'downloaded'); assert.equal(state.availableVersion, '0.7.0');
  assert.equal(updater.autoDownload, false); assert.equal(updater.autoInstallOnAppQuit, true);
  assert.equal(updater.allowPrerelease, false); assert.equal(updater.allowDowngrade, false);
  await updates.install();
  assert.deepEqual(events, ['check', 'download', 'shutdown', ['install',true,true]]);
});
test('ordinary closing installs silently without reopening; manual install requests restart', () => {
  const {updater} = fixture(); assert.equal(updater.autoRunAppAfterInstall,false);
});
test('live sessions defer download, then download without checking GitHub twice', async () => {
  let active = true;
  const {updates, events} = fixture({isSessionActive:async()=>active});
  assert.equal((await updates.check()).status,'available'); assert.deepEqual(events,['check']);
  active = false;
  assert.equal((await updates.check()).status,'downloaded'); assert.deepEqual(events,['check','download']);
});
test('a live session blocks a manual restart and preserves the downloaded update', async () => {
  let active = false;
  const {updates, events} = fixture({isSessionActive:async()=>active});
  await updates.check(); active = true;
  await assert.rejects(updates.install(),/Encerre a sessão/);
  assert.equal(updates.snapshot().status,'downloaded'); assert.deepEqual(events,['check','download']);
});
test('concurrent checks share one network request and one download', async () => {
  const {updater, updates, events} = fixture();
  let resolve;
  updater.checkForUpdates = () => {events.push('check'); return new Promise(r=>{resolve=r;});};
  const first = updates.check(); const second = updates.check();
  assert.equal(first,second); updater.emit('update-available',{version:'0.7.0'}); resolve();
  await Promise.all([first,second]); assert.deepEqual(events,['check','download']);
});
test('an up-to-date installation does not download or restart', async () => {
  const {updater, updates, events} = fixture();
  updater.checkForUpdates = async()=>{events.push('check');updater.emit('update-not-available',{version:'0.6.0'});};
  assert.equal((await updates.check()).status,'current'); assert.deepEqual(events,['check']);
  await assert.rejects(updates.install(),/Aguarde o download/);
});
test('offline requests remain retryable without leaking headers or token URLs', async () => {
  const logs = [];
  const {updater, updates} = fixture({log:v=>logs.push(v)});
  updater.checkForUpdates = async()=>{throw Object.assign(new Error('https://secret:TOKEN@github.com Authorization: TOKEN'),{code:'ECONNRESET'});};
  assert.equal((await updates.check()).status,'error');
  assert.ok(!JSON.stringify([updates.snapshot(),logs]).includes('TOKEN'));
  updater.checkForUpdates = async()=>updater.emit('update-not-available',{version:'0.6.0'});
  assert.equal((await updates.check()).status,'current');
});
test('a checksum failure never marks a corrupt installer as ready', async () => {
  const {updater, updates, events} = fixture();
  updater.downloadUpdate = async()=>{throw Object.assign(new Error('corrupt'),{code:'ERR_UPDATER_CHECKSUM_MISMATCH'});};
  assert.equal((await updates.check()).status,'error');
  await assert.rejects(updates.install(),/Aguarde/); assert.deepEqual(events,['check']);
});
test('source runs and missing feeds never contact GitHub', async () => {
  for (const options of [{isPackaged:false},{repository:null}]) {
    const {updates, events} = fixture(options); await updates.check();updates.start();
    assert.deepEqual(events,[]);
  }
});
test('repeated manual restart requests only prepare/install once', async () => {
  const {updates, events} = fixture(); await updates.check();
  await Promise.all([updates.install(),updates.install()]);
  assert.equal(events.filter(v=>v==='shutdown').length,1);
  assert.equal(events.filter(Array.isArray).length,1);
});
test('service shutdown errors keep the installer ready and do not launch it', async () => {
  const {updates, events} = fixture({prepareInstall:async()=>{throw new Error('shutdown failed');}});
  await updates.check(); await assert.rejects(updates.install(),/shutdown failed/);
  assert.equal(updates.snapshot().status,'downloaded');assert.deepEqual(events,['check','download']);
});
test('progress is bounded and disposal cancels timers and subscriptions', () => {
  const cancelled = [];
  const {updater, updates} = fixture({setTimer:()=>12,setRepeat:()=>34,clearTimer:v=>cancelled.push(v),clearRepeat:v=>cancelled.push(v)});
  updates.start(); updates.start();
  updater.emit('download-progress',{percent:110,transferred:-10,total:-2});
  assert.equal(updates.snapshot().percent,100);assert.equal(updates.snapshot().total,0);
  updates.dispose();assert.deepEqual(cancelled,[12,34]);assert.equal(updater.listenerCount('update-downloaded'),0);
});
