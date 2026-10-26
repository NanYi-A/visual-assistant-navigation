"""Correlative scan matching: locate a session inside a recorded occupancy map.

Why this exists
---------------
The learned matcher (ALIKED + LightGlue + PnP) fails indoors for a reason that
has nothing to do with the matcher.  Door frames, wall corners and floor
texture look alike from anywhere in a house, so appearance cannot tell which
corner of the room a pixel belongs to -- and PnP then converges on a
plausible-looking but wrong pose.  Geometry can tell them apart.

This tool matches the *shape* of the current depth scan against the shape of
the recorded map.  It uses no colour, no texture and no brightness, so
lighting and exposure cannot break it.

It never merges anything.  It reports a transform and a score, and the caller
decides what to do with them.

The map is the reference, not the appearance: a scan point that lands on a
known obstacle scores high, and one that lands in the middle of a wall-free
room scores low.  A scan translated to the "right" place by accident of its
centroid therefore loses the match, which is exactly the failure mode that
made the earlier one-sided depth agreement metric useless.
"""

import heapq
import math
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------- map reading

OCCUPANCY_MAGIC = 0x56414D50  # 'VAMP'
OCCUPANCY_VERSION = 1


def occupancy_state(score: int, occupied_observations: int, free_observations: int,
                    confidence_sum: int, view_mask: int) -> str:
    """The same state machine the Kotlin backend uses, so both agree on what is solid."""
    if occupied_observations >= 2 and free_observations >= 2 and abs(score) <= 3:
        return "CONFLICT"
    if score >= 6 and view_mask.bit_count() >= 2 and confidence_sum >= 180 * max(1, occupied_observations):
        return "RELIABLE_OCCUPIED"
    if score <= -4 and free_observations >= 2:
        return "RELIABLE_FREE"
    return "TENTATIVE"


def read_occupancy(path: Path) -> tuple[float, int, dict]:
    """Read the Kotlin `occupancy.vamap` file into {voxel key: record}."""
    raw = Path(path).read_bytes()
    if len(raw) < 40:
        raise ValueError("occupancy map is too short")
    payload, checksum_bytes = raw[:-8], raw[-8:]
    expected = struct.unpack(">Q", checksum_bytes)[0]
    actual = zlib.crc32(payload) & 0xFFFFFFFF
    if actual != expected:
        raise ValueError(f"occupancy CRC mismatch: expected {expected}, got {actual}")
    magic, version, voxel_size, revision, _timestamp, count = struct.unpack_from(">iifqqi", payload, 0)
    if magic != OCCUPANCY_MAGIC or version != OCCUPANCY_VERSION or not 0 <= count <= 2_000_000:
        raise ValueError("unsupported occupancy map")
    offset = 32
    cells: dict[tuple[int, int, int], dict] = {}
    record = struct.Struct(">6i7q")
    for _ in range(count):
        values = record.unpack_from(payload, offset)
        offset += record.size
        x, y, z, score, occupied, free = values[:6]
        confidence_sum, _confidence_square_sum, view_mask = values[6:9]
        cells[(x, y, z)] = {
            "state": occupancy_state(score, occupied, free, confidence_sum, view_mask),
            "score": score,
            "occupiedObservations": occupied,
            "freeObservations": free,
        }
    if offset != len(payload):
        raise ValueError("trailing occupancy payload")
    return voxel_size, revision, cells


# -------------------------------------------------------------- 2D grid + field

NEIGHBOURS = ((1, 0, 1.0), (-1, 0, 1.0), (0, 1, 1.0), (0, -1, 1.0),
              (1, 1, math.sqrt(2.0)), (1, -1, math.sqrt(2.0)),
              (-1, 1, math.sqrt(2.0)), (-1, -1, math.sqrt(2.0)))


class OccupancyGrid:
    """A 2D obstacle map with a distance field, in metres.

    The distance field answers "how far is this point from the nearest known
    obstacle", which is what the likelihood is built on.  Points outside the
    map have no answer and are treated as not observed, never as free.
    """

    def __init__(self, cell_size: float, obstacles, max_distance: float = 2.0):
        self.cell_size = float(cell_size)
        self.max_distance = float(max_distance)
        self.obstacles = {(int(ix), int(iz)) for ix, iz in obstacles}
        if not self.obstacles:
            raise ValueError("the reference map has no obstacles")
        xs = [o[0] for o in self.obstacles]
        zs = [o[1] for o in self.obstacles]
        self._min_ix, self._max_ix = min(xs) - 1, max(xs) + 1
        self._min_iz, self._max_iz = min(zs) - 1, max(zs) + 1
        self._width = self._max_ix - self._min_ix + 1
        self._height = self._max_iz - self._min_iz + 1
        self._distance = self._build_field()

    def _build_field(self) -> np.ndarray:
        distance = np.full((self._height, self._width), self.max_distance, dtype=np.float64)
        heap: list[tuple[float, int, int]] = []
        for ix, iz in self.obstacles:
            distance[iz - self._min_iz, ix - self._min_ix] = 0.0
            heap.append((0.0, ix, iz))
        heapq.heapify(heap)
        while heap:
            current, ix, iz = heapq.heappop(heap)
            if current > distance[iz - self._min_iz, ix - self._min_ix]:
                continue
            for dx, dz, step in NEIGHBOURS:
                nx, nz = ix + dx, iz + dz
                if nx < self._min_ix or nx > self._max_ix or nz < self._min_iz or nz > self._max_iz:
                    continue
                candidate = current + step * self.cell_size
                if candidate >= distance[nz - self._min_iz, nx - self._min_ix]:
                    continue
                if candidate > self.max_distance:
                    continue
                distance[nz - self._min_iz, nx - self._min_ix] = candidate
                heapq.heappush(heap, (candidate, nx, nz))
        return distance

    def distance_at(self, x: float, z: float):
        """Distance to the nearest known obstacle, or None where nothing is known."""
        ix = math.floor(x / self.cell_size)
        iz = math.floor(z / self.cell_size)
        if ix < self._min_ix or ix > self._max_ix or iz < self._min_iz or iz > self._max_iz:
            return None
        return float(self._distance[iz - self._min_iz, ix - self._min_ix])


def project_voxels_to_grid(cells: dict, voxel_size: float,
                            states=("RELIABLE_OCCUPIED",)) -> OccupancyGrid:
    """Flatten the 3D occupancy into the 2D obstacle map a laser would produce.

    A voxel that is reliably occupied is an obstacle at that horizontal
    position, whatever its height: that is exactly what a single plane of laser
    hits.  Only the listed states count; tentative and conflicting voxels stay
    unknown so they cannot pull the match towards a wall that was never solid.
    """
    obstacles = set()
    for (x, _y, z), record in cells.items():
        if record["state"] in states:
            # The stored keys are already voxel indices, and the grid cell size is the
            # voxel size, so they can be used as grid indices unchanged.
            obstacles.add((int(x), int(z)))
    return OccupancyGrid(voxel_size, obstacles)


# ------------------------------------------------------------- scan extraction

@dataclass
class Scan:
    """A set of 2D obstacle points expressed in one camera frame."""
    points: np.ndarray                      # (N, 2) as x, z in the reference camera frame
    reference_pose: np.ndarray = field(default_factory=lambda: np.eye(4))


def scan_from_frames(frames, reference_index: int = 0, pixel_step: int = 4,
                     min_range: float = 0.3, max_range: float = 6.0,
                     min_confidence: int = 180, max_points: int = 4000) -> Scan:
    """Project several depth frames into one camera frame and keep the 2D points.

    Several frames are used because a single depth frame is sparse and noisy,
    but they are all expressed in one camera frame, which is what the matcher
    expects: a set of obstacle points seen from a single viewpoint.
    """
    if not frames:
        raise ValueError("no frames to scan")
    reference = frames[reference_index]
    reference_pose = np.asarray(reference["pose"], dtype=np.float64).reshape(4, 4)
    reference_inverse = np.linalg.inv(reference_pose)
    collected: list[np.ndarray] = []
    for frame in frames:
        depth = np.asarray(frame["depth"], dtype=np.float64) / 1000.0
        confidence = np.asarray(frame["confidence"])
        height, width = depth.shape
        ys, xs = np.mgrid[0:height:pixel_step, 0:width:pixel_step]
        valid = (depth[ys, xs] > min_range) & (depth[ys, xs] < max_range)
        valid &= confidence[ys, xs] >= min_confidence
        xs = xs[valid]
        ys = ys[valid]
        z = depth[ys, xs]
        if not len(z):
            continue
        fx, fy, cx, cy = [float(v) for v in frame["k"]]
        camera = np.column_stack(((xs - cx) * z / fx, (ys - cy) * z / fy, z, np.ones(len(z))))
        pose = np.asarray(frame["pose"], dtype=np.float64).reshape(4, 4)
        # ARCore poses are camera-to-world in an OpenGL frame: +X right, +Y up, -Z forward.
        to_opengl = np.diag([1.0, -1.0, -1.0, 1.0])
        world = (pose @ to_opengl @ camera.T).T
        reference_frame = (reference_inverse @ world.T).T
        collected.append(reference_frame[:, [0, 2]])
    if not collected:
        raise ValueError("no usable depth points")
    points = np.concatenate(collected, axis=0)
    if len(points) > max_points:
        step = len(points) / float(max_points)
        points = points[[int(i * step) for i in range(max_points)]]
    return Scan(points=points, reference_pose=reference_pose)


# ------------------------------------------------------------------- matching

@dataclass
class CsmParameters:
    search_translation: float = 0.20
    search_rotation: float = 0.16
    coarse_translation_step: float = 0.05
    coarse_rotation_step: float = 0.04
    fine_translation_step: float = 0.01
    fine_rotation_step: float = 0.01
    likelihood_sigma: float = 0.10
    minimum_score: float = 0.32
    minimum_valid_fraction: float = 0.45
    prior_weight: float = 0.025
    minimum_points: int = 25
    maximum_points: int = 160


@dataclass
class MatchResult:
    accepted: bool
    x: float = 0.0
    z: float = 0.0
    yaw: float = 0.0
    score: float = 0.0
    valid_fraction: float = 0.0
    position_variance: float = 0.25
    yaw_variance: float = 0.10
    points_used: int = 0


def _normalise(angle: float) -> float:
    while angle > math.pi:
        angle -= 2.0 * math.pi
    while angle < -math.pi:
        angle += 2.0 * math.pi
    return angle


def _score_pose(grid: OccupancyGrid, points: np.ndarray, x: float, z: float, yaw: float,
                predicted_x: float, predicted_z: float, predicted_yaw: float,
                parameters: CsmParameters):
    cos_yaw, sin_yaw = math.cos(yaw), math.sin(yaw)
    inverse_two_sigma_squared = 0.5 / (parameters.likelihood_sigma ** 2)
    likelihood_sum = 0.0
    valid = 0
    for px, pz in points:
        world_x = x + cos_yaw * px - sin_yaw * pz
        world_z = z + sin_yaw * px + cos_yaw * pz
        distance = grid.distance_at(world_x, world_z)
        if distance is None:
            continue
        likelihood_sum += math.exp(-distance * distance * inverse_two_sigma_squared)
        valid += 1
    if valid < parameters.minimum_points:
        return -1.0, 0.0
    valid_fraction = valid / float(len(points))
    if valid_fraction < parameters.minimum_valid_fraction:
        return -1.0, valid_fraction
    likelihood = likelihood_sum / valid
    translation_scale = max(0.01, parameters.search_translation)
    rotation_scale = max(0.01, parameters.search_rotation)
    dx = (x - predicted_x) / translation_scale
    dz = (z - predicted_z) / translation_scale
    dyaw = _normalise(yaw - predicted_yaw) / rotation_scale
    prior_penalty = parameters.prior_weight * (dx * dx + dz * dz + dyaw * dyaw)
    coverage_weight = 0.5 + 0.5 * valid_fraction
    return likelihood * coverage_weight - prior_penalty, valid_fraction


def match(grid: OccupancyGrid, points: np.ndarray, predicted_x: float, predicted_z: float,
          predicted_yaw: float, parameters: CsmParameters = None) -> MatchResult:
    """Find the pose that puts the scan points onto the map's obstacles.

    `predicted_*` is the prior.  With a good prior the search window is tiny and
    the result is cheap; without one the window has to cover the whole house,
    which is why a marker place can be used to narrow it down first.
    """
    parameters = parameters or CsmParameters()
    points = np.asarray(points, dtype=np.float64)
    if len(points) > parameters.maximum_points:
        step = len(points) / float(parameters.maximum_points)
        points = points[[int(i * step) for i in range(parameters.maximum_points)]]
    if len(points) < parameters.minimum_points:
        return MatchResult(accepted=False)

    best_x, best_z, best_yaw = predicted_x, predicted_z, predicted_yaw
    best_score, best_fraction = _score_pose(grid, points, best_x, best_z, best_yaw,
                                            predicted_x, predicted_z, predicted_yaw, parameters)
    if best_score < 0:
        return MatchResult(accepted=False)

    translation_steps = max(0, int(math.ceil(parameters.search_translation / parameters.coarse_translation_step)))
    rotation_steps = max(0, int(math.ceil(parameters.search_rotation / parameters.coarse_rotation_step)))
    for yaw_step in range(-rotation_steps, rotation_steps + 1):
        yaw = _normalise(predicted_yaw + yaw_step * parameters.coarse_rotation_step)
        for z_step in range(-translation_steps, translation_steps + 1):
            for x_step in range(-translation_steps, translation_steps + 1):
                candidate_x = predicted_x + x_step * parameters.coarse_translation_step
                candidate_z = predicted_z + z_step * parameters.coarse_translation_step
                score, fraction = _score_pose(grid, points, candidate_x, candidate_z, yaw,
                                              predicted_x, predicted_z, predicted_yaw, parameters)
                if score > best_score:
                    best_x, best_z, best_yaw = candidate_x, candidate_z, yaw
                    best_score, best_fraction = score, fraction

    # Refine around the winner with shrinking 3x3x3 neighbourhoods.
    translation_step = parameters.coarse_translation_step * 0.5
    rotation_step = parameters.coarse_rotation_step * 0.5
    while translation_step >= parameters.fine_translation_step or rotation_step >= parameters.fine_rotation_step:
        translation_step = max(translation_step, parameters.fine_translation_step)
        rotation_step = max(rotation_step, parameters.fine_rotation_step)
        centre_x, centre_z, centre_yaw = best_x, best_z, best_yaw
        for yaw_offset in (-1, 0, 1):
            for z_offset in (-1, 0, 1):
                for x_offset in (-1, 0, 1):
                    candidate_x = centre_x + x_offset * translation_step
                    candidate_z = centre_z + z_offset * translation_step
                    candidate_yaw = _normalise(centre_yaw + yaw_offset * rotation_step)
                    if math.hypot(candidate_x - predicted_x, candidate_z - predicted_z) > parameters.search_translation * 1.5:
                        continue
                    if abs(_normalise(candidate_yaw - predicted_yaw)) > parameters.search_rotation * 1.5:
                        continue
                    score, fraction = _score_pose(grid, points, candidate_x, candidate_z, candidate_yaw,
                                                  predicted_x, predicted_z, predicted_yaw, parameters)
                    if score > best_score:
                        best_x, best_z, best_yaw = candidate_x, candidate_z, candidate_yaw
                        best_score, best_fraction = score, fraction
        if translation_step <= parameters.fine_translation_step and rotation_step <= parameters.fine_rotation_step:
            break
        translation_step = max(parameters.fine_translation_step, translation_step * 0.5)
        rotation_step = max(parameters.fine_rotation_step, rotation_step * 0.5)

    if best_score < parameters.minimum_score:
        return MatchResult(accepted=False, x=best_x, z=best_z, yaw=best_yaw,
                           score=max(0.0, best_score), valid_fraction=best_fraction,
                           points_used=len(points))
    return MatchResult(
        accepted=True,
        x=best_x, z=best_z, yaw=best_yaw,
        score=best_score,
        valid_fraction=best_fraction,
        position_variance=min(0.20, max(0.005, 0.005 + 0.12 * (1.0 - best_score))),
        yaw_variance=min(0.12, max(0.003, 0.003 + 0.08 * (1.0 - best_score))),
        points_used=len(points),
    )


def transform_to_matrix(x: float, z: float, yaw: float) -> np.ndarray:
    """A planar pose as a 4x4 matrix that maps the reference camera frame into the map."""
    cos_yaw, sin_yaw = math.cos(yaw), math.sin(yaw)
    matrix = np.eye(4)
    matrix[0, 0], matrix[0, 2] = cos_yaw, -sin_yaw
    matrix[2, 0], matrix[2, 2] = sin_yaw, cos_yaw
    matrix[0, 3], matrix[2, 3] = x, z
    return matrix


def parent_from_session(result: MatchResult, reference_pose: np.ndarray) -> np.ndarray:
    """Combine the match with the reference camera pose into the map-from-session transform.

    The matcher works in one camera frame; the session frame is what the phone
    keeps running in, so the two have to be composed.
    """
    parent_from_camera = transform_to_matrix(result.x, result.z, result.yaw)
    camera_from_session = np.linalg.inv(np.asarray(reference_pose, dtype=np.float64).reshape(4, 4))
    return parent_from_camera @ camera_from_session
