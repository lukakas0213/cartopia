"""Village view: plain OSM map + driven-road corridors + whole park polygons, for the black-fog design."""
import io, json, math, sys, time, urllib.parse, urllib.request
from PIL import Image

OUT = sys.argv[1]
UA = {"User-Agent": "roadcanvas-mockup/0.1 (design prototype)"}

HOME = (37.5468, 127.0452)  # lat, lon (성수동1가 아파트 단지)
VIEWS = {
    "village": (37.5470, 127.0375, 16, 1280, 720),
    "home": (HOME[0], HOME[1], 18, 900, 720),
}
ROUTES = {
    "today": [(127.0379, 37.5612), (127.0346, 37.5500), (HOME[1], HOME[0])],   # 왕십리역 -> 응봉역 -> 집
    "past1": [(HOME[1], HOME[0]), (127.0374, 37.5444), (127.0350, 37.5395)],   # 집 -> 서울숲 -> 성수대교 북단
    "past2": [(127.0346, 37.5500), (127.0285, 37.5462)],                       # 응봉역 -> 응봉산 남쪽
}
PARKS = {"seoulforest": 17338887, "eungbong": 14528902}


def get(url, data=None):
    req = urllib.request.Request(url, data=data, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def world_px(lat, lon, z):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


def project(view, lat, lon):
    clat, clon, z, w, h = VIEWS[view]
    cx, cy = world_px(clat, clon, z)
    x, y = world_px(lat, lon, z)
    return x - cx + w / 2, y - cy + h / 2


def fetch_osm(view):
    lat, lon, z, w, h = VIEWS[view]
    cx, cy = world_px(lat, lon, z)
    x0, y0 = cx - w / 2, cy - h / 2
    tx0, ty0, tx1, ty1 = int(x0 // 256), int(y0 // 256), int((x0 + w) // 256), int((y0 + h) // 256)
    canvas = Image.new("RGB", ((tx1 - tx0 + 1) * 256, (ty1 - ty0 + 1) * 256))
    for tx in range(tx0, tx1 + 1):
        for ty in range(ty0, ty1 + 1):
            tile = Image.open(io.BytesIO(get(f"https://tile.openstreetmap.org/{z}/{tx}/{ty}.png"))).convert("RGB")
            canvas.paste(tile, ((tx - tx0) * 256, (ty - ty0) * 256))
            time.sleep(0.2)
    ox, oy = round(x0 - tx0 * 256), round(y0 - ty0 * 256)
    canvas.crop((ox, oy, ox + w, oy + h)).save(f"{OUT}/osm-{view}.jpg", quality=86)


def compact(pts, step=5):
    out = [pts[0]]
    for p in pts[1:]:
        if abs(p[0] - out[-1][0]) + abs(p[1] - out[-1][1]) >= step:
            out.append(p)
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    return "M" + " L".join(f"{round(x)} {round(y)}" for x, y in out)


def route(pts):
    q = ";".join(f"{lo},{la}" for lo, la in pts)
    r = json.loads(get(f"https://router.project-osrm.org/route/v1/driving/{q}?overview=full&geometries=geojson"))["routes"][0]
    return r["geometry"]["coordinates"], r["distance"]


def park_rings(rel_id):
    """Outer rings of a multipolygon relation, stitched from member ways."""
    q = f"[out:json][timeout:30];relation({rel_id});out geom;"
    rel = json.loads(get("https://overpass-api.de/api/interpreter", urllib.parse.urlencode({"data": q}).encode()))["elements"][0]
    ways = [[(p["lat"], p["lon"]) for p in m["geometry"]] for m in rel["members"] if m["type"] == "way" and m.get("role") in ("outer", "")]
    rings = []
    while ways:
        ring = ways.pop(0)
        changed = True
        while ring[0] != ring[-1] and changed:
            changed = False
            for i, w in enumerate(ways):
                if w[0] == ring[-1]:
                    ring += w[1:]
                elif w[-1] == ring[-1]:
                    ring += w[::-1][1:]
                elif w[-1] == ring[0]:
                    ring = w[:-1] + ring
                elif w[0] == ring[0]:
                    ring = w[::-1][:-1] + ring
                else:
                    continue
                ways.pop(i)
                changed = True
                break
        rings.append(ring)
    return rings


def main():
    for v in VIEWS:
        fetch_osm(v)
    meta = {"routes": {}, "parks": {}, "points": {}}
    for name, pts in ROUTES.items():
        coords, dist = route(pts)
        meta["routes"][name] = {"km": round(dist / 1000, 1), "d": compact([project("village", la, lo) for lo, la in coords])}
    for name, rid in PARKS.items():
        rings = park_rings(rid)
        meta["parks"][name] = " ".join(compact([project("village", la, lo) for la, lo in r], 4) + "Z" for r in rings if len(r) > 3)
    meta["points"]["home_village"] = [round(v) for v in project("village", *HOME)]
    meta["points"]["peak_village"] = [round(v) for v in project("village", 37.5476323, 127.0298604)]
    meta["points"]["home_home"] = [round(v) for v in project("home", *HOME)]
    with open(f"{OUT}/village.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)


main()
