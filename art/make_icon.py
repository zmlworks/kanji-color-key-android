#!/usr/bin/env python3
"""Icons for Kanji Color Key from the project owner's artwork (art/icon-source.png: a dark rounded square with a
rainbow brush-stroke 鍵 and a keyhole; icon artwork supplied by the project owner).

1. The rounded square is located by fitting a superellipse |x/a|^n + |y/b|^n = 1 to its edge (sub-pixel crossings
   of the dark/light boundary), so the white background and the outer shadow are dropped and the edge is a clean,
   4x supersampled anti-aliased curve (inset 3 px so no background pixel is kept). Cropped tight, padded to a square
   and centred ("tile").
2. Adaptive icon (no double masking): the foreground is the tile at 76/108 of the 108 dp layer, so 鍵 and the keyhole
   stay inside the 66 dp safe zone, with the tile's own edge removed (inset) and its colours extended smoothly to the
   layer border, so the launcher's mask is the only mask. Background: the tile's mean edge colour (@color/icon_bg).
   Monochrome (themed icon) layer: the bright brush strokes as alpha.
Writes:
  app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png             legacy 48 dp icon (tight tile)
  app/src/main/res/mipmap-*/ic_launcher_foreground.png, ic_launcher_monochrome.png   adaptive layers (108 dp)
  art/icon-512.png, docs/icon.png, fastlane/metadata/android/*/images/icon.png      512 px store icon (tight tile)
  <web dir>/favicon-64.png, <web dir>/apple-touch-icon.png (180, full-bleed square)  with --web <dir>
  <preview> (default art/icon-preview.png): light and dark backgrounds
Usage: python3 art/make_icon.py [--web DIR] [preview.png]      (Python 3, Pillow, numpy)
"""
import sys, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'art/icon-source.png')
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
TILE_IN_LAYER = 76 / 108   # tile size in the 108 dp adaptive layer (glyph radius ~0.85 x half-tile -> ~32 dp < 33 dp)
INSET = 3                  # px removed from the fitted edge (source resolution)

src = np.asarray(Image.open(SRC).convert('RGB')).astype(np.float64)
H, W, _ = src.shape

def crossings(T=130.0):
    L = src.mean(2); pts = []
    def first(seq, i, step):
        while 0 <= i + step < len(seq):
            if seq[i] >= T and seq[i + step] < T: return i + step * (seq[i] - T) / (seq[i] - seq[i + step])
            i += step
        return None
    for y in range(20, H - 20, 2):
        l, r = first(L[y], 0, 1), first(L[y], W - 1, -1)
        if l is not None and r is not None and r - l > W / 3: pts += [(l, y), (r, y)]
    for x in range(20, W - 20, 2):
        t, b = first(L[:, x], 0, 1), first(L[:, x], H - 1, -1)
        if t is not None and b is not None and b - t > H / 3: pts += [(x, t), (x, b)]
    return np.array(pts)

def nelder_mead(f, x0, steps, it=3000):
    pts = [np.array(x0, float)] + [np.array(x0, float) + np.eye(len(x0))[i] * steps[i] for i in range(len(x0))]
    vals = [f(q) for q in pts]
    for _ in range(it):
        o = np.argsort(vals); pts = [pts[i] for i in o]; vals = [vals[i] for i in o]
        c = np.mean(pts[:-1], 0); xr = c + (c - pts[-1]); fr = f(xr)
        if fr < vals[0]:
            xe = c + 2 * (c - pts[-1]); fe = f(xe)
            pts[-1], vals[-1] = (xe, fe) if fe < fr else (xr, fr)
        elif fr < vals[-2]: pts[-1], vals[-1] = xr, fr
        else:
            xc = c + 0.5 * (pts[-1] - c); fc = f(xc)
            if fc < vals[-1]: pts[-1], vals[-1] = xc, fc
            else: pts = [pts[0] + 0.5 * (q - pts[0]) for q in pts]; vals = [f(q) for q in pts]
    return pts[int(np.argmin(vals))]

def fit():
    P = crossings()
    def res(p):
        cx, cy, ax, ay, n = p
        return (np.abs((P[:, 0] - cx) / ax) ** n + np.abs((P[:, 1] - cy) / ay) ** n) ** (1 / n) - 1
    p = nelder_mead(lambda q: float(np.sum(res(q) ** 2)), [W / 2, H / 2, W / 2 - 20, H / 2 - 20, 4], [2, 2, 2, 2, 0.5])
    r = res(p) * p[2]
    print(f'superellipse fit: centre {p[0]:.1f},{p[1]:.1f} half-size {p[2]:.1f} x {p[3]:.1f} n={p[4]:.2f}; edge rms {np.sqrt(np.mean(r ** 2)):.2f} px, max {np.abs(r).max():.2f} px')
    return p

def coverage(p, inset, shape, scale=1.0, off=(0.0, 0.0), ss=4):
    """Anti-aliased superellipse coverage on a grid of `shape`, source coords = (grid - off) / scale."""
    cx, cy, ax, ay, n = p; h, w = shape
    o = (np.arange(ss) + 0.5) / ss
    acc = np.zeros(shape)
    for dy in o:
        for dx in o:
            yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
            X = (xx + dx - off[0]) / scale; Y = (yy + dy - off[1]) / scale
            acc += (np.abs((X - cx) / (ax - inset)) ** n + np.abs((Y - cy) / (ay - inset)) ** n) < 1
    return acc / ss ** 2

def blur(a, sigma):
    """Gaussian blur (FFT, zero padded), a: HxW or HxWxC."""
    h, w = a.shape[:2]; pad = int(3 * sigma) + 1
    H2, W2 = h + 2 * pad, w + 2 * pad
    fy = np.fft.fftfreq(H2)[:, None]; fx = np.fft.fftfreq(W2)[None, :]
    g = np.exp(-2 * (np.pi * sigma) ** 2 * (fx ** 2 + fy ** 2))
    def one(c):
        z = np.zeros((H2, W2)); z[pad:pad + h, pad:pad + w] = c
        return np.real(np.fft.ifft2(np.fft.fft2(z) * g))[pad:pad + h, pad:pad + w]
    return one(a) if a.ndim == 2 else np.stack([one(a[..., k]) for k in range(a.shape[2])], -1)

def extend(rgb, known):
    """Fill the unknown area smoothly from the known colours (coarse-to-fine normalized convolution)."""
    out = None
    for s in (256, 128, 64, 32, 16, 8, 4, 2):
        wgt = blur(known, s); est = blur(rgb * known[..., None], s) / np.maximum(wgt, 1e-6)[..., None]
        if out is None: out = est
        else: t = np.clip(wgt / 0.3, 0, 1)[..., None]; out = est * t + out * (1 - t)
    return np.where(known[..., None] > 0.999, rgb, out)

def to_img(rgb, alpha=None):
    a = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is None: return Image.fromarray(a, 'RGB')
    return Image.fromarray(np.dstack([a, np.clip(alpha * 255 + 0.5, 0, 255).astype(np.uint8)]), 'RGBA')

def tile(p):
    """Tight square tile (RGBA) at source resolution, colours bled under transparent pixels for clean resizing."""
    cov = coverage(p, INSET, (H, W))
    ys, xs = np.nonzero(cov > 0)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    s = max(x1 - x0, y1 - y0); ox = (s - (x1 - x0)) // 2; oy = (s - (y1 - y0)) // 2
    rgb = np.zeros((s, s, 3)); al = np.zeros((s, s))
    rgb[oy:oy + y1 - y0, ox:ox + x1 - x0] = src[y0:y1, x0:x1]; al[oy:oy + y1 - y0, ox:ox + x1 - x0] = cov[y0:y1, x0:x1]
    rgb = extend(rgb, (al > 0.999).astype(float))
    return to_img(rgb, al), (x0 - ox, y0 - oy, s)

def fullbleed(p, size, tile_frac, inset_frac):
    """size x size opaque square: the tile at tile_frac of the side, its edge (inset_frac of the half-size) replaced by
    a smooth extension of the inner colours to the border."""
    cx, cy, ax, ay, n = p
    scale = size * tile_frac / (2 * max(ax, ay) - 2 * INSET)
    off = (size / 2 - cx * scale, size / 2 - cy * scale)
    big = np.asarray(Image.fromarray(src.astype(np.uint8)).resize((round(W * scale), round(H * scale)), Image.LANCZOS)).astype(float)
    rgb = np.zeros((size, size, 3)); ox, oy = round(off[0]), round(off[1])
    sy0, sx0 = max(0, -oy), max(0, -ox); dy0, dx0 = max(0, oy), max(0, ox)
    hh = min(big.shape[0] - sy0, size - dy0); ww = min(big.shape[1] - sx0, size - dx0)
    rgb[dy0:dy0 + hh, dx0:dx0 + ww] = big[sy0:sy0 + hh, sx0:sx0 + ww]
    known = coverage(p, INSET + inset_frac * ax, (size, size), scale, (ox, oy), ss=2)
    known = (known > 0.999).astype(float)
    return to_img(extend(rgb, known)), known, (scale, ox, oy)

def monochrome(p, size, geo):
    """Themed-icon layer: white with the brush strokes' brightness as alpha, same geometry as the foreground."""
    scale, ox, oy = geo
    big = np.asarray(Image.fromarray(src.astype(np.uint8)).resize((round(W * scale), round(H * scale)), Image.LANCZOS)).astype(float)
    v = big.max(2)
    a = np.clip((v - 95) / 115, 0, 1) ** 1.2
    cov = coverage(p, INSET + 0.06 * p[2], big.shape[:2], scale, (0, 0), ss=1)
    a *= cov
    out = np.zeros((size, size))
    sy0, sx0 = max(0, -oy), max(0, -ox); dy0, dx0 = max(0, oy), max(0, ox)
    hh = min(a.shape[0] - sy0, size - dy0); ww = min(a.shape[1] - sx0, size - dx0)
    out[dy0:dy0 + hh, dx0:dx0 + ww] = a[sy0:sy0 + hh, sx0:sx0 + ww]
    return to_img(np.full((size, size, 3), 255.0), out)

def masked(im, kind, size):
    S = size * 4; m = Image.new('L', (S, S), 0); d = ImageDraw.Draw(m)
    if kind == 'circle': d.ellipse([0, 0, S - 1, S - 1], fill=255)
    elif kind == 'rounded': d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.12, fill=255)
    elif kind == 'teardrop':
        d.ellipse([0, 0, S - 1, S - 1], fill=255); d.rectangle([S // 2, S // 2, S - 1, S - 1], fill=255)
    else:  # squircle
        yy, xx = np.mgrid[0:S, 0:S]; u = np.abs((xx + 0.5) / S * 2 - 1); v = np.abs((yy + 0.5) / S * 2 - 1)
        m = Image.fromarray(((u ** 5 + v ** 5) < 1).astype(np.uint8) * 255)
    m = m.resize((size, size), Image.LANCZOS)
    im = im.resize((size, size), Image.LANCZOS).convert('RGBA'); out = Image.new('RGBA', (size, size), (0, 0, 0, 0)); out.paste(im, (0, 0), m)
    return out

def adaptive_view(fg, bg_rgb, size):
    """What a launcher shows: the central 72 dp of the 108 dp layers."""
    S = fg.size[0]; c = S * 18 // 108
    lay = Image.new('RGBA', fg.size, bg_rgb + (255,)); lay.alpha_composite(fg.convert('RGBA'))
    return lay.crop((c, c, S - c, S - c)).resize((size, size), Image.LANCZOS)

def main():
    args = sys.argv[1:]; web = None
    if '--web' in args: i = args.index('--web'); web = args[i + 1]; del args[i:i + 2]
    preview = args[0] if args else os.path.join(ROOT, 'art/icon-preview.png')
    p = fit()
    t, _ = tile(p)
    print('tile', t.size)
    res = os.path.join(ROOT, 'app/src/main/res')
    # background colour = mean colour of the tile's inner edge band
    cov_in = coverage(p, INSET + 0.06 * p[2], (H, W), ss=1); cov_out = coverage(p, INSET, (H, W), ss=1)
    band = (cov_out > 0.99) & (cov_in < 0.01)
    bg = tuple(int(round(v)) for v in src[band].mean(0))
    print('icon_bg', '#%02X%02X%02X' % bg)
    fg_master, _, geo = fullbleed(p, 1728, TILE_IN_LAYER, 0.06)          # 108 dp at 16 px/dp
    mono_master = monochrome(p, 1728, geo)
    for d, k in DENS.items():
        os.makedirs(os.path.join(res, f'mipmap-{d}'), exist_ok=True)
        t.resize((round(48 * k * 46 / 48),) * 2, Image.LANCZOS).save('/tmp/_t.png')
        leg = Image.new('RGBA', (round(48 * k),) * 2, (0, 0, 0, 0)); ti = Image.open('/tmp/_t.png')
        leg.alpha_composite(ti, ((leg.size[0] - ti.size[0]) // 2,) * 2)
        leg.save(os.path.join(res, f'mipmap-{d}/ic_launcher.png'), optimize=True)
        s = round(108 * k)
        fg_master.resize((s, s), Image.LANCZOS).save(os.path.join(res, f'mipmap-{d}/ic_launcher_foreground.png'), optimize=True)
        mono_master.resize((s, s), Image.LANCZOS).save(os.path.join(res, f'mipmap-{d}/ic_launcher_monochrome.png'), optimize=True)
    with open(os.path.join(res, 'values/colors.xml')) as f: colors = f.read()
    import re
    colors = re.sub(r'<color name="icon_bg">#[0-9A-Fa-f]{8}</color>', '<color name="icon_bg">#FF%02X%02X%02X</color>' % bg, colors)
    with open(os.path.join(res, 'values/colors.xml'), 'w') as f: f.write(colors)
    big = t.resize((512, 512), Image.LANCZOS)
    big.save(os.path.join(ROOT, 'art/icon-512.png'), optimize=True)
    big.save(os.path.join(ROOT, 'docs/icon.png'), optimize=True)
    for loc in ('en-US', 'ja-JP'):
        d = os.path.join(ROOT, f'fastlane/metadata/android/{loc}/images'); os.makedirs(d, exist_ok=True)
        big.save(os.path.join(d, 'icon.png'), optimize=True)
    apple = fullbleed(p, 720, 1.0, 0.03)[0].resize((180, 180), Image.LANCZOS)
    if web:
        os.makedirs(web, exist_ok=True)
        t.resize((64, 64), Image.LANCZOS).save(os.path.join(web, 'favicon-64.png'), optimize=True)
        apple.save(os.path.join(web, 'apple-touch-icon.png'), optimize=True)
    # ---- preview: light and dark backgrounds
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 17)
        bold = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 20)
    except OSError: font = bold = ImageFont.load_default()
    Wp, Hh = 1560, 560
    sheet = Image.new('RGB', (Wp, Hh * 2), (0, 0, 0)); dr = ImageDraw.Draw(sheet)
    view = adaptive_view(fg_master, bg, 512)
    mono_view = Image.new('RGBA', mono_master.size, (0x2A, 0x33, 0x4D, 255))
    tint = Image.new('RGBA', mono_master.size, (0xD8, 0xE2, 0xFF, 255)); tint.putalpha(mono_master.split()[3]); mono_view.alpha_composite(tint)
    c = mono_master.size[0] * 18 // 108; mono_view = mono_view.crop((c, c, mono_master.size[0] - c, mono_master.size[0] - c))
    for row, (bgc, fgc, name) in enumerate([((242, 242, 238), (40, 40, 40), 'light background'), ((18, 18, 22), (225, 225, 225), 'dark background')]):
        y0 = row * Hh; dr.rectangle([0, y0, Wp, y0 + Hh], fill=bgc)
        dr.text((30, y0 + 16), f'Kanji Color Key 0.2.0 icon — {name}', fill=fgc, font=bold)
        x = 30; y = y0 + 60
        sheet.paste(big.resize((280, 280), Image.LANCZOS), (x, y), big.resize((280, 280), Image.LANCZOS)); dr.text((x, y + 290), '512 px store icon (tight)', fill=fgc, font=font)
        x += 320
        for kind in ('circle', 'squircle', 'rounded', 'teardrop'):
            im = masked(view, kind, 170); sheet.paste(im, (x, y), im); dr.text((x, y + 180), 'adaptive · ' + kind, fill=fgc, font=font); x += 200
        x = 350; y = y0 + 300
        for s in (144, 96, 48):
            im = masked(view, 'circle', s); sheet.paste(im, (x, y + 144 - s), im); x += s + 24
        dr.text((350, y + 160), 'launcher 48 / 32 / 16 dp @3x', fill=fgc, font=font)
        x = 750
        lg = Image.open(os.path.join(res, 'mipmap-xxxhdpi/ic_launcher.png')); sheet.paste(lg, (x, y - 48 + 0), lg)
        dr.text((x, y + 160), 'legacy 192 px', fill=fgc, font=font)
        x = 990; im = masked(mono_view, 'circle', 144); sheet.paste(im, (x, y), im); dr.text((x - 20, y + 160), 'themed (monochrome)', fill=fgc, font=font)
        x = 1210; sheet.paste(apple, (x, y - 20), None); dr.text((x - 10, y + 170), 'web apple-touch 180', fill=fgc, font=font)
        fav = t.resize((64, 64), Image.LANCZOS); sheet.paste(fav, (1450, y0 + 60), fav); dr.text((1440, y0 + 130), 'favicon', fill=fgc, font=font)
        lay = Image.new('RGBA', fg_master.size, bg + (255,)); lay.alpha_composite(fg_master.convert('RGBA')); lay = lay.resize((170, 170), Image.LANCZOS)
        ld = ImageDraw.Draw(lay); r = 170 * 33 / 108; ld.ellipse([85 - r, 85 - r, 85 + r, 85 + r], outline=(255, 200, 0), width=2)
        k72 = 170 * 18 / 108; ld.rectangle([k72, k72, 170 - k72, 170 - k72], outline=(0, 200, 255), width=1)
        sheet.paste(lay, (1170, y0 + 50))
        dr.text((1150, y0 + 226), '108 dp layer (blue: 72 dp view,', fill=fgc, font=font); dr.text((1150, y0 + 246), 'yellow: 66 dp safe zone)', fill=fgc, font=font)
    os.makedirs(os.path.dirname(os.path.abspath(preview)), exist_ok=True); sheet.save(preview, optimize=True)
    print('ok', preview)

main()
