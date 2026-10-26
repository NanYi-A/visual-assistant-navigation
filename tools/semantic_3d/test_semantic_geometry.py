import struct
import tempfile
import unittest
import zlib
from pathlib import Path

import numpy as np

from .fuse_semantic_geometry import build_navigation_candidates, nearest_distances, occupancy_state, read_occupancy, semantic_role


class SemanticGeometryTests(unittest.TestCase):
    def test_occupancy_state_matches_kotlin_rules(self):
        self.assertEqual("RELIABLE_OCCUPIED", occupancy_state(6, 2, 0, 400, 0b11))
        self.assertEqual("RELIABLE_FREE", occupancy_state(-4, 0, 2, 0, 0))
        self.assertEqual("CONFLICT", occupancy_state(1, 2, 2, 400, 0b11))
        self.assertEqual("TENTATIVE", occupancy_state(6, 2, 0, 400, 0b1))

    def test_reads_java_occupancy_file_and_crc(self):
        payload = struct.pack(">iifqqi", 0x56414D50, 1, .08, 12, 34, 1)
        payload += struct.pack(">6i7q", 1, 2, 3, 6, 2, 0, 400, 80_000, 3, 9, 8, -1, 100)
        checksum = zlib.crc32(payload) & 0xFFFFFFFF
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "occupancy.vamap"
            path.write_bytes(payload + struct.pack(">Q", checksum))
            size, revision, cells = read_occupancy(path)
            self.assertAlmostEqual(.08, size, places=5); self.assertEqual(12, revision)
            self.assertEqual("RELIABLE_OCCUPIED", cells[(1, 2, 3)]["state"])

    def test_surface_distance_and_roles_are_conservative(self):
        distances = nearest_distances(np.array([[0., 0., 0.], [2., 0., 0.]], np.float32),
                                      np.array([[0.1, 0., 0.], [1., 0., 0.]], np.float32))
        np.testing.assert_allclose(distances, [.1, 1.], atol=1e-6)
        self.assertEqual("SUPPORT_CANDIDATE", semantic_role("floor"))
        self.assertEqual("STRUCTURE", semantic_role("door"))
        self.assertEqual("OBSTACLE_CANDIDATE", semantic_role("chair"))

    def test_navigation_requires_geometry_confirmed_floor_and_full_clearance(self):
        occupancy = {(0, 0, 0): {"state": "RELIABLE_OCCUPIED"}}
        radius = 2
        for height in (2, 7, 11, 16, 18):
            for x in range(-radius, radius + 1):
                for z in range(-radius, radius + 1):
                    if x * x + z * z <= radius * radius:
                        occupancy[(x, height, z)] = {"state": "RELIABLE_FREE"}
        floor = [{"x": 0, "y": 0, "z": 0, "className": "floor", "onTsdfSurface": True,
                  "occupancyState": "RELIABLE_OCCUPIED"}]
        for y in (0, -1, -2):
            occupancy[(1, y, 0)] = {"state": "RELIABLE_FREE"}
        navigation = build_navigation_candidates(floor, occupancy, .1)
        self.assertEqual(1, len(navigation["nodes"]))
        self.assertEqual(1, len(navigation["dropCandidates"]))
        occupancy.pop((0, 18, 0))
        navigation = build_navigation_candidates(floor, occupancy, .1)
        self.assertEqual(0, len(navigation["nodes"]))
        self.assertEqual(1, navigation["rejectedUnknownClearance"])


if __name__ == "__main__":
    unittest.main()
