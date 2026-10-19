# Third-party notices

The app code is MIT licensed, © 2026 zmlworks ([LICENSE](LICENSE)).

## kotpass (KeePass KDBX) — MIT

From 0.3.0 the app vendors [kotpass](https://github.com/keemobile/kotpass) (MIT, © keemobile / Denis Trotsenko)
under `kotpass/` with a small `Credentials.fromComposite` patch for opening a database with a raw KeePass
composite key. It also depends on [okio](https://github.com/square/okio) (Apache-2.0). See
`kotpass/LICENSE-kotpass.txt`. JUnit and Robolectric remain test-only. Fonts are the phone's system fonts;
none are bundled.

## KanjiVG (kanji and kana stroke data) — CC BY-SA 3.0

`app/src/main/assets/pool.json` (stroke paths, stroke counts and stroke order of 4096 kanji) and
`app/src/main/assets/kana.json` (stroke paths, stroke counts and stroke order of 32 kana:
あおかきけさせたなにはふほまむもやを ウオキケサチツテネホミモヨヰ) are derived from
[KanjiVG](https://kanjivg.tagaini.net/) release r20260714, Copyright © Ulrich Apel, licensed under the
[Creative Commons Attribution-Share Alike 3.0 Unported](https://creativecommons.org/licenses/by-sa/3.0/) license.

Changes, made by the [kanji-color-key](https://github.com/zmlworks/kanji-color-key) project
(`tools/build-data.py` and `tools/build-kana.py` there): 4096 kanji were selected (JIS X 0208 level 1 and part of
level 2, only kanji present in KanjiVG) and 32 kana were selected, coordinates were rounded to 0.1, and metadata was
removed. The files are byte-identical to `data/pool.json` and `data/kana.json` of kanji-color-key v1.2.0 (checked by
`KckVectorsTest`).

`pool.json` and `kana.json`, as adaptations of KanjiVG, are distributed under **CC BY-SA 3.0**, not under the MIT
license. The kanji and kana cards drawn by the app are rendered from this data.

## Launcher icon

Icon artwork supplied by the project owner (`art/icon-source.png`); the launcher, adaptive and store icons are made from
it by `art/make_icon.py`.

## kanji-color-key (encoding scheme and JS core)

The encoding (kanji pool and kana order, colors, check code, surplus bits, decoder syntax) and the UI texts come from
[kanji-color-key](https://github.com/zmlworks/kanji-color-key) v1.2.0 by zmlworks (MIT), ported from JavaScript to Java.
