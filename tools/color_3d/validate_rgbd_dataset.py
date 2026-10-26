import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from rgbd_dataset import load_dataset


def validate(path):
    records = load_dataset(path)
    valid_fractions = []
    for record in records:
        root = Path(path)
        rgb = np.asarray(Image.open(root / record['rgb']))
        depth = np.asarray(Image.open(root / record['depth']))
        confidence = np.asarray(Image.open(root / record['confidence']))
        expected = (int(record['height']), int(record['width']))
        if rgb.shape[:2] != expected or depth.shape != expected or depth.dtype != np.uint16:
            raise ValueError(f'invalid RGB-D files for frame {record["frameId"]}')
        if confidence.shape != expected:
            raise ValueError(f'invalid confidence for frame {record["frameId"]}')
        valid_fractions.append(float(np.count_nonzero(depth)) / depth.size)
    return {'status': 'READY' if records else 'EMPTY', 'keyframes': len(records),
            'durationSeconds': (records[-1]['timestamp'] - records[0]['timestamp']) if len(records) > 1 else 0.0,
            'meanValidDepthFraction': float(np.mean(valid_fractions)) if valid_fractions else 0.0,
            'firstFrameId': records[0]['frameId'] if records else None,
            'lastFrameId': records[-1]['frameId'] if records else None}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('dataset', type=Path)
    args = parser.parse_args()
    print(json.dumps(validate(args.dataset), ensure_ascii=False, indent=2))
