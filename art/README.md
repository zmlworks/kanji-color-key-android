# art/ — launcher icon

`icon-source.png`: icon artwork supplied by the project owner (a dark rounded square with a rainbow brush-stroke 鍵 and
a keyhole). Metadata was removed; the pixels are unchanged.

`make_icon.py` (Python 3 + Pillow + numpy) writes everything from it:
- the rounded square is found by fitting a superellipse to its edge, so the white background and outer shadow are
  dropped and the edge is re-drawn anti-aliased ("tile");
- `app/src/main/res/mipmap-*/ic_launcher.png` — legacy 48 dp icon (unused at minSdk 26, kept for completeness)
- `app/src/main/res/mipmap-*/ic_launcher_foreground.png` — adaptive foreground (108 dp): the tile without its own
  rounded edge, its colours extended to the layer border, so only the launcher's mask shapes the icon (no double mask);
  鍵 and the keyhole stay in the 66 dp safe zone. Background `@color/icon_bg` (#1E3348)
- `app/src/main/res/mipmap-*/ic_launcher_monochrome.png` — themed-icon layer (the brush strokes as alpha)
- `icon-512.png`, `docs/icon.png`, `fastlane/metadata/android/*/images/icon.png` — 512 px store icon
- with `--web <kanji-color-key>/docs`: `favicon-64.png` and `apple-touch-icon.png` (180 px) for the web app
- a preview sheet (default `art/icon-preview.png`, or the path given): store icon, launcher masks, sizes, themed icon,
  on light and dark backgrounds

Usage: `python3 art/make_icon.py [--web DIR] [preview.png]`
