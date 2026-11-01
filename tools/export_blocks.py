"""Export revealed block polygons (village coords, 1x px) and pick the block for each village feature."""
import json, math, re, sys
from shapely.geometry import LineString, Point
from shapely.ops import polygonize, unary_union

D, V, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
VILLAGE = (37.5470, 127.0375, 16)
ROADS = {"motorway", "trunk", "primary", "secondary", "tertiary", "motorway_link", "trunk_link", "primary_link",
         "secondary_link", "residential", "unclassified", "living_street"}
FEATURES = {"farm": (284, 323), "forest": (460, 625), "dock": (445, 372), "home": (999, 372)}


def world_px(lat, lon, z):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


CX, CY = world_px(*VILLAGE)
pv = lambda lat, lon: (world_px(lat, lon, 16)[0] - CX + 640, world_px(lat, lon, 16)[1] - CY + 360)


def parse(d):
    return [(float(a), float(b)) for a, b in re.findall(r"(-?[\d.]+) (-?[\d.]+)", d)]


def despur(pts, near=7, min_loop=18):
    out = list(pts)
    i = 0
    while i < len(out):
        for j in range(min(len(out) - 1, i + 40), i + 1, -1):
            if math.dist(out[i], out[j]) < near and sum(math.dist(a, b) for a, b in zip(out[i:j], out[i + 1:j + 1])) > min_loop:
                del out[i + 1:j + 1]
                break
        i += 1
    return out


els = json.load(open(f"{D}/osm-cache.json"))
meta = json.load(open(f"{V}/village.json"))
lines = [LineString([pv(g["lat"], g["lon"]) for g in e["geometry"] if g]) for e in els
         if e.get("tags", {}).get("highway") in ROADS and e.get("geometry") and len(e["geometry"]) > 1]
blocks = [b for b in polygonize(unary_union(lines)) if b.area > 30]
routes = [despur(parse(meta["routes"][k]["d"])) for k in ("today", "past1", "past2")]
band = unary_union([LineString(p) for p in routes]).buffer(4)
revealed = [b for b in blocks if b.exterior.intersection(band).length > 36]

picked = {}
for name, (x, y) in FEATURES.items():
    pt = Point(x, y)
    inside = [i for i, b in enumerate(revealed) if b.contains(pt)]
    picked[name] = inside[0] if inside else min(range(len(revealed)), key=lambda i: revealed[i].distance(pt))


def ring(coords):
    return [[round(x, 1), round(y, 1)] for x, y in list(coords)[:-1]]


out = {"blocks": [], "features": picked}
for b in revealed:
    s = b.buffer(-2.8, join_style=2).simplify(0.8)  # inset so the roads stay visible between islands
    if s.is_empty:
        s = b.simplify(0.8)
    if s.geom_type == "MultiPolygon":
        s = max(s.geoms, key=lambda g: g.area)
    out["blocks"].append({"outer": ring(s.exterior.coords), "holes": [ring(h.coords) for h in s.interiors], "area": round(s.area)})
json.dump(out, open(OUT, "w"), separators=(",", ":"))
for name, i in picked.items():
    b = revealed[i]
    print(name, "block", i, "area px2", round(b.area), "bounds", [round(v) for v in b.bounds], "contains", b.contains(Point(*FEATURES[name])))
print("revealed", len(revealed))


# ---- feature areas: land-use inside the picked block ----
from shapely.geometry import Polygon


def park_poly(d):
    return unary_union([Polygon(parse(r)) for r in d.split("Z") if r.strip()]).buffer(0)


def water_polys():
    polys = []
    for e in els:
        t = e.get("tags", {})
        if t.get("natural") == "water" or t.get("waterway") == "riverbank":
            members = [e["geometry"]] if e["type"] == "way" else [m["geometry"] for m in e.get("members", []) if m.get("geometry") and m.get("role") != "inner"]
            for g in members:
                pts = [pv(q["lat"], q["lon"]) for q in g if q]
                if len(pts) > 3:
                    polys.append(Polygon(pts).buffer(0))
    return unary_union(polys)


def as_area(geom):
    g = geom.buffer(-2.8, join_style=2).simplify(0.8)
    if g.geom_type == "MultiPolygon":
        g = max(g.geoms, key=lambda q: q.area)
    return {"outer": ring(g.exterior.coords), "holes": [ring(h.coords) for h in g.interiors], "area": round(g.area)}


water = water_polys()
areas = {
    "farm": as_area(revealed[picked["farm"]].intersection(park_poly(meta["parks"]["eungbong"]))),
    "forest": as_area(revealed[picked["forest"]]),
    "home": as_area(revealed[picked["home"]]),
}
river = revealed[picked["dock"]].intersection(water)
from shapely.ops import nearest_points
shore = nearest_points(river.boundary, Point(*FEATURES["dock"]))[0]
ang = 0.0
for k in range(36):
    a = k * math.pi / 18
    if river.contains(Point(shore.x + math.cos(a) * 12, shore.y + math.sin(a) * 12)):
        ang = a
        break
areas["dock"] = {"x": round(shore.x, 1), "y": round(shore.y, 1), "ang": round(ang, 3)}
out["areas"] = areas
json.dump(out, open(OUT, "w"), separators=(",", ":"))
print("areas", {k: v.get("area", v) for k, v in areas.items()})
