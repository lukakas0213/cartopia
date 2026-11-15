"""Add Seoul's tall buildings (20 floors or more, or 66 m or taller) to the app's map tiles, in place.

usage: python add_towers.py <towers.json from Overpass> <map dir: app/src/main/assets/map/seoul>
  towers.json: everything in 서울특별시 with building:levels >= 18 or height >= 60, plus every building:part,
               `out geom tags` (this script applies the 20 floor / 66 m cut itself)

Adds "towers" to each tile: [block id, height m, floors, kind, name, footprint ring in map px, parts]. A tower belongs to
the block that holds its centre and is stored in that block's tile (big blocks: the tile under the tower), so the page
can stand it up once the block is revealed. The page builds each tower's shape from these fields.
  kind: "p" slab apartments (판상형, long and thin), "t" tower apartments (탑상형), "h" hotel, "o" office and the rest
  parts: OSM building:part pieces inside the footprint as [min m, max m, ring], so landmarks keep their real tiers
         (empty when the building has none)
Run again after build_seoul.py; it replaces the towers it wrote before.
"""
import glob, json, math, os, sys

from shapely import STRtree
from shapely.geometry import LineString, Polygon
from shapely.ops import polygonize

SRC, MAP = sys.argv[1], sys.argv[2]
VIEW = (37.5470, 127.0375, 16, 1280, 720)
TILE = 1024
MIN_FLOORS, MIN_M = 20, 66
FLOOR_M = {"p": 2.9, "t": 2.9, "h": 3.6, "o": 3.8}  # storey height when OSM has floors but no height


def world_px(lat, lon, z=VIEW[2]):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


CX, CY = world_px(VIEW[0], VIEW[1])


def pv(lat, lon):
    x, y = world_px(lat, lon)
    return x - CX + VIEW[3] / 2, y - CY + VIEW[4] / 2


def num(v):
    try:
        return float(str(v).lower().replace("m", "").split(";")[0].strip())
    except ValueError:
        return None


def footprint(e):
    if e["type"] == "way":
        rs = [[pv(g["lat"], g["lon"]) for g in e.get("geometry", []) if g]]
    else:
        parts = [[pv(g["lat"], g["lon"]) for g in m["geometry"] if g] for m in e.get("members", [])
                 if m.get("geometry") and m.get("role") != "inner"]
        rs = [list(p.exterior.coords) for p in polygonize([LineString(p) for p in parts if len(p) > 1])]
    ps = [Polygon(r).buffer(0) for r in rs if len(r) > 3]
    ps = [g for p in ps for g in getattr(p, "geoms", [p]) if not g.is_empty]
    return max(ps, key=lambda p: p.area) if ps else None


def ring(p):
    return [[round(x, 1), round(y, 1)] for x, y in list(p.simplify(0.4).exterior.coords)[:-1]]


def kind_of(t, fp):
    b = t.get("building", "")
    if b.startswith(("apartments", "residential")):
        r = fp.minimum_rotated_rectangle
        xs = list(r.exterior.coords)
        a, c = math.dist(xs[0], xs[1]), math.dist(xs[1], xs[2])
        return "p" if max(a, c) > 2.4 * max(min(a, c), 1e-6) else "t"
    return "h" if b == "hotel" else "o"


def main():
    blocks, owner, tiles = [], [], {}
    for f in glob.glob(f"{MAP}/t/*.json"):
        key = os.path.basename(f)[:-5]
        d = json.load(open(f))
        d["towers"] = []
        tiles[key] = d
        for b in d["blocks"]:
            blocks.append((Polygon(b["edge"]), b["id"]))
            owner.append(key)
    for b in json.load(open(f"{MAP}/big.json"))["blocks"]:
        blocks.append((Polygon(b["edge"]), b["id"]))
        owner.append(None)
    tree = STRtree([p for p, _ in blocks])

    els = json.load(open(SRC))["elements"]
    parts = []  # (polygon, min m, max m)
    for e in els:
        t = e.get("tags", {})
        if "building:part" not in t or "building" in t:
            continue
        top = num(t.get("height")) or (num(t.get("building:levels")) or 0) * 3.6
        if not top:
            continue
        low = num(t.get("min_height")) or (num(t.get("building:min_level")) or 0) * 3.6
        fp = footprint(e)
        if fp is not None and fp.area >= 1 and top > low:
            parts.append((fp, low, top))
    part_tree = STRtree([p for p, _, _ in parts])

    # landmarks OSM leaves unnamed; the page draws these by name (LANDMARKS in assets/village/index.html)
    NAMES = {1436266721: "현대자동차 강남대로 사옥"}  # 스케일타워, 강남대로 350 (opened 2023-12)
    kept = skipped = with_parts = 0
    for e in els:
        t = e.get("tags", {})
        if "building" not in t:
            continue
        floors, height = num(t.get("building:levels")), num(t.get("height"))
        if not ((floors or 0) >= MIN_FLOORS or (height or 0) >= MIN_M):
            continue
        fp = footprint(e)
        if fp is None or fp.area < 2:
            skipped += 1
            continue
        c = fp.representative_point()
        hits = [i for i in tree.query(c) if blocks[i][0].contains(c)]
        if not hits:
            skipped += 1  # on a road or outside the bundled map
            continue
        i = hits[0]
        key = owner[i] or f"{math.floor(c.x / TILE)}_{math.floor(c.y / TILE)}"
        if key not in tiles:
            skipped += 1
            continue
        kind = kind_of(t, fp)
        height = height or floors * FLOOR_M[kind]
        floors = round(floors or height / FLOOR_M[kind])
        mine = [parts[j] for j in part_tree.query(fp) if fp.contains(parts[j][0].representative_point())]
        mine = [[round(lo), round(hi), ring(p)] for p, lo, hi in mine if hi > lo]
        if mine:
            with_parts += 1
            height = max(height, max(hi for _, hi, _ in mine))
        name = NAMES.get(e.get("id"), t.get("name", ""))
        tiles[key]["towers"].append([blocks[i][1], round(height), floors, kind, name, ring(fp), mine])
        kept += 1

    for key, d in tiles.items():
        json.dump(d, open(f"{MAP}/t/{key}.json", "w"), ensure_ascii=False, separators=(",", ":"))
    print("towers", kept, "with parts", with_parts, "skipped", skipped)


main()
