"""S1: sparse voxel occupancy evidence from ARCore raw depth.

Design rules (see docs/NAVIGATION_COGNITION_PLAN.md section 4.1):
  * 8 cm sparse voxels; nothing is allocated for space we have not observed.
  * log-odds score is clamped to [-12, +12] so later observations can overturn
    earlier mistakes instead of the map being permanent.
  * the ray from camera to surface adds free evidence, the surface adds occupied.
  * a Raw Depth timestamp is fused at most once.
  * a voxel that never received a ray stays UNKNOWN; it is never promoted to free.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, Iterable, Optional, Tuple

import numpy as np

FREE = "FREE"
OCCUPIED = "OCCUPIED"
TENTATIVE = "TENTATIVE"
CONFLICT = "CONFLICT"
UNKNOWN = "UNKNOWN"

Key = Tuple[int, int, int]


@dataclass
class Voxel:
    score: int = 0
    free_obs: int = 0
    occupied_obs: int = 0
    first_frame: int = 0
    last_frame: int = 0
    min_height: float = math.inf   # relative to the estimated floor, metres
    max_height: float = -math.inf
    confidence_sum: int = 0
    observations: int = 0
    saw_free: bool = False
    saw_occupied: bool = False

    def state(self, free_threshold: int, occupied_threshold: int) -> str:
        if self.score >= occupied_threshold:
            return OCCUPIED
        if self.score <= free_threshold:
            return FREE
        if self.saw_free and self.saw_occupied:
            return CONFLICT
        return TENTATIVE


class DepthFrame:
    """One ARCore raw-depth sample already in the dataset's native resolution."""

    def __init__(self, frame_id, timestamp_ns, depth_mm, confidence, intrinsics, camera_to_world):
        self.frame_id = int(frame_id)
        self.timestamp_ns = int(timestamp_ns)
        self.depth = np.asarray(depth_mm)
        self.confidence = np.asarray(confidence)
        self.fx, self.fy, self.cx, self.cy = intrinsics
        self.camera_to_world = np.asarray(camera_to_world, dtype=np.float64)  # column-major 4x4
        self.origin = self.camera_to_world[:3, 3]
        self.rotation = self.camera_to_world[:3, :3]

    def world_points(self, min_confidence: int, stride: int, min_depth_m: float, max_depth_m: float):
        """Yield (world_point, distance, confidence) for accepted pixels."""
        height, width = self.depth.shape
        ys = range(0, height, stride)
        xs = range(0, width, stride)
        for y in ys:
            for x in xs:
                if self.confidence[y, x] < min_confidence:
                    continue
                metres = float(self.depth[y, x]) / 1000.0
                if metres < min_depth_m or metres > max_depth_m:
                    continue
                cam = np.array([
                    (x - self.cx) * metres / self.fx,
                    -(y - self.cy) * metres / self.fy,
                    -metres,
                ])
                yield self.rotation @ cam + self.origin, metres, int(self.confidence[y, x])


class OccupancyGrid:
    def __init__(self, voxel=0.08, min_confidence=180, min_depth_m=0.25, max_depth_m=6.0,
                 pixel_stride=3, free_delta=-2, occupied_delta=3,
                 free_threshold=-2, occupied_threshold=3, score_limit=12):
        self.voxel = float(voxel)
        self.min_confidence = int(min_confidence)
        self.min_depth_m = float(min_depth_m)
        self.max_depth_m = float(max_depth_m)
        self.pixel_stride = int(pixel_stride)
        self.free_delta = int(free_delta)
        self.occupied_delta = int(occupied_delta)
        self.free_threshold = int(free_threshold)
        self.occupied_threshold = int(occupied_threshold)
        self.score_limit = int(score_limit)
        self.cells: Dict[Key, Voxel] = {}
        self.floor_height: Optional[float] = None
        self.last_depth_timestamp: Optional[int] = None
        self.integrated_frames = 0
        self.rejected_stale = 0

    # ---- geometry -------------------------------------------------------
    def key(self, point) -> Key:
        v = self.voxel
        return (math.floor(point[0] / v), math.floor(point[1] / v), math.floor(point[2] / v))

    def centre(self, key: Key) -> np.ndarray:
        v = self.voxel
        return np.array([(key[0] + .5) * v, (key[1] + .5) * v, (key[2] + .5) * v])

    def _voxel(self, key: Key, frame_id: int) -> Voxel:
        v = self.cells.get(key)
        if v is None:
            v = Voxel(first_frame=frame_id)
            self.cells[key] = v
        return v

    # ---- floor ----------------------------------------------------------
    def estimate_floor(self, frames: Iterable[DepthFrame], below_min=0.45, below_max=2.2,
                       min_support=12, min_frames=3, band=0.18) -> Optional[float]:
        """Densest horizontal band of confirmed points below the camera."""
        samples = []
        for frame in frames:
            for point, distance, _ in frame.world_points(self.min_confidence, self.pixel_stride * 2,
                                                        self.min_depth_m, self.max_depth_m):
                drop = frame.origin[1] - point[1]
                if below_min <= drop <= below_max:
                    samples.append((point[1], frame.frame_id))
        if len(samples) < min_support:
            return None
        samples.sort(key=lambda s: s[0])
        best, left = (0, 0), 0
        for right in range(len(samples)):
            while samples[right][0] - samples[left][0] > band:
                left += 1
            if right - left > best[0]:
                best = (right - left, left)
        count, left = best
        chosen = samples[left:left + count + 1]
        if count + 1 < min_support or len({s[1] for s in chosen}) < min_frames:
            return None
        return float(np.median([s[0] for s in chosen]))

    # ---- fusion ---------------------------------------------------------
    def integrate(self, frame: DepthFrame) -> bool:
        if self.last_depth_timestamp is not None and frame.timestamp_ns <= self.last_depth_timestamp:
            self.rejected_stale += 1
            return False
        self.last_depth_timestamp = frame.timestamp_ns
        step = self.voxel * .75
        for point, distance, confidence in frame.world_points(
                self.min_confidence, self.pixel_stride, self.min_depth_m, self.max_depth_m):
            endpoint = self.key(point)
            voxel = self._voxel(endpoint, frame.frame_id)
            voxel.score = max(-self.score_limit, min(self.score_limit, voxel.score + self.occupied_delta))
            voxel.occupied_obs += 1
            voxel.saw_occupied = True
            voxel.observations += 1
            voxel.confidence_sum += confidence
            voxel.last_frame = frame.frame_id
            if self.floor_height is not None:
                height = float(point[1]) - self.floor_height
                voxel.min_height = min(voxel.min_height, height)
                voxel.max_height = max(voxel.max_height, height)
            # free evidence along the ray, stopping short of the surface
            length = float(np.linalg.norm(point - frame.origin))
            travelled = 0.0
            while travelled < length - self.voxel * 1.5:
                sample = frame.origin + (point - frame.origin) * (travelled / max(length, 1e-6))
                key = self.key(sample)
                free = self._voxel(key, frame.frame_id)
                free.score = max(-self.score_limit, min(self.score_limit, free.score + self.free_delta))
                free.free_obs += 1
                free.saw_free = True
                free.observations += 1
                free.confidence_sum += confidence
                free.last_frame = frame.frame_id
                travelled += step
        self.integrated_frames += 1
        return True

    # ---- queries --------------------------------------------------------
    def state_of(self, key: Key) -> str:
        v = self.cells.get(key)
        if v is None:
            return UNKNOWN
        return v.state(self.free_threshold, self.occupied_threshold)

    def counts(self) -> Dict[str, int]:
        out = {FREE: 0, OCCUPIED: 0, TENTATIVE: 0, CONFLICT: 0}
        for v in self.cells.values():
            out[v.state(self.free_threshold, self.occupied_threshold)] += 1
        out[UNKNOWN] = 0
        return out

    def voxels_in_state(self, state: str):
        for key, v in self.cells.items():
            if v.state(self.free_threshold, self.occupied_threshold) == state:
                yield key, v
