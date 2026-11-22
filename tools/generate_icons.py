"""生成应用图标（纯 Pillow 实现，无需设计资源）。

设计：深色圆角方块 + 从左到右 绿→蓝 渐变的 </> 符号。
"""
import os
import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

S = 1024  # 高分辨率母版


def lerp(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(3))


def gradient(w, h, c1, c2, diagonal=False):
    arr = np.zeros((h, w, 3), dtype=np.float32)
    if diagonal:
        yy, xx = np.mgrid[0:h, 0:w]
        t = (xx / max(w - 1, 1) * 0.6 + yy / max(h - 1, 1) * 0.4)
    else:
        t = np.linspace(0, 1, h, dtype=np.float32)[:, None].repeat(w, axis=1)
    t = np.clip(t, 0, 1)[..., None]
    a = np.array(c1, dtype=np.float32)
    b = np.array(c2, dtype=np.float32)
    arr = a * (1 - t) + b * t
    return Image.fromarray(arr.astype(np.uint8), "RGB")


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return m


def build_master():
    # 背景：深蓝黑对角渐变
    bg = gradient(S, S, (24, 52, 74), (8, 12, 18), diagonal=True)

    # </> 字形
    glyph = Image.new("L", (S, S), 0)
    gd = ImageDraw.Draw(glyph)
    w = 62
    gd.line([(360, 360), (232, 512), (360, 664)], fill=255, width=w, joint="curve")
    gd.line([(664, 360), (792, 512), (664, 664)], fill=255, width=w, joint="curve")
    gd.line([(586, 330), (438, 694)], fill=255, width=w, joint="curve")
    # 圆头
    for pt in [(360, 360), (232, 512), (360, 664), (664, 360), (792, 512), (664, 664),
               (586, 330), (438, 694)]:
        gd.ellipse((pt[0] - w // 2, pt[1] - w // 2, pt[0] + w // 2, pt[1] + w // 2), fill=255)

    # 字形彩色渐变：绿 -> 蓝
    glyph_color = gradient(S, S, (52, 211, 153), (91, 141, 239))

    icon = bg.convert("RGBA")
    icon.paste(glyph_color, (0, 0), glyph)

    # 圆角裁切
    icon.putalpha(rounded_mask(S, int(S * 0.23)))
    return icon


def main():
    master = build_master()
    plan = {
        "mipmap-xxxhdpi": 192,
        "mipmap-xxhdpi": 144,
        "mipmap-xhdpi": 96,
        "mipmap-hdpi": 72,
        "mipmap-mdpi": 48,
    }
    for folder, px in plan.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        print("wrote", os.path.join(folder, "ic_launcher.png"), px)

    out = os.path.join(ROOT, "docs")
    os.makedirs(out, exist_ok=True)
    master.resize((512, 512), Image.LANCZOS).save(os.path.join(out, "icon-512.png"))
    print("wrote docs/icon-512.png")


if __name__ == "__main__":
    main()
