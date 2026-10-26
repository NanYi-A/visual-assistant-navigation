"""Does the fast path actually contain a usable pose?

The proposed production pipeline is: LightGlue proposes a handful of candidate poses, then the
depth score ranks them. That only works if the true pose is *among* the candidates. This script
tests exactly that, and reports how good the best candidate actually is — because a fast pipeline
that never contains the right answer is worthless.

There is no ground truth here: two separate ARCore sessions of one room have no known relative
transform. So this measures the honest question — does the best candidate agree with the parent
geometry far better than chance would?
"""

import math
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, r'.\tools\relocalization')

from line_depth_reloc import depth_agreement, refine_by_depth
from relocalize_segment import LearnedMatcher, estimate_pair, load_frames, shortlist_pairs


def rank_candidates(parent_frames, child_frames, limit=12, verbose=True):
    """Score every pose LightGlue proposes and keep the best by depth agreement."""
    matcher = LearnedMatcher()
    shortlisted = shortlist_pairs(parent_frames, child_frames, limit=4)
    poses = []
    for pf, cf in shortlisted:
        result = estimate_pair(pf, cf)
        if result:
            poses.append((result['inliers'], result['transform'], pf, cf))
    if verbose:
        print(f'LightGlue proposed {len(poses)} candidate poses from {len(shortlisted)} shortlisted pairs')
        if poses:
            print(f'  PnP inliers: {[p[0] for p in sorted(poses, key=lambda x: -x[0])[:12]]}')

    scored = []
    for inliers, transform, pf, cf in poses:
        score, _, _ = depth_agreement(parent_frames, child_frames, transform)
        scored.append((score, inliers, transform, pf, cf))
    scored.sort(key=lambda x: -x[0])
    return scored, poses


def main():
    root = Path(r'.\models\reloc_work')
    parent = load_frames(root / '19224975706779' / 'semantic_samples')
    child = load_frames(root / 'reloc_segment_55021990447968' / 'semantic_samples')
    parent_cluster = parent[:240]

    start = time.perf_counter()
    scored, _ = rank_candidates(parent_cluster, child[:6])
    elapsed = time.perf_counter() - start
    print(f'\nscoring {len(scored)} candidates took {elapsed:.1f} s')
    print()
    print('depth agreement of each candidate:')
    for score, inliers, _, _, _ in scored[:12]:
        print(f'  {score*100:5.1f}%   (PnP inliers {inliers})')

    if not scored:
        print('\nno candidates: the fast path has nothing to rank')
        return
    best = scored[0]
    print(f'\nbest candidate: depth agreement {best[0]*100:.1f}%')

    refined, refined_score = refine_by_depth(parent_cluster, child, best[2])
    print(f'after local refinement: {refined_score*100:.1f}%')
    print('refined pose:')
    for row in refined:
        print('   ', [f'{v:+.4f}' for v in row])

    # What does a random pose score? Without this the headline number means nothing.
    rng = np.random.default_rng(7)
    randoms = []
    for _ in range(12):
        candidate = np.eye(4)
        angle = rng.uniform(0, 2 * math.pi)
        c, s = math.cos(angle), math.sin(angle)
        candidate[:3, :3] = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
        candidate[:3, 3] = rng.uniform(-6, 6, 3)
        randoms.append(depth_agreement(parent_cluster, child, candidate)[0])
    print(f'\nrandom poses: mean {np.mean(randoms)*100:.1f}%, best {max(randoms)*100:.1f}%')
    print(f'margin over the best random pose: {best[0]/max(randoms):.2f}x')
    print()
    print('A margin near 1.0x would mean the score cannot tell poses apart at all.')
    print('A margin of 2x or more means the score carries real information, but it is')
    print('still NOT proof of correctness: nothing here checks the answer against truth.')


if __name__ == '__main__':
    main()