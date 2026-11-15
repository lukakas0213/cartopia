"""Give every Seoul block a terrain type (지형) from OSM land use, in place in the app's map tiles.

usage: python classify_land.py <base osm dir> <land osm dir> <poi osm dir> <map dir: app/src/main/assets/map/seoul>
  base osm dir: the c*.json chunks build_seoul.py used (parks, woods, water)
  land osm dir: c*.json chunks from Overpass with landuse / amenity / leisure / aeroway areas
               (landuse residential|commercial|retail|industrial|education|religious|military|railway|construction|
                farmland|orchard|allotments|greenhouse_horticulture|depot|garages, amenity school|university|college|
                kindergarten|hospital, leisure pitch|stadium|sports_centre|track, aeroway aerodrome)
  poi osm dir:  c*.json chunks with typed buildings (out center) and shop / food nodes

Adds "land" to each block. The types follow the Korean land cover map (환경부 토지피복지도 대분류: 시가화건조지역 /
농업지역 / 산림지역 / 초지 / 수역 ...), with 시가화건조지역 split the way the land-use zoning law and OSM landuse split it
(주거 / 상업 / 공업 / 공공):
  forest 숲      landuse=forest, natural=wood|scrub                       (산림지역)
  park   공원    leisure=park|golf_course|pitch, landuse=grass|meadow|recreation_ground|cemetery   (초지, 도시공원)
  farm   논밭    landuse=farmland|orchard|allotments|greenhouse_horticulture                         (농업지역)
  res    주거지  landuse=residential                                      (주거지역)
  com    상업지  landuse=commercial|retail                                (상업지역)
  ind    공업지  landuse=industrial|railway|depot|garages|construction     (공업지역, 교통)
  water  수역    natural=water, waterway=riverbank covering half the block  (수역)
  civic  공공시설 schools, universities, hospitals, religious, military, stadiums, airports        (공공시설지역)
Most Seoul blocks have no land-use area in OSM, so the rest are decided by typed buildings and shops inside the block,
then by the 주거/공업 type of the blocks around them, else "res" (about half of Seoul is zoned 주거).
Woods and parks never spread to neighbours: a block is 산림/공원 only when green areas really cover it.
Whether a block touches water stays the separate "water" field (물가), so a block can be 공원 and 물가 at once.
"""
import collections, glob, json, math, sys

from shapely import STRtree
from shapely.geometry import LineString, Polygon
from shapely.ops import polygonize, unary_union

BASE, LAND, POI, MAP = sys.argv[1:5]
BUILT = ("res", "com", "ind", "civic", "farm")
BUILDING = {
    "res": "apartments residential house detached terrace dormitory",
    "com": "commercial retail office hotel supermarket",
    "ind": "industrial warehouse factory manufacture",
    "civic": "school university college kindergarten hospital public government church temple civic stadium train_station",
    "farm": "farm greenhouse",
}
BUILDING = {t: c for c, ts in BUILDING.items() for t in ts.split()}
VIEW = (37.5470, 127.0375, 16, 1280, 720)


def world_px(lat, lon, z=VIEW[2]):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


CX, CY = world_px(VIEW[0], VIEW[1])


def pv(lat, lon):
    x, y = world_px(lat, lon)
    return x - CX + VIEW[3] / 2, y - CY + VIEW[4] / 2


def polys(e):
    rs = []
    if e["type"] == "way" and e.get("geometry"):
        rs = [[pv(g["lat"], g["lon"]) for g in e["geometry"] if g]]
    elif e["type"] == "relation":
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


def kind(t):
    lu, le, am, na = t.get("landuse"), t.get("leisure"), t.get("amenity"), t.get("natural")
    if na == "water" or t.get("waterway") == "riverbank":
        return "water"
    if lu == "forest" or na in ("wood", "scrub"):
        return "forest"
    if am in ("school", "university", "college", "kindergarten", "hospital") or lu in ("education", "religious", "military") \
            or le in ("stadium", "sports_centre", "track") or t.get("aeroway") == "aerodrome":
        return "civic"
    if le in ("park", "golf_course", "pitch") or lu in ("grass", "meadow", "recreation_ground", "cemetery"):
        return "park"
    if lu in ("farmland", "orchard", "allotments", "greenhouse_horticulture"):
        return "farm"
    if lu == "residential":
        return "res"
    if lu in ("commercial", "retail"):
        return "com"
    if lu in ("industrial", "railway", "depot", "garages", "construction"):
        return "ind"
    return None


def load(d):
    seen, out = set(), []
    for f in sorted(glob.glob(f"{d}/c*.json")):
        for e in json.load(open(f))["elements"]:
            k = (e["type"], e["id"])
            if k in seen:
                continue
            seen.add(k)
            c = kind(e.get("tags", {}))
            if c:
                out += [(c, p) for p in polys(e)]
    return out


def load_points():
    """(class, x, y, weight) for typed buildings and shops; six shops count as one commercial building (most streets have shops on the ground floor)."""
    seen, out = set(), []
    for f in sorted(glob.glob(f"{POI}/c*.json")):
        for e in json.load(open(f))["elements"]:
            k = (e["type"], e["id"])
            if k in seen:
                continue
            seen.add(k)
            t, c = e.get("tags", {}), e.get("center") or e
            if "lat" not in c:
                continue
            x, y = pv(c["lat"], c["lon"])
            if e["type"] == "way" and t.get("building") in BUILDING:
                out.append((BUILDING[t["building"]], x, y, 1.0))
            elif e["type"] == "node":
                out.append(("com", x, y, 1 / 6))
    return out


def main():
    areas = load(BASE) + load(LAND)
    print("areas", collections.Counter(c for c, _ in areas), flush=True)
    tree = STRtree([p for _, p in areas])

    files = sorted(glob.glob(f"{MAP}/t/*.json")) + [f"{MAP}/big.json"]
    data = {f: json.load(open(f)) for f in files}
    blocks = [b for d in data.values() for b in d["blocks"]]
    shapes = [Polygon(b["edge"]).buffer(0) for b in blocks]

    land, stats = [None] * len(blocks), collections.Counter()
    for i, (b, s) in enumerate(zip(blocks, shapes)):
        if s.is_empty or s.area <= 0:
            continue
        share = collections.defaultdict(list)
        for j in tree.query(s):
            c, p = areas[j]
            g = p.intersection(s)
            if not g.is_empty:
                share[c].append(g)
        sh = {c: unary_union(gs).area / s.area for c, gs in share.items()}
        if sh.get("water", 0) >= 0.5:  # river faces between bridges, reservoirs
            land[i] = "water"
            continue
        green = sh.get("forest", 0) + sh.get("park", 0)
        if green >= 0.35:  # same threshold as the page's 녹지 bonus
            land[i] = "forest" if sh.get("forest", 0) >= sh.get("park", 0) else "park"
            continue
        # schools and hospitals sit inside residential areas as smaller polygons, so they count a bit more
        built = {c: v * (1.5 if c == "civic" else 1) for c, v in sh.items() if c in ("res", "com", "ind", "civic", "farm")}
        if built and max(built.values()) >= 0.2:
            land[i] = max(built, key=built.get)
        elif green >= 0.2:
            land[i] = "forest" if sh.get("forest", 0) >= sh.get("park", 0) else "park"
    print("land-use areas decided", sum(1 for l in land if l), "of", len(blocks), flush=True)

    # buildings and shops inside the block
    from shapely.geometry import Point
    pts = load_points()
    print("points", collections.Counter(c for c, *_ in pts), flush=True)
    stree = STRtree(shapes)
    score = collections.defaultdict(collections.Counter)
    for c, x, y, w in pts:
        for i in stree.query(Point(x, y), predicate="intersects"):
            score[i][c] += w
    n = 0
    for i, sc in score.items():
        if not land[i] and sum(sc.values()) >= 2:
            land[i] = sc.most_common(1)[0][0]
            n += 1
    print("buildings decided", n, flush=True)

    # fill blocks without data from the 주거/공업 type of their neighbours (blocks that share an edge across a road)
    grown = [s.buffer(4) for s in shapes]
    for rnd in range(3):
        todo = [i for i, l in enumerate(land) if not l]
        fill = {}
        for i in todo:
            # a school or a farm does not make the next block one, and shops line the big roads rather than fill areas,
            # so only 주거/공업 spread, and only with two votes
            votes = collections.Counter(land[j] for j in stree.query(grown[i]) if j != i and land[j] in ("res", "ind"))
            if votes and votes.most_common(1)[0][1] >= 2:
                fill[i] = votes.most_common(1)[0][0]
        for i, l in fill.items():
            land[i] = l
        print("round", rnd, "filled", len(fill), "left", len(todo) - len(fill), flush=True)
    for b, l in zip(blocks, land):
        b["land"] = l or "res"
        stats[b["land"]] += 1
    print("final", stats, flush=True)

    for f, d in data.items():
        json.dump(d, open(f, "w"), ensure_ascii=False, separators=(",", ":"))


main()
