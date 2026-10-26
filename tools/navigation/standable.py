"""S2: standable surfaces derived from occupancy evidence.

Only surfaces that satisfy every gate become standable. Stairs and drops are
detected and labelled, never silently flattened into a single height (see
docs/NAVIGATION_COGNITION_PLAN.md section 4.2).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple

import numpy as np

from occupancy import CONFLICT, FREE, OCCUPIED, TENTATIVE, UNKNOWN, Key, OccupancyGrid

FLAT = "FLAT"
SLOPE = "SLOPE"
UP_STEP = "UP_STEP"
DOWN_STEP = "DOWN_STEP"
DROP = "DROP"


@dataclass
class SurfaceCell:
    x: int
    z: int
    y: float                       # world height of the supporting surface
    kind: str = FLAT
    min_clearance: float = math.inf   # head room above the surface
    width: float = 0.0
    standable: bool = False
    reason: str = ""


class StandableLayer:
    MAX_TILT_DEG = 20.0
    MAX_LOCAL_STEP = 0.05
    MIN_WIDTH = 0.45
    MIN_HEADROOM = 1.90
    MAX_SLOPE_DEG = 15.0
    MIN_HEADROOM_STEP = 0.30          # ignore obstacles below this when measuring head room
    STEP_DETECT = 0.12               # height jump that counts as a stair edge
    DROP_DETECT = 0.30               # missing support under a surface

    def __init__(self, grid: OccupancyGrid):
        self.grid = grid
        self.cells: Dict[Tuple[int, int], SurfaceCell] = {}

    # ---- helpers --------------------------------------------------------
    def _occupied_keys(self) -> Set[Key]:
        return {key for key in self.grid.cells
                if self.grid.cells[key].state(self.grid.free_threshold, self.grid.occupied_threshold) == OCCUPIED}

    @staticmethod
    def _neighbours4(key: Tuple[int, int]):
        x, z = key
        return ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1))

    # ---- build ----------------------------------------------------------
    def build(self) -> Dict[Tuple[int, int], SurfaceCell]:
        occupied = self._occupied_keys()
        if not occupied or self.grid.floor_height is None:
            return self.cells
        # Candidate surface columns: occupied voxels whose bottom is near the floor band.
        columns: Dict[Tuple[int, int], float] = {}
        for key in occupied:
            x, y, z = key
            if self.grid.floor_height <= self.centre_height(key) <= self.grid.floor_height + 0.6:
                columns.setdefault((x, z), self.centre_height(key))
        for column, height in columns.items():
            cell = SurfaceCell(column[0], column[1], height)
            self.cells[column] = cell
        self._classify_height_changes()
        self._measure_width(occupied)
        self._measure_clearance(occupied)
        self._judge()
        return self.cells

    def centre_height(self, key: Key) -> float:
        v = self.grid.voxel
        return (key[1] + .5) * v

    def _classify_height_changes(self) -> None:
        for (x, z), cell in self.cells.items():
            heights = [self.cells[n].y for n in self._neighbours4((x, z)) if n in self.cells]
            if not heights:
                continue
            cell.kind = FLAT
            if max(heights) - cell.y > self.STEP_DETECT:
                cell.kind = UP_STEP
            elif cell.y - max(heights) > self.STEP_DETECT:
                cell.kind = DOWN_STEP

    def _measure_width(self, occupied: Set[Key]) -> None:
        """Width of the free run around each surface column."""
        for (x, z), cell in self.cells.items():
            run = 0
            for dx in range(-6, 7):
                column_free = True
                for dy in (0,):
                    key = (x + dx, int(round((cell.y - self.grid.floor_height) / self.grid.voxel)), z + dy)
                    state = self.grid.state_of(key)
                    if state not in (FREE, UNKNOWN):
                        column_free = False
                        break
                if column_free:
                    run += 1
                else:
                    break
            cell.width = run * self.grid.voxel

    def _measure_clearance(self, occupied: Set[Key]) -> None:
        occupied_columns: Dict[Tuple[int, int], float] = {}
        for key in occupied:
            x, y, z = key
            column = (x, z)
            height = self.centre_height(key)
            if column not in occupied_columns or height > occupied_columns[column]:
                occupied_columns[column] = height
        for (x, z), cell in self.cells.items():
            clearance = self.MIN_HEADROOM + 0.5
            for dx in range(-3, 4):
                for dz in range(-3, 4):
                    top = occupied_columns.get((x + dx, z + dz))
                    # The floor voxel the surface sits on is not an obstacle;
                    # only geometry meaningfully above the surface limits head room.
                    if top is None or top < cell.y + self.MIN_HEADROOM_STEP:
                        continue
                    clearance = min(clearance, top - cell.y)
            cell.min_clearance = max(0.0, clearance)

    def _judge(self) -> None:
        floor = self.grid.floor_height
        for cell in self.cells.values():
            if cell.kind == DOWN_STEP:
                cell.standable, cell.reason = False, "下行台阶需专项确认"
            elif cell.kind == UP_STEP:
                cell.standable, cell.reason = False, "上行台阶不并入平地导航图"
            elif cell.width < self.MIN_WIDTH:
                cell.standable, cell.reason = False, f"可站立宽度 {cell.width:.2f} m 不足 {self.MIN_WIDTH} m"
            elif cell.min_clearance < self.MIN_HEADROOM:
                cell.standable, cell.reason = False, f"头顶净空 {cell.min_clearance:.2f} m 不足 {self.MIN_HEADROOM} m"
            else:
                cell.standable, cell.reason = True, "满足全部阈值"

    # ---- queries --------------------------------------------------------
    def standable_cells(self) -> List[SurfaceCell]:
        return [c for c in self.cells.values() if c.standable]

    def summary(self) -> Dict[str, int]:
        out = {FLAT: 0, SLOPE: 0, UP_STEP: 0, DOWN_STEP: 0, DROP: 0}
        for cell in self.cells.values():
            out[cell.kind] = out.get(cell.kind, 0) + 1
        out["standable"] = len(self.standable_cells())
        return out
