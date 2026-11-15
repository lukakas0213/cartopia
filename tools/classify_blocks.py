"""Tag every block in blocks.json with what the 3D village needs to decorate it.

Adds to each block:
  green  - share of the block that is park/wood on the dark base map (0..1)
  water  - [x, y] point on the block outline nearest to the river, or null when the block does not touch water
  areaM2 - real area in square metres
  isle   - the block outline pulled 2.8 px in from the road centre lines, so roads stay visible
           between the raised 3D islands (same inset as export_blocks.py); null for slivers

usage: python classify_blocks.py base_map.png blocks.json   (needs shapely)
The base map is drawn at 2x the block coordinates.
"""
import json
import math
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from shapely.geometry import Polygon

PARK = np.array([45, 49, 45])
RIVER = np.array([36, 40, 46])


def near(img, ref, tol=4):
    return (np.abs(img.astype(int) - ref).max(axis=2) <= tol)


def main(base_path, blocks_path):
    base = Image.open(base_path).convert("RGB").filter(ImageFilter.MedianFilter(9))
    arr = np.asarray(base)
    park, river = near(arr, PARK), near(arr, RIVER)
    scale = base.width / 1280
    data = json.load(open(blocks_path))
    v = data["view"]
    m_per_px = 156543.03392 * math.cos(math.radians(v["lat"])) / 2 ** v["zoom"]
    for b in data["blocks"]:
        poly = [(x * scale, y * scale) for x, y in b["draw"]]
        inner = Image.new("L", base.size, 0)
        ImageDraw.Draw(inner).polygon(poly, fill=255)
        ring = Image.new("L", base.size, 0)
        ImageDraw.Draw(ring).line(poly + [poly[0]], fill=255, width=int(16 * scale))
        im, rm = np.asarray(inner) > 0, (np.asarray(ring) > 0) & ~(np.asarray(inner) > 0)
        b["green"] = round(float(park[im].mean()) if im.any() else 0.0, 2)
        wet = river & rm
        if rm.any() and wet.mean() > 0 and wet.sum() / rm.sum() > 0.12:
            ys, xs = np.nonzero(wet)
            cx, cy = xs.mean() / scale, ys.mean() / scale
            px, py = min(b["draw"], key=lambda p: (p[0] - cx) ** 2 + (p[1] - cy) ** 2)
            b["water"] = [round(px, 1), round(py, 1)]
        else:
            b["water"] = None
        b["areaM2"] = round(b["area"] * m_per_px ** 2)
        isle = Polygon(b["edge"]).buffer(0).buffer(-2.8, join_style=2).simplify(0.8)
        if isle.geom_type == "MultiPolygon":
            isle = max(isle.geoms, key=lambda g: g.area)
        b["isle"] = [[round(x, 1), round(y, 1)] for x, y in list(isle.exterior.coords)[:-1]] if isle.area > 20 else None
    json.dump(data, open(blocks_path, "w"), ensure_ascii=False, separators=(",", ":"))
    print(sum(b["green"] > 0.35 for b in data["blocks"]), "green,", sum(b["water"] is not None for b in data["blocks"]), "waterside of", len(data["blocks"]))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
