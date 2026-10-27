"""Publish a coordinated Android/iPhone feature release without changing Windows latest.

Both real installable formats and a successful native iOS CI run are required.
The iPhone remains unsigned for personal AltStore signing. No signing secrets.
"""
import argparse,hashlib,json,plistlib,runpy,shutil,subprocess,tempfile,time,urllib.request
from pathlib import Path
from zipfile import ZipFile

ROOT=Path(__file__).resolve().parents[1]
REPO='Laikan2/tennis-vision-releases'
VERIFY=runpy.run_path(str(ROOT/'scripts/package-ios-ipa.py'))['verify_ipa']
ANDROID_CERT='faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9'

def validate(ipa,apk,android_metadata,contract):
    ios=VERIFY(ipa)
    version=contract['version']
    if set(contract.get('platforms',[]))!={'android','ios'} or ios['version']!=version or android_metadata['version']!=version:
        raise ValueError('Both apps must match the shared feature release')
    if android_metadata.get('certificateSha256')!=ANDROID_CERT:
        raise ValueError('Android update signing identity changed')
    if apk.name!=android_metadata['filename'] or apk.stat().st_size!=android_metadata['bytes'] or hashlib.sha256(apk.read_bytes()).hexdigest()!=android_metadata['sha256']:
        raise ValueError('Android package does not match the verified metadata')
    with ZipFile(ipa) as z:
        bundled=json.loads(z.read('Payload/TennisVisionCamera.app/MobileRelease.json'))
        if bundled!=contract:raise ValueError('iPhone feature contract is stale')
        info=plistlib.loads(z.read('Payload/TennisVisionCamera.app/Info.plist'))
    return {'version':version,'baseline':contract['baseline'],'ios':ios|{'build':info['CFBundleVersion']},'android':android_metadata}

def publish(args):
    def gh(*items):
        r=subprocess.run([str(args.gh),*map(str,items)],text=True,encoding='utf-8',capture_output=True)
        if r.returncode:raise RuntimeError(r.stderr)
        return r.stdout
    def api(path):return json.loads(gh('api',path))
    contract=json.loads((ROOT/'mobile/shared/release.json').read_text(encoding='utf-8'))
    version=contract['version'];tag='mobile-v'+version
    apk=ROOT/f'mobile/releases/Tennis-Vision-Camera-Android-v{version}.apk'
    android=json.loads((ROOT/f'mobile/releases/android-v{version}.json').read_text(encoding='utf-8'))
    metadata=validate(args.ipa,apk,android,contract)
    run=json.loads(gh('run','view',args.run,'--repo','Laikan2/tennis-vision-ai','--json','status,conclusion,headSha,jobs'))
    required=['Core regression tests','Build for physical iPhone without signing','Build for simulator','Package IPA for personal signing on Windows','Receiver integration','Simulator launch and screenshot']
    steps={s['name']:s['conclusion'] for j in run['jobs'] for s in j['steps']}
    if run['status']!='completed' or run['conclusion']!='success' or any(steps.get(s)!='success' for s in required):
        raise ValueError('A successful native build, tests and decoded-video integration are required')
    with tempfile.TemporaryDirectory(prefix='tennis-mobile-ci-') as folder:
        if not Path(folder).resolve().is_relative_to(Path(tempfile.gettempdir()).resolve()):raise ValueError('Unexpected temporary artifact path')
        gh('run','download',args.run,'--repo','Laikan2/tennis-vision-ai','--name','ios-validation-unsigned','--dir',folder)
        built=list(Path(folder).rglob(args.ipa.name))
        if len(built)!=1 or hashlib.sha256(built[0].read_bytes()).hexdigest()!=metadata['ios']['sha256']:
            raise ValueError('IPA does not match the successful CI artifact')
    metadata['sourceCommit']=run['headSha'];metadata['validationRun']=f'https://github.com/Laikan2/tennis-vision-ai/actions/runs/{args.run}'
    # Verify actual Android package, manifest and certificate using the local SDK.
    shell=shutil.which('pwsh') or shutil.which('powershell')
    if not shell:raise RuntimeError('PowerShell is required to verify the Android package')
    r=subprocess.run([shell,'-NoProfile','-File',str(ROOT/'scripts/publish-android-release.ps1'),'-Version',version,'-VerifyOnly'],capture_output=True,text=True,encoding='utf-8')
    if r.returncode:raise RuntimeError(r.stderr)
    latest=api(f'repos/{REPO}/releases/latest')['tag_name']
    meta_path=ROOT/f'mobile/releases/{tag}.json'
    meta_path.write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    project=ROOT/f'mobile/releases/Tennis-Vision-Camera-iOS-v{version}-Projeto.zip'
    files=[apk,args.ipa,project,meta_path]
    if not all(p.is_file() for p in files):raise ValueError('Missing release file')
    release=next((r for r in api(f'repos/{REPO}/releases?per_page=100&page=1') if r['tag_name']==tag),None)
    if release is None:
        gh('release','create',tag,'--repo',REPO,'--draft','--latest=false','--title',f'Tennis Vision mobile {version} — Android e iPhone','--notes-file',args.notes,'--target','main')
        for attempt in range(4):
            release=next((r for r in api(f'repos/{REPO}/releases?per_page=100&page=1') if r['tag_name']==tag),None)
            if release:break
            time.sleep(attempt+1)
        if release is None:raise RuntimeError('Draft not visible yet; rerun the same release')
    for p in files:
        digest='sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()
        asset=next((a for a in release['assets'] if a['name']==p.name),None)
        if asset and asset['size']==p.stat().st_size and asset.get('digest')==digest:continue
        if not release['draft']:raise ValueError('Published files are immutable; use a new version')
        gh('release','upload',tag,p,'--repo',REPO,'--clobber')
        release=api(f'repos/{REPO}/releases/{release["id"]}')
    for p in files:
        asset=next(a for a in release['assets'] if a['name']==p.name)
        if asset['size']!=p.stat().st_size or asset.get('digest')!='sha256:'+hashlib.sha256(p.read_bytes()).hexdigest():raise ValueError('Upload integrity mismatch')
    if release['draft']:gh('release','edit',tag,'--repo',REPO,'--draft=false','--prerelease=false','--latest=false')
    release=api(f'repos/{REPO}/releases/tags/{tag}')
    for p in [apk,args.ipa]:
        asset=next(a for a in release['assets'] if a['name']==p.name)
        with urllib.request.urlopen(urllib.request.Request(asset['browser_download_url'],headers={'User-Agent':'TennisVision-release-validation'}),timeout=60) as response:data=response.read()
        if len(data)!=p.stat().st_size or hashlib.sha256(data).hexdigest()!=hashlib.sha256(p.read_bytes()).hexdigest():raise ValueError('Public download integrity mismatch')
    if api(f'repos/{REPO}/releases/latest')['tag_name']!=latest:raise ValueError('Windows latest changed unexpectedly')
    source=runpy.run_path(str(ROOT/'scripts/altstore-source.py'))['publish'](args.ipa,tag,str(args.gh))
    metadata['published']=True;metadata['windowsLatestUnchanged']=latest;metadata['altStoreVersion']=source['apps'][0]['versions'][0]['version']
    folder=ROOT/f'mobile/releases/verification/{tag}'
    folder.mkdir(parents=True,exist_ok=True)
    (folder/'published.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'published':True,'version':version,'tag':tag,'altStoreVersion':metadata['altStoreVersion'],'windowsLatest':latest},indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ipa',type=Path,required=True);p.add_argument('--run',required=True);p.add_argument('--notes',type=Path,required=True)
    p.add_argument('--gh',type=Path,default=Path('gh'))
    publish(p.parse_args())
