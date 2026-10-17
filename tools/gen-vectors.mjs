// Generate cross-check vectors for the Java core from the web app's JS core.
// usage: node tools/gen-vectors.mjs <path to kanji-color-key web repo> > app/src/test/resources/vectors.json
// Keys are random (crypto.getRandomValues) and stored in the output, so the file is the test oracle.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const web = path.resolve(process.argv[2] ?? '../../gitrepos/kanji-color-key');
const K = await import(pathToFileURL(path.join(web, 'src/kck.js')));
const OLD = await import(pathToFileURL(path.join(web, 'test/fixtures/kck-1.0.0.js')));
const V11 = await import(pathToFileURL(path.join(web, 'test/fixtures/kck-1.1.0.js')));
const poolRaw = readFileSync(path.join(web, 'data/pool.json'));
const data = JSON.parse(poolRaw.toString('utf8'));
const pool = K.makePool(data.kanji, data.strokes.map(s => s.length));
const oldPool = OLD.makePool(data.kanji, data.strokes.map(s => s.length));
const v11Pool = V11.makePool(data.kanji, data.strokes.map(s => s.length));
const kanaRaw = readFileSync(path.join(web, 'data/kana.json'));
const kdata = JSON.parse(kanaRaw.toString('utf8'));
const kana = K.makeKanaSet(kdata.kana, kdata.strokes.map(s => s.length));
const pkg = JSON.parse(readFileSync(path.join(web, 'package.json'), 'utf8'));
// the shipped single-file app must embed exactly this core
const docs = readFileSync(path.join(web, 'docs/index.html'), 'utf8');
const core = readFileSync(path.join(web, 'src/kck.js'), 'utf8').replace(/^export /gm, '');
if (!docs.includes(core)) throw new Error('docs/index.html does not embed src/kck.js');
if (!docs.includes(poolRaw.toString('utf8'))) throw new Error('docs/index.html does not embed data/pool.json');
if (!docs.includes(kanaRaw.toString('utf8'))) throw new Error('docs/index.html does not embed data/kana.json');

const rnd = n => Math.floor(Math.random() * n);
const pick = a => a[rnd(a.length)];
const bstr = b => b.join('');
const out = {
  meta: {
    webVersion: pkg.version,
    webCommit: execSync('git rev-parse HEAD', { cwd: web }).toString().trim(),
    poolSha256: createHash('sha256').update(poolRaw).digest('hex'),
    kanaSha256: createHash('sha256').update(kanaRaw).digest('hex'),
    generated: new Date().toISOString().slice(0, 10),
  },
  hash: [], hex: [], bitString: [], spec: [], encode: [], web: [], rotation: [], decode: [], errors: [], kdf: [],
};

// ---- primitives
for (let r = 0; r < 60; r++) {
  const n = r < 10 ? r : rnd(600), b = K.randomBits(n), c = pick([0, 1, 4, 12, 16, 33, 64, 256, 300]);
  out.hash.push({ bits: bstr(b), checkBits: c, check: bstr(K.checkCode(b, c)), surplus: bstr(K.surplusBits(b, 700)) });
}
for (const h of ['', '0', 'ff', '0xAB cd', ' 0xab', 'DEAD beef\u3000ca', '0X12', 'xyz', '12 3\n4', 'ab_cd', 'é']) {
  try { out.hex.push({ in: h, bits: bstr(K.hexToBits(h)) }); } catch (e) { out.hex.push({ in: h, error: e.message }); }
}
for (const s of ['', '0101', '01 01_1\u3000 0', '012', 'abc']) {
  try { out.bitString.push({ in: s, bits: bstr(K.parseBitString(s)) }); } catch (e) { out.bitString.push({ in: s, error: e.message }); }
}
for (const s of ['0', '0?', '??', '12[30]', '1(23)', '12(30)', '[012]3', '(0)?(1)', '[]', '()', '1[2?]3', '0(12)[33]?', '[0123]', '(00)1(23)',
  '[0?]1?', '(12', '1)2', '[1)', '((1))', '(1[2])', '2(3]', '?(?)', '[1203]?(2)']) {
  try { out.spec.push({ in: s, out: K.expandColorSpec(s).map(a => a.join('')) }); } catch (e) { out.spec.push({ in: s, error: e.message }); }
}

// ---- encodes in every mode
const LENS = [0, 1, 7, 8, 11, 12, 13, 64, 100, 128, 200, 255, 256, 257, 300, 384, 511, 512];
const randPos = () => { const s = new Set(); const k = rnd(5); for (let i = 0; i < k; i++) s.add(1 + rnd(14)); return [...s]; };
const OLD_MODES = ['kanji', 'alpha', 'mixedK', 'mixedA', 'mixedAllAlpha'];
// v1.2: mixed with kana (tail kanji / alpha / kana), all kana (variable), fixed-length kana (KF)
const MODES = [...OLD_MODES, 'mixedKana', 'allKana', 'kanaFixed'];
const ALL = Array.from({ length: 80 }, (_, i) => i + 1);
function encode(mode, b, c, pos, kpos, tail) {
  if (mode === 'kanji') return K.encodeBits(pool, b, { checkBits: c });
  if (mode === 'alpha') return K.encodeAlpha(b, { checkBits: c });
  if (mode === 'mixedAllAlpha') return K.encodeMixed(pool, b, { checkBits: c, alphaPos: ALL });
  if (mode === 'mixedKana') return K.encodeMixed(pool, b, { checkBits: c, alphaPos: pos, kanaPos: kpos, tail, kana });
  if (mode === 'allKana') return K.encodeMixed(pool, b, { checkBits: c, kanaPos: ALL, tail, kana });
  if (mode === 'kanaFixed') return K.encodeKanaFixed(kana, b, { checkBits: c });
  return K.encodeMixed(pool, b, { checkBits: c, alphaPos: pos, tail: mode === 'mixedA' ? 'alpha' : 'kanji' });
}
const enc = [];
for (const mode of MODES) for (let r = 0; r < 240; r++) {
  const n = r < LENS.length ? LENS[r] : r % 3 === 0 ? 256 : 8 * (8 + rnd(57)), c = r % 4 === 0 ? 0 : pick([0, 0, 1, 5, 12, 12, 16, 32, 64]);
  const b = K.randomBits(n);
  let pos = randPos(), kpos = [], tail = 'kanji';
  if (mode === 'mixedKana') { kpos = randPos().filter(p => !pos.includes(p)); if (r % 3 === 0) kpos.push(...[1, 2, 3, 4].filter(p => !pos.includes(p) && !kpos.includes(p))); tail = ['kanji', 'alpha', 'kana'][r % 3]; }
  if (mode === 'allKana') { pos = []; kpos = ALL; tail = ['kanji', 'kana'][r % 2]; }
  if (mode === 'kanaFixed') pos = [];
  const e = encode(mode, b, c, pos, kpos, tail);
  const v = { mode, bits: bstr(b), checkBits: c, alphaPos: pos, kanaPos: kpos, tail, text: e.text, totalBits: e.totalBits, writtenBits: e.writtenBits, surplus: e.surplus };
  out.encode.push(v); enc.push({ ...v, e, b });
}

// ---- the web UI (webEncode in src/kck.js, tailTitle in web/app.html): endings, common prefix, KF
const tailTitle = (v) => { const last = v.enc.tokens.at(-1)?.type;
  return v.tail === 'fixed' ? '固定長（KF）' : v.tail === 'alpha' ? '最後を英数字で' : v.tail === 'kana' ? '最後を1色かなで'
    : last === 'kana' ? '最後をかな（全画）で' : last === 'alpha' ? '最後を英数字で' : '最後を漢字で'; };
const ENABLED = [{ alpha: true, kana: true }, { alpha: true, kana: false }, { alpha: false, kana: true }, { alpha: false, kana: false }];
for (let r = 0; r < 240; r++) {
  const n = 8 * (8 + rnd(57)), c = pick([0, 0, 12]), b = K.randomBits(n), en = ENABLED[Math.floor(r / 2) % 4];
  let types = {};
  if (r % 6 === 0) types = K.allOf('alpha'); else if (r % 6 === 1) types = K.allOf('kana');
  else for (let k = rnd(6); k > 0; k--) types[1 + rnd(14)] = pick(['alpha', 'kana']);
  types = K.keepEnabled(types, en);
  const fixed = r % 12 === 1 || rnd(4) === 0;
  const w = K.webEncode(pool, kana, b, { checkBits: c, types, ...en, fixed });
  out.web.push({ bits: bstr(b), checkBits: c, types, enabled: en, fixed, common: w.common, isFixed: w.fixed, head: K.webHead(w),
    variants: w.variants.map(v => ({ tail: v.tail, text: v.enc.text, info: K.webInfo(v.enc), title: tailTitle(v) })) });
}
// ---- symbol type rotation (click / toggles / buttons)
for (let r = 0; r < 120; r++) {
  const en = ENABLED[r % 4], steps = [];
  let types = r % 10 === 0 ? K.keepEnabled(K.allOf(pick(['alpha', 'kana'])), en) : {};
  const init = { ...types };
  for (let k = 0; k < 12; k++) {
    if (rnd(8) === 0) { const e2 = pick(ENABLED); types = K.keepEnabled(types, e2); steps.push({ op: 'keep', enabled: e2, types: { ...types } }); continue; }
    const pos = 1 + rnd(8); types = K.clickType(types, pos, en); steps.push({ op: 'click', pos, types: { ...types } });
  }
  out.rotation.push({ enabled: en, init, steps,
    next: K.TYPE_ROTATION.map(t => K.nextType(t, en)), allKana: K.isAllOf(types, 'kana'), allAlpha: K.isAllOf(types, 'alpha') });
}

// ---- decodes: unknowns, blocks, color names, whitespace, v1.0 outputs
const CN = '黒赤青緑';
// opts: { swapLookalikes, lookalikeFallback, maxCandidates } (the kana set is always passed, as in the web app)
function decodeCase(input, note, opts = {}) {
  if (process.env.DBG) process.stderr.write(note + " " + input.slice(0, 80) + "\n");
  try {
    const d = K.decodeAny(pool, input, { kana, ...opts });
    out.decode.push({ note, in: input, opts, tried: d.tried, total: d.total, dataBits: d.dataBits, checkBits: d.checkBits, lookalike: d.lookalike ?? null,
      count: d.candidates.length, candidates: d.candidates.slice(0, 64).map(x => ({ hex: x.hex, text: x.text, padding: x.padding })) });
  } catch (e) { out.errors.push({ note, in: input, opts, error: e.message }); }
}
function kanjiParts(text) {
  const kf = K.isKanaFixedText(text), mixed = !kf && K.isMixedText(text);
  const p = kf ? K.parseKanaFixed(kana, text) : mixed ? K.parseMixed(pool, text, kana) : K.parseText(pool, text);
  return { head: text.match(/^(M|KF)?\([^)]*\)/)[0], parts: p.tokens.map(t => [t.char, t.spec, t.type]), sep: kf || mixed ? ' ' : '' };
}
const HALF = { ウ: 'ｳ', オ: 'ｵ', キ: 'ｷ', ケ: 'ｹ', サ: 'ｻ', チ: 'ﾁ', ツ: 'ﾂ', テ: 'ﾃ', ネ: 'ﾈ', ホ: 'ﾎ', ミ: 'ﾐ', モ: 'ﾓ', ヨ: 'ﾖ' };
const PARTNER = Object.fromEntries(K.KANA_LOOKALIKES.flatMap(([a, b]) => [[a, b], [b, a]]));
function kanaCases(v) {
  const kp = kanjiParts(v.text), multi = (i) => kp.parts[i][1].replace(/[()[\]]/g, '').length > 1;
  const kanaIdx = kp.parts.map((p, i) => i).filter(i => kp.parts[i][2] === 'kana' || kp.parts[i][2] === 'kanaGlyph');
  const colored = kp.parts.map((p, i) => i).filter(multi);
  // the automatic look-alike fallback doubles the search per swappable kana; keep the vectors fast
  const nLook = kanaIdx.filter(i => PARTNER[kp.parts[i][0]]).length, fb = nLook <= 8 ? {} : { lookalikeFallback: false };
  if (colored.length) {
    const q = structuredClone(kp), i = pick(colored); q.parts[i][1] = qmarks(q.parts[i][1], 1 + rnd(2)); decodeCase(join(q), v.mode + ' ?');
    const w = structuredClone(kp), m = pick(colored), wp = [...w.parts[m][1]], z = rnd(wp.length);
    wp[z] = String((+wp[z] + 1) % 4); w.parts[m][1] = wp.join(''); decodeCase(join(w), v.mode + ' wrong color', fb);
    const u = structuredClone(kp); u.parts.forEach(p => { if (p[1].length > 1) p[1] = [...p[1]].map(x => CN[x] ?? x).join(''); });
    decodeCase(join(u).replace(/(.{5})/gu, '$1\u3000\n '), v.mode + ' names+ws');
  }
  if (kanaIdx.length) {
    decodeCase(join(kp).replace(/[ウオキケサチツテネホミモヨ]/g, c => HALF[c]), v.mode + ' halfwidth');
    const kk = kanaIdx.filter(i => kp.parts[i][2] === 'kana');
    if (kk.length) { const q = structuredClone(kp), i = pick(kk); q.parts[i][1] = qmarks(q.parts[i][1], 1); decodeCase(join(q), v.mode + ' kana ?'); }
    const g = kanaIdx.filter(i => kp.parts[i][2] === 'kanaGlyph');
    if (g.length) { // one-color kana copied stroke by stroke; and an unknown one-color kana color
      const q = structuredClone(kp); for (const i of g.slice(1)) q.parts[i][1] = q.parts[i][1].repeat(kana.counts[kana.index.get(q.parts[i][0])]);
      decodeCase(join(q), v.mode + ' glyph strokes'); // the first one-color kana keeps its single color
      const t = structuredClone(kp); t.parts[pick(g)][1] = '?'; decodeCase(join(t), v.mode + ' glyph ?');
    }
    // look-alikes: write a kana's hiragana/katakana partner instead
    const la = kanaIdx.filter(i => PARTNER[kp.parts[i][0]]);
    if (la.length && nLook <= 8) {
      const q = structuredClone(kp), i = pick(la); q.parts[i][0] = PARTNER[q.parts[i][0]];
      const t = join(q);
      decodeCase(t, v.mode + ' lookalike fallback');
      decodeCase(t, v.mode + ' lookalike always', { swapLookalikes: true });
      decodeCase(t, v.mode + ' lookalike off', { lookalikeFallback: false });
      decodeCase(join(kp), v.mode + ' exact always', { swapLookalikes: true });
      if (rnd(2)) { const h = structuredClone(kp); h.parts[i][0] = PARTNER[h.parts[i][0]]; decodeCase(join(h).replace(/[ウオキケサチツテネホミモヨ]/g, c => HALF[c]), v.mode + ' lookalike halfwidth'); }
      if (colored.length) { // wrong color: nothing found; the fallback is too large for maxCandidates 1
        const w = structuredClone(kp), m = pick(colored), wp = [...w.parts[m][1]], z = rnd(wp.length);
        wp[z] = String((+wp[z] + 1) % 4); w.parts[m][1] = wp.join(''); decodeCase(join(w), v.mode + ' lookalike skipped', { maxCandidates: 1 });
      }
    }
  }
  if (v.mode !== 'kanaFixed') decodeCase(v.text.replace(/^M/, 'K'), v.mode + ' K( alias');
}
const join = ({ head, parts, sep }) => head + parts.map(([c, s]) => c + s).join(sep);
function qmarks(spec, k) { const a = [...spec]; for (let i = 0; i < k; i++) a[rnd(a.length)] = '?'; return a.join(''); }
for (const v of enc) {
  if (v.e.tokens.length === 0) { decodeCase(v.text, v.mode + ' empty'); continue; }
  decodeCase(v.text, v.mode + ' exact');
  if (!OLD_MODES.includes(v.mode)) { kanaCases(v); continue; }
  if (v.mode === 'alpha' || v.mode === 'mixedAllAlpha') {
    // alpha syntax: unknown glyph or color, lower case, color names
    const body = v.mode === 'alpha' ? v.text.replace(/^A\([^)]*\)/, '') : null;
    if (body !== null) {
      const head = v.text.match(/^A\([^)]*\)/)[0], chars = [...body.replace(/\s/g, '')];
      const i = 2 * rnd(chars.length / 2); chars[i + 1] = '?';
      if (rnd(3) === 0) chars[2 * rnd(chars.length / 2)] = '?';
      decodeCase(head + chars.join(''), 'alpha ?');
      decodeCase(head.toLowerCase() + body.toLowerCase().replace(/([a-z2-9])([0-3])/g, (_, g, c) => g + CN[c]), 'alpha lower+names');
    } else {
      const parts = v.text.split(' '); const i = rnd(parts.length), h = i === 0 ? parts[0].indexOf(')') + 1 : 0;
      parts[i] = parts[i].slice(0, h + 1) + '?'; decodeCase(parts.join(' '), 'mixed all-alpha ?');
    }
    continue;
  }
  const kp = kanjiParts(v.text);
  const kanjiIdx = kp.parts.map((p, i) => [p, i]).filter(([p]) => p[1].length > 1 || pool.index.has(p[0])).map(([, i]) => i);
  const kidx = kanjiIdx.filter(i => pool.index.has(kp.parts[i][0]));
  if (kidx.length) {
    const q = structuredClone(kp), i = pick(kidx); q.parts[i][1] = qmarks(q.parts[i][1], 1 + rnd(3));
    decodeCase(join(q), v.mode + ' ?');
    // swapped strokes marked [..]
    const s = structuredClone(kp), j = pick(kidx), sp = s.parts[j][1];
    if (sp.length >= 3) { const a = rnd(sp.length - 1); s.parts[j][1] = sp.slice(0, a) + '[' + sp[a + 1] + sp[a] + ']' + sp.slice(a + 2); decodeCase(join(s), v.mode + ' []'); }
    // a block written at the end, marked (..)
    const t = structuredClone(kp), k = pick(kidx), tp = t.parts[k][1];
    if (tp.length >= 4) { t.parts[k][1] = tp.slice(2) + '(' + tp.slice(0, 2) + ')'; decodeCase(join(t), v.mode + ' ()'); }
    // color names + full-width/ASCII whitespace
    const u = structuredClone(kp); u.parts.forEach(p => { if (pool.index.has(p[0])) p[1] = [...p[1]].map(x => CN[x] ?? x).join(''); });
    decodeCase(join(u).replace(/(.{7})/gu, '$1\u3000\n '), v.mode + ' names+ws');
    // a wrong color (no mark): usually rejected when there is a check or surplus
    const w = structuredClone(kp), m = pick(kidx), wp = [...w.parts[m][1]], z = rnd(wp.length);
    wp[z] = String((+wp[z] + 1) % 4); w.parts[m][1] = wp.join(''); decodeCase(join(w), v.mode + ' wrong color');
  }
}
// v1.0 (zero padded) outputs must still decode
for (let r = 0; r < 120; r++) {
  const n = pick([64, 128, 200, 256, 257, 384, 512]), c = pick([0, 12]), b = K.randomBits(n), pos = randPos();
  const mode = OLD_MODES[r % OLD_MODES.length];
  const o = mode === 'kanji' ? OLD.encodeBits(oldPool, b, { checkBits: c }) : mode === 'alpha' ? OLD.encodeAlpha(b, { checkBits: c })
    : OLD.encodeMixed(oldPool, b, { checkBits: c, alphaPos: mode === 'mixedAllAlpha' ? Array.from({ length: 80 }, (_, i) => i + 1) : pos, tail: mode === 'mixedA' ? 'alpha' : 'kanji' });
  decodeCase(o.text, 'v1.0 ' + mode);
}
// v1.1 (surplus padded) outputs, from the frozen v1.1.0 encoder
for (let r = 0; r < 120; r++) {
  const n = pick([64, 128, 200, 256, 257, 384, 512]), c = pick([0, 12]), b = K.randomBits(n), pos = randPos();
  const mode = OLD_MODES[r % OLD_MODES.length];
  const o = mode === 'kanji' ? V11.encodeBits(v11Pool, b, { checkBits: c }) : mode === 'alpha' ? V11.encodeAlpha(b, { checkBits: c })
    : V11.encodeMixed(v11Pool, b, { checkBits: c, alphaPos: mode === 'mixedAllAlpha' ? ALL : pos, tail: mode === 'mixedA' ? 'alpha' : 'kanji' });
  decodeCase(o.text, 'v1.1 ' + mode);
}
// errors and edge syntax
for (const s of ['従0231', '(12+0bit)A1', '(4+0bit)a1b2', '(8bit)a1', '(8bit)a1b', '(8bit)g1a1', '(256bit)', 'A(256bit)2030', 'A(14bit)2030',
  'A(14bit)0030', 'A(14bit)205030', 'a(14bit)2?3?', 'M(14bit)20 3', 'M(14bit)20 0', 'M(14bit)2X', 'M(14bit)2', 'M(14bit)2030', 'M(7bit)Ü0', 'M(20bit)従0',
  'M(12bit)A1B1C1', '(０bit)', '(0bit)', '(0bit)a', 'A(0bit)', 'M(0bit)', '  m （ 7 bit ）2 0', '(12bit)亜0000000', '(12bit)亜000000(0)',
  '(12bit)亜00[0000)0', '(12bit)亜((0)000000', '(12bit)亜黒赤青緑黒赤青', '(12BIT)亜???????', 'M(12+4bit)亜0123012', 'hello',
  '(12bit)亜?????????', '(12bit)亜[0123012]', '(12bit)亜[01]2?????', 'M(2+5bit)?0', 'M(2+5bit)??', 'A(2+5bit)??',
  // v1.2 kana
  'M(11bit)あ012', 'M(11bit)あ01', 'M(11bit)あ0123', 'M(7bit)あ2', 'M(7bit)あ222', 'M(7bit)あ212', 'M(14bit)あ2い2', 'M(14bit)あ2 亜0000000',
  'M(14bit)あ2 A1', 'M(14bit)ウ2 ほ222', 'M(14bit)ウ2 ほ212', 'M(14bit)ｳ2ﾎ2', 'K(11bit)お123', 'k（11bit）オ1 2 3', 'M(13bit)き0123', 'M(13bit)き012',
  'M(7bit)ヰ3', 'M(7bit)?3', 'M(7bit)を?', 'M(7bit)を(1)', 'M(26bit)ホ0123ホ0123', 'M(18bit)ほ0123 2', 'M(18bit)ほ01232', 'M(4bit)お1',
  'KF(11bit)あ012', 'KF(11bit)き0120', 'KF(11bit)き0123', 'KF(11bit)き012', 'KF(11bit)A012', 'KF(22bit)あ012', 'KF(11bit)あ012 か000',
  'kf(11bit)ｳ0?2', 'KF(0bit)', 'KF(11+11bit)お???', 'KF(11bit)お[012]', 'KF(256bit)' + 'あ???'.repeat(24), 'M(256bit)' + 'あ???'.repeat(24)]) decodeCase(s, 'edge');
for (const [s, o] of [['M(11bit)お012', { swapLookalikes: true }], ['M(7bit)お2', { swapLookalikes: true }], ['KF(11bit)き012?', { swapLookalikes: true }],
  ['M(14bit)ほ2 も3', { swapLookalikes: true }], ['M(11bit)お0?2', { maxCandidates: 4 }], ['M(11bit)お0?2', { maxCandidates: 3 }]]) decodeCase(s, 'edge opts', o);

// ---- passphrase KDF
for (const [p, s, it] of [['correct horse', '', 1000], ['correct horse', 'x', 1000], ['', '', 1], ['ｶﾞ漢字\u0065\u0301', 'ソルト', 2000],
  ['pass\ud800word', 's\udfff', 10], ['a'.repeat(100), '', 3], ['correct horse battery staple', 'me', 600000]]) {
  const k = await K.deriveKey(p, { salt: s, iterations: it });
  out.kdf.push({ pass: p, salt: s, iterations: it, hex: Buffer.from(k).toString('hex') });
}

// ---- the web demo (#demo)
{
  const key = K.hexToBits('c2fb9f70272dae3adee45198b576b4873f5ad57ac41696bdfaa92e6de9923e14'.repeat(2)).slice(0, 256);
  const w = K.webEncode(pool, kana, key, { checkBits: 0, types: { 3: 'alpha', 5: 'kana', 8: 'kana' }, alpha: true, kana: true });
  const A = w.variants[0].enc, input = A.text.replace(/^(M\([\d+]+bit\)\S)\d/, '$1?'), d = K.decodeAny(pool, input, { kana });
  out.demo = { a: A.text, variants: w.variants.map(v => v.enc.text), common: w.common, input, tried: d.tried, candidates: d.candidates.map(x => x.hex) };
}
process.stdout.write(JSON.stringify(out));
const by = (k) => Object.entries(out[k].reduce((a, v) => (a[v.note.replace(/^(\S+ ?\S*).*$/, '$1')] = (a[v.note.replace(/^(\S+ ?\S*).*$/, '$1')] ?? 0) + 1, a), {})).length;
const la = out.decode.reduce((a, v) => (a[v.lookalike] = (a[v.lookalike] ?? 0) + 1, a), {});
process.stderr.write(`encode ${out.encode.length}, web ${out.web.length}, rotation ${out.rotation.length}, decode ${out.decode.length}, errors ${out.errors.length}, lookalike ${JSON.stringify(la)}, kinds ${by('decode')}\n`);
