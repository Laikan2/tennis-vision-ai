'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const updaterRoot = path.dirname(require.resolve('electron-updater/package.json'));
const yaml = require(require.resolve('js-yaml',{paths:[updaterRoot]}));

function verifyRelease(directory, expectedVersion, repository) {
  const manifest = yaml.load(fs.readFileSync(path.join(directory,'latest.yml'),'utf8'));
  if (!/^\d+\.\d+\.\d+$/.test(expectedVersion) || manifest.version!==expectedVersion)
    throw new Error('A versão do latest.yml não corresponde à versão estável do programa.');
  const expectedName = `Tennis-Vision-Studio-${expectedVersion}-Windows-x64.exe`;
  const file = manifest.files?.find(file=>file.url===expectedName);
  if (!file || manifest.path!==expectedName) throw new Error('O manifesto precisa apontar para o instalador x64 desta versão.');
  const installer = path.join(directory,expectedName);
  const bytes = fs.readFileSync(installer);
  const digest = createHash('sha512').update(bytes).digest('base64');
  if (file.sha512!==digest || manifest.sha512!==digest || (file.size!==undefined && file.size!==bytes.length))
    throw new Error('O instalador e o hash SHA-512 do manifesto não correspondem.');
  if (!fs.existsSync(installer+'.blockmap')) throw new Error('Falta o blockmap para o download incremental.');
  if (repository) {
    const config = yaml.load(fs.readFileSync(path.join(directory,'win-unpacked','resources','app-update.yml'),'utf8'));
    if (config.provider!=='github' || config.owner!==repository.owner || config.repo!==repository.repo || !config.updaterCacheDirName)
      throw new Error('O instalador foi compilado para um repositório de atualizações diferente.');
  }
  return {version:expectedVersion, installer, bytes:bytes.length, sha512:digest, files:[installer,installer+'.blockmap',path.join(directory,'latest.yml')]};
}
if (require.main===module) {
  try {
    const pkg = require('./package.json');
    require('./verify-ui.cjs').verifyUi(path.dirname(__dirname),path.join(__dirname,'releases','win-unpacked','resources','ui'));
    console.log(JSON.stringify(verifyRelease(path.join(__dirname,'releases'),pkg.version,pkg.build.publish)));
  } catch (error) {console.error(error.message);process.exitCode=1;}
}
module.exports = {verifyRelease};
