"""Turn an rgbd_dataset directory into the .npz frames relocalization consumes.

`relocalize_segment.load_frames()` expects one .npz per frame carrying rgb, depth_mm,
confidence, intrinsics and camera_to_world. The server writes semantic_samples straight
from the live stream, but an older model may only have the full `rgbd_dataset` recorded.
This converts that dataset so such a model can act as the parent of a relocalization.
"""

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np


def convert(dataset: Path, output: Path, stride: int, max_frames: int) -> int:
    dataset = Path(dataset)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    calibration = json.loads((dataset / 'calibration.json').read_text(encoding='utf-8'))
    scale = float(calibration.get('depthScale', 1000.0))

    lines = [l for l in (dataset / 'metadata.jsonl').read_text(encoding='utf-8').splitlines() if l.strip()]
    records = [json.loads(l) for l in lines]
    # Keeping the dataset's own time order matters: relocalization compares viewpoint
    # diversity, and re-sorting would silently drop the spread it depends on.
    records.sort(key=lambda r: float(r['timestamp']))
    if stride > 1:
        records = records[::stride]
    if max_frames > 0 and len(records) > max_frames:
        step = len(records) / float(max_frames)
        records = [records[int(i * step)] for i in range(max_frames)]

    written = 0
    skipped = 0
    for record in records:
        rgb_path = dataset / record['rgb']
        depth_path = dataset / record['depth']
        conf_path = dataset / record['confidence']
        if not (rgb_path.is_file() and depth_path.is_file() and conf_path.is_file()):
            skipped += 1
            continue
        rgb = cv2.imread(str(rgb_path), cv2.IMREAD_COLOR)
        depth = cv2.imread(str(depth_path), cv2.IMREAD_UNCHANGED)
        conf = cv2.imread(str(conf_path), cv2.IMREAD_UNCHANGED)
        if rgb is None or depth is None or conf is None:
            skipped += 1
            continue
        rgb = cv2.cvtColor(rgb, cv2.COLOR_BGR2RGB)
        if depth.dtype != np.uint16:
            depth = np.clip(depth.astype(np.float64) * scale, 0, 65535).astype(np.uint16)
        if conf.dtype != np.uint8:
            conf = np.clip(conf, 0, 255).astype(np.uint8)
        if conf.shape != depth.shape:
            conf = cv2.resize(conf, (depth.shape[1], depth.shape[0]), interpolation=cv2.INTER_NEAREST)
        intrinsics = record.get('intrinsics') or calibration
        pose = np.asarray(record['cameraToWorld'], dtype=np.float64).reshape(4, 4)
        name = Path(record['rgb']).name
        np.savez_compressed(
            output / f'frame_{written:06d}.npz',
            rgb=rgb,
            depth_mm=depth,
            confidence=conf,
            intrinsics=np.array([intrinsics['fx'], intrinsics['fy'], intrinsics['cx'], intrinsics['cy']]),
            camera_to_world=pose,
        )
        written += 1
    if skipped:
        print(f'skipped {skipped} unusable frames', file=sys.stderr)
    return written


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stride', type=int, default=1)
    parser.add_argument('--max-frames', type=int, default=0)
    args = parser.parse_args()
    count = convert(args.dataset, args.output, args.stride, args.max_frames)
    print(f'wrote {count} frames to {args.output}')