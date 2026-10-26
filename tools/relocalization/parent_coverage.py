"""Diagnose whether a parent model's frames cover one room densely enough to relocalize into.

The 10:42 parent was a whole-home walk, so uniformly sampling it gives a spread-out set in which
only a fraction saw any particular room. This groups frames by position and heading so the
caller can see how much of the parent actually looks at the same place.
"""

import json
from pathlib import Path

import numpy as np


def load_poses(dataset: Path):
    records = [json.loads(l) for l in (dataset / 'metadata.jsonl').read_text(encoding='utf-8').splitlines() if l.strip()]
    poses = np.array([r['cameraToWorld'] for r in records]).reshape(-1, 4, 4)
    return records, poses


def heading(pose):
    """Camera forward in world coordinates; ARCore looks down its own -Z."""
    fwd = -pose[:3, 2]
    return np.degrees(np.arctan2(fwd[0], fwd[2]))


def cluster_views(poses, distance_m=1.5, angle_deg=60.0):
    clusters = []
    for i in range(len(poses)):
        pos = poses[i, :3, 3]
        yaw = heading(poses[i])
        for cluster in clusters:
            if np.linalg.norm(pos - cluster['centroid']) < distance_m and \
                    abs(((yaw - cluster['yaw'] + 180) % 360) - 180) < angle_deg:
                cluster['members'].append(i)
                cluster['centroid'] = pos.mean(axis=0)
                break
        else:
            clusters.append({'members': [i], 'centroid': pos, 'yaw': yaw})
    return clusters


def main():
    dataset = Path(r'.\models\19224975706779\rgbd_dataset')
    records, poses = load_poses(dataset)
    clusters = cluster_views(poses)
    sizes = sorted((len(c['members']) for c in clusters), reverse=True)
    print(f'parent 总帧 {len(poses)}，聚成 {len(clusters)} 个视角簇')
    print(f'簇大小（降序）: {sizes[:15]}')
    print(f'最大簇 {sizes[0]} 帧 = {sizes[0]/len(poses)*100:.0f}%')
    print()
    # The relocalization pipeline compares one child view against many parent views. What matters
    # is the densest single viewpoint group, because a child standing in one room can only be
    # matched by parent frames that were also taken in that room looking a similar way.
    biggest = max(clusters, key=lambda c: len(c['members']))
    print(f'最密视角簇: {len(biggest["members"])} 帧，成员索引前20: {biggest["members"][:20]}')
    span = poses[biggest['members'], :3, 3]
    print(f'  该簇位置跨度: {np.linalg.norm(span.max(axis=0)-span.min(axis=0)):.2f}m')


if __name__ == '__main__':
    main()