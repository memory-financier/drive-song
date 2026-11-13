"""숏폼(세로 1080x1920)·본편(가로 1920x1080) 렌더러. 장면 정의는 plan.json (size 로 크기),
앱 화면 조각은 clips/*.mp4 (미리 잘라 둔 것).
python render.py plan.json out.mp4
"""
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1080, 1920, 30
FONT_B = "C:/Windows/Fonts/malgunbd.ttf"
FONT_R = "C:/Windows/Fonts/malgun.ttf"
C = {
    "bg": (10, 22, 40), "bg2": (19, 41, 74), "card": (18, 35, 58), "line": (30, 54, 84),
    "accent": (54, 194, 232), "text": (255, 255, 255), "sub": (169, 184, 204),
    "rec_bg": (42, 21, 32), "rec_line": (107, 43, 58), "rec_text": (255, 217, 217), "rec_dot": (255, 122, 122),
    "mom": (255, 158, 196), "dad": (120, 196, 255), "kid": (255, 214, 102),
}
_fonts = {}


def font(size, bold=True):
    key = (size, bold)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(FONT_B if bold else FONT_R, size)
    return _fonts[key]


def ease(x):
    x = max(0.0, min(1.0, x))
    return 1 - (1 - x) ** 3


def appear(t, start, end=None, fade=0.35):
    """0..1 불투명도: start 부터 fade 동안 나타나고 end 전에 사라진다."""
    a = ease((t - start) / fade)
    if end is not None:
        a = min(a, ease((end - t) / fade))
    return a


def wrap(text, fnt, width):
    """공백 우선, 넘치면 글자 단위로 줄바꿈."""
    lines, cur = [], ""
    for word in text.split(" "):
        trial = (cur + " " + word).strip()
        if fnt.getlength(trial) <= width:
            cur = trial
            continue
        if cur:
            lines.append(cur)
        cur = ""
        for ch in word:
            if fnt.getlength(cur + ch) > width:
                lines.append(cur)
                cur = ch
            else:
                cur += ch
    if cur:
        lines.append(cur)
    return lines


def layer():
    return Image.new("RGBA", (W, H), (0, 0, 0, 0))


def with_alpha(color, a):
    return (*color[:3], int(255 * a))


def pill(d, x, y, text, size, fg, bg, a, pad=(28, 14), dot=None, anchor="l", outline=None):
    f = font(size)
    tw = f.getlength(text) + (size * 0.9 if dot else 0)
    w, h = tw + pad[0] * 2, size * 1.25 + pad[1] * 2
    if anchor == "c":
        x -= w / 2
    d.rounded_rectangle((x, y, x + w, y + h), radius=h / 2, fill=with_alpha(bg, a),
                        outline=with_alpha(outline, a) if outline else None, width=3 if outline else 0)
    tx = x + pad[0]
    if dot:
        r = size * 0.28
        cy = y + h / 2
        d.ellipse((tx, cy - r, tx + 2 * r, cy + r), fill=with_alpha(dot, a))
        tx += size * 0.9
    d.text((tx, y + h / 2), text, font=f, fill=with_alpha(fg, a), anchor="lm")
    return w, h


def text_block(d, x, y, text, size, color, a, width=W - 160, bold=True, anchor="l", spacing=1.3, hl=None, hl_color=None):
    """여러 줄 글. hl 이 있으면 그 글자를 강조색으로 (한 줄 안에 있을 때)."""
    f = font(size, bold)
    lines = wrap(text, f, width)
    for i, line in enumerate(lines):
        ly = y + i * size * spacing
        lx = x - f.getlength(line) / 2 if anchor == "c" else x
        if hl and hl in line:
            pre, post = line.split(hl, 1)
            d.text((lx, ly), pre, font=f, fill=with_alpha(color, a))
            px = lx + f.getlength(pre)
            d.text((px, ly), hl, font=f, fill=with_alpha(hl_color, a))
            d.text((px + f.getlength(hl), ly), post, font=f, fill=with_alpha(color, a))
        else:
            d.text((lx, ly), line, font=f, fill=with_alpha(color, a))
    return len(lines) * size * spacing


def background():
    img = Image.new("RGB", (W, H), C["bg"])
    glow = Image.new("RGB", (W, H), C["bg"])
    gd = ImageDraw.Draw(glow)
    gd.ellipse((-300, -500, W + 300, 700), fill=C["bg2"])
    gd.ellipse((-200, H - 500, W + 200, H + 600), fill=(14, 30, 54))
    return Image.blend(img, glow.filter(ImageFilter.GaussianBlur(220)), 1.0)


class Clip:
    """미리 잘라 둔 앱 화면 조각(원본 크기)을 프레임 단위로 읽는다 (rgb24)."""

    def __init__(self, path):
        probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                "stream=width,height", "-of", "csv=p=0", str(path)], capture_output=True, text=True)
        self.w, self.h = map(int, probe.stdout.strip().split(",")[:2])
        self.p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(path), "-vf", f"fps={FPS}",
                                   "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        self.last = None

    def next(self):
        buf = self.p.stdout.read(self.w * self.h * 3)
        if len(buf) == self.w * self.h * 3:
            self.last = Image.frombuffer("RGB", (self.w, self.h), buf, "raw", "RGB", 0, 1)
        return self.last


def zoom_frame(img, z0, z1, p, w, h, linear=False):
    """z0 -> z1 상자(원본 좌표)로 천천히 다가가며 w x h 로 맞춘다."""
    p = max(0.0, min(1.0, p)) if linear else ease(p)
    box = tuple(a + (b - a) * p for a, b in zip(z0, z1))
    return img.resize((w, h), Image.BILINEAR, box=box)


def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), radius=r, fill=255)
    return m


def level_track(wav_pcm_path, dur):
    """대화 소리 크기 (프레임마다 0..1), 파형 막대용."""
    import array
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(wav_pcm_path), "-ac", "1", "-ar", "16000",
                          "-f", "s16le", "-"], capture_output=True).stdout
    s = array.array("h", raw)
    hop = 16000 // FPS
    out = []
    for i in range(int(dur * FPS) + 2):
        seg = s[i * hop:(i + 1) * hop]
        out.append((sum(v * v for v in seg[::4]) / max(1, len(seg[::4]))) ** 0.5 / 8000 if seg else 0)
    return [min(1.0, v) for v in out]


def main(plan_path, out_path):
    global W, H
    plan = json.loads(Path(plan_path).read_text(encoding="utf-8"))
    W, H = plan.get("size", (W, H))
    base = Path(plan_path).parent
    total = plan["duration"]
    bg = background()
    levels = level_track(base / plan["audio"], total)
    clips = {}  # 장면 이름 -> Clip (그 장면 시작 때 연다)
    enc = subprocess.Popen(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                            "-r", str(FPS), "-i", "-", "-i", str(base / plan["audio"]), "-map", "0:v", "-map", "1:a",
                            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                            "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", str(out_path)],
                           stdin=subprocess.PIPE)
    masks = {}
    stills = [float(x) for x in sys.argv[3].split(",")] if len(sys.argv) > 3 else None
    frames = [round(x * FPS) for x in stills] if stills else range(int(total * FPS))
    if stills:
        enc.kill()
    for n in frames:
        t = n / FPS
        frame = bg.copy()
        # 1) 앱 화면
        for sc in plan["screens"]:
            if not (sc["start"] <= t < sc["end"]):
                continue
            if stills:
                raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t - sc['start']:.3f}", "-i", str(base / sc["clip"]),
                                      "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"], capture_output=True).stdout
                import io
                src = Image.open(io.BytesIO(raw)).convert("RGB") if raw else None
            else:
                if sc["id"] not in clips:
                    clips[sc["id"]] = Clip(base / sc["clip"])
                src = clips[sc["id"]].next()
            if src is None:
                continue
            full = (0, 0, src.width, src.height)
            p = (t - sc["start"]) / (sc["end"] - sc["start"])
            img = zoom_frame(src, sc.get("z0", full), sc.get("z1", sc.get("z0", full)), p, sc["w"], sc["h"],
                             linear=sc.get("linear", False))
            a = appear(t, sc["start"], sc["end"], fade=sc.get("fade", 0.25))
            key = (sc["w"], sc["h"], sc.get("radius", 28))
            if key not in masks:
                masks[key] = rounded_mask(*key)
            m = masks[key] if a >= 1 else masks[key].point(lambda v, a=a: int(v * a))
            frame.paste(img, (sc["x"], sc["y"]), m)
        # 1-2) 영상 위 그림자 (위·아래 그라데이션, 자막이 잘 읽히게)
        for el in plan["elements"]:
            if el["kind"] != "grad" or not (el["start"] <= t < el["end"]):
                continue
            gkey = ("grad", tuple(el["box"]), el["a0"], el["a1"])
            if gkey not in masks:
                x0, y0, x1, y1 = el["box"]
                g = Image.new("RGBA", (x1 - x0, y1 - y0), (*C["bg"], 0))
                g.putalpha(Image.linear_gradient("L").resize((x1 - x0, y1 - y0)).point(
                    lambda v, a0=el["a0"], a1=el["a1"]: int(255 * (a0 + (a1 - a0) * v / 255))))
                masks[gkey] = g
            g = masks[gkey]
            a = appear(t, el["start"], el["end"] if el["end"] < total else None)
            if a < 1:
                g = g.copy()
                g.putalpha(g.getchannel("A").point(lambda v, a=a: int(v * a)))
            frame = frame.convert("RGBA")
            frame.alpha_composite(g, tuple(el["box"][:2]))
            frame = frame.convert("RGB")
        # 2) 글·도형
        ov = layer()
        d = ImageDraw.Draw(ov)
        for el in plan["elements"]:
            end = el.get("end", total)
            if not (el["start"] - 0.01 <= t < end):
                continue
            a = appear(t, el["start"], end if el.get("fade_out", True) and end < total else None)
            dy = (1 - a) * el.get("rise", 24)
            k = el["kind"]
            if k == "pill":
                pill(d, el["x"], el["y"] + dy, el["text"], el["size"], tuple(el["fg"]), tuple(el["bg"]), a,
                     dot=tuple(el["dot"]) if el.get("dot") else None, anchor=el.get("anchor", "l"),
                     outline=tuple(el["outline"]) if el.get("outline") else None)
            elif k == "text":
                text_block(d, el["x"], el["y"] + dy, el["text"], el["size"], tuple(el["color"]), a,
                           width=el.get("width", W - 160), bold=el.get("bold", True), anchor=el.get("anchor", "l"),
                           hl=el.get("hl"), hl_color=tuple(el.get("hl_color", C["accent"])))
            elif k == "bubble":
                f = font(el["size"], False)
                lines = wrap(el["text"], f, el.get("width", 800))
                bw = max(f.getlength(l) for l in lines) + 64
                bh = len(lines) * el["size"] * 1.35 + 44 + 70
                x, y = el["x"], el["y"] + dy
                col = tuple(el["color"])
                d.rounded_rectangle((x, y, x + max(bw, 260), y + bh), radius=36, fill=with_alpha(C["card"], a * 0.96),
                                    outline=with_alpha(C["line"], a), width=2)
                d.text((x + 32, y + 24), el["who"], font=font(34), fill=with_alpha(col, a))
                for i, line in enumerate(lines):
                    ly = y + 82 + i * el["size"] * 1.35
                    hl = el.get("hl")
                    if hl and hl in line:
                        pre, post = line.split(hl, 1)
                        d.text((x + 32, ly), pre, font=f, fill=with_alpha(C["text"], a))
                        px = x + 32 + f.getlength(pre)
                        d.text((px, ly), hl, font=font(el["size"]), fill=with_alpha(C["accent"], a))
                        d.text((px + font(el["size"]).getlength(hl), ly), post, font=f, fill=with_alpha(C["text"], a))
                    else:
                        d.text((x + 32, ly), line, font=f, fill=with_alpha(C["text"], a))
            elif k == "wave":
                bars, bw_, gap = el.get("bars", 28), 14, 12
                x0 = el.get("cx", W / 2) - (bars * (bw_ + gap) - gap) / 2
                lv = levels[min(n, len(levels) - 1)]
                for i in range(bars):
                    phase = abs((i - bars / 2) / (bars / 2))
                    jitter = ((n * 7 + i * 13) % 17) / 17
                    hgt = 10 + lv * el.get("height", 120) * (1 - phase * 0.7) * (0.55 + 0.45 * jitter)
                    cx = x0 + i * (bw_ + gap)
                    d.rounded_rectangle((cx, el["y"] - hgt / 2, cx + bw_, el["y"] + hgt / 2), radius=7,
                                        fill=with_alpha(tuple(el["color"]), a * 0.9))
            elif k == "rect":
                d.rounded_rectangle(tuple(el["box"]), radius=el.get("radius", 0), fill=with_alpha(tuple(el["color"]), a * el.get("alpha", 1)))
        frame = Image.alpha_composite(frame.convert("RGBA"), ov).convert("RGB")
        if stills:
            frame.save(base / f"still_{t:05.1f}.png")
            continue
        enc.stdin.write(frame.tobytes())
        if n % 150 == 0:
            print(f"{t:5.1f}s", flush=True)
    if not stills:
        enc.stdin.close()
        enc.wait()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
