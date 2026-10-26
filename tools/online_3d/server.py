"""Dependency-free live VA3D receiver and lossless packet recorder."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import socket
import struct
import time

from protocol import parse_frame

LENGTH = struct.Struct(">I")
MAX_PACKET = 4 * 1024 * 1024


def read_exact(connection: socket.socket, size: int) -> bytes:
    result = bytearray(size)
    view = memoryview(result)
    while view:
        received = connection.recv_into(view)
        if received == 0:
            raise EOFError
        view = view[received:]
    return bytes(result)


def serve(host: str, port: int, output_root: Path) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((host, port))
        listener.listen(2)
        print(f"VA3D listening on {host}:{port}; recordings: {output_root.resolve()}", flush=True)
        while True:
            connection, address = listener.accept()
            connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            capture = output_root / f"capture_{stamp}_{address[0].replace('.', '_')}.va3d"
            metadata = capture.with_suffix(".jsonl")
            print(f"connected {address}; recording {capture.name}", flush=True)
            frames = 0
            started = time.monotonic()
            try:
                with capture.open("wb") as raw, metadata.open("w", encoding="utf-8") as meta:
                    while True:
                        size = LENGTH.unpack(read_exact(connection, LENGTH.size))[0]
                        if size <= 0 or size > MAX_PACKET:
                            raise ValueError(f"invalid packet size {size}")
                        payload = read_exact(connection, size)
                        frame = parse_frame(payload)
                        raw.write(LENGTH.pack(size)); raw.write(payload)
                        meta.write(json.dumps({
                            "session_id": frame.session_id,
                            "frame_id": frame.frame_id,
                            "frame_timestamp_ns": frame.frame_timestamp_ns,
                            "depth_timestamp_ns": frame.depth_timestamp_ns,
                            "persistent_map": frame.persistent_map,
                            "received_wall_ns": time.time_ns(),
                            "width": frame.width, "height": frame.height,
                        }, separators=(",", ":")) + "\n")
                        frames += 1
                        if frames % 10 == 0:
                            elapsed = max(0.001, time.monotonic() - started)
                            print(f"frames={frames} rate={frames / elapsed:.1f}Hz "
                                  f"latest={frame.frame_id} depth={frame.width}x{frame.height}", flush=True)
            except EOFError:
                print(f"disconnected {address}; saved {frames} frames", flush=True)
            except Exception as error:
                print(f"connection failed: {error}; saved {frames} frames", flush=True)
            finally:
                connection.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=38400)
    parser.add_argument("--output", type=Path, default=Path("recordings"))
    args = parser.parse_args()
    serve(args.host, args.port, args.output)
