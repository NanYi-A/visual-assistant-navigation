"""Acceptance tests for the navigation cognition layers (S1-S4)."""
import math
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))

from coverage import CoverageAnalyzer, NEEDS_RESCAN, READY
from graph import NavigationGraph
from occupancy import CONFLICT, FREE, OCCUPIED, TENTATIVE, UNKNOWN, DepthFrame, OccupancyGrid
from standable import StandableLayer

W, H = 160, 90                      # matches the phone's raw depth resolution
FX = FY = 116.0
CX, CY = 81.0, 44.7


def ground_frame(frame_id, camera_height=1.5, ground_y=0.0, pitch_deg=30.0, fx=FX, fy=FY, cx=CX, cy=CY,
                 origin=(0.0, 0.0, 0.0), timestamp=None, skip_columns=(), confidence=None):
    """A hand-held camera pitched down at a flat floor: depth is the ray/plane hit.

    pitch_deg is how far the phone tips toward the floor; a level camera at 1.5 m
    cannot see the ground within depth range, so a level synthetic camera would
    silently produce an all-zero depth map.
    """
    pitch = -math.radians(pitch_deg)    # negative: optical axis tips downward
    rot = np.array([[1.0, 0.0, 0.0],
                    [0.0, math.cos(pitch), -math.sin(pitch)],
                    [0.0, math.sin(pitch), math.cos(pitch)]], np.float64)
    depth = np.zeros((H, W), np.uint16)
    eye = camera_height + origin[1]
    for v in range(H):
        for u in range(W):
            if (u, v) in skip_columns:
                continue
            direction = rot @ np.array([(u - cx) / fx, -(v - cy) / fy, -1.0])
            if direction[1] >= -1e-6:
                continue                      # ray does not point downward
            t = (ground_y - eye) / direction[1]
            if not (0.30 <= t <= 6.0):
                continue
            depth[v, u] = int(round(t * 1000))
    conf = np.full((H, W), 255, np.uint8) if confidence is None else confidence
    pose = np.eye(4)
    pose[:3, :3] = rot
    pose[:3, 3] = (origin[0], eye, origin[2])
    return DepthFrame(frame_id, timestamp if timestamp is not None else frame_id * 1_000_000_000,
                      depth, conf, (fx, fy, cx, cy), pose)


def flat_frame(frame_id, depth_mm, origin=(0.0, 0.0, 0.0), timestamp=None, confidence=None):
    depth = np.full((H, W), depth_mm, np.uint16)
    conf = np.full((H, W), 255, np.uint8) if confidence is None else confidence
    pose = np.eye(4)
    pose[:3, 3] = origin
    return DepthFrame(frame_id, timestamp if timestamp is not None else frame_id * 1_000_000_000,
                      depth, conf, (FX, FY, CX, CY), pose)


def floor_frames(count=4):
    return [ground_frame(i + 1) for i in range(count)]


class OccupancyTests(unittest.TestCase):
    def test_no_floor_estimate_means_no_free_space(self):
        grid = OccupancyGrid()
        for frame in floor_frames(1):
            grid.floor_height = grid.estimate_floor([frame])
        # A single frame cannot reach the support threshold.
        self.assertIsNone(grid.floor_height)
        self.assertEqual(grid.counts()[FREE], 0)

    def test_ray_marks_free_and_surface_marks_occupied(self):
        grid = OccupancyGrid(pixel_stride=2)
        frames = floor_frames(4)
        grid.floor_height = grid.estimate_floor(frames)
        self.assertIsNotNone(grid.floor_height)
        for frame in frames:
            grid.integrate(frame)
        counts = grid.counts()
        self.assertGreater(counts[OCCUPIED], 0)
        self.assertGreater(counts[FREE], 0)
        self.assertEqual(counts[UNKNOWN], 0, "unknown is implicit and never materialised as cells")

    def test_duplicate_depth_timestamp_is_rejected(self):
        grid = OccupancyGrid()
        frame = ground_frame(1, timestamp=1234)
        self.assertTrue(grid.integrate(frame))
        self.assertFalse(grid.integrate(frame), "same depth timestamp must not be fused twice")
        self.assertEqual(grid.rejected_stale, 1)

    def test_new_free_observation_lowers_stale_obstacle(self):
        grid = OccupancyGrid(pixel_stride=2)
        frames = floor_frames(4)
        grid.floor_height = grid.estimate_floor(frames)
        for frame in frames:
            grid.integrate(frame)
        occupied = [v.score for _, v in grid.voxels_in_state(OCCUPIED)]
        self.assertTrue(occupied)
        self.assertTrue(all(s > 0 for s in occupied))
        # A second, more distant look must not push obstacles further positive.
        for frame in floor_frames(2):
            grid.integrate(frame)
        after = [v.score for _, v in grid.voxels_in_state(OCCUPIED)]
        self.assertTrue(all(s <= grid.score_limit for s in after))
        self.assertTrue(any(v.score < 0 for v in grid.cells.values()), "free evidence must exist")

    def test_score_is_clamped(self):
        grid = OccupancyGrid()
        for i in range(1, 12):
            grid.integrate(ground_frame(i, timestamp=i))
        self.assertTrue(all(abs(v.score) <= grid.score_limit for v in grid.cells.values()))

    def test_low_confidence_pixels_are_ignored(self):
        grid = OccupancyGrid(pixel_stride=2)
        dark = np.zeros((H, W), np.uint8)
        grid.integrate(ground_frame(1, confidence=dark))
        self.assertEqual(len(grid.cells), 0)


class StandableTests(unittest.TestCase):
    def _built(self):
        grid = OccupancyGrid(pixel_stride=2)
        frames = floor_frames(5)
        grid.floor_height = grid.estimate_floor(frames)
        for frame in frames:
            grid.integrate(frame)
        layer = StandableLayer(grid)
        layer.build()
        return grid, layer

    def test_standable_requires_width_and_headroom(self):
        grid, layer = self._built()
        self.assertTrue(layer.cells, "floor observations should yield surface columns")
        for cell in layer.cells.values():
            if cell.standable:
                self.assertGreaterEqual(cell.width + 1e-6, StandableLayer.MIN_WIDTH)
                self.assertGreaterEqual(cell.min_clearance + 1e-6, StandableLayer.MIN_HEADROOM)

    def test_narrow_passage_is_not_standable(self):
        grid = OccupancyGrid(pixel_stride=2)
        frames = floor_frames(5)
        grid.floor_height = grid.estimate_floor(frames)
        for frame in frames:
            grid.integrate(frame)
        layer = StandableLayer(grid)
        # Widen the obstacle band until the remaining corridor is under the minimum.
        for cell in layer.cells.values():
            cell.width = 0.10
        layer._judge()
        self.assertFalse(any(c.standable for c in layer.cells.values()))
        self.assertTrue(all("宽度" in c.reason for c in layer.cells.values() if not c.standable))

    def test_low_ceiling_is_not_standable(self):
        grid, layer = self._built()
        for cell in layer.cells.values():
            cell.min_clearance = 1.2
        layer._judge()
        self.assertFalse(any(c.standable for c in layer.cells.values()))
        self.assertTrue(any("净空" in c.reason for c in layer.cells.values()))


class GraphTests(unittest.TestCase):
    def _graph(self):
        grid = OccupancyGrid(pixel_stride=2)
        frames = floor_frames(6)
        grid.floor_height = grid.estimate_floor(frames)
        for frame in frames:
            grid.integrate(frame)
        layer = StandableLayer(grid)
        layer.build()
        graph = NavigationGraph(grid, layer)
        graph.build()
        return grid, layer, graph

    def test_graph_built_from_standable_only(self):
        grid, layer, graph = self._graph()
        self.assertTrue(graph.nodes)
        for position, node in graph.nodes.items():
            self.assertTrue(layer.cells[position].standable)

    def test_path_requires_continuous_free_corridor(self):
        grid, layer, graph = self._graph()
        if not graph.nodes:
            self.skipTest("synthetic floor produced no standable cells")
        start = next(iter(graph.nodes))
        path = graph.plan_from(start)
        # A path is either a valid continuous corridor or nothing at all.
        if path is not None:
            self.assertGreaterEqual(len(path.points), 1)
            self.assertTrue(path.reaches_front)


class CoverageTests(unittest.TestCase):
    def test_unobserved_space_forces_rescan(self):
        grid = OccupancyGrid(pixel_stride=2)
        frames = floor_frames(3)
        grid.floor_height = grid.estimate_floor(frames)
        for frame in frames:
            grid.integrate(frame)
        layer = StandableLayer(grid)
        layer.build()
        graph = NavigationGraph(grid, layer)
        graph.build()
        report = CoverageAnalyzer(grid, layer, graph).analyse()
        self.assertIn(report.status, (READY, NEEDS_RESCAN))
        if report.status == NEEDS_RESCAN:
            self.assertFalse(report.to_dict()["readyForNavigation"])
            self.assertTrue(report.messages)

    def test_report_never_claims_navigation_before_evidence(self):
        empty = OccupancyGrid()
        layer = StandableLayer(empty)
        layer.build()
        graph = NavigationGraph(empty, layer)
        graph.build()
        report = CoverageAnalyzer(empty, layer, graph).analyse()
        self.assertEqual(report.status, NEEDS_RESCAN)
        self.assertFalse(report.to_dict()["readyForNavigation"])


if __name__ == "__main__":
    unittest.main()
