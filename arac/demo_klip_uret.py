"""Makes the demo clip the app plays in "Deneme modu": 48 s of a drawn night city with 12 English subtitles.

1920x1080, 30 fps, H.264 High yuv420p, one key frame a second, no B-frames, MP4, at most 3 MB.
The lines come from app/src/main/java/dev/turkce/olcum/DenemeSatirlari.kt, the app's own list, so both stay the same.
The subtitle sits inside the app's default read area (x 192-1728, y 777-1079).

Run from deneyler/tv-olcum: uv run --with imageio-ffmpeg --with pillow arac\\demo_klip_uret.py
"""
from pathlib import Path
import random
import re
import subprocess
import sys
import tempfile

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
APP = HERE.parent / "app" / "src" / "main"
LINES_FILE = APP / "java" / "dev" / "turkce" / "olcum" / "DenemeSatirlari.kt"
OUT = APP / "assets" / "demo" / "deneme.mp4"

W, H, FPS = 1920, 1080, 30
LINE_COUNT, SECONDS_PER_LINE = 12, 4
DURATION = LINE_COUNT * SECONDS_PER_LINE
READ_AREA = (192, 777, 1728, 1079)
HORIZON = 772
MAX_BYTES = 3 * 1024 * 1024
FONT_SIZE = 46
TEXT_BOTTOM = 900  # leaves room below for the Turkish line, which sits at the bottom of the read area
FONTS = ["C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/arial.ttf", "DejaVuSans-Bold.ttf"]


def read_lines() -> list[str]:
    source = LINES_FILE.read_text(encoding="utf-8")
    block = source.split("DEMO_LINES = listOf(", 1)[1].split(")", 1)[0]
    lines = re.findall(r'^\s*"([^"\\]*)",\s*$', block, re.MULTILINE)
    seconds = int(re.search(r"DEMO_SECONDS_PER_LINE = (\d+)", source).group(1))
    assert len(lines) == LINE_COUNT, f"{LINE_COUNT} satır bekleniyordu, {len(lines)} bulundu"
    assert seconds == SECONDS_PER_LINE, seconds
    return lines


def font() -> ImageFont.FreeTypeFont:
    for name in FONTS:
        try:
            return ImageFont.truetype(name, FONT_SIZE)
        except OSError:
            continue
    return ImageFont.load_default(FONT_SIZE)


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def background() -> Image.Image:
    """A dark sky fading to a warm horizon, stars, a moon and a row of buildings; plain dark ground below."""
    rng = random.Random(7)
    img = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(img)
    top, middle, low = (8, 12, 32), (30, 28, 66), (92, 52, 64)
    for y in range(HORIZON):
        t = y / HORIZON
        color = mix(top, middle, t / 0.6) if t < 0.6 else mix(middle, low, (t - 0.6) / 0.4)
        draw.line([(0, y), (W, y)], fill=color)
    for y in range(HORIZON, H):
        draw.line([(0, y), (W, y)], fill=mix((14, 14, 22), (6, 6, 10), (y - HORIZON) / (H - HORIZON)))
    for _ in range(140):
        x, y = rng.randrange(W), rng.randrange(int(HORIZON * 0.55))
        v = rng.randrange(150, 235)
        draw.point((x, y), fill=(v, v, v + 15 if v < 240 else 255))
    draw.ellipse([1460, 120, 1580, 240], fill=(232, 228, 210))
    draw.ellipse([1490, 112, 1600, 222], fill=mix(top, middle, 0.25))
    x = -20
    while x < W:
        width = rng.randrange(70, 190)
        height = rng.randrange(140, 430)
        roof = HORIZON - height
        draw.rectangle([x, roof, x + width, HORIZON], fill=(10, 12, 20))
        if rng.random() < 0.3:
            draw.rectangle([x + width // 2 - 2, roof - 40, x + width // 2 + 2, roof], fill=(10, 12, 20))
        for wy in range(roof + 18, HORIZON - 24, 30):
            for wx in range(x + 12, x + width - 16, 24):
                if rng.random() < 0.18:
                    draw.rectangle([wx, wy, wx + 9, wy + 13], fill=(210, 170, 90))
        x += width + rng.randrange(0, 14)
    return img


def frame(base: Image.Image, text: str, face: ImageFont.FreeTypeFont) -> Image.Image:
    img = base.copy()
    draw = ImageDraw.Draw(img)
    position = (W // 2, TEXT_BOTTOM)
    box = draw.textbbox(position, text, font=face, anchor="md", stroke_width=3)
    left, top, right, bottom = READ_AREA
    assert box[0] >= left and box[1] >= top and box[2] <= right and box[3] <= bottom, (text, box)
    draw.text(position, text, font=face, anchor="md", fill=(255, 255, 255), stroke_width=3, stroke_fill=(0, 0, 0))
    return img


def check(ffmpeg: str) -> str:
    """Reads the finished clip back and fails when it is not what the app expects."""
    probe = subprocess.run([ffmpeg, "-hide_banner", "-i", str(OUT)], capture_output=True, text=True).stderr
    stream = next(l.strip() for l in probe.splitlines() if "Video:" in l)
    duration = re.search(r"Duration: ([\d:.]+)", probe).group(1)
    frames = subprocess.run(
        [ffmpeg, "-hide_banner", "-i", str(OUT), "-vf", "showinfo", "-f", "null", "-"],
        capture_output=True, text=True,
    ).stderr
    total = len(re.findall(r"\bn:\s*\d+", frames))
    keys = len(re.findall(r"iskey:1", frames))
    b_frames = len(re.findall(r"type:B", frames))
    assert "h264 (High)" in stream and "yuv420p" in stream and "1920x1080" in stream and " 30 fps" in stream, stream
    assert duration.startswith("00:00:48.0"), duration
    assert total == DURATION * FPS, total
    assert keys == DURATION, keys
    assert b_frames == 0, b_frames
    size = OUT.stat().st_size
    assert size <= MAX_BYTES, size
    return f"{OUT.name}: {size} bayt, {duration}, {total} kare, {keys} anahtar kare, B-kare {b_frames}\n{stream}"


def main() -> None:
    lines = read_lines()
    face = font()
    base = background()
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        for i, text in enumerate(lines):
            frame(base, text, face).save(Path(work) / f"{i:02d}.png")
        subprocess.run([
            ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            "-framerate", f"1/{SECONDS_PER_LINE}", "-i", str(Path(work) / "%02d.png"),
            "-vf", f"tpad=stop_mode=clone:stop_duration={SECONDS_PER_LINE},fps={FPS},format=yuv420p",
            "-t", str(DURATION), "-c:v", "libx264", "-preset", "slow", "-profile:v", "high", "-pix_fmt", "yuv420p",
            "-crf", "20", "-bf", "0", "-g", str(FPS), "-keyint_min", str(FPS), "-sc_threshold", "0",
            "-an", "-movflags", "+faststart", str(OUT),
        ], check=True)
    print(check(ffmpeg))


if __name__ == "__main__":
    sys.exit(main())
