"""VA3D v1 packet parser shared by the live recorder and replay tools."""
from __future__ import annotations

from dataclasses import dataclass
import struct

MAGIC = 0x56413344
VERSION = 2
MESSAGE_FRAME = 1
HEADER = struct.Struct(">IHHQQQQQIIIffff16f")


@dataclass(frozen=True)
class Frame:
    session_id: int
    frame_id: int
    frame_timestamp_ns: int
    depth_timestamp_ns: int
    sent_at_ns: int
    persistent_map: bool
    width: int
    height: int
    fx: float
    fy: float
    cx: float
    cy: float
    camera_to_world: tuple[float, ...]
    depth_mm: memoryview
    confidence: memoryview


def parse_frame(payload: bytes) -> Frame:
    if len(payload) < HEADER.size:
        raise ValueError("packet is shorter than the VA3D header")
    values = HEADER.unpack_from(payload)
    magic, version, kind = values[:3]
    if magic != MAGIC or version != VERSION or kind != MESSAGE_FRAME:
        raise ValueError(f"unsupported packet magic={magic:x} version={version} kind={kind}")
    session_id, frame_id, frame_ts, depth_ts, sent_at = values[3:8]
    flags, width, height = values[8:11]
    fx, fy, cx, cy = values[11:15]
    matrix = values[15:31]
    pixels = width * height
    expected = HEADER.size + pixels * 3
    if width <= 0 or height <= 0 or expected != len(payload):
        raise ValueError(f"invalid dimensions/payload: {width}x{height}, {len(payload)} bytes")
    depth_start = HEADER.size
    confidence_start = depth_start + pixels * 2
    return Frame(session_id, frame_id, frame_ts, depth_ts, sent_at, bool(flags & 1), width, height,
                 fx, fy, cx, cy, matrix,
                 memoryview(payload)[depth_start:confidence_start],
                 memoryview(payload)[confidence_start:])


def iter_camera_points(frame: Frame, min_confidence: int = 180, stride: int = 2):
    """Yield camera-frame points. ARCore depth camera looks along negative Z."""
    depth = frame.depth_mm
    confidence = frame.confidence
    for y in range(0, frame.height, stride):
        for x in range(0, frame.width, stride):
            i = y * frame.width + x
            certainty = confidence[i]
            z_mm = (depth[2 * i] << 8) | depth[2 * i + 1]
            if certainty < min_confidence or z_mm == 0:
                continue
            distance = z_mm / 1000.0
            yield ((x - frame.cx) * distance / frame.fx,
                   -(y - frame.cy) * distance / frame.fy,
                   -distance, certainty)


def world_point(matrix: tuple[float, ...], p: tuple[float, float, float]):
    """Transform a point with ARCore's column-major camera-to-world matrix."""
    x, y, z = p
    return (matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14])
