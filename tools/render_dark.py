"""Render a clean dark-gray map (roads, water, parks, labels) from OSM data via Overpass, at 2x."""
import json, math, sys, urllib.parse, urllib.request
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
UA = {"User-Agent": "roadcanvas-mockup/0.1 (design prototype)"}
HOME = (37.5468, 127.0452)
VIEWS = {
    "village": (37.5470, 127.0375, 16, 1280, 720),
    "home": (HOME[0], HOME[1], 18, 900, 720),
}
S = 2  # output scale
BBOX = (37.5380, 127.0200, 37.5570, 127.0560)
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
FONT_KR = 1  # index of the KR face inside the collection

C = {
    "bg": (44, 44, 44), "green": (46, 56, 47), "water": (32, 42, 54), "building": (52, 52, 52),
    "major": (96, 96, 96), "mid": (80, 80, 80), "minor": (66, 66, 66), "service": (56, 56, 56),
    "rail": (70, 70, 70), "label": (165, 165, 165), "halo": (36, 36, 36),
}
ROAD = {  # class: (colour, width at z16 in css px)
    "motorway": ("major", 7), "trunk": ("major", 6.5), "primary": ("major", 5.5),
    "secondary": ("mid", 4.5), "tertiary": ("mid", 3.6),
    "motorway_link": ("mid", 3), "trunk_link": ("mid", 3), "primary_link": ("mid", 3), "secondary_link": ("mid", 2.6),
    "residential": ("minor", 2.4), "unclassified": ("minor", 2.4), "living_street": ("minor", 2),
    "service": ("service", 1.2),
}


def overpass():
    b = ",".join(map(str, BBOX))
    q = f"""[out:json][timeout:90];(
way["highway"~"^({'|'.join(ROAD)})$"]({b});
way["natural"="water"]({b});relation["natural"="water"]({b});way["waterway"="riverbank"]({b});
way["leisure"="park"]({b});relation["leisure"="park"]({b});
way["landuse"~"^(grass|forest|recreation_ground)$"]({b});way["natural"="wood"]({b});
way["building"]({b});
way["railway"="rail"]({b});
node["place"~"^(suburb|quarter|neighbourhood)$"]({b});
);out geom({b});"""
    req = urllib.request.Request("https://overpass-api.de/api/interpreter", urllib.parse.urlencode({"data": q}).encode(), UA)
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read())["elements"]


def world_px(lat, lon, z):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


def projector(view):
    clat, clon, z, w, h = VIEWS[view]
    cx, cy = world_px(clat, clon, z)
    def p(lat, lon):
        x, y = world_px(lat, lon, z)
        return ((x - cx + w / 2) * S, (y - cy + h / 2) * S)
    return p


def lines_of(el):
    if el["type"] == "way" and "geometry" in el:
        return [[(g["lat"], g["lon"]) for g in el["geometry"] if g]]
    if el["type"] == "relation":
        return [[(g["lat"], g["lon"]) for g in m["geometry"] if g] for m in el.get("members", []) if m.get("geometry") and m.get("role") != "inner"]
    return []


def kind(t):
    if t.get("natural") == "water" or t.get("waterway") == "riverbank":
        return "water"
    if t.get("leisure") == "park" or t.get("landuse") in ("grass", "forest", "recreation_ground") or t.get("natural") == "wood":
        return "green"
    if "building" in t:
        return "building"
    if t.get("railway") == "rail":
        return "rail"
    if "highway" in t:
        return "road"
    if "place" in t:
        return "place"
    return None


def text_img(txt, size, fill, halo):
    font = ImageFont.truetype(FONT, size, index=FONT_KR)
    l, t, r, b = font.getbbox(txt)
    im = Image.new("RGBA", (r - l + 8, b - t + 8), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((4 - l, 4 - t), txt, font=font, fill=fill, stroke_width=3, stroke_fill=halo)
    return im


def render(view, els):
    _, _, z, w, h = VIEWS[view]
    zf = 2 ** (z - 16)
    p = projector(view)
    img = Image.new("RGB", (w * S, h * S), C["bg"])
    d = ImageDraw.Draw(img)
    by_kind = {}
    for e in els:
        k = kind(e.get("tags", {}))
        if k:
            by_kind.setdefault(k, []).append(e)
    for k in ("green", "water", "building"):
        for e in by_kind.get(k, []):
            for ring in lines_of(e):
                if len(ring) > 2:
                    d.polygon([p(*q) for q in ring], fill=C[k])
    for e in by_kind.get("rail", []):
        for ln in lines_of(e):
            d.line([p(*q) for q in ln], fill=C["rail"], width=max(1, round(1.2 * S * zf)))
    order = ["service", "residential", "unclassified", "living_street", "secondary_link", "primary_link", "trunk_link",
             "motorway_link", "tertiary", "secondary", "primary", "trunk", "motorway"]
    roads = sorted(by_kind.get("road", []), key=lambda e: order.index(e["tags"]["highway"]))
    for e in roads:
        col, wd = ROAD[e["tags"]["highway"]]
        width = max(1, round(wd * S * min(zf, 3)))
        for ln in lines_of(e):
            pts = [p(*q) for q in ln]
            d.line(pts, fill=C[col], width=width, joint="curve")
            r = width / 2
            for x, y in (pts[0], pts[-1]):
                d.ellipse((x - r, y - r, x + r, y + r), fill=C[col])
    placed = []

    def free(box):
        if box[0] < 0 or box[1] < 0 or box[2] > w * S or box[3] > h * S:
            return False
        return all(box[2] < q[0] or box[0] > q[2] or box[3] < q[1] or box[1] > q[3] for q in placed)

    # road labels: one per name, on the longest straight run
    best = {}
    for e in roads:
        t = e["tags"]
        name = t.get("name")
        if not name or t["highway"] not in ("primary", "secondary", "trunk", "tertiary") or "_" in name:
            continue
        for ln in lines_of(e):
            pts = [p(*q) for q in ln]
            for a, b in zip(pts, pts[1:]):
                L = math.dist(a, b)
                if L > best.get(name, (0,))[0]:
                    best[name] = (L, a, b)
    size = round(12 * S * (1.25 if zf > 1 else 1))
    for name, (L, a, b) in sorted(best.items(), key=lambda kv: -kv[1][0]):
        ti = text_img(name, size, C["label"], C["halo"])
        if ti.width > L * 0.9:
            continue
        ang = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))
        if ang > 90:
            ang -= 180
        if ang < -90:
            ang += 180
        rot = ti.rotate(-ang, expand=True, resample=Image.BICUBIC)
        mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        box = (mx - rot.width / 2, my - rot.height / 2, mx + rot.width / 2, my + rot.height / 2)
        if free(box):
            img.paste(rot, (round(box[0]), round(box[1])), rot)
            placed.append(box)
    # neighbourhood labels, letter-spaced
    for e in by_kind.get("place", []):
        name = e["tags"].get("name")
        if not name or "lat" not in e:
            continue
        ti = text_img(" ".join(name), round(13 * S * (1.3 if zf > 1 else 1)), (120, 120, 120), C["halo"])
        x, y = p(e["lat"], e["lon"])
        box = (x - ti.width / 2, y - ti.height / 2, x + ti.width / 2, y + ti.height / 2)
        if free(box):
            img.paste(ti, (round(box[0]), round(box[1])), ti)
            placed.append(box)
    img.save(f"{OUT}/dark-{view}.png", optimize=True)


import os
cache = f"{OUT}/osm-cache.json"
if os.path.exists(cache):
    els = json.load(open(cache))
else:
    els = overpass()
    json.dump(els, open(cache, "w"))
print("elements", len(els))
for v in VIEWS:
    render(v, els)
