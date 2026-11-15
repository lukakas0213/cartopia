"""Builds the static site that phones open from the car's photo QR (GitHub Pages, gh-pages branch).

The site is the app's own village page and Seoul map tiles, unchanged: without the app bridge and with a `#p=` link
the page redraws the shared photo (see "photos and sharing" in assets/village/index.html). The QR links point to
https://lukakas0213.github.io/cartopia/village/#p=..., so the site root must hold village/ and map/ side by side.

    python3 tools/build_share_site.py site      # then publish the folder as the gh-pages branch
"""
import shutil
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "app/src/main/assets"


def main(out: str) -> None:
    dst = Path(out)
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(ASSETS / "village", dst / "village")
    shutil.copytree(ASSETS / "map/seoul", dst / "map/seoul")
    (dst / ".nojekyll").write_text("")  # serve files as they are
    (dst / "index.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>카토피아</title>'
        "<script>location.replace('village/' + location.hash);</script>\n",
        encoding="utf-8",
    )
    size = sum(f.stat().st_size for f in dst.rglob("*") if f.is_file())
    print(f"{dst}: {sum(1 for f in dst.rglob('*') if f.is_file())} files, {size / 1e6:.1f} MB")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "site")
