#!/usr/bin/env python3
"""Draws the README illustrations in assets/, each in a light and a dark version.

    python3 scripts/readme-art.py

Transparent backgrounds, so the cards sit on GitHub's own page color in either theme.
Numbers shown here are measured on the Xiaomi 15 Ultra; keep them in step with the app.
"""
import math
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "assets"
FONT = ("-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', "
        "'Helvetica Neue', 'Segoe UI', Inter, Arial, sans-serif")

THEMES = {
    "light": dict(text="#1d1d1f", dim="#6e6e73", faint="#d2d2d7", card="#f5f5f7", red="#ff3b30",
                  phone="#1d1d1f", bezel="#3a3a3c", yellow="#b78f00", inset="#ffffff"),
    "dark": dict(text="#f5f5f7", dim="#a1a1a6", faint="#3a3a3c", card="#161b22", red="#ff453a",
                 phone="#2c2c2e", bezel="#48484a", yellow="#ffd60a", inset="#0d1117"),
}


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def svg(w, h, label, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'role="img" aria-label="{esc(label)}"><title>{esc(label)}</title>'
            f'<style>text{{font-family:{FONT}}}</style>{body}</svg>\n')


def text(x, y, s, size, fill, weight=400, anchor="middle", spacing=0, opacity=None):
    op = f' fill-opacity="{opacity}"' if opacity is not None else ""
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" fill="{fill}"{op} '
            f'text-anchor="{anchor}" letter-spacing="{spacing}">{esc(s)}</text>')


def card(x, y, w, h, t, r=40, stroke=None):
    s = f' stroke="{stroke}" stroke-width="2.5"' if stroke else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{t["card"]}"{s}/>'


def polyline(points, stroke, width, extra=""):
    d = "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in points)
    return f'<path d="{d}" fill="none" stroke="{stroke}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"{extra}/>'


def flat_landscape(x, y, w, h, uid):
    """A low-contrast scene the way Apple Log looks before grading."""
    return (
        f'<defs><linearGradient id="sky{uid}" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="#a3a8a7"/><stop offset="1" stop-color="#8e9493"/></linearGradient>'
        f'<linearGradient id="hill{uid}" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="#6f7770"/><stop offset="1" stop-color="#5d645f"/></linearGradient>'
        f'<clipPath id="img{uid}"><rect x="{x}" y="{y}" width="{w}" height="{h}"/></clipPath></defs>'
        f'<g clip-path="url(#img{uid})">'
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="url(#sky{uid})"/>'
        f'<circle cx="{x + w * 0.72}" cy="{y + h * 0.3}" r="{h * 0.11}" fill="#c9c7c0"/>'
        f'<path d="M{x} {y + h * 0.72} C{x + w * 0.2} {y + h * 0.55} {x + w * 0.35} {y + h * 0.6} {x + w * 0.5} {y + h * 0.68} '
        f'C{x + w * 0.68} {y + h * 0.78} {x + w * 0.82} {y + h * 0.56} {x + w} {y + h * 0.64} L{x + w} {y + h} L{x} {y + h} Z" '
        f'fill="url(#hill{uid})"/>'
        f'<path d="M{x} {y + h * 0.86} C{x + w * 0.3} {y + h * 0.78} {x + w * 0.6} {y + h * 0.84} {x + w} {y + h * 0.82} '
        f'L{x + w} {y + h} L{x} {y + h} Z" fill="#535a55"/></g>'
    )


# --- hero ---------------------------------------------------------------------------------

def hero(t):
    W, H = 1600, 1000
    b = [
        text(800, 110, "Latitude", 34, t["red"], 600, spacing=-0.3),
        text(800, 228, "Room to grade.", 112, t["text"], 700, spacing=-3.4),
        text(800, 300, "A camera app for Android that records footage", 40, t["dim"], spacing=-0.4),
        text(800, 352, "built for DaVinci Resolve.", 40, t["dim"], spacing=-0.4),
    ]
    # Phone in landscape, the app as it looks mid-take (Xiaomi 15 Ultra, 2400 × 1080).
    px, py, pw, ph = 330, 450, 940, 440
    b.append(f'<rect x="{px}" y="{py}" width="{pw}" height="{ph}" rx="64" fill="{t["phone"]}"/>')
    b.append(f'<rect x="{px + 10}" y="{py + 10}" width="{pw - 20}" height="{ph - 20}" rx="56" fill="#000"/>')
    sx, sy, sw, sh = px + 22, py + 22, pw - 44, ph - 44  # screen
    iw = sh * 4 / 3  # open gate 4:3 fills the height
    ix = sx + (sw - iw) / 2
    b.append(f'<clipPath id="scr"><rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="44"/></clipPath>')
    b.append('<g clip-path="url(#scr)">')
    b.append(f'<rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" fill="#000"/>')
    b.append(flat_landscape(ix, sy, iw, sh, "h"))
    # Top readout: timer, frame rate, profile, shutter, ISO.
    pill_w = 430
    pxl = sx + sw / 2 - pill_w / 2
    b.append(f'<rect x="{pxl}" y="{sy + 14}" width="{pill_w}" height="40" rx="20" fill="#000" fill-opacity=".45"/>')
    b.append(f'<circle cx="{pxl + 24}" cy="{sy + 34}" r="5.5" fill="#ff3b30"/>')
    b.append(text(pxl + 38, sy + 41, "00:00:12", 18, "#fff", 600, "start"))
    b.append(text(pxl + 130, sy + 41, "30p", 18, "#fff", 600, "start"))
    b.append(text(pxl + 180, sy + 41, "LOG", 18, "#fff", 600, "start"))
    b.append(text(pxl + 236, sy + 41, "1/60", 18, "#fff", 600, "start"))
    b.append(text(pxl + 280, sy + 41, "180°", 13, "#fff", 500, "start", opacity=.6))
    b.append(text(pxl + 330, sy + 41, "ISO", 13, "#fff", 500, "start", opacity=.6))
    b.append(text(pxl + 358, sy + 41, "400", 18, "#fff", 600, "start"))
    # Left rail: settings, sound meter, locks (yellow: locked for the take).
    lx = sx + (ix - sx) / 2
    b.append(f'<circle cx="{lx}" cy="{sy + 44}" r="22" fill="#fff" fill-opacity=".14"/>')
    b.append(f'<circle cx="{lx}" cy="{sy + 44}" r="7" fill="none" stroke="#fff" stroke-width="2.5"/>')
    for i, level in enumerate((0.62, 0.55)):
        bx = lx - 7 + i * 10
        top, bottom = sy + 86, sy + 186
        b.append(f'<rect x="{bx}" y="{top}" width="5" height="{bottom - top}" rx="2.5" fill="#fff" fill-opacity=".2"/>')
        b.append(f'<rect x="{bx}" y="{bottom - (bottom - top) * level}" width="5" height="{(bottom - top) * level}" rx="2.5" fill="#fff" fill-opacity=".9"/>')
    for i, name in enumerate(("AE", "AWB", "AF")):
        cy = sy + sh - 118 + i * 38
        b.append(f'<rect x="{lx - 34}" y="{cy}" width="68" height="30" rx="15" fill="#ffd60a" fill-opacity=".2"/>')
        b.append(text(lx, cy + 20, name, 13, "#ffd60a", 700, spacing=.4))
    # Right rail: lenses, record button.
    rx = ix + iw + (sx + sw - ix - iw) / 2
    for i, name in enumerate(("14mm", "23mm", "69mm", "95mm")):
        cy = sy + 18 + i * 36
        if name == "23mm":
            b.append(f'<rect x="{rx - 38}" y="{cy}" width="76" height="30" rx="15" fill="#fff" fill-opacity=".2"/>')
        b.append(text(rx, cy + 20, name, 14, "#fff" if name == "23mm" else "#9a9a9a", 600))
    rcy = sy + sh / 2 + 52
    b.append(f'<circle cx="{rx}" cy="{rcy}" r="33" fill="none" stroke="#fff" stroke-width="4"/>')
    b.append(f'<rect x="{rx - 14}" y="{rcy - 14}" width="28" height="28" rx="6" fill="#ff3b30"/>')
    b.append('</g>')
    # Callouts beside the phone.
    for x, anchor, cap, value, sub in (
        (300, "end", "Record-ready", "0.3 s", "After launch"),
        (1300, "start", "Recorded as", "Apple Log", "10-bit · Rec.2020"),
    ):
        b.append(text(x, 612, cap, 24, t["dim"], 500, anchor))
        b.append(text(x, 666, value, 50, t["text"], 700, anchor, -1.2))
        b.append(text(x, 700, sub, 22, t["dim"], 400, anchor))
    return svg(W, H, "Latitude. Room to grade. A camera app for Android that records footage built for "
                     "DaVinci Resolve. Record-ready 0.3 seconds after launch, recorded as 10-bit Apple Log.",
               "".join(b))


# --- why ----------------------------------------------------------------------------------

def why(t):
    W, H = 1600, 600
    b = []
    for side in (0, 1):
        x0 = side * 816
        b.append(card(x0, 0, 784, H, t))
        cap = ("Typical phone video", "With Latitude")[side]
        title = ("The look is baked in.", "The grade is yours.")[side]
        foot = ("Contrast, sharpening and clipped highlights, fixed in the file",
                "A flat Apple Log image with the highlights still there")[side]
        b.append(text(x0 + 392, 70, cap, 26, t["red"] if side else t["dim"], 500))
        b.append(text(x0 + 392, 120, title, 44, t["text"], 700, spacing=-0.8))
        # Tone curve: scene brightness in stops (x) to signal (y).
        gx, gy, gw, gh = x0 + 152, 170, 480, 300
        b.append(f'<rect x="{gx}" y="{gy}" width="{gw}" height="{gh}" fill="none" stroke="{t["faint"]}" stroke-width="2"/>')
        for k in range(1, 4):
            b.append(f'<line x1="{gx + gw * k / 4}" y1="{gy}" x2="{gx + gw * k / 4}" y2="{gy + gh}" stroke="{t["faint"]}" stroke-width="1" stroke-dasharray="4 8"/>')
        pts = []
        for i in range(201):
            u = i / 200
            if side == 0:
                # Steep S-curve that runs into the top two stops early.
                v = min(1.0, 1 / (1 + math.exp(-11 * (u - 0.38))) * 1.08)
            else:
                # Log: even spacing per stop, reaches the top only at the brightest highlight.
                v = 0.06 + 0.9 * (math.log2(1 + 31 * u) / 5)
            pts.append((gx + gw * u, gy + gh - gh * v))
        if side == 0:
            clip_x = next(px for px, py in pts if py <= gy + 0.5)
            b.append(f'<rect x="{clip_x}" y="{gy}" width="{gx + gw - clip_x}" height="{gh}" fill="{t["red"]}" fill-opacity=".12"/>')
            b.append(text((clip_x + gx + gw) / 2, gy + gh / 2 + 8, "Clipped", 22, t["red"], 600))
        else:
            hx = gx + gw * 0.62
            b.append(f'<rect x="{hx}" y="{gy}" width="{gx + gw - hx}" height="{gh}" fill="{t["red"]}" fill-opacity=".08"/>')
            b.append(text((hx + gx + gw) / 2, gy + gh / 2 + 70, "Highlights kept", 22, t["red"], 600))
        b.append(polyline(pts, t["text"], 5))
        b.append(text(gx, gy + gh + 34, "Shadows", 20, t["dim"], 400, "start"))
        b.append(text(gx + gw, gy + gh + 34, "Highlights", 20, t["dim"], 400, "end"))
        b.append(text(x0 + 392, H - 40, foot, 24, t["dim"]))
    return svg(W, H, "Typical phone video: the look is baked in, with contrast, sharpening and clipped "
                     "highlights fixed in the file. With Latitude: the grade is yours, a flat Apple Log "
                     "image with the highlights still there.", "".join(b))


# --- how it works -------------------------------------------------------------------------

def how(t):
    W, H = 1600, 760
    b = []
    steps = (
        ("Press a volume key.", ("Recording starts at once,", "even from standby.")),
        ("Import into Resolve.", ("Apple Log, lens, exposure and", "timecode are already filled in.")),
        ("Grade with the rest.", ("One color-managed timeline", "with your other cameras.")),
    )
    for i, (title, desc) in enumerate(steps):
        x0 = i * 544
        cx = x0 + 256
        b.append(card(x0, 0, 512, H, t))
        b.append(f'<circle cx="{cx}" cy="530" r="24" fill="{t["red"]}"/>')
        b.append(text(cx, 539, str(i + 1), 26, "#fff", 700))
        b.append(text(cx, 610, title, 38, t["text"], 700, spacing=-0.6))
        b.append(text(cx, 656, desc[0], 25, t["dim"]))
        b.append(text(cx, 690, desc[1], 25, t["dim"]))

    # 1: phone in landscape, volume key on its top edge, recording.
    x, y, w, h = 76, 210, 360, 170
    b.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="30" fill="{t["phone"]}"/>')
    b.append(f'<rect x="{x + 230}" y="{y - 11}" width="70" height="9" rx="4.5" fill="{t["red"]}"/>')
    b.append(f'<rect x="{x + 150}" y="{y - 11}" width="40" height="9" rx="4.5" fill="{t["phone"]}"/>')
    for k, r in enumerate((26, 46)):
        b.append(f'<path d="M{x + 265 - r} {y - 28 - k * 4} A{r} {r} 0 0 1 {x + 265 + r} {y - 28 - k * 4}" fill="none" '
                 f'stroke="{t["red"]}" stroke-opacity="{.55 - k * .25}" stroke-width="4" stroke-linecap="round"/>')
    b.append(f'<rect x="{x + 8}" y="{y + 8}" width="{w - 16}" height="{h - 16}" rx="23" fill="#000"/>')
    iw = (h - 16) * 4 / 3
    b.append(flat_landscape(x + (w - iw) / 2, y + 8, iw, h - 16, "s"))
    b.append(f'<rect x="{x + 125}" y="{y + 18}" width="110" height="26" rx="13" fill="#000" fill-opacity=".45"/>')
    b.append(f'<circle cx="{x + 140}" cy="{y + 31}" r="4.5" fill="#ff3b30"/>')
    b.append(text(x + 150, y + 37, "00:00:03", 15, "#fff", 600, "start"))
    b.append(f'<circle cx="{x + w - 38}" cy="{y + h / 2}" r="17" fill="none" stroke="#fff" stroke-width="3"/>')
    b.append(f'<rect x="{x + w - 45}" y="{y + h / 2 - 7}" width="14" height="14" rx="3" fill="#ff3b30"/>')

    # 2: Resolve's metadata for the clip.
    x, y = 544 + 56, 70
    b.append(f'<rect x="{x}" y="{y}" width="400" height="380" rx="22" fill="{t["inset"]}" stroke="{t["faint"]}" stroke-width="2"/>')
    b.append(flat_landscape(x + 24, y + 24, 128, 96, "m"))
    b.append(text(x + 172, y + 62, "LAT_20261102_193225", 17, t["text"], 600, "start"))
    b.append(text(x + 172, y + 92, "4096 × 3072 · 29.97", 17, t["dim"], 400, "start"))
    rows = (("Input Color Space", "Apple Log"), ("Lens Type", "23mm f/1.63"), ("ISO", "400"),
            ("Shutter Angle", "180"), ("White Point", "5600 K"), ("Start TC", "19:32:26:02"))
    for k, (name, value) in enumerate(rows):
        ry = y + 170 + k * 36
        b.append(text(x + 24, ry, name, 18, t["dim"], 400, "start"))
        b.append(text(x + 376, ry, value, 18, t["red"] if k == 0 else t["text"], 600, "end"))

    # 3: timeline with the user's other cameras.
    x, y = 1088 + 46, 120
    clips = (("Sony A7C · HLG", 30, 190, False), ("DJI Action 6 · D-Log M", 140, 230, False),
             ("Latitude · Apple Log", 60, 300, True))
    b.append(f'<line x1="{x + 250}" y1="{y - 30}" x2="{x + 250}" y2="{y + 250}" stroke="{t["text"]}" stroke-width="3"/>')
    b.append(f'<path d="M{x + 240} {y - 40} h20 l-10 12 z" fill="{t["text"]}"/>')
    for k, (name, start, length, ours) in enumerate(clips):
        cy = y + k * 76
        b.append(f'<rect x="{x + start}" y="{cy}" width="{length}" height="56" rx="12" '
                 f'fill="{t["red"] if ours else t["faint"]}"/>')
        b.append(text(x + start + 16, cy + 35, name, 17, "#fff" if ours else t["text"], 600, "start"))
    b.append(text(x + 210, y + 300, "DaVinci Wide Gamut", 22, t["dim"], 500))
    return svg(W, H, "How it works: 1. Press a volume key, recording starts at once, even from standby. "
                     "2. Import into Resolve, Apple Log, lens, exposure and timecode are already filled in. "
                     "3. Grade with the rest, one color-managed timeline with your other cameras.", "".join(b))


# --- pipeline -----------------------------------------------------------------------------

def pipeline(t):
    W, H = 1600, 300
    steps = (("01", "Locked capture", "ISO · shutter · WB · focus"),
             ("02", "Apple Log", "Rec.2020 · 10-bit HEVC"),
             ("03", "DaVinci Resolve", "Input: Apple Log"),
             ("04", "Wide Gamut", "Your look, unchanged"))
    b = []
    cw, gap = 352, 64
    for i, (num, title, sub) in enumerate(steps):
        x0 = i * (cw + gap)
        ours = i == 1
        b.append(card(x0, 0, cw, 200, t, 32, t["red"] if ours else None))
        b.append(text(x0 + cw / 2, 70, num, 20, t["red"] if ours else t["dim"], 600, spacing=.6))
        b.append(text(x0 + cw / 2, 120, title, 34, t["text"], 700, spacing=-0.6))
        b.append(text(x0 + cw / 2, 160, sub, 21, t["dim"]))
        if i < 3:
            ax = x0 + cw + 14
            b.append(f'<path d="M{ax} 100 h36 m-10 -9 l10 9 l-10 9" fill="none" stroke="{t["dim"]}" stroke-opacity=".6" '
                     f'stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>')
    b.append(text(800, 262, "Resolve picks Apple Log by itself. No LUT, no DRT wrapping, no custom transform.", 22, t["dim"]))
    return svg(W, H, "Locked capture, recorded as Apple Log in Rec.2020 and 10-bit HEVC, recognized as Apple Log "
                     "by DaVinci Resolve, lands in DaVinci Wide Gamut with your look unchanged.", "".join(b))


# --- principles ---------------------------------------------------------------------------

def principles(t):
    W, H = 1600, 664
    items = (
        ("rec", "Ready instantly.", ("Opens straight into a live,", "record-ready preview.", "Volume buttons start and stop.")),
        ("lock", "Nothing moves mid-clip.", ("Exposure, white balance and", "focus lock the moment", "you press record.")),
        ("cfr", "Constant frame rate.", ("30p, stored as 29.97 like DJI", "and Sony do. Never variable,", "because Resolve hates VFR.")),
        ("curve", "No baked-in look.", ("No sharpening, no LUT,", "no AI. Just the image,", "ready for your grade.")),
    )
    b = []
    for i, (icon, title, lines) in enumerate(items):
        x0, y0 = (i % 2) * 816, (i // 2) * 332
        b.append(card(x0, y0, 784, 300, t, 32))
        ix, iy = x0 + 70, y0 + 80
        if icon == "rec":
            b.append(f'<circle cx="{ix}" cy="{iy}" r="26" fill="none" stroke="{t["text"]}" stroke-width="3.5"/>')
            b.append(f'<circle cx="{ix}" cy="{iy}" r="17" fill="{t["red"]}"/>')
        elif icon == "lock":
            b.append(f'<rect x="{ix - 34}" y="{iy - 17}" width="68" height="34" rx="17" fill="none" stroke="{t["yellow"]}" stroke-width="3"/>')
            b.append(text(ix, iy + 7, "AE", 17, t["yellow"], 700))
        elif icon == "cfr":
            for k in range(6):
                b.append(f'<rect x="{ix - 30 + k * 11}" y="{iy - 18}" width="5" height="36" rx="1.5" fill="{t["text"]}"/>')
        else:
            b.append(f'<path d="M{ix - 32} {iy + 18} C{ix - 10} {iy + 18} {ix - 8} {iy - 8} {ix + 32} {iy - 16}" fill="none" '
                     f'stroke="{t["text"]}" stroke-width="3.5" stroke-linecap="round"/>')
        b.append(text(x0 + 40, y0 + 165, title, 40, t["text"], 700, "start", -0.8))
        for k, line in enumerate(lines):
            b.append(text(x0 + 40, y0 + 210 + k * 32, line, 25, t["dim"], 400, "start"))
    return svg(W, H, "Ready instantly: opens straight into a record-ready preview, volume buttons start and stop. "
                     "Nothing moves mid-clip: exposure, white balance and focus lock on record. Constant frame "
                     "rate: 30p, stored as 29.97 like DJI and Sony do. No baked-in look: no sharpening, no LUT, no AI.",
               "".join(b))


# --- measured -----------------------------------------------------------------------------

def measured(t):
    W, H = 1600, 380
    items = (("0.3 s", "Launch to record-ready", "Cold start"),
             ("< 1 frame", "Picture to sound", "Clap test, internal mic"),
             ("0", "Frames dropped", "Test takes at a strict 29.97"))
    b = []
    for i, (value, title, sub) in enumerate(items):
        x0 = i * 544
        b.append(card(x0, 0, 512, H, t))
        b.append(text(x0 + 256, 168, value, 96, t["text"], 700, spacing=-3))
        b.append(text(x0 + 256, 248, title, 36, t["text"], 600, spacing=-0.5))
        b.append(text(x0 + 256, 298, sub, 28, t["dim"]))
    return svg(W, H, "Measured on the Xiaomi 15 Ultra: 0.3 seconds from launch to record-ready, picture and sound "
                     "within one frame, no frames dropped at a strict 29.97.", "".join(b))


# --- wordmark -----------------------------------------------------------------------------

def wordmark(t):
    b = (f'<circle cx="40" cy="40" r="27" fill="none" stroke="{t["text"]}" stroke-width="4"/>'
         f'<circle cx="40" cy="40" r="15" fill="{t["red"]}"/>'
         + text(88, 56, "Latitude", 46, t["text"], 700, "start", -1.2))
    return svg(300, 80, "Latitude", b)


def main():
    for name, draw in (("hero", hero), ("why", why), ("how-it-works", how), ("pipeline", pipeline),
                       ("principles", principles), ("measured", measured), ("wordmark", wordmark)):
        for theme, colors in THEMES.items():
            (ASSETS / f"{name}-{theme}.svg").write_text(draw(colors))


if __name__ == "__main__":
    main()
