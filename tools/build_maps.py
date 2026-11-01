"""Download CARTO basemap tiles, fetch OSRM routes, and emit map images + SVG overlay data."""
import io, json, math, sys, time, urllib.request
import colorsys
from PIL import Image, ImageOps, ImageEnhance

OUT = sys.argv[1]
UA = {"User-Agent": "roadcanvas-mockup/0.1 (design prototype)"}

STYLES = ("dark", "paper", "light")

# view = (center lat, center lon, zoom, css width, css height)
VIEWS = {
    "wide": (37.5515, 127.0520, 15, 1280, 720),
    "close": (37.5478, 127.0515, 16, 900, 720),
}

ROUTES = {
    "today": [(127.0379, 37.5612), (127.0436, 37.5557), (127.0560, 37.5446)],   # 왕십리역 -> 한양대역 -> 성수역
    "past1": [(127.0374, 37.5444), (127.0474, 37.5474), (127.0557, 37.5435)],   # 서울숲 -> 뚝섬역 -> 카페거리
    "past2": [(127.0560, 37.5446), (127.0692, 37.5404)],   # 성수역 -> 건대입구
}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def world_px(lat, lon, z):
    """Web Mercator pixel at 256-px tiles (CSS px)."""
    n = 256 * 2 ** z
    x = (lon + 180) / 360 * n
    s = math.sin(math.radians(lat))
    y = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n
    return x, y


def fetch_osm(view):
    lat, lon, z, w, h = VIEWS[view]
    cx, cy = world_px(lat, lon, z)
    x0, y0 = cx - w / 2, cy - h / 2
    tx0, ty0 = int(x0 // 256), int(y0 // 256)
    tx1, ty1 = int((x0 + w) // 256), int((y0 + h) // 256)
    canvas = Image.new("RGB", ((tx1 - tx0 + 1) * 256, (ty1 - ty0 + 1) * 256))
    for tx in range(tx0, tx1 + 1):
        for ty in range(ty0, ty1 + 1):
            tile = Image.open(io.BytesIO(get(f"https://tile.openstreetmap.org/{z}/{tx}/{ty}.png"))).convert("RGB")
            canvas.paste(tile, ((tx - tx0) * 256, (ty - ty0) * 256))
            time.sleep(0.2)
    ox, oy = round(x0 - tx0 * 256), round(y0 - ty0 * 256)
    return canvas.crop((ox, oy, ox + w, oy + h))


def hue_rotate_invert(img):
    inv = ImageOps.invert(img)
    h, s, v = inv.convert("HSV").split()
    h = h.point(lambda x: (x + 128) % 256)
    out = Image.merge("HSV", (h, s, v)).convert("RGB")
    out = ImageEnhance.Brightness(out).enhance(0.92)
    return ImageEnhance.Color(out).enhance(0.6)


def stylize(img, style):
    if style == "dark":
        return hue_rotate_invert(img)
    gray = ImageOps.autocontrast(img.convert("L"), cutoff=1)
    if style == "paper":
        return ImageOps.colorize(gray, black="#5A4632", white="#F6EEDC", mid="#D9C7A6")
    return ImageOps.colorize(gray, black="#5B6577", white="#F7F8FA", mid="#D7DCE4")


def render_all():
    for view in VIEWS:
        base = fetch_osm(view)
        base.save(f"{OUT}/osm-{view}.jpg", quality=85)
        for style in STYLES:
            stylize(base, style).save(f"{OUT}/{style}-{view}.jpg", quality=85, optimize=True)


def route_coords(pts):
    q = ";".join(f"{lo},{la}" for lo, la in pts)
    data = json.loads(get(f"https://router.project-osrm.org/route/v1/driving/{q}?overview=full&geometries=geojson"))
    r = data["routes"][0]
    return r["geometry"]["coordinates"], r["distance"], r["duration"]


def to_view(coords, view):
    lat, lon, z, w, h = VIEWS[view]
    cx, cy = world_px(lat, lon, z)
    pts = []
    for lo, la in coords:
        x, y = world_px(la, lo, z)
        pts.append((x - cx + w / 2, y - cy + h / 2))
    return pts


def simplify(pts, tol=1.0):
    if len(pts) < 3:
        return pts
    (x1, y1), (x2, y2) = pts[0], pts[-1]
    dx, dy = x2 - x1, y2 - y1
    L = math.hypot(dx, dy) or 1e-9
    i, dmax = 0, 0
    for k in range(1, len(pts) - 1):
        d = abs(dy * pts[k][0] - dx * pts[k][1] + x2 * y1 - y2 * x1) / L
        if d > dmax:
            i, dmax = k, d
    if dmax <= tol:
        return [pts[0], pts[-1]]
    return simplify(pts[: i + 1], tol)[:-1] + simplify(pts[i:], tol)


def path_d(pts):
    return "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in pts)


def seg_dist(p, a, b):
    ax, ay = a; bx, by = b; px, py = p
    dx, dy = bx - ax, by - ay
    t = 0 if dx == dy == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def hexes(lines, w, h, r):
    """Pointy-top hex cells whose centre lies within r of any route segment."""
    out = []
    hw = math.sqrt(3) * r
    row = 0
    y = 0.0
    while y < h + r:
        x = (hw / 2 if row % 2 else 0.0)
        while x < w + hw:
            if any(seg_dist((x, y), a, b) < r * 1.05 for pts in lines for a, b in zip(pts, pts[1:])):
                corners = [(x + r * math.cos(math.radians(60 * k - 30)), y + r * math.sin(math.radians(60 * k - 30))) for k in range(6)]
                out.append("M" + " L".join(f"{cx:.1f} {cy:.1f}" for cx, cy in corners) + "Z")
            x += hw
        y += 1.5 * r
        row += 1
    return " ".join(out)


def main():
    render_all()
    meta = {"routes": {}, "views": {}}
    raw = {}
    for name, pts in ROUTES.items():
        coords, dist, dur = route_coords(pts)
        raw[name] = coords
        meta["routes"][name] = {"km": round(dist / 1000, 1), "min": round(dur / 60)}
    for view, (_, _, _, w, h) in VIEWS.items():
        lines = {n: simplify(to_view(c, view), 0.8) for n, c in raw.items()}
        meta["views"][view] = {
            "paths": {n: path_d(p) for n, p in lines.items()},
            "end": [round(v, 1) for v in lines["today"][-1]],
            "hex": hexes(list(lines.values()), w, h, 14 if view == "wide" else 22),
        }
    with open(f"{OUT}/meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)


main()
