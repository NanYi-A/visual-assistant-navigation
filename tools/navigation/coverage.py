"""S4: coverage report and rescan guidance.

The report is the only thing allowed to claim a model is navigable. When a gate
fails the status becomes NEEDS_RESCAN and concrete rescan spots are listed
(docs/NAVIGATION_COGNITION_PLAN.md section 4.4).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List

from graph import NavigationGraph
from occupancy import CONFLICT, FREE, OCCUPIED, TENTATIVE, UNKNOWN, OccupancyGrid
from standable import DOWN_STEP, UP_STEP, StandableLayer

READY = "READY"
NEEDS_RESCAN = "NEEDS_RESCAN"


@dataclass
class RescanSpot:
    world_x: float
    world_y: float
    world_z: float
    radius: float
    reason: str
    state: str


@dataclass
class CoverageReport:
    status: str
    observed_voxels: int
    free_voxels: int
    occupied_voxels: int
    tentative_voxels: int
    conflict_voxels: int
    standable_cells: int
    headroom_cells: int
    support_coverage: float
    headroom_coverage: float
    reachable_ratio: float
    rescan_spots: List[RescanSpot] = field(default_factory=list)
    messages: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict:
        return {
            "status": self.status,
            "observedVoxels": self.observed_voxels,
            "freeVoxels": self.free_voxels,
            "occupiedVoxels": self.occupied_voxels,
            "tentativeVoxels": self.tentative_voxels,
            "conflictVoxels": self.conflict_voxels,
            "standableCells": self.standable_cells,
            "headroomCells": self.headroom_cells,
            "supportCoverage": round(self.support_coverage, 4),
            "headroomCoverage": round(self.headroom_coverage, 4),
            "reachableRatio": round(self.reachable_ratio, 4),
            "rescanSpots": [
                {"x": round(s.world_x, 2), "y": round(s.world_y, 2), "z": round(s.world_z, 2),
                 "radius": round(s.radius, 2), "reason": s.reason, "state": s.state}
                for s in self.rescan_spots
            ],
            "messages": self.messages,
            "readyForNavigation": self.status == READY,
        }


class CoverageAnalyzer:
    MIN_SUPPORT_COVERAGE = 0.60
    MIN_HEADROOM_COVERAGE = 0.60
    MIN_REACHABLE_RATIO = 0.50
    MAX_SPOTS = 12

    def __init__(self, grid: OccupancyGrid, standable: StandableLayer, graph: NavigationGraph,
                 heading=(1.0, 0.0, 0.0)):
        self.grid = grid
        self.standable = standable
        self.graph = graph
        self.heading = heading

    def analyse(self, start_position=None) -> CoverageReport:
        counts = self.grid.counts()
        cells = self.standable.cells
        standable_cells = [c for c in cells.values() if c.standable]
        headroom_cells = [c for c in standable_cells if c.min_clearance >= StandableLayer.MIN_HEADROOM]
        total_columns = max(1, len(cells))
        support = len(standable_cells) / total_columns
        headroom = len(headroom_cells) / total_columns
        reachable = self._reachable_ratio(start_position)
        spots = self._rescan_spots()
        messages = []
        if self.grid.floor_height is None:
            messages.append("尚未确认地面，无法判断可站立区域")
        if support < self.MIN_SUPPORT_COVERAGE:
            messages.append(f"可靠支撑面覆盖率 {support*100:.0f}%，低于 {self.MIN_SUPPORT_COVERAGE*100:.0f}%")
        if headroom < self.MIN_HEADROOM_COVERAGE:
            messages.append(f"头顶净空覆盖率 {headroom*100:.0f}%，低于 {self.MIN_HEADROOM_COVERAGE*100:.0f}%")
        if reachable < self.MIN_REACHABLE_RATIO:
            messages.append(f"可通行节点连通比例 {reachable*100:.0f}%，低于 {self.MIN_REACHABLE_RATIO*100:.0f}%")
        status = READY if not messages else NEEDS_RESCAN
        return CoverageReport(status, self.grid.integrated_frames,
                              counts[FREE], counts[OCCUPIED], counts[TENTATIVE], counts[CONFLICT],
                              len(standable_cells), len(headroom_cells), support, headroom, reachable,
                              spots, messages)

    def _reachable_ratio(self, start_position) -> float:
        if not self.graph.nodes:
            return 0.0
        if start_position is None:
            start_position = next(iter(self.graph.nodes))
        path = self.graph.plan_from(start_position, forward=self.heading)
        if path is None:
            return 0.0
        return min(1.0, len(path.points) / max(1, len(self.graph.nodes)))

    def _rescan_spots(self) -> List[RescanSpot]:
        """Concrete places where more observation would change the answer."""
        v = self.grid.voxel
        buckets: Dict[tuple, List[str]] = {}
        reasons = {TENTATIVE: "证据不足，需要换角度补扫", CONFLICT: "占据与自由证据冲突，必须复查"}
        for state in (TENTATIVE, CONFLICT):
            for key, voxel in self.grid.voxels_in_state(state):
                if state == CONFLICT and voxel.occupied_obs < 2:
                    continue
                column = (key[0] // 6, key[2] // 6)
                buckets.setdefault(column, []).append(state)
        spots = []
        for (cx, cz), states in buckets.items():
            centre = np_centre = ((cx * 6 + 3) * v, (cz * 6 + 3) * v)
            dominant = max(set(states), key=states.count)
            spots.append(RescanSpot(centre[0], float(self.grid.floor_height or 0.0), centre[1],
                                    3 * v, reasons[dominant], dominant))
        spots.sort(key=lambda s: -s.radius)
        return spots[:self.MAX_SPOTS]
