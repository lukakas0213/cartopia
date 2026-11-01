"""Clean dark map in the style of running apps: anti-aliased roads, muted fills, block reveal, white routes."""
import json, math, re, sys
from PIL import Image, ImageDraw, ImageFont
from shapely.geometry import LineString
from shapely.ops import polygonize, unary_union

D, V = sys.argv[1], sys.argv[2]   # dark/ (osm-cache.json), village/ (village.json)
SS = 4                             # draw at 4x, save at 2x
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
HOME = (37.5468, 127.0452)
VIEWS = {"village": (37.5470, 127.0375, 16, 1280, 720), "home": (HOME[0], HOME[1], 18, 900, 720)}
COL = {"fog": (17, 17, 17), "land": (43, 43, 43), "green": (45, 49, 45), "water": (36, 40, 46),
       "major": (86, 86, 86), "mid": (74, 74, 74), "minor": (62, 62, 62), "label": (140, 140, 140)}
ROAD = {"motorway": ("major", 6), "trunk": ("major", 6), "primary": ("major", 5), "secondary": ("mid", 4),
        "tertiary": ("mid", 3.4), "motorway_link": ("mid", 2.6), "trunk_link": ("mid", 2.6),
        "primary_link": ("mid", 2.6), "secondary_link": ("mid", 2.4), "residential": ("minor", 2.2),
        "unclassified": ("minor", 2.2), "living_street": ("minor", 2)}
ORDER = list(reversed(list(ROAD)))
PLACES = {"응봉동", "성수동1가", "행당동", "옥수동", "금호동4가", "성수동2가"}


def world_px(lat, lon, z):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


def proj(view):
    clat, clon, z, w, h = VIEWS[view]
    cx, cy = world_px(clat, clon, z)
    return lambda lat, lon: ((world_px(lat, lon, z)[0] - cx + w / 2), (world_px(lat, lon, z)[1] - cy + h / 2))


def rings(e):
    if e["type"] == "way" and e.get("geometry"):
        return [[(g["lat"], g["lon"]) for g in e["geometry"] if g]]
    if e["type"] == "relation":
        return [[(g["lat"], g["lon"]) for g in m["geometry"] if g] for m in e.get("members", []) if m.get("geometry") and m.get("role") != "inner"]
    return []


def despur(pts, near=7, min_loop=18):
    """Drop out-and-back hooks: when the path returns within `near` px of an earlier point after a short detour."""
    out = list(pts)
    i = 0
    while i < len(out):
        for j in range(min(len(out) - 1, i + 40), i + 1, -1):
            if math.dist(out[i], out[j]) < near:
                seg = sum(math.dist(a, b) for a, b in zip(out[i:j], out[i + 1:j + 1]))
                if seg > min_loop:
                    del out[i + 1:j + 1]
                    break
        i += 1
    return out


def parse(d):
    return [(float(a), float(b)) for a, b in re.findall(r"(-?[\d.]+) (-?[\d.]+)", d)]


def label(img, text, xy, size, spacing):
    font = ImageFont.truetype(FONT, size, index=1)
    txt = (" " * spacing).join(text) if spacing else text
    d = ImageDraw.Draw(img)
    l, t, r, b = d.textbbox((0, 0), txt, font=font)
    d.text((xy[0] - (r - l) / 2, xy[1] - (b - t) / 2), txt, font=font, fill=COL["label"])


def render(view, els, reveal, routes, out):
    _, _, z, w, h = VIEWS[view]
    zf = min(2 ** (z - 16), 2.5)
    p0 = proj(view)
    k = 2 * SS
    P = lambda lat, lon: tuple(v * k for v in p0(lat, lon))
    land = Image.new("RGB", (w * k, h * k), COL["land"])
    d = ImageDraw.Draw(land)
    for e in els:
        t = e.get("tags", {})
        c = "water" if t.get("natural") == "water" or t.get("waterway") == "riverbank" else \
            "green" if t.get("leisure") == "park" or t.get("landuse") in ("grass", "forest", "recreation_ground") or t.get("natural") == "wood" else None
        if c:
            for r in rings(e):
                if len(r) > 2:
                    d.polygon([P(*q) for q in r], fill=COL[c])
    roads = sorted([e for e in els if e.get("tags", {}).get("highway") in ROAD], key=lambda e: ORDER.index(e["tags"]["highway"]))
    for e in roads:
        c, wd = ROAD[e["tags"]["highway"]]
        width = round(wd * k * zf)
        for r in rings(e):
            pts = [P(*q) for q in r]
            d.line(pts, fill=COL[c], width=width, joint="curve")
            for x, y in (pts[0], pts[-1]):
                d.ellipse((x - width / 2, y - width / 2, x + width / 2, y + width / 2), fill=COL[c])
    for e in els:
        t = e.get("tags", {})
        if t.get("name") in PLACES and "lat" in e:
            x, y = P(e["lat"], e["lon"])
            if 0 < x < w * k and 0 < y < h * k:
                label(land, t["name"], (x, y), round(13 * k * (1.2 if zf > 1 else 1)), 1)
    mask = Image.new("L", land.size, 0)
    md = ImageDraw.Draw(mask)
    for poly in getattr(reveal, "geoms", [reveal]):
        md.polygon([tuple(v * k for v in view_xy(view, x, y)) for x, y in poly.exterior.coords], fill=255)
        for hole in poly.interiors:
            md.polygon([tuple(v * k for v in view_xy(view, x, y)) for x, y in hole.coords], fill=0)
    img = Image.composite(land, Image.new("RGB", land.size, COL["fog"]), mask)
    rd = ImageDraw.Draw(img, "RGBA")
    for pts, alpha, wd in routes:
        q = [tuple(v * k for v in view_xy(view, x, y)) for x, y in pts]
        width = round(wd * k)
        rd.line(q, fill=(255, 255, 255, alpha), width=width, joint="curve")
        for x, y in (q[0], q[-1]):
            rd.ellipse((x - width / 2, y - width / 2, x + width / 2, y + width / 2), fill=(255, 255, 255, alpha))
    img.resize((w * 2, h * 2), Image.LANCZOS).save(out, optimize=True)


HOME_PT = None


def view_xy(view, x, y):
    """Village-plane coordinates -> view coordinates (1x)."""
    if view == "village":
        return x, y
    return (x - HOME_PT[0]) * 4 + 450, (y - HOME_PT[1]) * 4 + 360


def main():
    global HOME_PT
    els = json.load(open(f"{D}/osm-cache.json"))
    meta = json.load(open(f"{V}/village.json"))
    HOME_PT = proj("village")(*HOME)
    pv = proj("village")
    lines = [LineString([pv(g["lat"], g["lon"]) for g in e["geometry"] if g]) for e in els
             if e.get("tags", {}).get("highway") in ROAD and e.get("geometry") and len(e["geometry"]) > 1]
    blocks = [b for b in polygonize(unary_union(lines)) if b.area > 30]
    today = despur(parse(meta["routes"]["today"]["d"]))
    past = [despur(parse(meta["routes"][k]["d"])) for k in ("past1", "past2")]
    cut = min(range(len(today)), key=lambda i: math.dist(today[i], (596, 370))) + 1

    def reveal(paths):
        band = unary_union([LineString(p) for p in paths]).buffer(4)
        chosen = [b for b in blocks if b.exterior.intersection(band).length > 36]
        return unary_union(chosen).buffer(2.6, join_style=2)

    full, drive = reveal([today] + past), reveal([today[:cut]] + past)
    render("village", els, full, [(p, 255, 2.4) for p in past] + [(today, 255, 2.4)], f"{D}/clean-village.png")
    render("village", els, drive, [(p, 150, 2.0) for p in past] + [(today[:cut], 255, 3.0)], f"{D}/clean-drive.png")
    render("home", els, full, [(p, 255, 2.4) for p in past] + [(today, 255, 2.4)], f"{D}/clean-home.png")
    print("blocks", len(blocks), "today pts", len(today), "car at", today[cut - 1])


if __name__ == "__main__":
    main()
