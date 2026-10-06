"""Read-only validation of the active appearance model on reviewed held-out crops."""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from backend.appearance import features, load_model, metrics, votes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--folder', type=Path, default=ROOT/'data/learning')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    info = json.loads((args.folder/'active.json').read_text(encoding='utf-8'))
    model = load_model(args.folder/info['file'])
    rows = [row for row in json.loads((args.folder/'examples.json').read_text(encoding='utf-8'))
            if row.get('reviewed') and row.get('label') in (0,1)]
    train = [row for row in rows if row.get('split') == 'train']
    test = [row for row in rows if row.get('split') == 'test']
    if not test or {row['group'] for row in train} & {row['group'] for row in test}:
        raise ValueError('Missing independent validation groups.')
    patches = [cv2.imread(str(args.folder/row['image'])) for row in test]
    if any(patch is None for patch in patches):
        raise ValueError('A reviewed validation image is missing.')
    labels = [row['label'] for row in test]
    transforms = {
        'original': lambda image: image,
        'darker': lambda image: np.clip(image.astype(float)*.7,0,255).astype(np.uint8),
        'brighter': lambda image: np.clip(image.astype(float)*1.25,0,255).astype(np.uint8),
        'small': lambda image: cv2.resize(image,(10,10),interpolation=cv2.INTER_AREA),
        'horizontal_blur': lambda image: cv2.filter2D(image,-1,np.ones((1,5),np.float32)/5),
    }
    report = dict(version=info['version'],trainExamples=len(train),testExamples=len(test),
                  positiveExamples=sum(labels),independentGroups=True,
                  scope='Crop classification in the reviewed room dataset. Perturbations are synthetic; this does not measure impacts, speed, full-frame detection or a new court.')
    report['metrics'] = {name: metrics(labels,votes(model,[features(transform(patch)) for patch in patches]),info['threshold'])
                         for name,transform in transforms.items()}
    print(json.dumps(report))
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
