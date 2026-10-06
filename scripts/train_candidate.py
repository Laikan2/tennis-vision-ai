"""Train a candidate in a new isolated folder; never alters the active dataset/model."""
import argparse
import json
import shutil
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from backend.appearance import train_model


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,default=ROOT/'data/learning')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--brightness-range',type=float,nargs=2,default=(.65,1.15))
    args=parser.parse_args()
    source=args.source.resolve(); output=args.output.resolve()
    output.mkdir(parents=True,exist_ok=False)
    rows=json.loads((source/'examples.json').read_text(encoding='utf-8'))
    for row in rows:
        if not row.get('reviewed') or row.get('label') not in (0,1):
            continue
        image=(source/row['image']).resolve()
        if not image.is_relative_to(source):
            raise ValueError('Image outside dataset.')
        target=output/image.relative_to(source)
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(image,target)
    shutil.copy2(source/'examples.json',output/'examples.json')
    if (source/'active.json').exists():
        info=json.loads((source/'active.json').read_text(encoding='utf-8'))
        model=(source/info['file']).resolve()
        if not model.is_relative_to(source):
            raise ValueError('Model outside dataset.')
        target=output/model.relative_to(source)
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(model,target)
        shutil.copy2(source/'active.json',output/'active.json')
    print(json.dumps(train_model(output,tuple(args.brightness_range))),flush=True)


if __name__=='__main__':
    main()
