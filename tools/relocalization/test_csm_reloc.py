"""Tests for the correlative scan matcher.

The point of these tests is not that the matcher runs, but that it fails in
the right way: a scan that matches nothing must be refused, and a scan that has
been slid onto the map's centre of mass must score worse than the true pose.
That second case is the one that made the earlier depth-agreement metric
useless, and it must not come back.
"""

import math
import unittest

import numpy as np

from csm_reloc import (MatchResult, OccupancyGrid, match, transform_to_matrix,
                       _normalise)

CELL = 0.08


def make_room(width=40, height=30, furniture=((12, 8), (12, 9), (12, 10), (25, 20))):
    """A room outline with a few pieces of furniture, in grid cells."""
    obstacles = set()
    for x in range(width + 1):
        obstacles.add((x, 0))
        obstacles.add((x, height))
    for z in range(height + 1):
        obstacles.add((0, z))
        obstacles.add((width, z))
    for cell in furniture:
        obstacles.add(cell)
    return OccupancyGrid(CELL, obstacles)


def make_scan(grid, truth_x, truth_z, truth_yaw, count=140, noise=0.015, seed=7):
    """Scan points of the map's obstacles, seen from a known pose."""
    rng = np.random.default_rng(seed)
    obstacles = sorted(grid.obstacles)
    picked = rng.choice(len(obstacles), size=min(count, len(obstacles)), replace=False)
    world = np.array([[(obstacles[i][0] + 0.5) * grid.cell_size,
                       (obstacles[i][1] + 0.5) * grid.cell_size] for i in picked])
    world = world + rng.normal(0.0, noise, world.shape)
    cos_yaw, sin_yaw = math.cos(truth_yaw), math.sin(truth_yaw)
    dx = world[:, 0] - truth_x
    dz = world[:, 1] - truth_z
    return np.column_stack((cos_yaw * dx + sin_yaw * dz, -sin_yaw * dx + cos_yaw * dz))


class CorrelativeScanMatcherTest(unittest.TestCase):

    def test_theDistanceFieldGrowsAwayFromObstacles(self):
        grid = make_room()
        # The wall along z = 0, measured from a cell on it and from a step inside.
        wall = ((10 + 0.5) * CELL, (0 + 0.5) * CELL)
        on_wall = grid.distance_at(*wall)
        inside = grid.distance_at(wall[0], wall[1] + 0.3)
        self.assertAlmostEqual(0.0, on_wall)
        # The field is sampled on a grid, so the exact value is not asserted; what matters is
        # that it grows as the point moves away from the wall.
        self.assertTrue(inside > on_wall + 0.2, f"distance did not grow: {on_wall} -> {inside}")

    def test_groundOutsideTheMapHasNoDistance(self):
        grid = make_room()
        self.assertIsNone(grid.distance_at(1000.0, 1000.0))

    def test_itCorrectsAKnownPoseError(self):
        grid = make_room()
        truth = (1.2, 0.9, 0.7)
        points = make_scan(grid, *truth)
        # The prior is what a drifting session would report.
        prior = (truth[0] + 0.15, truth[1] - 0.12, truth[2] + 0.08)
        result = match(grid, points, prior[0], prior[1], prior[2])
        self.assertTrue(result.accepted, f"the match was refused: {result}")
        self.assertLess(abs(result.x - truth[0]), 0.05, f"position error {result.x - truth[0]}")
        self.assertLess(abs(result.z - truth[1]), 0.05, f"position error {result.z - truth[1]}")
        self.assertLess(abs(_normalise(result.yaw - truth[2])), 0.03,
                        f"heading error {result.yaw - truth[2]}")

    def test_itRefusesAScanThatMatchesNothing(self):
        grid = make_room()
        rng = np.random.default_rng(3)
        points = rng.uniform(-3.0, 3.0, size=(200, 2))
        result = match(grid, points, 0.0, 0.0, 0.0)
        self.assertFalse(result.accepted)

    def test_slidingTheScanOntoTheCentreOfMassLosesTheMatch(self):
        """A scan shifted onto the map's centroid must not look as good as the true pose.

        This is the failure that made the one-sided depth agreement number
        meaningless: sliding the points onto the average of the map always looks
        decent, because the average is where most of the mass is.
        """
        grid = make_room()
        truth = (1.2, 0.9, 0.7)
        points = make_scan(grid, *truth)
        good = match(grid, points, truth[0], truth[1], truth[2])
        self.assertTrue(good.accepted)

        centroid = np.mean(
            np.array([[(ix + 0.5) * grid.cell_size, (iz + 0.5) * grid.cell_size]
                      for ix, iz in sorted(grid.obstacles)]), axis=0)
        shifted = points + (centroid[0] - truth[0], centroid[1] - truth[1])
        bad = match(grid, shifted, truth[0], truth[1], truth[2])
        self.assertLess(bad.score, good.score * 0.9,
                        f"a centred scan scored {bad.score}, the true pose scored {good.score}")

    def test_aHeadingErrorBeyondTheSearchWindowIsRefusedNotGuessed(self):
        """The matcher only searches a small window around the prior.

        A scan whose heading is far off that window cannot be placed, and the
        honest answer is to refuse.  Returning a plausible-looking pose here is
        exactly the failure that made the earlier learned matcher useless.
        """
        grid = make_room()
        truth = (1.2, 0.9, 0.0)
        points = make_scan(grid, *truth)
        rotated = points @ np.array([[math.cos(0.8), -math.sin(0.8)],
                                     [math.sin(0.8), math.cos(0.8)]])
        result = match(grid, rotated, truth[0], truth[1], truth[2])
        self.assertFalse(result.accepted,
                         f"a scan that cannot be placed was accepted: {result}")

    def test_theTransformMapsTheReferenceFrameIntoTheMap(self):
        matrix = transform_to_matrix(1.0, 2.0, math.pi / 2)
        point = np.array([1.0, 0.0, 0.0, 1.0])
        mapped = matrix @ point
        self.assertAlmostEqual(1.0, mapped[0])
        self.assertAlmostEqual(3.0, mapped[2])


if __name__ == "__main__":
    unittest.main()
