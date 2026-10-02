// NSIS 3.0 uses MAX_PATH for !include files. Deep pnpm workspaces can exceed
// it even when the app itself fits. Keep only compiler templates/vendor assets
// in a short, private temporary directory; no global or dependency files change.
const fs=require('node:fs');const os=require('node:os');const path=require('node:path');
const electronRoot=path.dirname(require.resolve('electron-builder/package.json'));
const builderRoot=path.dirname(require.resolve('app-builder-lib/package.json',{paths:[electronRoot]}));
const compilerAssets=fs.mkdtempSync(path.join(os.tmpdir(),'tennis-nsis-'));
const pathManager=require(path.join(builderRoot,'out/util/pathManager.js'));
fs.cpSync(path.join(builderRoot,'templates'),path.join(compilerAssets,'templates'),{recursive:true});
pathManager.getTemplatePath=file=>path.join(compilerAssets,'templates',file);
if(fs.existsSync(path.join(builderRoot,'vendor'))) {
  fs.cpSync(path.join(builderRoot,'vendor'),path.join(compilerAssets,'vendor'),{recursive:true});
  pathManager.getVendorPath=file=>path.join(compilerAssets,'vendor',file || '');
}
const {build,Platform,Arch}=require('electron-builder');
build({projectDir:__dirname,publish:'never',targets:Platform.WINDOWS.createTarget(['nsis'],Arch.x64),
  ...(process.argv.includes('--prepackaged')?{prepackaged:path.join(__dirname,'releases/win-unpacked')}:{})})
  .then(files=>{for(const file of files)process.stdout.write(file+'\n');})
  .catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;})
  .finally(()=>{
    const relative=path.relative(path.resolve(os.tmpdir()),path.resolve(compilerAssets));
    if(!relative.startsWith('..')&&!path.isAbsolute(relative)&&/^tennis-nsis-[^\\/]+$/.test(relative))
      fs.rmSync(compilerAssets,{recursive:true,force:true});
  });
