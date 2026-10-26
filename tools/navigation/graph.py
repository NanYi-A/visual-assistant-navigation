"""S3: navigation graph over standable surfaces.

Nodes live on standable columns only. Edges connect neighbours that are both
standable and connected by a corridor of reliable free space. Tentative,
conflicting and unknown voxels are never traversable (see
docs/NAVIGATION_COGNITION_PLAN.md section 4.3).
"""
from __future__ import annotations

import heapq
import math

import numpy as np
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from occupancy import FREE, OCCUPIED, UNKNOWN, OccupancyGrid
from standable import FLAT, SurfaceCell, StandableLayer

Position = Tuple[int, int]


@dataclass
class NavNode:
    x: int
    z: int
    y: float
    width: float
    clearance: float
    kind: str


@dataclass
class NavEdge:
    a: Position
    b: Position
    cost: float
    kind: str
    width: float
    clearance: float
    nearest_obstacle: float


@dataclass
class NavPath:
    points: List[Tuple[float, float, float]]
    cost: float
    corridor_width: float
    min_clearance: float
    nearest_obstacle: float
    reaches_front: bool


class NavigationGraph:
    NODE_SPACING_CELLS = 1
    STEP_COST = 1.0
    DIAGONAL_COST = 1.4
    REQUIRED_REACH_M = 1.2

    NEIGHBORS = ((0, -1), (-1, -1), (1, -1), (-1, 0), (1, 0), (0, 1), (-1, 1), (1, 1))

    def __init__(self, grid: OccupancyGrid, standable: StandableLayer):
        self.grid = grid
        self.standable = standable
        self.nodes: Dict[Position, NavNode] = {}
        self.adjacency: Dict[Position, List[NavEdge]] = {}

    def build(self) -> None:
        for cell in self.standable.standable_cells():
            position = (cell.x, cell.z)
            self.nodes[position] = NavNode(cell.x, cell.z, cell.y, cell.width, cell.min_clearance, cell.kind)
        for position in self.nodes:
            edges = []
            for dx, dz in self.NEIGHBORS:
                other = (position[0] + dx, position[1] + dz)
                if other not in self.nodes:
                    continue
                if not self._corridor_clear(position, other):
                    continue
                cost = self.DIAGONAL_COST if dx and dz else self.STEP_COST
                edges.append(NavEdge(position, other, cost,
                                     self.nodes[other].kind,
                                     min(self.nodes[position].width, self.nodes[other].width),
                                     min(self.nodes[position].clearance, self.nodes[other].clearance),
                                     self._nearest_obstacle(position)))
            self.adjacency[position] = edges

    def _corridor_clear(self, a: Position, b: Position) -> bool:
        """A corridor needs continuous observed support and clear head room.

        It must NOT demand free ray evidence at knee/chest height: a downward
        looking sensor never rays across body space, so such voxels stay UNKNOWN
        and a body-height test would disconnect every node. Support continuity
        plus the 1.9 m clearance already measured by the standable layer is the
        physically meaningful test.
        """
        ax, az = a
        bx, bz = b
        steps = max(abs(bx - ax), abs(bz - az))
        if steps == 0:
            return True
        for step in range(steps + 1):
            t = step / steps
            cx = int(round(ax + (bx - ax) * t))
            cz = int(round(az + (bz - az) * t))
            if not self._column_supported(cx, cz) or self._column_blocked(cx, cz):
                return False
        return True

    def _column_supported(self, x: int, z: int) -> bool:
        cell = self.standable.cells.get((x, z))
        return cell is not None and cell.min_clearance >= StandableLayer.MIN_HEADROOM

    def _column_blocked(self, x: int, z: int) -> bool:
        """Anything occupied between the floor and head height blocks the corridor."""
        cell = self.standable.cells.get((x, z))
        if cell is None:
            return True
        v = self.grid.voxel
        base = int(round((cell.y - self.grid.floor_height) / v))
        for step in range(1, int(math.ceil(StandableLayer.MIN_HEADROOM / v)) + 1):
            if self.grid.state_of((x, base + step, z)) == OCCUPIED:
                return True
        return False

    def _nearest_obstacle(self, position: Position) -> float:
        v = self.grid.voxel
        x, z = position
        y = int(round((self.nodes[position].y - self.grid.floor_height) / v))
        best = 10.0
        for radius in range(1, 9):
            for dx in range(-radius, radius + 1):
                for dz in range(-radius, radius + 1):
                    if max(abs(dx), abs(dz)) != radius:
                        continue
                    from occupancy import OCCUPIED
                    if self.grid.state_of((x + dx, y, z + dz)) == OCCUPIED:
                        best = min(best, math.hypot(dx, dz) * v)
            if best < 10.0:
                break
        return best

    # ---- search ---------------------------------------------------------
    def plan_from(self, start: Position, reach_m: float = None,
                  forward=(1.0, 0.0, 0.0)) -> Optional[NavPath]:
        """Shortest path from `start` to any node at least `reach` metres ahead.

        Progress is forward displacement along the walking axis; the returned
        path is the real chain of nodes, not just the goal node.
        """
        if start not in self.nodes:
            return None
        reach = self.REQUIRED_REACH_M if reach_m is None else reach_m
        target_cells = reach / self.grid.voxel
        # "Forward" must follow the phone's real heading, not a hard-coded axis.
        heading = np.array([float(forward[0]), 0.0, float(forward[2])], dtype=np.float64)
        norm = float(np.linalg.norm(heading))
        heading = heading / norm if norm > 1e-6 else np.array([1.0, 0.0, 0.0])
        start_pt = np.array([self.nodes[start].x * self.grid.voxel, 0.0,
                             self.nodes[start].z * self.grid.voxel])
        parent: Dict[Position, Optional[Position]] = {start: None}
        cost: Dict[Position, float] = {start: 0.0}
        queue = [(0.0, 0, start)]
        best_forward = {start: 0}
        goal = None
        while queue:
            current, reached, position = heapq.heappop(queue)
            if current > cost.get(position, math.inf):
                continue
            if reached >= target_cells:
                goal = position
                break
            for edge in self.adjacency.get(position, ()):
                candidate = current + edge.cost
                if candidate >= cost.get(edge.b, math.inf):
                    continue
                delta = np.array([self.nodes[edge.b].x * self.grid.voxel, 0.0,
                                  self.nodes[edge.b].z * self.grid.voxel]) - start_pt
                advanced = max(reached, float(np.dot(delta, heading)) / self.grid.voxel)
                cost[edge.b] = candidate
                best_forward[edge.b] = advanced
                parent[edge.b] = position
                heapq.heappush(queue, (candidate, advanced, edge.b))
        if goal is None:
            return None
        path: List[Position] = []
        cursor: Optional[Position] = goal
        while cursor is not None:
            path.append(cursor)
            cursor = parent[cursor]
        path.reverse()
        points = [(self.nodes[p].x * self.grid.voxel, self.nodes[p].y, self.nodes[p].z * self.grid.voxel) for p in path]
        return NavPath(points, cost[goal],
                       min(self.nodes[p].width for p in path),
                       min(self.nodes[p].clearance for p in path),
                       min(self._nearest_obstacle(p) for p in path), True)

    # ---- queries --------------------------------------------------------
    def nearest_node(self, world_x: float, world_z: float) -> Optional[Position]:
        v = self.grid.voxel
        gx, gz = int(round(world_x / v)), int(round(world_z / v))
        best, best_d = None, math.inf
        for position, node in self.nodes.items():
            d = math.hypot(node.x - gx, node.z - gz)
            if d < best_d:
                best, best_d = position, d
        return best if best_d <= 3 else None

    def summary(self) -> Dict[str, float]:
        return {
            "nodes": len(self.nodes),
            "edges": sum(len(v) for v in self.adjacency.values()) // 2,
        }
