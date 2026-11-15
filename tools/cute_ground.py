"""Recolor the dark village ground map into the bright pastel palette used by the 3D village.

usage: python cute_ground.py dark_ground.jpg out_ground.jpg [--no-route] [--width N]

With --no-route the bright pixels stay road-coloured (for base_map.png, which has no driven route).
"""
import sys

import numpy as np
from PIL import Image, ImageFilter

# dark map colour -> pastel colour
BASE = [
    ((17, 17, 17), (190, 229, 242)),   # not yet driven: soft sky-blue sea
    ((43, 43, 43), (252, 238, 204)),   # ordinary block: cream
    ((44, 49, 45), (150, 220, 120)),   # park / green block
    ((35, 37, 40), (128, 200, 236)),   # river
    ((44, 49, 53), (128, 200, 236)),
]
ROAD = np.array([255, 252, 244], float)
ROUTE = np.array([255, 160, 90], float)


def main(src, dst, route=True, width=None):
    src_im = Image.open(src).convert("RGB")
    im = np.asarray(src_im, float)
    lum = im.mean(axis=2)
    # base layer: a median filter wipes out the thin lines, then a soft nearest-colour blend
    # over the dark classes keeps the block edges smooth
    dark = np.minimum(np.asarray(src_im.filter(ImageFilter.MedianFilter(9)), float), 55)
    base_lum = dark.mean(axis=2)
    weights, cols = [], []
    for ref, col in BASE:
        d2 = ((dark - np.array(ref, float)) ** 2).sum(axis=2)
        weights.append(np.exp(-d2 / (2 * 3.5 ** 2)))
        cols.append(np.array(col, float))
    w = np.stack(weights, axis=-1) + 1e-9
    w /= w.sum(axis=-1, keepdims=True)
    out = np.einsum("hwk,kc->hwc", w, np.stack(cols))
    # grey road lines, then the white driven route on top
    a_road = np.clip((lum - base_lum - 5) / 28, 0, 1)[..., None]
    out = out * (1 - a_road) + ROAD * a_road
    if route:
        a_route = np.clip((lum - 130) / (240 - 130), 0, 1)[..., None]
        out = out * (1 - a_route) + ROUTE * a_route
    img = Image.fromarray(out.clip(0, 255).astype(np.uint8))
    if width:
        img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    img.save(dst, quality=90)


if __name__ == "__main__":
    args = sys.argv[1:]
    width = int(args[args.index("--width") + 1]) if "--width" in args else None
    main(args[0], args[1], route="--no-route" not in args, width=width)
