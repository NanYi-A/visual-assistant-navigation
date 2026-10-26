import tempfile
import unittest
from pathlib import Path

import numpy as np

from .infer_dformerv2_samples import palette, project_semantic_points, write_semantic_voxels


class SemanticProjectionTests(unittest.TestCase):
    def test_center_depth_projects_forward_in_ar_coordinates(self):
        depth = np.array([[1000]], np.uint16)
        confidence = np.array([[255]], np.uint8)
        labels = np.array([[7]], np.uint8)
        model_confidence = np.array([[0.9]], np.float32)
        points, point_labels, scores = project_semantic_points(
            depth, confidence, labels, model_confidence,
            np.array([100.0, 100.0, 0.0, 0.0], np.float32), np.eye(4, dtype=np.float32))
        np.testing.assert_allclose(points, [[0.0, 0.0, -1.0]], atol=1e-6)
        np.testing.assert_array_equal(point_labels, [7])
        np.testing.assert_allclose(scores, [0.9])

    def test_low_depth_or_model_confidence_is_rejected(self):
        depth = np.array([[1000, 1000]], np.uint16)
        confidence = np.array([[179, 255]], np.uint8)
        labels = np.array([[1, 1]], np.uint8)
        model_confidence = np.array([[0.9, 0.54]], np.float32)
        points, _, _ = project_semantic_points(
            depth, confidence, labels, model_confidence,
            np.array([100.0, 100.0, 0.0, 0.0], np.float32), np.eye(4, dtype=np.float32))
        self.assertEqual(0, len(points))

    def test_voxel_vote_records_cross_frame_agreement(self):
        observations = [
            (np.array([[0.01, 0.01, 0.01]], np.float32), np.array([1], np.uint8), np.array([0.9], np.float32)),
            (np.array([[0.02, 0.02, 0.02]], np.float32), np.array([1], np.uint8), np.array([0.8], np.float32)),
        ]
        with tempfile.TemporaryDirectory() as folder:
            summary = write_semantic_voxels(Path(folder), observations, palette(40),
                                            [str(index) for index in range(40)])
            self.assertEqual(1, summary['voxelCount'])
            self.assertEqual(1, summary['trustedVoxelCount'])
            with np.load(Path(folder) / 'semantic_voxels.npz') as saved:
                np.testing.assert_array_equal(saved['frame_counts'], [2])
                np.testing.assert_array_equal(saved['labels'], [1])


if __name__ == '__main__':
    unittest.main()
