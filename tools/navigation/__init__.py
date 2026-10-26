"""Navigation cognition pipeline: RGB-D keyframes -> coverage report.

Runs the four stages defined in docs/NAVIGATION_COGNITION_PLAN.md:
  S1 occupancy evidence -> S2 standable surfaces -> S3 navigation graph -> S4 coverage.
"""
from __future__ import annotations

import json
import math
from pathlib import Path
from typing import List, Optional

import numpy as np
from PIL import Image

from coverage import CoverageAnalyzer, CoverageReport
from graph import NavigationGraph
from occupancy import DepthFrame, OccupancyGrid
from standable import StandableLayer

__all__ = ["DepthFrame", "OccupancyGrid", "StandableLayer", "NavigationGraph",
           "CoverageAnalyzer", "CoverageReport", "build_navigation_model", "load_dataset_frames"]


def load_dataset_frames(dataset: Path, limit: Optional[int] = None) -> List[DepthFrame]:
    dataset = Path(dataset)
    records = [json.loads(l) for l in (dataset / "metadata.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    if limit:
        records = records[:limit]
    frames = []
    for rec in records:
        depth = np.asarray(Image.open(dataset / rec["depth"]))
        confidence = np.asarray(Image.open(dataset / rec["confidence"]))
        pose = np.asarray(rec["cameraToWorld"], dtype=np.float64).reshape(4, 4, order="F")
        intr = rec["intrinsics"]
        frames.append(DepthFrame(rec["frameId"], rec["depthTimestampNs"], depth, confidence,
                                 (intr["fx"], intr["fy"], intr["cx"], intr["cy"]), pose))
    return frames


def build_navigation_model(dataset: Path, limit: Optional[int] = None, pixel_stride: int = 3,
                           progress=None) -> CoverageReport:
    frames = load_dataset_frames(dataset, limit)
    grid = OccupancyGrid(pixel_stride=pixel_stride)
    if progress:
        progress(f"loaded {len(frames)} RGB-D keyframes")
    grid.floor_height = grid.estimate_floor(frames)
    if progress:
        progress(f"floor height = {grid.floor_height}")
    for index, frame in enumerate(frames):
        grid.integrate(frame)
        if progress and index % 25 == 0:
            progress(f"fused {index+1}/{len(frames)} frames, {len(grid.cells)} voxels")
    standable = StandableLayer(grid)
    standable.build()
    graph = NavigationGraph(grid, standable)
    graph.build()
    if progress:
        progress(f"graph: {graph.summary()}")
    heading = (1.0, 0.0, 0.0)
    if frames:
        rot = frames[-1].camera_to_world[:3, :3]
        heading = (-float(rot[0, 2]), 0.0, -float(rot[2, 2]))
    report = CoverageAnalyzer(grid, standable, graph, heading).analyse()
    if progress:
        progress(f"coverage: {report.to_dict()['status']}")
    return report
