'use strict';
const fs=require('node:fs');
const path=require('node:path');
const {createHash}=require('node:crypto');
const proofName='ui-build.json';

function files(root) {
  if(!fs.existsSync(root))return [];
  return fs.readdirSync(root,{withFileTypes:true}).flatMap(item=>
    item.isDirectory()?files(path.join(root,item.name)):item.isFile()?[path.join(root,item.name)]:[]).sort();
}
function inputs(root) {
  const frontend=path.join(root,'frontend');
  return ['app','components','lib','public'].flatMap(name=>files(path.join(frontend,name)))
    .concat(fs.readdirSync(frontend).filter(name=>/^(next\.config\.|postcss\.config\.|package\.json$|pnpm-lock\.yaml$|tsconfig\.json$)/.test(name))
      .map(name=>path.join(frontend,name))).sort();
}
function digest(root,list) {
  const hash=createHash('sha256');
  for(const file of list) {
    const bytes=fs.readFileSync(file);
    hash.update(path.relative(root,file).replaceAll('\\','/')+'\0'+bytes.length+'\0');hash.update(bytes);
  }
  return hash.digest('hex');
}
function stampUi(root) {
  const directory=path.join(root,'frontend','out'),index=path.join(directory,'index.html'),source=inputs(root);
  if(!source.length || !fs.existsSync(index) || fs.statSync(index).mtimeMs<Math.max(...source.map(file=>fs.statSync(file).mtimeMs)))
    throw new Error('A exportação do painel está desatualizada. Compile com TV_DESKTOP_BUILD=true antes de empacotar.');
  const proof={schema:1,inputSha256:digest(root,source),bundleSha256:digest(directory,files(directory).filter(file=>path.basename(file)!==proofName))};
  fs.writeFileSync(path.join(directory,proofName),JSON.stringify(proof,null,2));
  return proof;
}
function verifyUi(root,directory=path.join(root,'frontend','out')) {
  let proof;
  try {proof=JSON.parse(fs.readFileSync(path.join(directory,proofName),'utf8'));}
  catch {throw new Error('Falta a verificação da interface compilada. Execute scripts/build-desktop.ps1.');}
  if(proof.schema!==1 || proof.inputSha256!==digest(root,inputs(root)))
    throw new Error('O painel exportado não corresponde ao código atual. Execute scripts/build-desktop.ps1.');
  if(proof.bundleSha256!==digest(directory,files(directory).filter(file=>path.basename(file)!==proofName)))
    throw new Error('Os arquivos do painel compilado foram alterados ou estão incompletos. Recompile a interface.');
  return proof;
}
if(require.main===module) {
  try {const root=path.dirname(__dirname);console.log(JSON.stringify(process.argv.includes('--stamp')?stampUi(root):verifyUi(root)));}
  catch(error) {console.error(error.message);process.exitCode=1;}
}
module.exports={stampUi,verifyUi};
