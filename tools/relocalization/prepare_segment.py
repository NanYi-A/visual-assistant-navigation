"""Run the real relocalization pipeline against an existing pair of models.

`relocalize_segment.run()` expects a proper supplementary segment — a directory holding
`segment.json` with a `parentModelId`, plus its own `semantic_samples`. Rather than reshape
someone's data, this prepares a throwaway segment that points at an existing child scan, so the
unchanged production code can be exercised end to end.

Nothing under --models is modified: the segment is written to --work and only read by the
pipeline via a temporary link-free copy of the child frames.
"""

import argparse
import json
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--models', type=Path, required=True, help='directory holding both models')
    parser.add_argument('--parent', required=True, help='parent model id (must own manifest.json)')
    parser.add_argument('--child', required=True, help='existing child scan directory id')
    parser.add_argument('--work', type=Path, required=True, help='scratch directory for the segment')
    parser.add_argument('--copy-frames', action='store_true',
                        help='copy the child npz files instead of referencing them in place')
    args = parser.parse_args()

    child_source = args.models / args.child
    if not child_source.is_dir():
        raise SystemExit(f'child model not found: {child_source}')
    samples = child_source / 'semantic_samples'
    if not samples.is_dir():
        raise SystemExit(f'child has no semantic_samples: {samples}')

    segment = args.work / f'reloc_segment_{args.child}'
    if segment.exists():
        shutil.rmtree(segment)
    target_samples = segment / 'semantic_samples'
    target_samples.mkdir(parents=True, exist_ok=True)

    npz_files = sorted(samples.glob('*.npz'))
    if not npz_files:
        raise SystemExit(f'no .npz frames in {samples}')
    for path in npz_files:
        if args.copy_frames:
            shutil.copy2(path, target_samples / path.name)
        else:
            # The pipeline resolves every frame relative to the segment directory, so a hard link
            # keeps the bytes in one place without doubling a few hundred megabytes.
            try:
                (target_samples / path.name).hardlink_to(path.resolve())
            except (OSError, AttributeError):
                shutil.copy2(path, target_samples / path.name)

    (segment / 'segment.json').write_text(json.dumps({
        'schemaVersion': 1,
        'segmentId': args.child,
        'parentModelId': args.parent,
        'status': 'PENDING_RELOCALIZATION',
        'frameCount': len(npz_files),
    }, ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'segment prepared: {segment}  ({len(npz_files)} frames)')


if __name__ == '__main__':
    main()