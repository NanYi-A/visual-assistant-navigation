"""Validate a capture and export its coarse accumulated world points as ASCII PLY."""
from __future__ import annotations

import argparse
from pathlib import Path
import struct

from protocol import iter_camera_points, parse_frame, world_point

LENGTH = struct.Struct(">I")


def read_capture(path: Path):
    with path.open("rb") as source:
        while prefix := source.read(4):
            if len(prefix) != 4:
                raise ValueError("truncated packet length")
            size = LENGTH.unpack(prefix)[0]
            payload = source.read(size)
            if len(payload) != size:
                raise ValueError("truncated packet payload")
            yield parse_frame(payload)


def export_ply(capture: Path, output: Path, frame_stride: int, pixel_stride: int) -> None:
    points: list[tuple[float, float, float, int]] = []
    previous_depth_timestamp = -1
    sessions: set[int] = set()
    frame_count = 0
    for index, frame in enumerate(read_capture(capture)):
        frame_count += 1; sessions.add(frame.session_id)
        if frame.depth_timestamp_ns <= previous_depth_timestamp:
            raise ValueError(f"non-increasing depth timestamp at frame {frame.frame_id}")
        previous_depth_timestamp = frame.depth_timestamp_ns
        if index % frame_stride:
            continue
        for x, y, z, confidence in iter_camera_points(frame, stride=pixel_stride):
            wx, wy, wz = world_point(frame.camera_to_world, (x, y, z))
            points.append((wx, wy, wz, confidence))
    if len(sessions) != 1:
        raise ValueError(f"capture contains {len(sessions)} sessions; export them separately")
    with output.open("w", encoding="ascii", newline="\n") as ply:
        ply.write("ply\nformat ascii 1.0\n")
        ply.write(f"element vertex {len(points)}\n")
        ply.write("property float x\nproperty float y\nproperty float z\nproperty uchar confidence\nend_header\n")
        for point in points:
            ply.write(f"{point[0]:.5f} {point[1]:.5f} {point[2]:.5f} {point[3]}\n")
    print(f"validated {frame_count} frames; exported {len(points)} points to {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--frame-stride", type=int, default=1)
    parser.add_argument("--pixel-stride", type=int, default=2)
    args = parser.parse_args()
    export_ply(args.capture, args.output or args.capture.with_suffix(".ply"),
               max(1, args.frame_stride), max(1, args.pixel_stride))
