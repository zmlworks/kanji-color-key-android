# art/ — launcher icon

An original glyph (5 strokes, the shape of 主 "master" — as in master key) drawn as colored strokes on a dark
background (#1B1E26), in the app's palette: stroke 1 and 5 light (standing in for 黒 on the dark background),
2 red, 3 blue, 4 green, in stroke order.

`make_icon.py` (Python 3 + Pillow) writes everything from one stroke definition:
- `app/src/main/res/drawable/ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml` — adaptive icon layers (vector, 108 dp; the glyph stays in the 66 dp safe zone), background `@color/icon_bg`
- `icon-512.png`, `docs/icon.png`, `fastlane/metadata/android/*/images/icon.png` — 512 px store icon
- `icon-preview.png` (or the path given as argument) — preview sheet: store icon, circle / rounded-square masks, launcher sizes, themed icon

Replace the strokes in `STROKES` (cubic Béziers in the 108-unit layer) to change the glyph.
