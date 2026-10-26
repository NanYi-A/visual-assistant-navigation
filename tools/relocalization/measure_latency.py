"""Measure what each localization stage actually costs, in deployment terms.

The exploratory runs that produced the relocalization numbers were interactive: minutes each,
with no latency budget and no concurrency. Before any of this can ship, the per-stage cost needs
to be known, because the phone has to answer "where am I" while the user is standing still
waiting. This script reports the wall-clock cost of each stage separately so the budget can be
allocated honestly.
"""

import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, r'.\tools\relocalization')

from relocalize_segment import LearnedMatcher, estimate_pair, load_frames, shortlist_pairs
from line_depth_reloc import depth_agreement


def timed(label, fn):
    start = time.perf_counter()
    value = fn()
    return label, value, time.perf_counter() - start


def main():
    root = Path(r'.\models\reloc_work')
    parent = load_frames(root / '19224975706779' / 'semantic_samples')
    child = load_frames(root / 'reloc_segment_55021990447968' / 'semantic_samples')
    child_small = child[:6]

    _, matcher, cold = timed('matcher init', lambda: LearnedMatcher())
    print(f'model load (once per process)   {cold:6.1f} s')
    print(f'parent frames {len(parent)}, child frames {len(child)}')
    print()

    _, _, t_orb = timed('shortlist', lambda: shortlist_pairs(parent[:240], child_small))
    print(f'ORB shortlist ({len(parent[:240])}x6)          {t_orb:6.1f} s')
    print()

    single = shortlist_pairs(parent[:4], child[:2])
    start = time.perf_counter()
    for pf, cf in single:
        estimate_pair(pf, cf)
    per_pair = (time.perf_counter() - start) / max(1, len(single))
    print(f'LightGlue+PnP per pair          {per_pair:6.2f} s   ({len(single)} pairs measured)')
    print()

    # Depth scoring is the proposed production score, so its cost decides the budget.
    ps = parent[:240]
    start = time.perf_counter()
    for _ in range(3):
        depth_agreement(ps, child, np.eye(4))
    per_score = (time.perf_counter() - start) / 3
    print(f'depth_agreement (240x46 frames) {per_score:6.2f} s   per call')
    print()

    # A grid search at the resolution my exploration used.
    grid = 24 * 41 * 41
    print(f'exhaustive 6-DoF grid search would be {grid} poses')
    print(f'  at {per_score:.2f} s each  ->  {grid*per_score/60:.1f} minutes   NOT deployable')
    print()
    print('Deployment budget at 60 fps camera / 1 s user tolerance:')
    print(f'  a single depth_agreement call costs {per_score:.2f} s, which already')
    print('  dominates. Scoring many candidates needs a precomputed parent point cloud,')
    print('  not a per-call concatenation of 240 frames.')


if __name__ == '__main__':
    main()