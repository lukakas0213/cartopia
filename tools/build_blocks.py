"""Split the road network into city blocks and reveal only blocks that a driven route runs along."""
import json, math, re, sys
from PIL import Image, ImageDraw
from shapely.geometry import LineString, Polygon
from shapely.ops import polygonize, unary_union

D = sys.argv[1]          # folder with dark-*.png and osm-cache.json
V = sys.argv[2]          # folder with village.json
MIN_SHARED = 18          # px of route that must run along a block's edge
ROAD_PAD = 3.5           # px grown around revealed blocks so edge roads show whole

VILLAGE = (37.5470, 127.0375, 16, 1280, 720)
HOME_PT = (999, 372)     # home in village coords
BLOCK_ROADS = {"motorway", "trunk", "primary", "secondary", "tertiary", "residential", "unclassified",
               "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link"}


def world_px(lat, lon, z):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


CX, CY = world_px(VILLAGE[0], VILLAGE[1], 16)


def to_village(lat, lon):
    x, y = world_px(lat, lon, 16)
    return x - CX + 640, y - CY + 360


def parse(d):
    return [(float(a), float(b)) for a, b in re.findall(r"(-?[\d.]+) (-?[\d.]+)", d)]


def blocks_from_roads(els):
    lines = []
    for e in els:
        if e["type"] == "way" and e.get("tags", {}).get("highway") in BLOCK_ROADS and e.get("geometry"):
            pts = [to_village(g["lat"], g["lon"]) for g in e["geometry"] if g]
            if len(pts) > 1:
                lines.append(LineString(pts))
    return [p for p in polygonize(unary_union(lines)) if p.area > 30]


def pick(blocks, route):
    band = route.buffer(4)
    return [b for b in blocks if b.exterior.intersection(band).length > MIN_SHARED * 2]


def draw_mask(polys, size, f):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    for p in polys:
        for g in getattr(p, "geoms", [p]):
            d.polygon([f(x, y) for x, y in g.exterior.coords], fill=255)
            for hole in g.interiors:
                d.polygon([f(x, y) for x, y in hole.coords], fill=0)
    return m


def compose(base_png, revealed, f, out):
    base = Image.open(base_png).convert("RGB")
    grown = unary_union(revealed).buffer(ROAD_PAD, join_style=2)
    mask = draw_mask([grown], base.size, f)
    Image.composite(base, Image.new("RGB", base.size, (0, 0, 0)), mask).save(out, optimize=True)


def main():
    els = json.load(open(f"{D}/osm-cache.json"))
    meta = json.load(open(f"{V}/village.json"))
    today = parse(meta["routes"]["today"]["d"])
    cut = today.index((596.0, 370.0)) + 1
    past = [LineString(parse(meta["routes"][k]["d"])) for k in ("past1", "past2")]
    blocks = blocks_from_roads(els)
    full = pick(blocks, unary_union([LineString(today)] + past))
    drive = pick(blocks, unary_union([LineString(today[:cut])] + past))
    print("blocks", len(blocks), "revealed full", len(full), "drive", len(drive))
    print("largest revealed areas", sorted(round(b.area) for b in full)[-5:])
    v2x = lambda x, y: (x * 2, y * 2)
    h2x = lambda x, y: (((x - HOME_PT[0]) * 4 + 450) * 2, ((y - HOME_PT[1]) * 4 + 360) * 2)
    compose(f"{D}/dark-village.png", full, v2x, f"{D}/blocks-village.png")
    compose(f"{D}/dark-village.png", drive, v2x, f"{D}/blocks-drive.png")
    compose(f"{D}/dark-home.png", full, h2x, f"{D}/blocks-home.png")


main()
