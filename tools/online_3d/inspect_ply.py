"""Summarize and render top/side projections of an ASCII PLY without Open3D."""
from __future__ import annotations

import argparse
from pathlib import Path


def load_points(path: Path, limit: int = 400_000):
    points = []
    with path.open("r", encoding="ascii") as source:
        for line in source:
            if line.strip() == "end_header":
                break
        for index, line in enumerate(source):
            if index >= limit:
                break
            values = line.split()
            if len(values) >= 3:
                points.append(tuple(float(value) for value in values[:3]))
    return points


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int(fraction * (len(ordered) - 1))))]


def main(path: Path, output: Path):
    points = load_points(path)
    if not points:
        raise ValueError("PLY has no vertices")
    axes = list(zip(*points))
    print(f"points={len(points)}")
    for name, values in zip("xyz", axes):
        print(f"{name}: min={min(values):.3f} p01={percentile(values,.01):.3f} "
              f"p50={percentile(values,.5):.3f} p99={percentile(values,.99):.3f} max={max(values):.3f}")
    from PIL import Image, ImageDraw
    step = max(1, len(points) // 50_000)
    sample = points[::step]
    image = Image.new("RGB", (1200, 600), "white")
    draw = ImageDraw.Draw(image, "RGBA")

    def panel(values, left, title):
        horizontal, vertical = zip(*values)
        h0, h1 = percentile(horizontal, .01), percentile(horizontal, .99)
        v0, v1 = percentile(vertical, .01), percentile(vertical, .99)
        margin = 45
        width, height = 555, 500
        draw.rectangle((left + margin, margin, left + width, height + margin), outline=(80,80,80,255))
        draw.text((left + margin, 15), title, fill="black")
        draw.text((left + margin, height + 55), f"H {h0:.2f}..{h1:.2f} m; V {v0:.2f}..{v1:.2f} m", fill="black")
        for h, v in values:
            if not (h0 <= h <= h1 and v0 <= v <= v1):
                continue
            px = left + margin + int((h-h0) / max(.001, h1-h0) * (width-margin))
            py = margin + int((v1-v) / max(.001, v1-v0) * height)
            draw.point((px, py), fill=(15, 95, 180, 75))

    panel([(x, z) for x, _, z in sample], 0, "Top view: X / Z")
    panel([(z, y) for _, y, z in sample], 600, "Side view: Z / Y")
    image.save(output)
    print(f"rendered {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("ply", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    main(args.ply, args.output)
