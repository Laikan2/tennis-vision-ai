"""Bundle only the iOS Xcode source, without keys, user data or unsigned IPA."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import re
import runpy

ROOT=Path(__file__).resolve().parents[1]
IOS=ROOT/'mobile/ios'

def package_ios():
    runpy.run_path(str(IOS/'project.py'),run_name='__main__')
    version=re.search(r'"MARKETING_VERSION": "([0-9.]+)"',(IOS/'project.py').read_text(encoding='utf-8')).group(1)
    destination=ROOT/'mobile/releases'/f'Tennis-Vision-Camera-iOS-v{version}-Projeto.zip'
    destination.parent.mkdir(parents=True,exist_ok=True)
    allowed=[IOS/'Package.swift',IOS/'project.py',IOS/'README.md',
             *sorted((IOS/'App').rglob('*')),*sorted((IOS/'Sources').rglob('*')),
             *sorted((IOS/'Tests').rglob('*')),*sorted((IOS/'Smoke').rglob('*')),*sorted((IOS/'Performance').rglob('*')),
             IOS/'TennisVisionCamera.xcodeproj/project.pbxproj',
             IOS/'TennisVisionCamera.xcodeproj/xcshareddata/xcschemes/TennisVisionCamera.xcscheme']
    with ZipFile(destination,'w',ZIP_DEFLATED) as archive:
        for path in allowed:
            if not path.is_file() or path.suffix.lower() in ('.p12','.pem','.mobileprovision','.key'):
                continue
            archive.write(path,'TennisVisionCamera-iOS/'+path.relative_to(IOS).as_posix())
    print(f'iOS Xcode project: {destination}')
    return destination

if __name__=='__main__':
    package_ios()
