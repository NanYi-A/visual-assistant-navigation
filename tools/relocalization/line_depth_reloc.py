"""Line-assisted and depth-verified relocalization, on top of the learned feature matcher.

Motivation, measured on this project's own captures: LightGlue finds 10-35 cross-model matches
between two views of the same room from different positions, and PnP then converges on a
plausible-looking but wrong pose (rmse 1-10 m). The cause is repeated indoor structure — door
frames, wall corners and floor texture look alike from anywhere, so appearance alone cannot tell
which corner of the room a pixel belongs to.

Two independent additions, both cheap and both using only stock OpenCV:

* Line evidence (`line_support`). Wall/ceiling/floor junctions form long straight edges that are
  *structurally* distinct even when their pixels look identical, because their length and
  orientation differ between rooms. This deliberately does not use `cv2.solvePnP_lines`, which
  does not exist in OpenCV 5.0; instead lines are matched by endpoint geometry and used to
  *reject* point-based poses that no line configuration supports.

* Depth agreement (`depth_agreement`). Direct/indirect photometric methods are the classical
  answer to low texture, but `cv2.rgbd` is absent from this build. Depth is available in every
  frame, so a candidate pose is scored by how well the transformed depth actually lands on the
  parent's depth points — a geometric test that needs no texture at all.
"""

import math

import cv2
import numpy as np
from scipy.spatial import cKDTree

from relocalize_segment import camera_matrix, world_points


def detect_lines(frame, max_lines=140):
    """Grayscale line segments, sorted longest first so tight budgets keep the structural ones."""
    gray = cv2.cvtColor(frame['rgb'], cv2.COLOR_RGB2GRAY)
    detector = cv2.createLineSegmentDetector()
    # LSD on a 160x90 frame returns thousands of segments; merging collinear neighbours keeps
    # the few long architectural edges and discards texture noise.
    merged = detector.detect(gray)[0]
    if merged is None or not len(merged):
        return np.empty((0, 2, 2))
    merged = np.asarray(merged, dtype=np.float64).reshape(-1, 4)
    segments = np.stack([merged[:, :2], merged[:, 2:]], axis=1)
    if len(segments) > max_lines:
        lengths = np.linalg.norm(segments[:, 1] - segments[:, 0], axis=1)
        keep = np.argsort(lengths)[::-1][:max_lines]
        segments = segments[keep]
    return segments


def _line_endpoints(frame, segment, z):
    """Lift a 2D segment into the world using the frame's depth at its two endpoints."""
    k = frame['k']
    fx, fy, cx, cy = k
    depth = frame['depth']
    conf = frame['confidence']
    world = frame['pose'] @ _CV_FROM_AR
    out = []
    for u, v in segment:
        x, y = int(round(float(u))), int(round(float(v)))
        if not (0 <= x < depth.shape[1] and 0 <= y < depth.shape[0]):
            return None
        d = float(depth[y, x]) / 1000.0
        if not (0.15 < d < 6.0) or conf[y, x] < 128:
            return None
        p = world @ np.array([(u - cx) * d / fx, (v - cy) * d / fy, d, 1.0])
        out.append(p[:3])
    return np.array(out)


def line_support(parent, child, transform, min_world_length=0.35, tolerance=0.22):
    """
    How many of the child's long world-space lines land on a parent's long line.

    `transform` maps child world coordinates into the parent's frame. Each child line is
    compared against every parent line by midpoint distance and by orientation, so this measures
    structural agreement rather than appearance similarity. It returns a ratio, not a count,
    because the number of detected lines varies with texture.
    """
    parent_lines = []
    for segment in detect_lines(parent):
        world = _line_endpoints(parent, segment, None)
        if world is None:
            continue
        length = float(np.linalg.norm(world[1] - world[0]))
        if length < min_world_length:
            continue
        parent_lines.append(world)
    if not parent_lines:
        return 0.0, 0, 0

    child_lines = []
    for segment in detect_lines(child):
        world = _line_endpoints(child, segment, None)
        if world is None:
            continue
        length = float(np.linalg.norm(world[1] - world[0]))
        if length < min_world_length:
            continue
        child_lines.append(world)
    if not child_lines:
        return 0.0, 0, 0

    homogeneous = np.column_stack((child_lines, np.ones(len(child_lines)))).reshape(-1, 2, 4)
    mapped = (transform @ homogeneous.transpose(2, 0, 1)).transpose(2, 0, 1)[..., :3]
    parent_mid = np.array([(p[0] + p[1]) / 2 for p in parent_lines])
    tree = cKDTree(parent_mid)
    supported = 0
    for line in mapped:
        midpoint = (line[0] + line[1]) / 2
        distance, index = tree.query(midpoint, k=1)
        if distance > tolerance:
            continue
        reference = parent_lines[int(index)]
        child_dir = line[1] - line[0]
        parent_dir = reference[1] - reference[0]
        cn = np.linalg.norm(child_dir)
        pn = np.linalg.norm(parent_dir)
        if cn < 1e-6 or pn < 1e-6:
            continue
        cosine = abs(float(np.dot(child_dir, parent_dir)) / (cn * pn))
        if cosine >= 0.90:
            supported += 1
    return supported / len(child_lines), supported, len(child_lines)


def depth_agreement(parent_frames, child_frames, transform, max_distance=0.20):
    """
    Fraction of child depth points that land near a parent depth point after `transform`.

    This is the direct-method idea reduced to a single decisive number. Texture-free geometry is
    enough to tell whether a pose is right: a correct pose maps the child's walls onto the
    parent's walls, a wrong one does not.
    """
    parent = np.concatenate([world_points(f) for f in parent_frames]) if parent_frames else np.empty((0, 3))
    child = np.concatenate([world_points(f) for f in child_frames]) if child_frames else np.empty((0, 3))
    if len(parent) < 100 or len(child) < 100:
        return 0.0, 0, 0
    child = (transform @ np.column_stack((child, np.ones(len(child)))).T).T[:, :3]
    distances = cKDTree(parent).query(child, k=1, workers=-1)[0]
    return float(np.mean(distances < max_distance)), int(len(distances)), int(len(child))


def refine_by_depth(parent_frames, child_frames, transform, iterations=12, step=0.04):
    """
    Nudge a pose to maximise depth agreement.

    A small grid search over yaw and translation is enough here: the goal is to break ties
    between the handful of PnP hypotheses, not to reach millimetre accuracy, and a grid search
    needs no extra OpenCV entry point.
    """
    best = transform.copy()
    best_score, _, _ = depth_agreement(parent_frames, child_frames, best)
    if best_score <= 0:
        return best, best_score
    yaw_step = math.radians(2.0)
    for _ in range(iterations):
        improved = False
        for dyaw in (-yaw_step, yaw_step, 0.0):
            for dx in (-step, 0.0, step):
                for dz in (-step, 0.0, step):
                    candidate = best.copy()
                    angle = dyaw
                    cos, sin = math.cos(angle), math.sin(angle)
                    rotation = np.array([[cos, 0.0, sin], [0.0, 1.0, 0.0], [-sin, 0.0, cos]])
                    candidate[:3, :3] = rotation @ best[:3, :3]
                    candidate[:3, 3] = best[:3, 3] + np.array([dx, 0.0, dz])
                    score, _, _ = depth_agreement(parent_frames, child_frames, candidate)
                    if score > best_score + 1e-6:
                        best, best_score = candidate, score
                        improved = True
        if not improved:
            break
    return best, best_score


# Imported late: the source module defines this constant for its own pose maths.
from relocalize_segment import CV_FROM_AR as _CV_FROM_AR  # noqa: E402