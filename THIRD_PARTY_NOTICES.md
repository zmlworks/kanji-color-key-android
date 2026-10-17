# Third-party notices

The app code is MIT licensed, © 2026 zmlworks ([LICENSE](LICENSE)). The app has **no third-party code libraries**
(JUnit and Robolectric are used only by the tests and are not part of the APK). Fonts are the phone's system fonts;
none are bundled. The launcher icon is original to this repository (`art/make_icon.py`).

## KanjiVG (kanji stroke data) — CC BY-SA 3.0

`app/src/main/assets/pool.json` (stroke paths, stroke counts and stroke order of 4096 kanji) is derived from
[KanjiVG](https://kanjivg.tagaini.net/) release r20260714, Copyright © Ulrich Apel, licensed under the
[Creative Commons Attribution-Share Alike 3.0 Unported](https://creativecommons.org/licenses/by-sa/3.0/) license.

Changes, made by the [kanji-color-key](https://github.com/zmlworks/kanji-color-key) project
(`tools/build-data.py` there): 4096 kanji were selected (JIS X 0208 level 1 and part of level 2, only kanji present in
KanjiVG), coordinates were rounded to 0.1, and metadata was removed. The file is byte-identical to `data/pool.json` of
kanji-color-key v1.1.0 (checked by `KckVectorsTest`).

`pool.json`, as an adaptation of KanjiVG, is distributed under **CC BY-SA 3.0**, not under the MIT license. The
kanji cards drawn by the app are rendered from this data.

## kanji-color-key (encoding scheme and JS core)

The encoding (kanji pool order, colors, check code, surplus bits, decoder syntax) and the UI texts come from
[kanji-color-key](https://github.com/zmlworks/kanji-color-key) v1.1.0 by zmlworks (MIT), ported from JavaScript to Java.
