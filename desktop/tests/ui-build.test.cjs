'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const os=require('node:os');const path=require('node:path');
const {stampUi,verifyUi}=require('../verify-ui.cjs');
function fixture(t) {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'tennis-ui-test-'));
  t.after(()=>{const relative=path.relative(os.tmpdir(),root);if(/^tennis-ui-test-[^\\/]+$/.test(relative))fs.rmSync(root,{recursive:true,force:true});});
  const app=path.join(root,'frontend','app'),out=path.join(root,'frontend','out');
  fs.mkdirSync(app,{recursive:true});fs.mkdirSync(out,{recursive:true});
  const source=path.join(app,'page.tsx');fs.writeFileSync(source,'current interface');
  fs.utimesSync(source,1,1);fs.writeFileSync(path.join(out,'index.html'),'<html>compiled interface</html>');
  return {root,out,source};
}
test('a source change prevents shipping an older compiled panel',t=>{
  const {root,source}=fixture(t);stampUi(root);assert.ok(verifyUi(root).bundleSha256);
  fs.writeFileSync(source,'new controls');assert.throws(()=>verifyUi(root),/código atual/);
});
test('an incomplete or altered export cannot be packaged',t=>{
  const {root,out}=fixture(t);stampUi(root);fs.writeFileSync(path.join(out,'index.html'),'old or corrupted page');
  assert.throws(()=>verifyUi(root),/alterados ou estão incompletos/);
});
test('a server build cannot approve an old static export',t=>{
  const {root,out}=fixture(t);fs.utimesSync(path.join(out,'index.html'),0,0);
  assert.throws(()=>stampUi(root),/TV_DESKTOP_BUILD=true/);
});
