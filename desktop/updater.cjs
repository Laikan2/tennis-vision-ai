'use strict';
const fs = require('node:fs');
const path = require('node:path');

function loadUpdateRepository(resourcesPath) {
  // electron-builder intentionally removes `build` from the packaged
  // package.json. The installed app must use the generated app-update.yml.
  const updaterRoot = path.dirname(require.resolve('electron-updater/package.json'));
  const yaml = require(require.resolve('js-yaml', {paths:[updaterRoot]}));
  const config = yaml.load(fs.readFileSync(path.join(resourcesPath,'app-update.yml'),'utf8'));
  if (config?.provider !== 'github' || !/^[a-z0-9-]+$/i.test(config.owner || '') || !/^[a-z0-9_.-]+$/i.test(config.repo || ''))
    throw new Error('Repositório de atualizações inválido.');
  return {owner:config.owner, repo:config.repo};
}

// Keep update state separate from camera/analysis state. A failed GitHub request
// must never interrupt the engine or prevent the studio from opening offline.
function createStudioUpdater({ updater, isPackaged, version, repository, onStatus = () => {},
  log = () => {}, isSessionActive = async () => false, prepareInstall = async () => {},
  setTimer = setTimeout, clearTimer = clearTimeout, setRepeat = setInterval, clearRepeat = clearInterval }) {
  const configured = Boolean(repository?.owner && repository?.repo);
  let state = { status: !isPackaged ? 'development' : configured ? 'idle' : 'unconfigured',
    currentVersion: version, availableVersion: null, percent: 0, transferred: 0, total: 0,
    checkedAt: null, message: '', repository: configured ? `https://github.com/${repository.owner}/${repository.repo}` : null };
  let operation, installing = false, disposed = false, firstTimer, repeatTimer, progressAt = 0;
  const listeners = [];
  const snapshot = () => ({ ...state });
  const emit = patch => {
    if (disposed) return;
    state = { ...state, ...patch };
    onStatus(snapshot());
  };
  const reportError = error => {
    // No raw updater URLs/headers in the renderer or logs (private feeds can
    // contain credentials). Log only a bounded machine-readable error code.
    const code = String(error?.code || 'NETWORK').replace(/[^A-Z0-9_]/gi, '').slice(0, 80);
    log(`Atualização: ${code}`);
    emit({ status: 'error', message: code === 'ERR_UPDATER_CHECKSUM_MISMATCH' || code === 'ERR_UPDATER_INVALID_SIGNATURE'
      ? 'O arquivo não passou na verificação. A atualização não foi instalada; tente novamente.'
      : 'Não foi possível consultar ou baixar a atualização. Verifique a conexão e tente novamente.' });
  };
  const listen = (event, callback) => { updater.on(event, callback); listeners.push([event, callback]); };
  updater.autoDownload = false;
  // electron-updater's quit hook runs after main.cjs has stopped local services.
  updater.autoInstallOnAppQuit = true;
  updater.autoRunAppAfterInstall = false;
  updater.allowPrerelease = false;
  updater.allowDowngrade = false;
  updater.disableWebInstaller = true;
  updater.logger = { info: () => {}, warn: () => {}, debug: () => {}, error: () => {} };

  listen('checking-for-update', () => emit({ status: 'checking', message: '' }));
  listen('update-not-available', () => emit({ status: 'current', availableVersion: null, percent: 0, message: '', checkedAt: new Date().toISOString() }));
  listen('update-available', value => emit({ status: 'available', availableVersion: value.version, percent: 0, message: '', checkedAt: new Date().toISOString() }));
  listen('download-progress', value => {
    const percent = Math.max(0, Math.min(100, Number(value.percent) || 0));
    if (Date.now() - progressAt < 400 && percent < 100) return;
    progressAt = Date.now();
    emit({ status: 'downloading', percent, transferred: Math.max(0, Number(value.transferred) || 0), total: Math.max(0, Number(value.total) || 0) });
  });
  listen('update-downloaded', value => {
    log(`Atualização ${value.version} verificada e pronta.`);
    emit({ status: 'downloaded', availableVersion: value.version, percent: 100, message: 'Será instalada automaticamente ao fechar o programa.' });
  });
  listen('error', reportError);

  async function downloadWhenIdle() {
    if (disposed || state.status !== 'available') return;
    // Do not compete with a live camera for bandwidth/disk. A status failure
    // also defers download until the engine can confirm the session is idle.
    if (await isSessionActive()) {
      emit({ message: 'O download começa quando a sessão terminar.' });
      return;
    }
    if (disposed) return;
    emit({ status: 'downloading', message: '' });
    await updater.downloadUpdate();
  }

  function check() {
    if (disposed || !isPackaged || !configured || installing || state.status === 'downloaded') return Promise.resolve(snapshot());
    if (operation) return operation;
    operation = (async () => {
      try {
        if (state.status === 'available') await downloadWhenIdle();
        else {
          await updater.checkForUpdates();
          await downloadWhenIdle();
        }
      } catch (error) { reportError(error); }
      return snapshot();
    })().finally(() => { operation = undefined; });
    return operation;
  }

  async function install() {
    if (disposed || installing) return snapshot();
    if (state.status !== 'downloaded') throw new Error('Aguarde o download da atualização.');
    installing = true;
    try {
      if (await isSessionActive()) throw new Error('Encerre a sessão antes de reiniciar para atualizar. A atualização também será instalada ao fechar o programa.');
      emit({ status: 'installing', message: 'Salvando e encerrando os serviços locais…' });
      await prepareInstall();
      updater.quitAndInstall(true, true);
    } catch (error) {
      emit({ status: 'downloaded', message: 'Será instalada automaticamente ao fechar o programa.' });
      throw error;
    } finally { installing = false; }
    return snapshot();
  }

  function start() {
    if (disposed || firstTimer || repeatTimer || !isPackaged || !configured) return;
    firstTimer = setTimer(() => { firstTimer = undefined; void check(); }, 15000);
    firstTimer?.unref?.();
    // Deferred downloads are retried promptly; ordinary checks every four hours.
    let nextCheck = Date.now() + 4 * 60 * 60 * 1000;
    repeatTimer = setRepeat(() => {
      if (state.status === 'available' || Date.now() >= nextCheck) {
        nextCheck = Date.now() + 4 * 60 * 60 * 1000;
        void check();
      }
    }, 60000);
    repeatTimer?.unref?.();
  }
  function dispose() {
    disposed = true;
    if (firstTimer) clearTimer(firstTimer);
    if (repeatTimer) clearRepeat(repeatTimer);
    for (const [event, callback] of listeners) updater.removeListener(event, callback);
  }
  return { snapshot, check, install, start, dispose };
}
module.exports = { createStudioUpdater, loadUpdateRepository };
