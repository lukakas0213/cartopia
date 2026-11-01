"""Build the files the Android app bundles: base map, all blocks, demo/history routes (lat/lon)."""
import json, math, os, sys, urllib.request
from shapely.geometry import LineString, box
from shapely.ops import polygonize, unary_union

sys.path.insert(0, os.path.dirname(__file__))
import final_maps as fm  # noqa: E402  (reads D, V from argv[1], argv[2])

OUT = sys.argv[3]
HOME = (37.5468, 127.0452)
ROUTES = {
    "demo": [(127.0379, 37.5612), (127.0346, 37.5500), (HOME[1], HOME[0])],    # 왕십리역 -> 응봉역 -> 집
    "past1": [(HOME[1], HOME[0]), (127.0374, 37.5444), (127.0350, 37.5395)],   # 집 -> 서울숲 -> 성수대교 북단
    "past2": [(127.0346, 37.5500), (127.0285, 37.5462)],                       # 응봉역 -> 응봉산 남쪽
}


def osrm(pts):
    q = ";".join(f"{lo},{la}" for lo, la in pts)
    req = urllib.request.Request(f"https://router.project-osrm.org/route/v1/driving/{q}?overview=full&geometries=geojson",
                                 headers={"User-Agent": "roadcanvas-mockup/0.1"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return [(la, lo) for lo, la in json.loads(r.read())["routes"][0]["geometry"]["coordinates"]]


def despur_idx(px, near=7, min_loop=18):
    """Same hook removal as final_maps.despur, but returns the kept indices."""
    keep = list(range(len(px)))
    i = 0
    while i < len(keep):
        for j in range(min(len(keep) - 1, i + 40), i + 1, -1):
            a, b = px[keep[i]], px[keep[j]]
            if math.dist(a, b) < near:
                seg = sum(math.dist(px[keep[k]], px[keep[k + 1]]) for k in range(i, j))
                if seg > min_loop:
                    del keep[i + 1:j + 1]
                    break
        i += 1
    return keep


def ring(coords, nd=1):
    return [[round(x, nd), round(y, nd)] for x, y in list(coords)[:-1]]


def main():
    os.makedirs(OUT, exist_ok=True)
    els = json.load(open(f"{fm.D}/osm-cache.json"))
    pv = fm.proj("village")
    fm.HOME_PT = pv(*HOME)

    # base map: the whole view as land, no routes (the app masks it per revealed block)
    fm.render("village", els, box(-200, -200, 1500, 950), [], f"{OUT}/base_map.png")

    lines = [LineString([pv(g["lat"], g["lon"]) for g in e["geometry"] if g]) for e in els
             if e.get("tags", {}).get("highway") in fm.ROAD and e.get("geometry") and len(e["geometry"]) > 1]
    blocks = [b for b in polygonize(unary_union(lines)) if b.area > 30]
    out_blocks = []
    for i, b in enumerate(blocks):
        grown = b.buffer(2.6, join_style=2).simplify(0.6)
        if grown.geom_type == "MultiPolygon":
            grown = max(grown.geoms, key=lambda g: g.area)
        out_blocks.append({"id": i, "edge": ring(b.exterior.simplify(0.4).coords), "draw": ring(grown.exterior.coords), "area": round(b.area)})

    routes = {}
    for name, pts in ROUTES.items():
        ll = osrm(pts)
        px = [pv(la, lo) for la, lo in ll]
        routes[name] = [[round(ll[k][0], 6), round(ll[k][1], 6)] for k in despur_idx(px)]

    meta = {"view": {"lat": fm.VIEWS["village"][0], "lon": fm.VIEWS["village"][1], "zoom": fm.VIEWS["village"][2],
                     "width": fm.VIEWS["village"][3], "height": fm.VIEWS["village"][4]},
            "reveal": {"bandPx": 4, "minSharedPx": 36}}
    json.dump({**meta, "blocks": out_blocks}, open(f"{OUT}/blocks.json", "w"), separators=(",", ":"))
    json.dump({"history": [routes["past1"], routes["past2"]], "demo": routes["demo"]}, open(f"{OUT}/routes.json", "w"), separators=(",", ":"))
    print("blocks", len(out_blocks), "routes", {k: len(v) for k, v in routes.items()})


main()
