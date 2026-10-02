const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {verifyRelease} = require('../verify-release.cjs');
function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),'tennis-release-test-'));
  t.after(()=>{if(path.dirname(dir)===os.tmpdir() && path.basename(dir).startsWith('tennis-release-test-')) fs.rmSync(dir,{recursive:true,force:true});});
  const name = 'Tennis-Vision-Studio-0.6.0-Windows-x64.exe';
  const bytes = Buffer.from('test installer');
  const digest = createHash('sha512').update(bytes).digest('base64');
  fs.writeFileSync(path.join(dir,name),bytes);fs.writeFileSync(path.join(dir,name+'.blockmap'),'map');
  fs.writeFileSync(path.join(dir,'latest.yml'),`version: 0.6.0\npath: ${name}\nsha512: ${digest}\nfiles:\n  - url: ${name}\n    sha512: ${digest}\n    size: ${bytes.length}\n`);
  fs.mkdirSync(path.join(dir,'win-unpacked','resources'),{recursive:true});
  fs.writeFileSync(path.join(dir,'win-unpacked','resources','app-update.yml'),'provider: github\nowner: example\nrepo: releases\nupdaterCacheDirName: tennis-updater\n');
  return {dir,name};
}
test('release publication requires an intact installer, matching version, blockmap and feed', t=>{
  const {dir} = fixture(t); const result = verifyRelease(dir,'0.6.0',{owner:'example',repo:'releases'});
  assert.equal(result.files.length,3);assert.equal(result.bytes,14);
});
test('a corrupt installer is rejected before publication',t=>{
  const {dir,name}=fixture(t);fs.appendFileSync(path.join(dir,name),'changed');
  assert.throws(()=>verifyRelease(dir,'0.6.0'),/SHA-512/);
});
test('a stale manifest or wrong GitHub feed cannot be published',t=>{
  const {dir}=fixture(t);
  assert.throws(()=>verifyRelease(dir,'0.7.0'),/versão/);
  assert.throws(()=>verifyRelease(dir,'0.6.0',{owner:'example',repo:'wrong'}),/repositório/);
});
