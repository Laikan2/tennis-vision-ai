"""Build/validate an AltStore Classic source from a verified unsigned iPhone IPA.
Publishing changes only the official public source after its release is public.
"""
import argparse, base64, json, plistlib, re, runpy, subprocess
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZipFile

ROOT=Path(__file__).resolve().parents[1]
REPO='Laikan2/tennis-vision-releases'
SOURCE_URL=f'https://raw.githubusercontent.com/{REPO}/main/altstore.json'
ICON_URL=f'https://raw.githubusercontent.com/{REPO}/main/app-icon-ios.png'
VERIFY=runpy.run_path(str(ROOT/'scripts/package-ios-ipa.py'))['verify_ipa']

def source(ipa, tag, previous=None):
    if not re.fullmatch(r'v\d+\.\d+\.\d+',tag):
        raise ValueError('A stable version tag is required')
    metadata=VERIFY(ipa)
    with ZipFile(ipa) as archive:
        info=plistlib.loads(archive.read('Payload/TennisVisionCamera.app/Info.plist'))
    bundle=metadata['bundleIdentifier']
    old=next((app for app in (previous or {}).get('apps',[]) if app['bundleIdentifier']==bundle),{})
    version_key=lambda version:tuple(map(int,version.split('.')))
    if any(version_key(v['version'])>version_key(metadata['version']) for v in old.get('versions',[])):
        raise ValueError('The iPhone source cannot downgrade an existing release')
    existing=next((v for v in old.get('versions',[]) if v['version']==metadata['version']),None)
    # A Windows-only release must preserve the original immutable iOS download.
    if existing and existing['size']!=metadata['bytes']:
        raise ValueError('An existing iOS version cannot be replaced by a different IPA')
    release=existing or dict(version=metadata['version'],buildVersion=info['CFBundleVersion'],
        date=datetime.now(timezone.utc).isoformat(timespec='seconds'),
        downloadURL=f"https://github.com/{REPO}/releases/download/{tag}/{metadata['filename']}",
        size=metadata['bytes'],minOSVersion=metadata['minimumIOS'],
        localizedDescription='Painel do treino no iPhone, seleção de área por toque, IN/OUT e som com análise no PC. Perfil automático conforme a câmera e taxas medidas. Interface reorganizada.')
    versions=[release]+[v for v in old.get('versions',[]) if v['version']!=metadata['version']]
    app=dict(name='Tennis Vision Camera',bundleIdentifier=bundle,developerName='Tennis Vision',
        subtitle='Sua câmera. Seu treino. Seu estúdio.',category='photo-video',iconURL=ICON_URL,tintColor='C7F56B',
        localizedDescription='Câmera nativa para iPhone conectada ao Tennis Vision Studio no Windows. H.264, perfis de 30 a 240 FPS conforme o suporte real, painel de análise, áreas, impactos e estatísticas. O PC executa a IA e deve estar ligado na mesma rede. Instalação e renovação pelo AltStore Classic.',
        versions=versions,appPermissions=dict(entitlements=[],privacy={
            key:value for key,value in info.items() if key.startswith('NS') and key.endswith('UsageDescription')
        }))
    return dict(name='Tennis Vision',identifier='ai.tennisvision.source',sourceURL=SOURCE_URL,
        subtitle='Câmera e análise de tênis no seu iPhone',iconURL=ICON_URL,website=f'https://github.com/{REPO}',
        tintColor='C7F56B',apps=[app],news=(previous or {}).get('news',[]))

def publish(ipa, tag, gh):
    def api(endpoint, body=None):
        args=[gh,'api',endpoint]
        if body is not None: args+=['--method','PUT','--input','-']
        result=subprocess.run(args,input=json.dumps(body) if body is not None else None,
            text=True,encoding='utf-8',capture_output=True,check=True)
        return json.loads(result.stdout)
    release=api(f'repos/{REPO}/releases/tags/{tag}')
    if release['draft'] or release['prerelease']: raise ValueError('Publish and validate the stable release before updating the source')
    metadata=VERIFY(ipa)
    asset=next((a for a in release['assets'] if a['name']==metadata['filename']),None)
    if not asset or asset['size']!=metadata['bytes'] or asset.get('digest')!='sha256:'+metadata['sha256']:
        raise ValueError('The public IPA digest/size does not match the validated local IPA')
    previous=None; sha=None
    listing=api(f'repos/{REPO}/contents')
    if any(item['name']=='altstore.json' for item in listing):
        current=api(f'repos/{REPO}/contents/altstore.json')
        previous=json.loads(base64.b64decode(current['content']))
        sha=current['sha']
    feed=source(ipa,tag,previous)
    # Validate the actual bundled privacy strings, not a stale hand-written copy.
    icon=ROOT/'mobile/ios/App/Assets.xcassets/AppIcon.appiconset/AppIcon.png'
    if not any(item['name']=='app-icon-ios.png' for item in listing):
        api(f'repos/{REPO}/contents/app-icon-ios.png',dict(message='Add native iPhone app icon',content=base64.b64encode(icon.read_bytes()).decode()))
    body=dict(message=f"Update iPhone source to {metadata['version']}",content=base64.b64encode((json.dumps(feed,ensure_ascii=False,indent=2)+'\n').encode()).decode())
    if sha: body['sha']=sha
    api(f'repos/{REPO}/contents/altstore.json',body)
    return feed

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ipa',required=True,type=Path)
    parser.add_argument('--tag',required=True)
    parser.add_argument('--previous',type=Path)
    parser.add_argument('--output',type=Path,default=ROOT/'mobile/releases/altstore.json')
    parser.add_argument('--publish',action='store_true')
    parser.add_argument('--gh',default='gh')
    args=parser.parse_args()
    value=publish(args.ipa,args.tag,args.gh) if args.publish else source(args.ipa,args.tag,
        json.loads(args.previous.read_text(encoding='utf-8')) if args.previous else None)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"AltStore source validated: {value['apps'][0]['versions'][0]['version']}")
