"""Check the correlative matcher against real captures.

The synthetic test proves the matcher works on a map it made itself.  This
script runs it on the project's own recordings: a parent model with a recorded
occupancy map and a separate scan of the same room from a different spot.

There is no ground truth for those captures, so the evidence is
self-consistency: several frames are matched independently, and if the matcher
really located the room they must all land on the same pose.  A matcher that
guessed would scatter.
"""

import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

from csm_reloc import (CsmParameters, OccupancyGrid, match, project_voxels_to_grid,
                       read_occupancy, scan_from_frames, _normalise)


def read_png(path: Path) -> np.ndarray:
    """Read a PNG into an array, keeping 16 bit depth intact."""
    with Image.open(path) as image:
        array = np.array(image)
    if array.dtype == np.int32:            # Pillow widens I;16 on some builds
        array = (array & 0xFFFF).astype(np.uint16)
    return array


def load_dataset(model: Path, stride: int = 1, max_frames: int = 0):
    """Read a recorded capture into the frame format the matcher consumes."""
    dataset = model / "rgbd_dataset"
    calibration = json.loads((dataset / "calibration.json").read_text(encoding="utf-8"))
    lines = [line for line in (dataset / "metadata.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    records = [json.loads(line) for line in lines]
    records.sort(key=lambda record: float(record["timestamp"]))
    if stride > 1:
        records = records[::stride]
    if max_frames > 0 and len(records) > max_frames:
        step = len(records) / float(max_frames)
        records = [records[int(index * step)] for index in range(max_frames)]

    frames = []
    for record in records:
        depth_path = dataset / record["depth"]
        confidence_path = dataset / record["confidence"]
        if not (depth_path.is_file() and confidence_path.is_file()):
            continue
        depth = read_png(depth_path)
        confidence = read_png(confidence_path)
        if depth.dtype != np.uint16:
            depth = np.clip(depth.astype(np.float64) * float(calibration.get("depthScale", 1000.0)),
                            0, 65535).astype(np.uint16)
        if confidence.shape != depth.shape:
            confidence = np.array(Image.fromarray(confidence.astype(np.uint8)).resize(
                (depth.shape[1], depth.shape[0])))
        intrinsics = record.get("intrinsics") or calibration
        pose = np.asarray(record["cameraToWorld"], dtype=np.float64).reshape(4, 4)
        frames.append({
            "path": depth_path,
            "rgb": None,
            "depth": depth,
            "confidence": confidence,
            "k": np.array([intrinsics["fx"], intrinsics["fy"], intrinsics["cx"], intrinsics["cy"]]),
            "pose": pose,
        })
    return frames


def global_search(grid: OccupancyGrid, points: np.ndarray, parameters: CsmParameters,
                  translation_range: float, rotation_range: float,
                  coarse_step: float = 0.15, coarse_rotation: float = 0.25):
    """Search a wide area, because a first fix has no prior to lean on.

    The coarse pass uses few points and wide steps; the promising candidates are
    then refined with the normal window.
    """
    step = parameters.coarse_translation_step
    coarse_points = points[::max(1, len(points) // 40)]
    best = None
    positions = int(math.ceil(translation_range / coarse_step))
    rotations = int(math.ceil(rotation_range / coarse_rotation))
    for yaw_index in range(-rotations, rotations + 1):
        yaw = yaw_index * coarse_rotation
        cos_yaw, sin_yaw = math.cos(yaw), math.sin(yaw)
        for z_index in range(-positions, positions + 1):
            for x_index in range(-positions, positions + 1):
                x = x_index * coarse_step
                z = z_index * coarse_step
                total = 0.0
                valid = 0
                for px, pz in coarse_points:
                    world_x = x + cos_yaw * px - sin_yaw * pz
                    world_z = z + sin_yaw * px + cos_yaw * pz
                    distance = grid.distance_at(world_x, world_z)
                    if distance is None:
                        continue
                    total += math.exp(-(distance ** 2) / (2 * parameters.likelihood_sigma ** 2))
                    valid += 1
                if valid < parameters.minimum_points:
                    continue
                score = total / valid
                if best is None or score > best[0]:
                    best = (score, x, z, yaw)
    if best is None:
        return None
    _, x, z, yaw = best
    refined = match(grid, points, x, z, yaw, parameters)
    return refined


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--parent", type=Path, required=True, help="model with a recorded occupancy map")
    parser.add_argument("--child", type=Path, required=True, help="model to locate inside the parent")
    parser.add_argument("--stride", type=int, default=1)
    parser.add_argument("--frames", type=int, default=3, help="how many frames to match independently")
    parser.add_argument("--window", type=int, default=1,
                        help="how many frames to pool into one scan; more than one blurs the viewpoint")
    parser.add_argument("--translation-range", type=float, default=3.0)
    parser.add_argument("--rotation-range", type=float, default=math.pi)
    parser.add_argument("--prior-x", type=float, default=0.0)
    parser.add_argument("--prior-z", type=float, default=0.0)
    parser.add_argument("--prior-yaw", type=float, default=0.0)
    parser.add_argument("--use-prior", action="store_true",
                        help="search only a small window around the given prior")
    arguments = parser.parse_args()

    occupancy_path = arguments.parent / "cognition" / "occupancy.vamap"
    if not occupancy_path.is_file():
        print(f"no occupancy map at {occupancy_path}")
        return 2
    voxel_size, revision, cells = read_occupancy(occupancy_path)
    grid = project_voxels_to_grid(cells, voxel_size)
    print(f"parent map: revision {revision}, voxel {voxel_size} m, "
          f"{len(grid.obstacles)} obstacle cells")

    frames = load_dataset(arguments.child, stride=arguments.stride)
    if not frames:
        print("the child model has no usable frames")
        return 2
    print(f"child frames: {len(frames)}")

    parameters = CsmParameters()
    poses = []
    references = []
    for index in range(min(arguments.frames, len(frames))):
        window = frames[index:index + max(1, arguments.window)]
        try:
            scan = scan_from_frames(window, reference_index=0)
        except ValueError as error:
            print(f"frame {index}: {error}")
            continue
        if arguments.use_prior:
            result = match(grid, scan.points, arguments.prior_x, arguments.prior_z,
                           arguments.prior_yaw, parameters)
        else:
            result = global_search(grid, scan.points, parameters,
                                   arguments.translation_range, arguments.rotation_range)
        if result is None:
            print(f"frame {index}: no candidate")
            continue
        print(f"frame {index}: accepted={result.accepted} score={result.score:.3f} "
              f"valid={result.valid_fraction:.2f} x={result.x:.3f} z={result.z:.3f} "
              f"yaw={math.degrees(result.yaw):.1f}deg")
        if result.accepted:
            poses.append((result.x, result.z, result.yaw))
            references.append(np.asarray(frames[index]["pose"], dtype=np.float64)[:3, 3])

    if len(poses) >= 2:
        xs = [pose[0] for pose in poses]
        zs = [pose[1] for pose in poses]
        spread = max(math.hypot(xs[i] - xs[0], zs[i] - zs[0]) for i in range(len(poses)))
        print(f"spread across {len(poses)} independent matches: {spread:.3f} m")
        # The strongest available check: the distance the matches claim between two frames
        # must equal the distance the phone itself recorded between those frames.  A matcher
        # that only got the shape right would fail this.
        print("matched displacement vs the phone's own displacement:")
        worst = 0.0
        for i in range(len(poses)):
            for j in range(i + 1, len(poses)):
                matched = math.hypot(poses[i][0] - poses[j][0], poses[i][1] - poses[j][1])
                recorded = math.hypot(references[i][0] - references[j][0],
                                      references[i][2] - references[j][2])
                error = abs(matched - recorded)
                worst = max(worst, error)
                print(f"  frames {i}->{j}: matched {matched:.3f} m, recorded {recorded:.3f} m, "
                      f"error {error:.3f} m")
        print(f"worst displacement error: {worst:.3f} m")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
