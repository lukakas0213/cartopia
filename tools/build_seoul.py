"""Build the Seoul-wide map the app bundles: city blocks and pastel ground vectors, cut into square tiles.

usage: python build_seoul.py <osm dir: c*.json + boundary.json from Overpass> <out dir: app/src/main/assets/map/seoul>

Coordinates are the same map pixels as before (zoom 16, the old 1280x720 view around 응봉·성수 is (0,0)-(1280,720)),
so they go negative west and north of it. Every block belongs to the tile holding its representative point;
blocks wider or taller than a tile go to big.json, which the app always loads.
Block ids encode their tile: ((ty + 64) * 128 + (tx + 64)) * 8192 + i, big blocks use tile key 0.
After this, run classify_land.py on the output to give every block its terrain type ("land").
"""
import glob, json, math, os, sys

from shapely import STRtree
from shapely.geometry import LineString, Polygon, box
from shapely.ops import polygonize, unary_union

SRC, OUT = sys.argv[1], sys.argv[2]
VIEW = (37.5470, 127.0375, 16, 1280, 720)
TILE = 1024
ROAD = {  # class -> (width in map px, rank: wider roads are drawn last)
    "motorway": (6, 5), "trunk": (6, 5), "primary": (5, 4), "secondary": (4, 3), "tertiary": (3.4, 2),
    "motorway_link": (2.6, 1), "trunk_link": (2.6, 1), "primary_link": (2.6, 1), "secondary_link": (2.4, 1),
    "tertiary_link": (2.2, 1), "residential": (2.2, 0), "unclassified": (2.2, 0), "living_street": (2, 0),
}


def world_px(lat, lon, z=VIEW[2]):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


CX, CY = world_px(VIEW[0], VIEW[1])
M_PER_PX = 156543.03392 * math.cos(math.radians(VIEW[0])) / 2 ** VIEW[2]


def pv(lat, lon):
    x, y = world_px(lat, lon)
    return x - CX + VIEW[3] / 2, y - CY + VIEW[4] / 2


def ring(coords, nd=1):
    return [[round(x, nd), round(y, nd)] for x, y in list(coords)[:-1]]


def line(coords, nd=1):
    return [[round(x, nd), round(y, nd)] for x, y in coords]


def tile_of(x, y):
    return math.floor(x / TILE), math.floor(y / TILE)


def tile_key(tx, ty):
    return (ty + 64) * 128 + (tx + 64)


def load():
    seen, els = set(), []
    for f in sorted(glob.glob(f"{SRC}/c*.json")):
        for e in json.load(open(f))["elements"]:
            k = (e["type"], e["id"])
            if k not in seen:
                seen.add(k)
                els.append(e)
    return els


def polys(e):
    """Outer rings of a way or multipolygon relation as shapely polygons (inner rings are ignored, as before)."""
    rs = []
    if e["type"] == "way" and e.get("geometry"):
        rs = [[pv(g["lat"], g["lon"]) for g in e["geometry"] if g]]
    elif e["type"] == "relation":
        # stitch the outer member ways into closed rings
        parts = [[pv(g["lat"], g["lon"]) for g in m["geometry"] if g] for m in e.get("members", [])
                 if m.get("geometry") and m.get("role") != "inner"]
        rs = [list(p.exterior.coords) for p in polygonize([LineString(p) for p in parts if len(p) > 1])]
    out = []
    for r in rs:
        if len(r) > 3:
            p = Polygon(r).buffer(0)
            if not p.is_empty:
                out += list(getattr(p, "geoms", [p]))
    return out


def main():
    els = load()
    print("elements", len(els), flush=True)
    seoul = unary_union([p for e in json.load(open(f"{SRC}/boundary.json"))["elements"] for p in polys(e)])
    area = seoul.buffer(TILE / 2)  # ship tiles a little past the city line so edge drives still have a map

    roads, water, green, labels = [], [], [], []
    for e in els:
        t = e.get("tags", {})
        if e["type"] == "way" and t.get("highway") in ROAD and e.get("geometry") and len(e["geometry"]) > 1:
            roads.append((t["highway"], LineString([pv(g["lat"], g["lon"]) for g in e["geometry"] if g])))
        elif t.get("natural") == "water" or t.get("waterway") == "riverbank":
            water += polys(e)
        elif t.get("leisure") in ("park", "golf_course") or t.get("landuse") in ("grass", "forest", "recreation_ground", "meadow", "cemetery") \
                or t.get("natural") in ("wood", "scrub"):
            green += polys(e)
        elif e["type"] == "node" and t.get("place") and t.get("name"):
            x, y = pv(e["lat"], e["lon"])
            labels.append((t["name"], x, y, t["place"]))
    print("roads", len(roads), "water", len(water), "green", len(green), "labels", len(labels), flush=True)

    # city blocks: faces of the whole road network
    faces = [b for b in polygonize(unary_union([g for _, g in roads])) if b.area > 30]
    faces = [b for b in faces if area.contains(b.representative_point())]
    print("blocks", len(faces), flush=True)

    green_tree, water_tree = STRtree(green), STRtree(water)

    def green_share(b):
        hits = [green[i] for i in green_tree.query(b)]
        if not hits:
            return 0.0
        return min(1.0, unary_union([h.intersection(b) for h in hits]).area / b.area)

    def water_point(b, draw):
        band = b.exterior.buffer(8).difference(b)
        hits = [water[i] for i in water_tree.query(band)]
        if not hits:
            return None
        wet = unary_union([h.intersection(band) for h in hits])
        if wet.is_empty or wet.area / band.area <= 0.12:
            return None
        c = wet.centroid
        return min(draw, key=lambda p: (p[0] - c.x) ** 2 + (p[1] - c.y) ** 2)

    tiles, big = {}, []
    for b in faces:
        x0, y0, x1, y1 = b.bounds
        rp = b.representative_point()
        tx, ty = tile_of(rp.x, rp.y)
        edge = ring(b.exterior.simplify(0.4).coords)
        isle = b.buffer(-2.8, join_style=2).simplify(0.8)
        if isle.geom_type == "MultiPolygon":
            isle = max(isle.geoms, key=lambda g: g.area)
        grown = b.buffer(2.6, join_style=2)
        if grown.geom_type == "MultiPolygon":
            grown = max(grown.geoms, key=lambda g: g.area)
        rec = {
            "edge": edge,
            "isle": ring(isle.exterior.coords) if not isle.is_empty and isle.area > 20 else None,
            "area": round(b.area),
            "areaM2": round(b.area * M_PER_PX ** 2),
            "green": round(green_share(b), 2),
            "water": water_point(b, ring(grown.exterior.coords)),
            "bbox": [math.floor(x0), math.floor(y0), math.ceil(x1), math.ceil(y1)],
        }
        if x1 - x0 > TILE or y1 - y0 > TILE:
            big.append(rec)
        else:
            tiles.setdefault((tx, ty), []).append(rec)

    # every tile that has blocks or lies in the city gets ground vectors
    keys = set(tiles)
    gx0, gy0, gx1, gy1 = area.bounds
    for ty in range(math.floor(gy0 / TILE), math.floor(gy1 / TILE) + 1):
        for tx in range(math.floor(gx0 / TILE), math.floor(gx1 / TILE) + 1):
            if area.intersects(box(tx * TILE, ty * TILE, (tx + 1) * TILE, (ty + 1) * TILE)):
                keys.add((tx, ty))

    road_tree = STRtree([g for _, g in roads])
    os.makedirs(f"{OUT}/t", exist_ok=True)
    index = {}
    for (tx, ty) in sorted(keys):
        bs = tiles.get((tx, ty), [])
        assert len(bs) < 8192
        for i, r in enumerate(bs):
            r["id"] = tile_key(tx, ty) * 8192 + i
        clip = box(tx * TILE - 16, ty * TILE - 16, (tx + 1) * TILE + 16, (ty + 1) * TILE + 16)

        def cut(polys_, tree):
            out = []
            for i in tree.query(clip):
                g = polys_[i].intersection(clip).simplify(0.5)
                for p in getattr(g, "geoms", [g]):
                    if p.geom_type == "Polygon" and p.area > 4:
                        out.append(ring(p.exterior.coords))
            return out

        rd = []
        for i in road_tree.query(clip):
            cls, g = roads[i]
            g = g.intersection(clip).simplify(0.4)
            for l in getattr(g, "geoms", [g]):
                if l.geom_type == "LineString" and l.length > 0:
                    rd.append([ROAD[cls][0], ROAD[cls][1], line(l.coords)])
        rd.sort(key=lambda r: r[1])
        lb = [[n, round(x), round(y)] for n, x, y, kind in labels
              if tx * TILE <= x < (tx + 1) * TILE and ty * TILE <= y < (ty + 1) * TILE and kind in ("suburb", "quarter")]
        ground = {"water": cut(water, water_tree), "green": cut(green, green_tree), "roads": [[w, pts] for w, _, pts in rd], "labels": lb}
        json.dump({"blocks": bs, "ground": ground}, open(f"{OUT}/t/{tx}_{ty}.json", "w"), ensure_ascii=False, separators=(",", ":"))
        if bs:
            index[f"{tx}_{ty}"] = [min(r["bbox"][0] for r in bs), min(r["bbox"][1] for r in bs),
                                   max(r["bbox"][2] for r in bs), max(r["bbox"][3] for r in bs)]
        else:
            index[f"{tx}_{ty}"] = None
    for i, r in enumerate(big):
        r["id"] = i
    json.dump({"blocks": big}, open(f"{OUT}/big.json", "w"), ensure_ascii=False, separators=(",", ":"))
    city = seoul.simplify(20)
    city = max(getattr(city, "geoms", [city]), key=lambda g: g.area)
    json.dump({
        "view": {"lat": VIEW[0], "lon": VIEW[1], "zoom": VIEW[2], "width": VIEW[3], "height": VIEW[4]},
        "reveal": {"bandPx": 4, "minSharedPx": 36},
        "tile": TILE,
        "tiles": index,
        "city": ring(city.exterior.coords, 0),
        "version": 1,
    }, open(f"{OUT}/index.json", "w"), ensure_ascii=False, separators=(",", ":"))
    print("tiles", len(keys), "with blocks", len(tiles), "big", len(big), flush=True)


main()
