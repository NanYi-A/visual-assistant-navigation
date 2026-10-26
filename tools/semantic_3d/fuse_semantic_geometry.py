"""Associate trusted DFormer voxels with persistent occupancy and the saved TSDF mesh.

This module deliberately does not produce a walkable/free decision.  Semantic labels
may explain an already observed surface, but geometry remains the safety authority.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import zlib
from pathlib import Path

import numpy as np


def occupancy_state(score: int, occupied: int, free: int, confidence_sum: int, view_mask: int) -> str:
    views = int(view_mask & 0xFFFFFFFFFFFFFFFF).bit_count()
    mean_confidence = confidence_sum / occupied if occupied else 0.0
    if occupied >= 2 and free >= 2 and abs(score) <= 3:
        return "CONFLICT"
    if score >= 6 and views >= 2 and mean_confidence >= 180:
        return "RELIABLE_OCCUPIED"
    if score <= -4 and free >= 2:
        return "RELIABLE_FREE"
    return "TENTATIVE"


def read_occupancy(path: Path) -> tuple[float, int, dict[tuple[int, int, int], dict]]:
    raw = path.read_bytes()
    if len(raw) < 40:
        raise ValueError("occupancy map is too short")
    payload, checksum_bytes = raw[:-8], raw[-8:]
    expected = struct.unpack(">Q", checksum_bytes)[0]
    actual = zlib.crc32(payload) & 0xFFFFFFFF
    if actual != expected:
        raise ValueError(f"occupancy CRC mismatch: expected {expected}, got {actual}")
    magic, version, voxel_size, revision, _timestamp, count = struct.unpack_from(">iifqqi", payload, 0)
    if magic != 0x56414D50 or version != 1 or not 0 <= count <= 2_000_000:
        raise ValueError("unsupported occupancy map")
    offset = 32
    cells: dict[tuple[int, int, int], dict] = {}
    record = struct.Struct(">6i7q")
    for _ in range(count):
        values = record.unpack_from(payload, offset); offset += record.size
        x, y, z, score, occupied, free = values[:6]
        confidence_sum, _confidence_square_sum, view_mask = values[6:9]
        cells[(x, y, z)] = {
            "state": occupancy_state(score, occupied, free, confidence_sum, view_mask),
            "score": score, "occupiedObservations": occupied, "freeObservations": free,
        }
    if offset != len(payload):
        raise ValueError("trailing occupancy payload")
    return voxel_size, revision, cells


def read_open3d_mesh_vertices(path: Path) -> np.ndarray:
    with path.open("rb") as stream:
        header_lines = []
        while True:
            line = stream.readline()
            if not line:
                raise ValueError("PLY header is incomplete")
            decoded = line.decode("ascii").strip(); header_lines.append(decoded)
            if decoded == "end_header":
                break
        if "format binary_little_endian 1.0" not in header_lines:
            raise ValueError("only Open3D binary little-endian PLY is supported")
        vertex_line = next(line for line in header_lines if line.startswith("element vertex "))
        count = int(vertex_line.split()[-1])
        # The color backend writes x/y/z + nx/ny/nz as doubles and RGB as bytes.
        dtype = np.dtype([(name, "<f8") for name in ("x", "y", "z", "nx", "ny", "nz")] +
                         [(name, "u1") for name in ("red", "green", "blue")])
        vertices = np.frombuffer(stream.read(count * dtype.itemsize), dtype=dtype, count=count)
        return np.column_stack((vertices["x"], vertices["y"], vertices["z"])).astype(np.float32)


def nearest_distances(points: np.ndarray, surface: np.ndarray, chunk_size: int = 128) -> np.ndarray:
    result = np.full(len(points), np.inf, np.float32)
    for start in range(0, len(points), chunk_size):
        batch = points[start:start + chunk_size]
        squared = np.sum((batch[:, None, :] - surface[None, :, :]) ** 2, axis=2)
        result[start:start + len(batch)] = np.sqrt(np.min(squared, axis=1))
    return result


def semantic_role(name: str) -> str:
    if name in {"floor", "floor mat"}:
        return "SUPPORT_CANDIDATE"
    if name in {"wall", "door", "window", "otherstructure", "ceiling"}:
        return "STRUCTURE"
    return "OBSTACLE_CANDIDATE"


def build_navigation_candidates(rows: list[dict], occupancy: dict[tuple[int, int, int], dict],
                                voxel_size: float, body_radius: float = 0.15,
                                body_height: float = 1.80) -> dict:
    radius = math.ceil(body_radius / voxel_size)
    heights = sorted({math.ceil(height / voxel_size) for height in (.20, .65, 1.10, 1.55, body_height)})
    support_keys = {
        (row["x"], row["y"], row["z"])
        for row in rows
        if row["onTsdfSurface"] and row["occupancyState"] == "RELIABLE_OCCUPIED"
        and row["className"] in {"floor", "floor mat"}
    }
    accepted = []
    rejected_unknown = rejected_blocked = 0
    layer_diagnostics = {str(round(dy * voxel_size, 2)): {"clear": 0, "unknown": 0, "blocked": 0}
                         for dy in heights}
    for x, y, z in sorted(support_keys):
        states = []
        for dy in heights:
            layer_states = []
            for dx in range(-radius, radius + 1):
                for dz in range(-radius, radius + 1):
                    if dx * dx + dz * dz <= radius * radius:
                        state = occupancy.get((x + dx, y + dy, z + dz), {}).get("state", "UNKNOWN")
                        states.append(state); layer_states.append(state)
            layer_key = str(round(dy * voxel_size, 2))
            if any(state in {"RELIABLE_OCCUPIED", "CONFLICT"} for state in layer_states):
                layer_diagnostics[layer_key]["blocked"] += 1
            elif all(state == "RELIABLE_FREE" for state in layer_states):
                layer_diagnostics[layer_key]["clear"] += 1
            else:
                layer_diagnostics[layer_key]["unknown"] += 1
        if all(state == "RELIABLE_FREE" for state in states):
            accepted.append((x, y, z))
        elif any(state in {"RELIABLE_OCCUPIED", "CONFLICT"} for state in states):
            rejected_blocked += 1
        else:
            rejected_unknown += 1
    # Keep a single support height per horizontal column. Multiple floors in one
    # column are ambiguous until room-level relocalization can select a storey.
    columns: dict[tuple[int, int], list[tuple[int, int, int]]] = {}
    for key in accepted:
        columns.setdefault((key[0], key[2]), []).append(key)
    unambiguous = {column: values[0] for column, values in columns.items() if len(values) == 1}
    ambiguous_columns = sum(len(values) > 1 for values in columns.values())
    nodes = []
    for index, key in enumerate(sorted(unambiguous.values())):
        nearby = [candidate for candidate in support_keys
                  if abs(candidate[0] - key[0]) <= 3 and abs(candidate[2] - key[2]) <= 3]
        slope = None
        if len(nearby) >= 5:
            horizontal = np.asarray([[candidate[0] * voxel_size, candidate[2] * voxel_size, 1.] for candidate in nearby])
            vertical = np.asarray([candidate[1] * voxel_size for candidate in nearby])
            coefficients, *_ = np.linalg.lstsq(horizontal, vertical, rcond=None)
            slope = math.degrees(math.atan(math.hypot(float(coefficients[0]), float(coefficients[1]))))
        nodes.append({"id": index, "x": key[0], "floorY": key[1], "z": key[2],
                      "slopeDegrees": round(slope, 2) if slope is not None else None,
                      "surface": "LEVEL_CANDIDATE" if slope is not None and slope <= 8 else
                                 "RAMP_CANDIDATE" if slope is not None and slope <= 15 else
                                 "STEEP_OR_UNCERTAIN"})
    by_column = {(node["x"], node["z"]): node for node in nodes}
    edges = []; step_candidates = []; drop_candidates = []
    for node in nodes:
        for dx, dz in ((1, 0), (0, 1)):
            other = by_column.get((node["x"] + dx, node["z"] + dz))
            if other is None:
                nx, ny, nz = node["x"] + dx, node["floorY"], node["z"] + dz
                below = [occupancy.get((nx, ny - amount, nz), {}).get("state", "UNKNOWN") for amount in (0, 1, 2)]
                if all(state == "RELIABLE_FREE" for state in below):
                    drop_candidates.append({"from": node["id"], "dx": dx, "dz": dz,
                                            "minimumObservedDropMeters": round(2 * voxel_size, 3)})
                continue
            delta = abs(other["floorY"] - node["floorY"]) * voxel_size
            if delta <= .10:
                edges.append([node["id"], other["id"]])
            elif delta <= .20:
                step_candidates.append({"from": node["id"], "to": other["id"],
                                        "heightMeters": round(delta, 3)})
    return {
        "schemaVersion": 1, "safeForNavigation": False,
        "status": "CANDIDATES_REQUIRE_VALIDATION" if nodes else "NO_FULL_BODY_CLEARANCE",
        "voxelSize": voxel_size, "bodyRadiusMeters": body_radius, "bodyHeightMeters": body_height,
        "sourceSupportCandidates": len(support_keys), "rejectedUnknownClearance": rejected_unknown,
        "rejectedBlockedClearance": rejected_blocked, "ambiguousColumns": ambiguous_columns,
        "clearanceByHeightMeters": layer_diagnostics,
        "nodes": nodes, "edges": edges, "stepCandidates": step_candidates,
        "dropCandidates": drop_candidates,
        "limitations": ["unknown space is never accepted", "semantic labels cannot create free space",
                        "drop edges, ramps and stairs still require geometric validation"],
    }


def fuse(model: Path, maximum_surface_distance: float = 0.14) -> dict:
    semantic_path = model / "semantics" / "dformerv2" / "semantic_voxels.npz"
    occupancy_path = model / "cognition" / "occupancy.vamap"
    mesh_path = model / "scan" / "colored_mesh.ply"
    with np.load(semantic_path) as semantic:
        xyz = semantic["xyz"].astype(np.float32)
        labels = semantic["labels"].astype(np.uint8)
        agreement = semantic["agreement"].astype(np.float32)
        frame_counts = semantic["frame_counts"].astype(np.uint8)
        names = [str(value) for value in semantic["class_names"].tolist()]
        semantic_voxel_size = float(semantic["voxel_size"])
    occupancy_voxel_size, revision, occupancy = read_occupancy(occupancy_path)
    if not math.isclose(semantic_voxel_size, occupancy_voxel_size, abs_tol=1e-4):
        raise ValueError("semantic and occupancy voxel sizes differ")
    surface = read_open3d_mesh_vertices(mesh_path)
    trusted = (frame_counts >= 2) & (agreement >= 0.7)
    trusted_xyz = xyz[trusted]; trusted_labels = labels[trusted]
    trusted_agreement = agreement[trusted]; trusted_frames = frame_counts[trusted]
    surface_distances = nearest_distances(trusted_xyz, surface)
    rows = []
    for point, label, certainty, frames, distance in zip(
            trusted_xyz, trusted_labels, trusted_agreement, trusted_frames, surface_distances):
        key = tuple(int(math.floor(float(axis) / occupancy_voxel_size)) for axis in point)
        occupied = occupancy.get(key)
        on_surface = float(distance) <= maximum_surface_distance
        rows.append({
            "x": key[0], "y": key[1], "z": key[2], "classId": int(label),
            "className": names[int(label)], "role": semantic_role(names[int(label)]),
            "agreement": round(float(certainty), 4), "sourceFrames": int(frames),
            "nearestSurfaceMeters": round(float(distance), 4), "onTsdfSurface": on_surface,
            "occupancyState": occupied["state"] if occupied else "UNKNOWN",
        })
    surface_rows = [row for row in rows if row["onTsdfSurface"]]
    geometry_confirmed = [row for row in surface_rows if row["occupancyState"] == "RELIABLE_OCCUPIED"]
    result = {
        "schemaVersion": 1, "modelId": model.name, "sourceOccupancyRevision": revision,
        "voxelSizeMeters": occupancy_voxel_size,
        "policy": {
            "semanticCanCreateFreeSpace": False,
            "safeForNavigation": False,
            "minimumSourceFrames": 2, "minimumAgreement": 0.7,
            "maximumTsdfSurfaceDistanceMeters": maximum_surface_distance,
        },
        "counts": {
            "trustedSemantic": len(rows), "onTsdfSurface": len(surface_rows),
            "geometryConfirmedOccupied": len(geometry_confirmed),
            "supportCandidates": sum(row["role"] == "SUPPORT_CANDIDATE" for row in geometry_confirmed),
            "structure": sum(row["role"] == "STRUCTURE" for row in geometry_confirmed),
            "obstacleCandidates": sum(row["role"] == "OBSTACLE_CANDIDATE" for row in geometry_confirmed),
        },
        "cells": rows,
    }
    target = model / "cognition" / "semantic_geometry.json"
    temporary = target.with_suffix(".tmp.json")
    temporary.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(target)
    navigation = build_navigation_candidates(rows, occupancy, occupancy_voxel_size)
    navigation_target = model / "cognition" / "semantic_navigation.json"
    navigation_temporary = navigation_target.with_suffix(".tmp.json")
    navigation_temporary.write_text(json.dumps(navigation, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    navigation_temporary.replace(navigation_target)
    result["navigationCandidates"] = {
        "status": navigation["status"], "nodes": len(navigation["nodes"]),
        "edges": len(navigation["edges"]), "stepCandidates": len(navigation["stepCandidates"]),
        "dropCandidates": len(navigation["dropCandidates"]),
        "rejectedUnknownClearance": navigation["rejectedUnknownClearance"],
        "rejectedBlockedClearance": navigation["rejectedBlockedClearance"],
    }
    return result


def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--model", type=Path, required=True)
    args = parser.parse_args(); result = fuse(args.model.resolve())
    print(json.dumps({"modelId": result["modelId"], **result["counts"],
                      "navigationCandidates": result["navigationCandidates"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
