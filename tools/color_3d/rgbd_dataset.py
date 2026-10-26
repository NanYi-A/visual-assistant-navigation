import json
import math
import os
import threading
import time
import uuid
from pathlib import Path

import numpy as np
from PIL import Image


def _rotation_angle(a, b):
    relative = a[:3, :3].T @ b[:3, :3]
    cosine = float(np.clip((np.trace(relative) - 1.0) * 0.5, -1.0, 1.0))
    return math.acos(cosine)


def _quaternion_xyzw(rotation):
    # Stable matrix-to-quaternion conversion without adding scipy to the backend.
    m = np.asarray(rotation, dtype=np.float64)
    q = np.empty(4, dtype=np.float64)
    trace = float(np.trace(m))
    if trace > 0.0:
        s = math.sqrt(trace + 1.0) * 2.0
        q[:] = ((m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s,
                (m[1, 0] - m[0, 1]) / s, 0.25 * s)
    else:
        i = int(np.argmax(np.diag(m)))
        if i == 0:
            s = math.sqrt(max(1.0 + m[0, 0] - m[1, 1] - m[2, 2], 0.0)) * 2.0
            q[:] = (0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s,
                    (m[2, 1] - m[1, 2]) / s)
        elif i == 1:
            s = math.sqrt(max(1.0 + m[1, 1] - m[0, 0] - m[2, 2], 0.0)) * 2.0
            q[:] = ((m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s,
                    (m[0, 2] - m[2, 0]) / s)
        else:
            s = math.sqrt(max(1.0 + m[2, 2] - m[0, 0] - m[1, 1], 0.0)) * 2.0
            q[:] = ((m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s,
                    (m[1, 0] - m[0, 1]) / s)
    norm = np.linalg.norm(q)
    return q / norm if norm > 1e-12 else np.array((0.0, 0.0, 0.0, 1.0))


class RgbdDatasetRecorder:
    """Durable phone RGB-D keyframes for offline replay and navigation cognition.

    RGB and depth use the TUM folder convention, while metadata.jsonl preserves
    the per-frame phone calibration and ARCore pose. We intentionally do not run
    any single-tool demo executable: those hard-code Freiburg/Bonn
    intrinsics and would silently apply the wrong camera model to phone frames.
    """

    def __init__(self, models, translation_m=0.03, rotation_deg=2.5, max_interval_s=0.20,
                 confidence_threshold=128, minimum_valid_fraction=0.03):
        self.models = Path(models)
        self.translation_m = float(translation_m)
        self.rotation_rad = math.radians(rotation_deg)
        self.max_interval_s = float(max_interval_s)
        self.confidence_threshold = int(confidence_threshold)
        self.minimum_valid_fraction = float(minimum_valid_fraction)
        self._session = None
        self._last_pose = None
        self._last_timestamp_ns = None
        self._count = 0
        self._lock = threading.RLock()

    def _reset(self, session):
        self._session = int(session)
        self._last_pose = None
        self._last_timestamp_ns = None
        dataset = self.models / str(session) / 'rgbd_dataset'
        for name in ('rgb_sync', 'depth_sync', 'confidence'):
            (dataset / name).mkdir(parents=True, exist_ok=True)
        metadata = dataset / 'metadata.jsonl'
        self._count = sum(1 for _ in metadata.open('r', encoding='utf-8')) if metadata.exists() else 0
        return dataset

    def _is_keyframe(self, frame):
        if self._last_pose is None:
            return True, 'first'
        pose = np.asarray(frame.camera_to_world, dtype=np.float64)
        translation = float(np.linalg.norm(pose[:3, 3] - self._last_pose[:3, 3]))
        rotation = _rotation_angle(self._last_pose, pose)
        elapsed = max(0.0, (int(frame.frame_timestamp_ns) - self._last_timestamp_ns) / 1e9)
        if translation >= self.translation_m:
            return True, 'translation'
        if rotation >= self.rotation_rad:
            return True, 'rotation'
        if elapsed >= self.max_interval_s:
            return True, 'interval'
        return False, 'redundant'

    @staticmethod
    def _write_png(path, image):
        temporary = path.with_name(f'.{path.stem}.{uuid.uuid4().hex}.tmp{path.suffix}')
        Image.fromarray(image).save(temporary, format='PNG', compress_level=3)
        os.replace(temporary, path)

    def offer(self, frame):
        with self._lock:
            dataset = self._reset(frame.session_id) if self._session != frame.session_id else self.models / str(frame.session_id) / 'rgbd_dataset'
            valid = (frame.depth > 0) & (frame.confidence >= self.confidence_threshold)
            valid_fraction = float(valid.mean())
            if valid_fraction < self.minimum_valid_fraction:
                return None
            keep, reason = self._is_keyframe(frame)
            if not keep:
                return None

            timestamp = int(frame.frame_timestamp_ns) / 1e9
            stem = f'{timestamp:.9f}'
            rgb_relative = f'rgb_sync/{stem}.png'
            depth_relative = f'depth_sync/{stem}.png'
            confidence_relative = f'confidence/{stem}.png'
            depth = frame.depth.copy()
            depth[~valid] = 0
            self._write_png(dataset / rgb_relative, frame.rgb)
            self._write_png(dataset / depth_relative, depth)
            self._write_png(dataset / confidence_relative, frame.confidence)

            pose = np.asarray(frame.camera_to_world, dtype=np.float64)
            qx, qy, qz, qw = _quaternion_xyzw(pose[:3, :3])
            tx, ty, tz = pose[:3, 3]
            record = {
                'schemaVersion': 1, 'sessionId': str(frame.session_id), 'frameId': int(frame.frame_id),
                'timestamp': timestamp, 'frameTimestampNs': int(frame.frame_timestamp_ns),
                'depthTimestampNs': int(frame.depth_timestamp_ns), 'imageTimestampNs': int(frame.image_timestamp_ns),
                'rgb': rgb_relative, 'depth': depth_relative, 'confidence': confidence_relative,
                'width': int(frame.width), 'height': int(frame.height),
                'intrinsics': {'fx': float(frame.fx), 'fy': float(frame.fy), 'cx': float(frame.cx), 'cy': float(frame.cy)},
                'cameraToWorld': pose.reshape(-1, order='F').tolist(), 'validDepthFraction': valid_fraction,
                'keyframeReason': reason,
            }
            with (dataset / 'metadata.jsonl').open('a', encoding='utf-8', newline='\n') as stream:
                stream.write(json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n')
                stream.flush()
                os.fsync(stream.fileno())
            with (dataset / 'groundtruth.txt').open('a', encoding='ascii', newline='\n') as stream:
                stream.write(f'{timestamp:.9f} {tx:.9f} {ty:.9f} {tz:.9f} {qx:.9f} {qy:.9f} {qz:.9f} {qw:.9f}\n')
            calibration = {
                'schemaVersion': 1, 'width': int(frame.width), 'height': int(frame.height),
                'fx': float(frame.fx), 'fy': float(frame.fy), 'cx': float(frame.cx), 'cy': float(frame.cy),
                'depthScale': 1000.0, 'coordinateConvention': 'ARCore camera-to-world; images use OpenCV pixel axes',
                'updatedAt': time.time(),
            }
            target = dataset / 'calibration.json'
            temporary = dataset / f'.calibration.{uuid.uuid4().hex}.tmp'
            temporary.write_text(json.dumps(calibration, ensure_ascii=False, indent=2), encoding='utf-8')
            os.replace(temporary, target)
            self._last_pose = pose.copy()
            self._last_timestamp_ns = int(frame.frame_timestamp_ns)
            self._count += 1
            return {'path': dataset, 'count': self._count, 'record': record}


def load_dataset(dataset):
    dataset = Path(dataset)
    metadata = dataset / 'metadata.jsonl'
    if not metadata.exists():
        return []
    records = [json.loads(line) for line in metadata.read_text(encoding='utf-8').splitlines() if line.strip()]
    previous = -math.inf
    for record in records:
        timestamp = float(record['timestamp'])
        if timestamp <= previous:
            raise ValueError('dataset timestamps are not strictly increasing')
        previous = timestamp
        for key in ('rgb', 'depth', 'confidence'):
            if not (dataset / record[key]).is_file():
                raise FileNotFoundError(dataset / record[key])
    return records
