// Reads a QR PNG back off disk and checks it independently of the encoder:
// structure, format info via BCH, Reed-Solomon syndromes, and a full round-trip
// of the payload. A third-party decoder would be better; this is the next best.
// Usage: node qr-verify.js out.png "<expected text>" [modulePixels]
'use strict';
const zlib = require('zlib'), fs = require('fs');

/* --------------------------------------------------- read our own PNG --- */
function readPNG(file){
  const buf = fs.readFileSync(file);
  let off = 8, width = 0, height = 0, idat = [];
  while (off < buf.length){
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.slice(off + 8, off + 8 + len);
    if (type === 'IHDR'){
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      if (data[8] !== 8 || data[9] !== 2) throw new Error('expected 8-bit RGB');
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * 3 + 1, px = [];
  for (let y = 0; y < height; y++){
    if (raw[y * stride] !== 0) throw new Error('unexpected PNG filter');
    const row = [];
    for (let x = 0; x < width; x++) row.push(raw[y * stride + 1 + x * 3]);  // red channel
    px.push(row);
  }
  return { width, height, px };
}

/* ------------------------------------------------------------ GF(256) -- */
const EXP = new Uint8Array(512), LOG = new Uint8Array(256);
(function(){ let x = 1;
  for (let i = 0; i < 255; i++){ EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11D; }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
})();
const mul = (a,b) => (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]];

const M_TABLE = {
  1:[10,1,16,0,0],  2:[16,1,28,0,0],  3:[26,1,44,0,0],  4:[18,2,32,0,0],
  5:[24,2,43,0,0],  6:[16,4,27,0,0],  7:[18,4,31,0,0],  8:[22,2,38,2,39],
  9:[22,3,36,2,37], 10:[26,4,43,1,44]
};
const ALIGN = { 1:[], 2:[6,18], 3:[6,22], 4:[6,26], 5:[6,30],
                6:[6,34], 7:[6,22,38], 8:[6,24,42], 9:[6,26,46], 10:[6,28,50] };
const REMAINDER = { 1:0, 2:7, 3:7, 4:7, 5:7, 6:7, 7:0, 8:0, 9:0, 10:0 };
const MASKS = [
  (i,j)=>(i+j)%2===0, (i,j)=>i%2===0, (i,j)=>j%3===0, (i,j)=>(i+j)%3===0,
  (i,j)=>(Math.floor(i/2)+Math.floor(j/3))%2===0,
  (i,j)=>((i*j)%2+(i*j)%3)===0, (i,j)=>(((i*j)%2+(i*j)%3)%2)===0,
  (i,j)=>(((i+j)%2+(i*j)%3)%2)===0
];

const fail = [];
const check = (ok, label) => { if (!ok) fail.push(label); return ok; };

/* ----------------------------------------------------------- the work -- */
const [,, file, expected, scaleArg] = process.argv;
const scale = parseInt(scaleArg || '9', 10), quiet = 4;
const img = readPNG(file);
const size = img.width / scale - quiet * 2;
check(Number.isInteger(size), 'module grid divides evenly');
const version = (size - 17) / 4;
check(Number.isInteger(version) && version >= 1 && version <= 10, 'plausible version');

// sample each module at its centre
const m = [];
for (let r = 0; r < size; r++){
  const row = [];
  for (let c = 0; c < size; c++){
    const y = (r + quiet) * scale + (scale >> 1), x = (c + quiet) * scale + (scale >> 1);
    row.push(img.px[y][x] < 128 ? 1 : 0);
  }
  m.push(row);
}

// quiet zone must be clear
let quietClean = true;
for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++){
  const insideR = y >= quiet*scale && y < (quiet+size)*scale;
  const insideC = x >= quiet*scale && x < (quiet+size)*scale;
  if (!(insideR && insideC) && img.px[y][x] < 128) quietClean = false;
}
check(quietClean, 'quiet zone is blank');

// finder patterns
function finderOK(r0, c0){
  const want = [
    [1,1,1,1,1,1,1],[1,0,0,0,0,0,1],[1,0,1,1,1,0,1],[1,0,1,1,1,0,1],
    [1,0,1,1,1,0,1],[1,0,0,0,0,0,1],[1,1,1,1,1,1,1]];
  for (let i = 0; i < 7; i++) for (let j = 0; j < 7; j++)
    if (m[r0+i][c0+j] !== want[i][j]) return false;
  return true;
}
check(finderOK(0,0) && finderOK(0,size-7) && finderOK(size-7,0), 'three finder patterns');

// timing patterns
let timingOK = true;
for (let i = 8; i < size - 8; i++){
  if (m[6][i] !== (i % 2 === 0 ? 1 : 0)) timingOK = false;
  if (m[i][6] !== (i % 2 === 0 ? 1 : 0)) timingOK = false;
}
check(timingOK, 'timing patterns alternate');
check(m[size-8][8] === 1, 'dark module set');

// format info: read copy 1, BCH-verify, recover level + mask
let fb = 0;
for (let i = 0; i <= 5; i++) fb |= m[8][i] << (14 - i);
fb |= m[8][7] << 8; fb |= m[8][8] << 7; fb |= m[7][8] << 6;
for (let i = 9; i <= 14; i++) fb |= m[14-i][8] << (14 - i);
const unmasked = fb ^ 0x5412;
let rem = unmasked;
for (let i = 14; i >= 10; i--) if ((rem >> i) & 1) rem ^= 0x537 << (i - 10);
check(rem === 0, 'format info passes its BCH check');
const ecLevelBits = (unmasked >> 13) & 3, maskId = (unmasked >> 10) & 7;
check(ecLevelBits === 0, 'error correction level is M');

// second format copy must agree
let fb2 = 0;
for (let i = 0; i <= 6; i++) fb2 |= m[size-1-i][8] << i;
for (let i = 7; i <= 14; i++) fb2 |= m[8][size-15+i] << i;
check(fb2 === fb, 'both format copies agree');

// rebuild the function-module map so data extraction skips it
const isFunc = Array.from({length:size}, () => new Array(size).fill(false));
const mark = (r,c) => { if (r>=0 && r<size && c>=0 && c<size) isFunc[r][c] = true; };
for (const [r0,c0] of [[0,0],[0,size-7],[size-7,0]])
  for (let i=-1;i<=7;i++) for (let j=-1;j<=7;j++) mark(r0+i, c0+j);
for (let i=0;i<size;i++){ mark(6,i); mark(i,6); }
for (const r of ALIGN[version]) for (const c of ALIGN[version]){
  if ((r<=8&&c<=8)||(r<=8&&c>=size-9)||(r>=size-9&&c<=8)) continue;
  for (let i=-2;i<=2;i++) for (let j=-2;j<=2;j++) mark(r+i,c+j);
}
for (let i=0;i<=8;i++){ mark(8,i); mark(i,8); }
for (let i=0;i<8;i++){ mark(8,size-1-i); mark(size-1-i,8); }
if (version >= 7) for (let i=0;i<6;i++) for (let j=0;j<3;j++){ mark(i,size-11+j); mark(size-11+j,i); }

// unmask and read the zigzag
const bits = [];
let up = true;
for (let col = size-1; col > 0; col -= 2){
  if (col === 6) col--;
  for (let k = 0; k < size; k++){
    const row = up ? size-1-k : k;
    for (const c of [col, col-1]){
      if (isFunc[row][c]) continue;
      bits.push(m[row][c] ^ (MASKS[maskId](row, c) ? 1 : 0));
    }
  }
  up = !up;
}
const stream = [];
for (let i = 0; i + 8 <= bits.length; i += 8){
  let b = 0; for (let j = 0; j < 8; j++) b = (b << 1) | bits[i+j];
  stream.push(b);
}

// de-interleave back into blocks, then check RS syndromes
const [ecLen, b1, d1, b2, d2] = M_TABLE[version];
const counts = [], total = b1 + b2;
for (let i=0;i<b1;i++) counts.push(d1);
for (let i=0;i<b2;i++) counts.push(d2);
const dataBlocks = counts.map(n => new Array(n));
let idx = 0;
for (let i = 0; i < Math.max(d1,d2); i++)
  for (let b = 0; b < total; b++) if (i < counts[b]) dataBlocks[b][i] = stream[idx++];
const ecBlocks = Array.from({length: total}, () => new Array(ecLen));
for (let i = 0; i < ecLen; i++)
  for (let b = 0; b < total; b++) ecBlocks[b][i] = stream[idx++];

let syndromesZero = true;
for (let b = 0; b < total; b++){
  const code = dataBlocks[b].concat(ecBlocks[b]);
  for (let s = 0; s < ecLen; s++){
    let acc = 0;
    for (const byte of code) acc = mul(acc, EXP[s]) ^ byte;
    if (acc !== 0) syndromesZero = false;
  }
}
check(syndromesZero, 'Reed-Solomon syndromes are all zero');

// round-trip the payload
const dataBytes = [];
for (const blk of dataBlocks) dataBytes.push(...blk);
const bs = [];
for (const b of dataBytes) for (let i = 7; i >= 0; i--) bs.push((b >> i) & 1);
const take = n => { let v = 0; for (let i = 0; i < n; i++) v = (v << 1) | bs.shift(); return v; };
const mode = take(4);
check(mode === 0b0100, 'mode is byte');
const len = take(version <= 9 ? 8 : 16);
const outBytes = [];
for (let i = 0; i < len; i++) outBytes.push(take(8));
const text = Buffer.from(outBytes).toString('utf8');
check(text === expected, 'payload round-trips');

console.log(`${file}  v${version} mask ${maskId} ${size}x${size}`);
console.log(`  decoded: ${JSON.stringify(text)}`);
if (fail.length){ console.log('  FAILED: ' + fail.join('; ')); process.exit(1); }
console.log('  all checks passed');
