# Third-party notices

The app code is MIT licensed, © 2026 zmlworks ([LICENSE](LICENSE)).

## kotpass (KeePass KDBX) — MIT

From 0.3.0 the app vendors [kotpass](https://github.com/keemobile/kotpass) (MIT, © 2021 Denis T.)
under `kotpass/` with a small `Credentials.fromComposite` patch for opening a database with a raw KeePass
composite key. License: [`kotpass/LICENSE-kotpass.txt`](kotpass/LICENSE-kotpass.txt).

## Apache-2.0 components

Full text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

- **kotlin-xml-builder** ([redundent/kotlin-xml-builder](https://github.com/redundent/kotlin-xml-builder)), vendored with
  kotpass under `kotpass/src/main/kotlin/org/redundent/kotlin/xml/` (license file kept there).
- **Apache Commons Lang** — a small subset vendored under `kotpass/src/main/kotlin/org/apache/commons/lang3/`
  (LICENSE and NOTICE kept there) and `org.apache.commons:commons-lang3` 3.17.0 as a build dependency.
  NOTICE: “Apache Commons Lang, Copyright 2001-2024 The Apache Software Foundation. This product includes software
  developed at The Apache Software Foundation (https://www.apache.org/).”
- **okio** 3.15.0 ([square/okio](https://github.com/square/okio)), © Square, Inc. — I/O for kotpass.
- **Kotlin standard library** 2.1.21, © JetBrains s.r.o. and Kotlin Programming Language contributors.

JUnit and Robolectric are test-only and not in the APK. Fonts are the phone's system fonts; none are bundled.

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
license. The kanji and kana cards and the stroke-order samples of the paper restore screen are rendered from this
data. Full license text: [`LICENSES/CC-BY-SA-3.0.txt`](LICENSES/CC-BY-SA-3.0.txt).

## Launcher icon

Icon artwork supplied by the project owner (`art/icon-source.png`); the launcher, adaptive and store icons are made from
it by `art/make_icon.py`.

## kanji-color-key (encoding scheme and JS core)

The encoding (kanji pool and kana order, colors, check code, surplus bits, decoder syntax) and the UI texts come from
[kanji-color-key](https://github.com/zmlworks/kanji-color-key) v1.2.0 by zmlworks (MIT), ported from JavaScript to Java.
