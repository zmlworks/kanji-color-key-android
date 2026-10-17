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
const poolRaw = readFileSync(path.join(web, 'data/pool.json'));
const data = JSON.parse(poolRaw.toString('utf8'));
const pool = K.makePool(data.kanji, data.strokes.map(s => s.length));
const oldPool = OLD.makePool(data.kanji, data.strokes.map(s => s.length));
const pkg = JSON.parse(readFileSync(path.join(web, 'package.json'), 'utf8'));
// the shipped single-file app must embed exactly this core
const docs = readFileSync(path.join(web, 'docs/index.html'), 'utf8');
const core = readFileSync(path.join(web, 'src/kck.js'), 'utf8').replace(/^export /gm, '');
if (!docs.includes(core)) throw new Error('docs/index.html does not embed src/kck.js');
if (!docs.includes(poolRaw.toString('utf8'))) throw new Error('docs/index.html does not embed data/pool.json');

const rnd = n => Math.floor(Math.random() * n);
const pick = a => a[rnd(a.length)];
const bstr = b => b.join('');
const out = {
  meta: {
    webVersion: pkg.version,
    webCommit: execSync('git rev-parse HEAD', { cwd: web }).toString().trim(),
    poolSha256: createHash('sha256').update(poolRaw).digest('hex'),
    generated: new Date().toISOString().slice(0, 10),
  },
  hash: [], hex: [], bitString: [], spec: [], encode: [], web: [], decode: [], errors: [], kdf: [],
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
const MODES = ['kanji', 'alpha', 'mixedK', 'mixedA', 'mixedAllAlpha'];
function encode(mode, b, c, pos) {
  if (mode === 'kanji') return K.encodeBits(pool, b, { checkBits: c });
  if (mode === 'alpha') return K.encodeAlpha(b, { checkBits: c });
  if (mode === 'mixedAllAlpha') return K.encodeMixed(pool, b, { checkBits: c, alphaPos: Array.from({ length: 80 }, (_, i) => i + 1) });
  return K.encodeMixed(pool, b, { checkBits: c, alphaPos: pos, tail: mode === 'mixedA' ? 'alpha' : 'kanji' });
}
const enc = [];
for (const mode of MODES) for (let r = 0; r < 240; r++) {
  const n = r < LENS.length ? LENS[r] : r % 3 === 0 ? 256 : 8 * (8 + rnd(57)), c = r % 4 === 0 ? 0 : pick([0, 0, 1, 5, 12, 12, 16, 32, 64]);
  const b = K.randomBits(n), pos = randPos(), e = encode(mode, b, c, pos);
  const v = { mode, bits: bstr(b), checkBits: c, alphaPos: pos, text: e.text, totalBits: e.totalBits, writtenBits: e.writtenBits, surplus: e.surplus };
  out.encode.push(v); enc.push({ ...v, e, b });
}

// ---- the web UI's two endings + common prefix (render() in web/app.html)
for (let r = 0; r < 120; r++) {
  const n = 8 * (8 + rnd(57)), c = pick([0, 0, 12]), b = K.randomBits(n);
  const pos = r % 5 === 0 ? Array.from({ length: 80 }, (_, i) => i + 1) : randPos();
  const o = { checkBits: c, alphaPos: pos };
  const A = K.encodeMixed(pool, b, { ...o, tail: 'kanji' }), B = K.encodeMixed(pool, b, { ...o, tail: 'alpha' });
  let p = 0; while (p < A.tokens.length && p < B.tokens.length && A.tokens[p].type === B.tokens[p].type && A.tokens[p].char === B.tokens[p].char) p++;
  const info = R => `漢字${R.tokens.filter(t => t.type === 'kanji').length}・英数字${R.tokens.filter(t => t.type === 'alpha').length}・書く量${R.writtenBits}bit`;
  out.web.push({ bits: bstr(b), checkBits: c, alphaPos: pos, a: A.text, b: B.text, common: p, infoA: info(A), infoB: info(B),
    head: `${A.totalBits}bit (データ${A.dataBits}+チェック${A.checkBits}) ／ 共通部分 ${p}記号` });
}

// ---- decodes: unknowns, blocks, color names, whitespace, v1.0 outputs
const CN = '黒赤青緑';
function decodeCase(input, note) {
  try {
    const d = K.decodeAny(pool, input);
    out.decode.push({ note, in: input, tried: d.tried, total: d.total, dataBits: d.dataBits, checkBits: d.checkBits,
      count: d.candidates.length, candidates: d.candidates.slice(0, 64).map(x => ({ hex: x.hex, text: x.text, padding: x.padding })) });
  } catch (e) { out.errors.push({ note, in: input, error: e.message }); }
}
function kanjiParts(text) {
  const p = K.isMixedText(text) ? K.parseMixed(pool, text) : K.parseText(pool, text);
  return { head: text.match(/^M?\([^)]*\)/)[0], parts: p.tokens.map(t => [t.char, t.spec]), sep: K.isMixedText(text) ? ' ' : '' };
}
const join = ({ head, parts, sep }) => head + parts.map(([c, s]) => c + s).join(sep);
function qmarks(spec, k) { const a = [...spec]; for (let i = 0; i < k; i++) a[rnd(a.length)] = '?'; return a.join(''); }
for (const v of enc) {
  if (v.e.tokens.length === 0) { decodeCase(v.text, v.mode + ' empty'); continue; }
  decodeCase(v.text, v.mode + ' exact');
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
      const parts = v.text.split(' '); const i = 1 + rnd(parts.length - 1); parts[i] = parts[i][0] + '?';
      decodeCase(parts.join(' '), 'mixed all-alpha ?');
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
  const mode = MODES[r % MODES.length];
  const o = mode === 'kanji' ? OLD.encodeBits(oldPool, b, { checkBits: c }) : mode === 'alpha' ? OLD.encodeAlpha(b, { checkBits: c })
    : OLD.encodeMixed(oldPool, b, { checkBits: c, alphaPos: mode === 'mixedAllAlpha' ? Array.from({ length: 80 }, (_, i) => i + 1) : pos, tail: mode === 'mixedA' ? 'alpha' : 'kanji' });
  decodeCase(o.text, 'v1.0 ' + mode);
}
// errors and edge syntax
for (const s of ['従0231', '(12+0bit)A1', '(4+0bit)a1b2', '(8bit)a1', '(8bit)a1b', '(8bit)g1a1', '(256bit)', 'A(256bit)2030', 'A(14bit)2030',
  'A(14bit)0030', 'A(14bit)205030', 'a(14bit)2?3?', 'M(14bit)20 3', 'M(14bit)20 0', 'M(14bit)2X', 'M(14bit)2', 'M(14bit)2030', 'M(7bit)Ü0', 'M(20bit)従0',
  'M(12bit)A1B1C1', '(０bit)', '(0bit)', '(0bit)a', 'A(0bit)', 'M(0bit)', '  m （ 7 bit ）2 0', '(12bit)亜0000000', '(12bit)亜000000(0)',
  '(12bit)亜00[0000)0', '(12bit)亜((0)000000', '(12bit)亜黒赤青緑黒赤青', '(12BIT)亜???????', 'M(12+4bit)亜0123012', 'hello',
  '(12bit)亜?????????', '(12bit)亜[0123012]', '(12bit)亜[01]2?????', 'M(2+5bit)?0', 'M(2+5bit)??', 'A(2+5bit)??']) decodeCase(s, 'edge');

// ---- passphrase KDF
for (const [p, s, it] of [['correct horse', '', 1000], ['correct horse', 'x', 1000], ['', '', 1], ['ｶﾞ漢字\u0065\u0301', 'ソルト', 2000],
  ['pass\ud800word', 's\udfff', 10], ['a'.repeat(100), '', 3], ['correct horse battery staple', 'me', 600000]]) {
  const k = await K.deriveKey(p, { salt: s, iterations: it });
  out.kdf.push({ pass: p, salt: s, iterations: it, hex: Buffer.from(k).toString('hex') });
}

// ---- the web demo (#demo)
{
  const key = K.hexToBits('c2fb9f70272dae3adee45198b576b4873f5ad57ac41696bdfaa92e6de9923e14'.repeat(2)).slice(0, 256);
  const A = K.encodeMixed(pool, key, { checkBits: 0, alphaPos: [3, 6], tail: 'kanji' });
  const input = A.text.replace(/^(M\([\d+]+bit\)\S)\d/, '$1?'), d = K.decodeAny(pool, input);
  out.demo = { a: A.text, input, tried: d.tried, candidates: d.candidates.map(x => x.hex) };
}
process.stdout.write(JSON.stringify(out));
process.stderr.write(`encode ${out.encode.length}, web ${out.web.length}, decode ${out.decode.length}, errors ${out.errors.length}\n`);
