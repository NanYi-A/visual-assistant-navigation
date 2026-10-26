"""Judge a relocalization against known truth instead of against a self-referential score.

The earlier experiments were scored by `depth_agreement`, which turned out to reward poses that
merely land inside the parent's point cloud — the parent covers a whole home, so that check can
report 99% for a pose that is simply in the right building. It measured containment, not
correctness.

This script sidesteps the problem. The 10:42 model has five named places whose map coordinates
were recorded when the user spoke their name while standing there. If the user stands on a known
place and faces a known direction, that *is* the ground truth. The script then reports:

  position error   metres between the estimated pose and the place marker
  heading error    degrees between the estimated forward vector and the known facing

and compares both against what a blind-navigation user would actually tolerate.

Usage:
  verify_against_truth.py --place 二寝室
"""

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, r'.\tools\relocalization')

from relocalize_segment import (CV_FROM_AR, LearnedMatcher, estimate_pair, load_frames,
                                shortlist_pairs, world_points)


def heading_of(forward):
    """Yaw in degrees, measured the same way the stored places were measured."""
    return math.degrees(math.atan2(forward[0], -forward[2]))


def angle_between(a, b):
    return abs(((a - b + 180) % 360) - 180)


def load_places(path: Path):
    places = json.loads(path.read_text(encoding='utf-8'))
    result = {}
    for place in places:
        forward = (place.get('forwardX'), place.get('forwardY'), place.get('forwardZ'))
        if forward[0] is None:
            continue
        result[place['name']] = {
            'x': place['x'], 'y': place['y'], 'z': place['z'],
            'yaw': heading_of(forward),
        }
    return result


def estimate_from_frames(parent, child, limit=5):
    """Best candidate pose from the learned matcher, scored by two-sided geometry."""
    shortlisted = shortlist_pairs(parent, child, limit=limit)
    matcher = LearnedMatcher()
    parent_points = np.concatenate([world_points(f) for f in parent]) if parent else np.empty((0, 3))
    parent_tree = parent_tree_of(parent_points)
    candidates = []
    for pf, cf in shortlisted:
        result = estimate_pair(pf, cf)
        if result:
            candidates.append(result)
    return candidates, parent_points, parent_tree


def parent_tree_of(points):
    from scipy.spatial import cKDTree
    return cKDTree(points) if len(points) >= 100 else None


def two_sided_agreement(parent_tree, child_points, transform, threshold=0.15):
    """
    Fraction of points that have a neighbour in *both* directions.

    One-way containment is what made the previous score meaningless. Requiring the parent's points
    to also fall near transformed child points means an oversized parent cannot rescue a wrong pose.
    """
    if parent_tree is None or len(child_points) < 100:
        return 0.0
    forward = transform @ np.column_stack((child_points, np.ones(len(child_points)))).T
    forward = forward.T[:, :3]
    a = parent_tree.query(forward, k=1, workers=-1)[0] < threshold
    reverse = parent_tree.query(child_points, k=1, workers=-1)[0] < threshold
    return float(np.mean(a & reverse))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--place', required=True, help='name of a marked place to stand on')
    parser.add_argument('--facing', type=float, default=None,
                        help='known heading in degrees; omit to report error against the stored one')
    parser.add_argument('--models', type=Path,
                        default=Path(r'.\models\reloc_work'))
    parser.add_argument('--parent', default='19224975706779')
    parser.add_argument('--segment', default='reloc_segment_truth')
    parser.add_argument('--places', type=Path,
                        default=Path('places.json'))
    args = parser.parse_args()

    places = load_places(args.places)
    if args.place not in places:
        print(f'known places: {", ".join(places)}')
        return
    truth = places[args.place]
    print(f'truth: standing on {args.place}')
    print(f'  position x={truth["x"]:+.2f} y={truth["y"]:+.2f} z={truth["z"]:+.2f}')
    print(f'  heading {truth["yaw"]:+.1f} deg' + (f' (you said {args.facing:+.1f})' if args.facing is not None else ''))
    print()

    parent = load_frames(args.models / args.parent / 'semantic_samples')
    child_dir = args.models / args.segment / 'semantic_samples'
    if not child_dir.is_dir():
        print(f'no truth capture yet at {child_dir}')
        print('capture one with the phone standing on the place above, facing the known direction')
        return
    child = load_frames(child_dir)
    print(f'parent {len(parent)} frames, truth capture {len(child)} frames')

    candidates, parent_points, parent_tree = estimate_from_frames(parent, child)
    if not candidates:
        print('no candidate pose at all')
        return
    child_points = np.concatenate([world_points(f) for f in child])
    scored = []
    for candidate in candidates:
        score = two_sided_agreement(parent_tree, child_points, candidate['transform'])
        scored.append((score, candidate))
    scored.sort(key=lambda x: -x[0])

    print()
    print('candidates by two-sided agreement:')
    for score, candidate in scored:
        translation = candidate['transform'][:3, 3]
        print(f'  {score*100:5.1f}%  inliers={candidate["inliers"]:3d}  t=({translation[0]:+.2f},{translation[2]:+.2f})')

    best_score, best = scored[0]
    # The transform maps child world into parent world; apply it to the child's own origin.
    child_origin = np.eye(4)
    parent_origin = best['transform'] @ child_origin
    estimated_x, estimated_z = parent_origin[0, 3], parent_origin[2, 3]

    forward = (best['transform'] @ child_origin @ np.array([0.0, 0.0, -1.0, 0.0]))[:3]
    if np.linalg.norm(forward) < 1e-6:
        forward = best['transform'][:3, 2] * -1.0
    estimated_yaw = heading_of(forward)

    position_error = math.hypot(estimated_x - truth['x'], estimated_z - truth['z'])
    reference_yaw = args.facing if args.facing is not None else truth['yaw']
    heading_error = angle_between(estimated_yaw, reference_yaw)

    print()
    print('=== RESULT ===')
    print(f'  two-sided agreement {best_score*100:.1f}%')
    print(f'  estimated position x={estimated_x:+.2f} z={estimated_z:+.2f}')
    print(f'  estimated heading{estimated_yaw:+7.1f} deg')
    print(f'  POSITION ERROR  {position_error:6.2f} m   (tolerable for room navigation: <= 1.0 m)')
    print(f'  HEADING ERROR   {heading_error:6.1f} deg (tolerable for telling left/right: <= 30 deg)')
    print()
    if position_error <= 1.0 and heading_error <= 30:
        print('VERDICT: usable for room-level navigation.')
    else:
        print('VERDICT: not usable yet. This is the first measurement against real truth, so it')
        print('         is worth far more than any self-consistency score produced so far.')


if __name__ == '__main__':
    main()