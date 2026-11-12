# /// script
# dependencies = ["pillow", "font-roboto"]
# ///
"""Tv Türkçe Altyazı: ikon, TV banner'ı ve Google Play mağaza görsellerini üretir.

Çalıştırma: uv run --with pillow --with font-roboto arac\\gorsel_uret.py
Yazı tipi: Roboto Bold (Apache 2.0, PyPI "font-roboto" paketi). Yalnız çizim, fotoğraf yok.
"""
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
STORE = ROOT.parent.parent / "docs" / "yayin" / "play" / "gorseller"

NAVY = (18, 32, 47)            # #12202F
NAVY_TOP = (24, 42, 62)
NAVY_BOT = (12, 22, 33)
AMBER = (255, 176, 32)         # #FFB020
WHITE = (255, 255, 255)
SLOGAN_COL = (196, 208, 222)
SCREEN = (27, 46, 68)
FADED = (255, 255, 255, 120)
SLOGAN = "Oyun altyazısı televizyonda Türkçe"
TITLE = "Tv Türkçe Altyazı"


def font_path():
    import font_roboto
    d = Path(font_roboto.__file__).resolve().parent / "files"
    return str(d / "Roboto-Bold.ttf")


def font(size):
    return ImageFont.truetype(font_path(), int(round(size)))


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(NAVY_TOP[i] + (NAVY_BOT[i] - NAVY_TOP[i]) * t) for i in range(3))
        px.line([(0, y), (w, y)], fill=c)
    return img.convert("RGBA")


def glyph(img, cx, cy, width, screen_fill=None):
    """TV çerçevesi + iki altyazı çizgisi. width: çerçeve genişliği (px). Toplam yükseklik 0.82*width."""
    u = width / 100.0
    x0 = cx - width / 2
    y0 = cy - 41 * u
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = 14 * u
    if screen_fill:
        d.rounded_rectangle([x0, y0, x0 + 100 * u, y0 + 68 * u], radius=r, fill=screen_fill)
    d.rounded_rectangle([x0, y0, x0 + 100 * u, y0 + 68 * u], radius=r, outline=WHITE + (255,), width=max(1, round(8 * u)))
    # ayak
    d.rectangle([x0 + 43 * u, y0 + 67 * u, x0 + 57 * u, y0 + 76 * u], fill=WHITE + (255,))
    d.rounded_rectangle([x0 + 28 * u, y0 + 74 * u, x0 + 72 * u, y0 + 82 * u], radius=4 * u, fill=WHITE + (255,))
    # altyazı çizgileri
    t = 8 * u
    d.rounded_rectangle([x0 + 27 * u, y0 + 38 * u - t / 2, x0 + 73 * u, y0 + 38 * u + t / 2], radius=t / 2, fill=FADED)
    d.rounded_rectangle([x0 + 19 * u, y0 + 52 * u - t / 2, x0 + 81 * u, y0 + 52 * u + t / 2], radius=t / 2, fill=AMBER + (255,))
    img.alpha_composite(layer)


def fit_font(text, max_w, start):
    s = start
    while s > 4:
        f = font(s)
        b = f.getbbox(text)
        if b[2] - b[0] <= max_w:
            return f
        s -= 1
    return font(4)


def text_center(d, text, f, cx, top, fill):
    """Metni yatayda cx'e ortalar; mürekkep kutusunun üstü 'top' olur. Alt sınırı döndürür."""
    b = f.getbbox(text)
    d.text((cx - (b[0] + b[2]) / 2, top - b[1]), text, font=f, fill=fill)
    return top + (b[3] - b[1])


def text_left(d, text, f, x, top, fill):
    b = f.getbbox(text)
    d.text((x - b[0], top - b[1]), text, font=f, fill=fill)
    return top + (b[3] - b[1])


def finish(img, size, mode):
    out = img.resize(size, Image.LANCZOS)
    if mode == "RGB":
        bg = Image.new("RGB", size, NAVY)
        bg.paste(out, mask=out.split()[3])
        return bg
    return out


def stacked_banner(w, h, ss):
    """Ortalı düzen: üstte simge, altında ad ve slogan (TV banner'ı)."""
    W, H = w * ss, h * ss
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    f_t = fit_font(TITLE, W * 0.88, H * 0.22)
    f_s = fit_font(SLOGAN, W * 0.80, H * 0.074)
    gw = H * 0.34
    gh = gw * 0.82
    tb = f_t.getbbox(TITLE)
    sb = f_s.getbbox(SLOGAN)
    th, sh = tb[3] - tb[1], sb[3] - sb[1]
    gap1, gap2 = H * 0.065, H * 0.045
    total = gh + gap1 + th + gap2 + sh
    top = (H - total) / 2
    glyph(img, W / 2, top + gh / 2, gw, screen_fill=SCREEN)
    y = text_center(d, TITLE, f_t, W / 2, top + gh + gap1, WHITE)
    text_center(d, SLOGAN, f_s, W / 2, y + gap2, SLOGAN_COL)
    return img


def feature_graphic(w, h, ss):
    W, H = w * ss, h * ss
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    gw = H * 0.50
    gh = gw * 0.82
    text_w = W - 2 * W * 0.07 - gw - W * 0.05
    f_t = fit_font(TITLE, text_w, H * 0.26)
    f_s = fit_font(SLOGAN, text_w, H * 0.075)
    tb = f_t.getbbox(TITLE)
    sb = f_s.getbbox(SLOGAN)
    th, sh = tb[3] - tb[1], sb[3] - sb[1]
    gap = H * 0.06
    block_w = max(tb[2] - tb[0], sb[2] - sb[0])
    spacing = W * 0.05
    total_w = gw + spacing + block_w
    x0 = (W - total_w) / 2
    glyph(img, x0 + gw / 2, H / 2, gw, screen_fill=SCREEN)
    tx = x0 + gw + spacing
    top = (H - (th + gap + sh)) / 2
    y = text_left(d, TITLE, f_t, tx, top, WHITE)
    text_left(d, SLOGAN, f_s, tx, y + gap, SLOGAN_COL)
    return img


def icon_square(size, ss=4, rounded=False):
    W = size * ss
    img = gradient(W, W)
    glyph(img, W / 2, W / 2 + W * 0.005, W * 0.66, screen_fill=SCREEN)
    if rounded:
        m = Image.new("L", (W, W), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, W - 1, W - 1], radius=W * 0.20, fill=255)
        img.putalpha(m)
    return img


def foreground(size, ss=4):
    W = size * ss
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    glyph(img, W / 2, W / 2, W * 0.58)
    return img


def main():
    results = []  # (yol, beklenen_boyut, beklenen_kip)

    def save(img, path, size, mode, **kw):
        path.parent.mkdir(parents=True, exist_ok=True)
        out = finish(img, size, mode)
        out.save(path, "PNG", optimize=True, **kw)
        results.append((path, size, mode))

    # TV banner (uygulama içi)
    save(stacked_banner(320, 180, 6), RES / "drawable-xhdpi" / "banner.png", (320, 180), "RGB")

    # Klasik ikonlar
    for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(icon_square(px, rounded=True), RES / f"mipmap-{dpi}" / "ic_launcher.png", (px, px), "RGBA")

    # Uyarlanabilir ikon ön planı
    for dpi, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        save(foreground(px), RES / f"mipmap-{dpi}" / "ic_launcher_foreground.png", (px, px), "RGBA")

    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n', encoding="utf-8")
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        '    <solid android:color="#12202F" />\n'
        '</shape>\n', encoding="utf-8")

    # Mağaza görselleri
    save(icon_square(512, ss=2), STORE / "ikon-512.png", (512, 512), "RGBA")
    save(feature_graphic(1024, 500, 3), STORE / "one-cikan-1024x500.png", (1024, 500), "RGB")
    save(stacked_banner(1280, 720, 2), STORE / "tv-banner-1280x720.png", (1280, 720), "RGB")

    # Doğrulama
    bad = []
    print(f"{'dosya':70s} {'boyut':>10s} kip")
    for path, size, mode in results:
        with Image.open(path) as im:
            ok = im.size == size and im.mode == mode
            kb = path.stat().st_size / 1024
            print(f"{str(path.relative_to(ROOT.parent.parent)):70s} {im.size[0]}x{im.size[1]:<5d} {im.mode} {kb:.0f}KB {'OK' if ok else 'HATA'}")
            if not ok:
                bad.append(f"{path}: {im.size} {im.mode} != {size} {mode}")
    for n in ("one-cikan-1024x500.png", "tv-banner-1280x720.png"):
        with Image.open(STORE / n) as im:
            if im.mode != "RGB" or "A" in im.getbands():
                bad.append(f"{n}: alfa var")
    if (STORE / "ikon-512.png").stat().st_size > 1024 * 1024:
        bad.append("ikon-512.png 1024 KB üstünde")
    # güvenli alan
    for dpi, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        with Image.open(RES / f"mipmap-{dpi}" / "ic_launcher_foreground.png") as im:
            bb = im.split()[3].point(lambda v: 255 if v > 8 else 0).getbbox()
            lo, hi = 0.195 * px - 1, 0.805 * px + 1
            ok = bb[0] >= lo and bb[1] >= lo and bb[2] <= hi and bb[3] <= hi
            print(f"on plan {dpi}: kutu={bb} guvenli=[{lo:.1f},{hi:.1f}] {'OK' if ok else 'HATA'}")
            if not ok:
                bad.append(f"on plan {dpi} güvenli alan dışında")
    if bad:
        print("HATALAR:", *bad, sep="\n  ")
        sys.exit(1)
    print("Tümü beklenen ölçü ve kipte.")


if __name__ == "__main__":
    main()
