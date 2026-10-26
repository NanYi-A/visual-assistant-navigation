import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from protocol import RgbdFrame
from rgbd_dataset import RgbdDatasetRecorder, load_dataset
from validate_rgbd_dataset import validate


def frame(frame_id, timestamp_ns, x=0.0, angle=0.0):
    c, s = np.cos(angle), np.sin(angle)
    pose = np.array(((c, 0, s, x), (0, 1, 0, 0), (-s, 0, c, 0), (0, 0, 0, 1)), np.float64)
    width, height = 8, 6
    return RgbdFrame(42, frame_id, timestamp_ns, timestamp_ns, timestamp_ns, timestamp_ns,
                     width, height, 100.0, 100.0, 3.5, 2.5, pose,
                     np.full((height, width), 1200, np.uint16), np.full((height, width), 255, np.uint8),
                     np.full((height, width, 3), (20, 80, 160), np.uint8))


class RgbdDatasetTests(unittest.TestCase):
    def test_records_motion_and_interval_keyframes_and_replays(self):
        with tempfile.TemporaryDirectory() as temporary:
            recorder = RgbdDatasetRecorder(temporary, translation_m=.12, rotation_deg=8, max_interval_s=.75)
            self.assertIsNotNone(recorder.offer(frame(1, 1_000_000_000)))
            self.assertIsNone(recorder.offer(frame(2, 1_100_000_000, x=.01)))
            self.assertIsNotNone(recorder.offer(frame(3, 1_200_000_000, x=.13)))
            self.assertIsNotNone(recorder.offer(frame(4, 2_100_000_000, x=.13)))
            dataset = Path(temporary) / '42' / 'rgbd_dataset'
            records = load_dataset(dataset)
            self.assertEqual([r['keyframeReason'] for r in records], ['first', 'translation', 'interval'])
            report = validate(dataset)
            self.assertEqual(report['status'], 'READY')
            self.assertEqual(report['keyframes'], 3)
            self.assertGreater(report['meanValidDepthFraction'], .99)

    def test_rejects_low_confidence_frame(self):
        with tempfile.TemporaryDirectory() as temporary:
            recorder = RgbdDatasetRecorder(temporary)
            item = frame(1, 1_000_000_000)
            item.confidence[:] = 0
            self.assertIsNone(recorder.offer(item))

    def test_camera_to_world_is_column_major(self):
        """Regression: the exporter used numpy's default C order, which pushed the
        translation to indices 3/7/11 and made every consumer see a motionless phone."""
        with tempfile.TemporaryDirectory() as temporary:
            recorder = RgbdDatasetRecorder(temporary, translation_m=.01, rotation_deg=.01, max_interval_s=99)
            self.assertIsNotNone(recorder.offer(frame(1, 1_000_000_000, x=.37, angle=.4)))
            record = load_dataset(Path(temporary) / '42' / 'rgbd_dataset')[0]
            pose = np.asarray(record['cameraToWorld'], dtype=np.float64)
            self.assertEqual(pose.size, 16)
            # Column-major 4x4 must expose translation at 12/13/14.
            self.assertAlmostEqual(pose[12], .37, places=5)
            self.assertAlmostEqual(pose[13], 0.0, places=5)
            self.assertAlmostEqual(pose[14], 0.0, places=5)
            # And it must reconstruct the rotation, not its transpose.
            rebuilt = pose.reshape(4, 4, order='F')
            c, s = np.cos(.4), np.sin(.4)
            expected = np.array(((c, 0, s), (0, 1, 0), (-s, 0, c)), np.float64)
            np.testing.assert_allclose(rebuilt[:3, :3], expected, atol=1e-6)
            self.assertAlmostEqual(rebuilt[0][3], .37, places=5)

    def test_stored_pose_keeps_camera_motion_visible(self):
        """Two keyframes 40 cm apart must not collapse onto the same cell."""
        with tempfile.TemporaryDirectory() as temporary:
            recorder = RgbdDatasetRecorder(temporary, translation_m=.01, rotation_deg=.01, max_interval_s=99)
            recorder.offer(frame(1, 1_000_000_000, x=0.0))
            recorder.offer(frame(2, 2_000_000_000, x=.4))
            records = load_dataset(Path(temporary) / '42' / 'rgbd_dataset')
            travel = sum(float(np.linalg.norm(np.subtract(
                b['cameraToWorld'][12:15], a['cameraToWorld'][12:15])))
                for a, b in zip(records, records[1:]))
            self.assertAlmostEqual(travel, .4, places=3)


if __name__ == '__main__':
    unittest.main()
