// Minimal QR encoder (model 2, byte mode, error correction level M) plus a PNG
// writer, because this machine has no image library and no qrencode.
// Usage: node qr.js "<text>" out.png [modulePixels]
'use strict';
const zlib = require('zlib'), fs = require('fs');

/* ----------------------------------------------------------- GF(256) ---- */
const EXP = new Uint8Array(512), LOG = new Uint8Array(256);
(function(){
  let x = 1;
  for (let i = 0; i < 255; i++){
    EXP[i] = x; LOG[x] = i;
    x <<= 1; if (x & 0x100) x ^= 0x11D;          // QR's primitive polynomial
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
})();
const mul = (a, b) => (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]];

function rsGenerator(n){
  let g = [1];
  for (let i = 0; i < n; i++){
    const next = new Array(g.length + 1).fill(0);
    for (let j = 0; j < g.length; j++){
      next[j]     ^= g[j];                 // g · x
      next[j + 1] ^= mul(g[j], EXP[i]);    // g · α^i
    }
    g = next;
  }
  return g;
}
function rsEncode(data, ecLen){
  const gen = rsGenerator(ecLen), res = new Array(ecLen).fill(0);
  for (const byte of data){
    const factor = byte ^ res[0];
    res.shift(); res.push(0);
    for (let i = 0; i < ecLen; i++) res[i] ^= mul(gen[i + 1], factor);
  }
  return res;
}

/* --------------------------------------------- per-version tables (M) ---- */
// [ecCodewordsPerBlock, group1Blocks, group1DataCodewords, group2Blocks, group2DataCodewords]
const M_TABLE = {
  1:[10,1,16,0,0],  2:[16,1,28,0,0],  3:[26,1,44,0,0],  4:[18,2,32,0,0],
  5:[24,2,43,0,0],  6:[16,4,27,0,0],  7:[18,4,31,0,0],  8:[22,2,38,2,39],
  9:[22,3,36,2,37], 10:[26,4,43,1,44]
};
const ALIGN = {
  1:[], 2:[6,18], 3:[6,22], 4:[6,26], 5:[6,30],
  6:[6,34], 7:[6,22,38], 8:[6,24,42], 9:[6,26,46], 10:[6,28,50]
};
const REMAINDER = { 1:0, 2:7, 3:7, 4:7, 5:7, 6:7, 7:0, 8:0, 9:0, 10:0 };
const dataCapacity = v => {
  const [, b1, d1, b2, d2] = M_TABLE[v];
  return b1 * d1 + b2 * d2;
};

/* ------------------------------------------------------------ encoding -- */
function encode(text){
  const bytes = Buffer.from(text, 'utf8');
  let version = 0;
  for (let v = 1; v <= 10; v++){
    const countBits = v <= 9 ? 8 : 16;
    if (4 + countBits + bytes.length * 8 <= dataCapacity(v) * 8){ version = v; break; }
  }
  if (!version) throw new Error('text too long for version 10 at level M');

  const bits = [];
  const push = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >> i) & 1); };
  push(0b0100, 4);                                   // byte mode
  push(bytes.length, version <= 9 ? 8 : 16);
  for (const b of bytes) push(b, 8);

  const cap = dataCapacity(version) * 8;
  for (let i = 0; i < 4 && bits.length < cap; i++) bits.push(0);   // terminator
  while (bits.length % 8) bits.push(0);
  const PADS = [0xEC, 0x11];
  for (let i = 0; bits.length < cap; i++) push(PADS[i % 2], 8);

  const codewords = [];
  for (let i = 0; i < bits.length; i += 8){
    let b = 0; for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    codewords.push(b);
  }

  // split into blocks, compute EC per block, then interleave
  const [ecLen, b1, d1, b2, d2] = M_TABLE[version];
  const blocks = [], ecBlocks = [];
  let at = 0;
  for (let i = 0; i < b1; i++){ blocks.push(codewords.slice(at, at + d1)); at += d1; }
  for (let i = 0; i < b2; i++){ blocks.push(codewords.slice(at, at + d2)); at += d2; }
  for (const blk of blocks) ecBlocks.push(rsEncode(blk, ecLen));

  const out = [];
  const maxData = Math.max(d1, d2);
  for (let i = 0; i < maxData; i++)
    for (const blk of blocks) if (i < blk.length) out.push(blk[i]);
  for (let i = 0; i < ecLen; i++)
    for (const blk of ecBlocks) out.push(blk[i]);

  const finalBits = [];
  for (const b of out) for (let i = 7; i >= 0; i--) finalBits.push((b >> i) & 1);
  for (let i = 0; i < REMAINDER[version]; i++) finalBits.push(0);
  return { version, bits: finalBits };
}

/* ------------------------------------------------------------- matrix --- */
function buildMatrix(version, bits){
  const size = version * 4 + 17;
  const m = Array.from({ length: size }, () => new Array(size).fill(null));
  const set = (r, c, v) => { if (r >= 0 && r < size && c >= 0 && c < size) m[r][c] = v; };

  function finder(r, c){
    for (let i = -1; i <= 7; i++) for (let j = -1; j <= 7; j++){
      const inner = i >= 0 && i <= 6 && j >= 0 && j <= 6;
      const on = inner && ((i === 0 || i === 6 || j === 0 || j === 6) ||
                           (i >= 2 && i <= 4 && j >= 2 && j <= 4));
      set(r + i, c + j, on ? 1 : 0);
    }
  }
  finder(0, 0); finder(0, size - 7); finder(size - 7, 0);

  for (let i = 8; i < size - 8; i++){                       // timing
    m[6][i] = (i % 2 === 0) ? 1 : 0;
    m[i][6] = (i % 2 === 0) ? 1 : 0;
  }
  const centres = ALIGN[version];
  for (const r of centres) for (const c of centres){
    if ((r <= 8 && c <= 8) || (r <= 8 && c >= size - 9) || (r >= size - 9 && c <= 8)) continue;
    for (let i = -2; i <= 2; i++) for (let j = -2; j <= 2; j++)
      m[r + i][c + j] = (Math.abs(i) === 2 || Math.abs(j) === 2 || (i === 0 && j === 0)) ? 1 : 0;
  }
  m[size - 8][8] = 1;                                        // dark module

  // reserve format areas so data placement skips them
  const reserved = [];
  for (let i = 0; i <= 8; i++){ reserved.push([8, i], [i, 8]); }
  for (let i = 0; i < 8; i++){ reserved.push([8, size - 1 - i], [size - 1 - i, 8]); }
  for (const [r, c] of reserved) if (m[r][c] === null) m[r][c] = 0;
  if (version >= 7){
    for (let i = 0; i < 6; i++) for (let j = 0; j < 3; j++){
      if (m[i][size - 11 + j] === null) m[i][size - 11 + j] = 0;
      if (m[size - 11 + j][i] === null) m[size - 11 + j][i] = 0;
    }
  }
  const isFunction = Array.from({ length: size }, (_, r) =>
    Array.from({ length: size }, (_, c) => m[r][c] !== null));

  // zigzag data placement
  let bi = 0, up = true;
  for (let col = size - 1; col > 0; col -= 2){
    if (col === 6) col--;                                    // skip the timing column
    for (let k = 0; k < size; k++){
      const row = up ? size - 1 - k : k;
      for (const c of [col, col - 1]){
        if (isFunction[row][c]) continue;
        m[row][c] = bi < bits.length ? bits[bi++] : 0;
      }
    }
    up = !up;
  }
  return { m, size, isFunction };
}

const MASKS = [
  (i,j)=> (i+j)%2===0,
  (i,j)=> i%2===0,
  (i,j)=> j%3===0,
  (i,j)=> (i+j)%3===0,
  (i,j)=> (Math.floor(i/2)+Math.floor(j/3))%2===0,
  (i,j)=> ((i*j)%2 + (i*j)%3)===0,
  (i,j)=> (((i*j)%2 + (i*j)%3)%2)===0,
  (i,j)=> (((i+j)%2 + (i*j)%3)%2)===0
];

function formatBits(maskId){
  const ecLevelBits = 0b00;                                  // level M
  let data = (ecLevelBits << 3) | maskId;
  let rem = data << 10;
  for (let i = 14; i >= 10; i--) if ((rem >> i) & 1) rem ^= 0x537 << (i - 10);
  return ((data << 10) | rem) ^ 0x5412;
}
function versionBits(version){
  let rem = version << 12;
  for (let i = 17; i >= 12; i--) if ((rem >> i) & 1) rem ^= 0x1F25 << (i - 12);
  return (version << 12) | rem;
}

function penalty(m, size){
  let score = 0;
  const run = line => {
    let s = 0, count = 1;
    for (let i = 1; i < size; i++){
      if (line[i] === line[i-1]) count++;
      else { if (count >= 5) s += 3 + (count - 5); count = 1; }
    }
    if (count >= 5) s += 3 + (count - 5);
    return s;
  };
  for (let r = 0; r < size; r++) score += run(m[r]);
  for (let c = 0; c < size; c++) score += run(m.map(row => row[c]));
  for (let r = 0; r < size - 1; r++) for (let c = 0; c < size - 1; c++){
    const v = m[r][c];
    if (v === m[r][c+1] && v === m[r+1][c] && v === m[r+1][c+1]) score += 3;
  }
  const pat = [1,0,1,1,1,0,1,0,0,0,0];
  const pat2 = [0,0,0,0,1,0,1,1,1,0,1];
  const scan = line => {
    let s = 0;
    for (let i = 0; i + 11 <= size; i++){
      let a = true, b = true;
      for (let k = 0; k < 11; k++){ if (line[i+k] !== pat[k]) a = false; if (line[i+k] !== pat2[k]) b = false; }
      if (a) s += 40; if (b) s += 40;
    }
    return s;
  };
  for (let r = 0; r < size; r++) score += scan(m[r]);
  for (let c = 0; c < size; c++) score += scan(m.map(row => row[c]));
  let dark = 0;
  for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) dark += m[r][c];
  const pct = dark * 100 / (size * size);
  score += Math.floor(Math.abs(pct - 50) / 5) * 10;
  return score;
}

function makeQR(text){
  const { version, bits } = encode(text);
  const { m, size, isFunction } = buildMatrix(version, bits);
  let best = null;
  for (let mask = 0; mask < 8; mask++){
    const t = m.map(row => row.slice());
    for (let r = 0; r < size; r++) for (let c = 0; c < size; c++)
      if (!isFunction[r][c] && MASKS[mask](r, c)) t[r][c] ^= 1;
    const fb = formatBits(mask);
    for (let i = 0; i <= 5; i++) t[8][i] = (fb >> (14 - i)) & 1;
    t[8][7] = (fb >> 8) & 1; t[8][8] = (fb >> 7) & 1; t[7][8] = (fb >> 6) & 1;
    for (let i = 9; i <= 14; i++) t[14 - i][8] = (fb >> (14 - i)) & 1;
    for (let i = 0; i <= 6; i++) t[size - 1 - i][8] = (fb >> i) & 1;
    for (let i = 7; i <= 14; i++) t[8][size - 15 + i] = (fb >> i) & 1;
    t[size - 8][8] = 1;                                   // the dark module, always
    if (version >= 7){
      const vb = versionBits(version);
      for (let i = 0; i < 18; i++){
        const bit = (vb >> i) & 1;
        t[Math.floor(i / 3)][size - 11 + (i % 3)] = bit;
        t[size - 11 + (i % 3)][Math.floor(i / 3)] = bit;
      }
    }
    const p = penalty(t, size);
    if (!best || p < best.p) best = { p, t, mask };
  }
  return { matrix: best.t, size, version, mask: best.mask };
}

/* ---------------------------------------------------------------- PNG --- */
const CRCT = new Int32Array(256);
for (let n = 0; n < 256; n++){ let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c>>>1) : c>>>1; CRCT[n] = c; }
const crc = buf => { let c = -1; for (const v of buf) c = CRCT[(c ^ v) & 255] ^ (c>>>8); return (c ^ -1)>>>0; };
function chunk(type, data){
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const cc = Buffer.alloc(4); cc.writeUInt32BE(crc(td));
  return Buffer.concat([len, td, cc]);
}
function writePNG(matrix, size, scale, quiet, file){
  const dim = (size + quiet * 2) * scale;
  const px = Buffer.alloc(dim * dim * 3, 255);
  for (let r = 0; r < size; r++) for (let c = 0; c < size; c++){
    if (!matrix[r][c]) continue;
    for (let y = 0; y < scale; y++) for (let x = 0; x < scale; x++){
      const py = (r + quiet) * scale + y, pxx = (c + quiet) * scale + x;
      const i = (py * dim + pxx) * 3;
      px[i] = 0; px[i+1] = 0; px[i+2] = 0;
    }
  }
  const raw = Buffer.alloc(dim * (dim * 3 + 1));
  for (let y = 0; y < dim; y++){
    raw[y * (dim * 3 + 1)] = 0;
    px.copy(raw, y * (dim * 3 + 1) + 1, y * dim * 3, (y + 1) * dim * 3);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(dim, 0); ihdr.writeUInt32BE(dim, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]));
  return dim;
}

const [,, text, out, scaleArg] = process.argv;
if (!text || !out){ console.error('usage: node qr.js "<text>" out.png [modulePixels]'); process.exit(1); }
const qr = makeQR(text);
const dim = writePNG(qr.matrix, qr.size, parseInt(scaleArg || '8', 10), 4, out);
console.log(`${out}  version ${qr.version}  mask ${qr.mask}  ${qr.size}x${qr.size} modules  ${dim}px`);
