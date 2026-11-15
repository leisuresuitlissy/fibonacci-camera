import numpy as np
from PIL import Image, ImageDraw, ImageFilter
rng = np.random.default_rng(7)
W, H = 2400, 3200
HZ = 1720  # horizon

def fbm(w, h, octaves=6, base=4, seed=0):
    r = np.random.default_rng(seed); out = np.zeros((h, w)); amp = 1.0; tot = 0
    for o in range(octaves):
        n = base * 2 ** o
        g = r.random((n + 1, int(n * w / h) + 2))
        im = Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        out += np.asarray(im, float) / 255 * amp; tot += amp; amp *= 0.5
    return out / tot

y = np.linspace(0, 1, H)[:, None]; x = np.linspace(0, 1, W)[None, :]
img = np.zeros((H, W, 3))
# sky gradient
stops = [(0.0, (34, 38, 92)), (0.25, (92, 70, 140)), (0.42, (214, 112, 92)), (0.5, (246, 168, 92)), (0.54, (255, 214, 150))]
t = np.clip(y / (HZ / H), 0, 1)[..., None]
sky = np.zeros((H, 1, 3))
for i in range(len(stops) - 1):
    a, ca = stops[i]; b, cb = stops[i + 1]
    m = ((t >= a / 0.54) & (t <= b / 0.54)).astype(float)
    k = np.clip((t - a / 0.54) / ((b - a) / 0.54), 0, 1)
    sky += m * (np.array(ca) * (1 - k) + np.array(cb) * k)
sky = np.clip(sky, 0, 255)
img[:] = sky
# clouds lit from below
cl = fbm(W, H, 6, 3, 1)
band = np.exp(-((y - 0.33) / 0.11) ** 2) + 0.6 * np.exp(-((y - 0.16) / 0.07) ** 2)
c = np.clip((cl - 0.48) * 4.5, 0, 1) * band
warm = np.clip(y / 0.54, 0, 1)[..., None]
cloudcol = np.array([120, 90, 150]) * (1 - warm) + np.array([255, 170, 120]) * warm
img = img * (1 - c[..., None] * 0.75) + c[..., None] * 0.75 * cloudcol
# sun
sx, sy = 860, 1660
d = np.sqrt((x * W - sx) ** 2 + (y * H - sy) ** 2)
img += (np.exp(-(d / 520) ** 2) * 90)[..., None] * np.array([1, 0.7, 0.4])
img += (np.exp(-(d / 160) ** 2) * 160)[..., None] * np.array([1, 0.85, 0.6])
img = np.where((d < 78)[..., None], np.array([255, 246, 220]), img)
# distant hazy hills (left)
pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
dr = ImageDraw.Draw(pil)
for k, (col, amp, base) in enumerate([((150, 98, 128), 90, HZ - 40), ((120, 76, 108), 70, HZ - 5)]):
    pts = [(0, H)]
    for xx in range(0, 1300, 20):
        pts.append((xx, base - amp * np.sin(xx / 260 + k) * np.exp(-xx / 900) - 30 * np.sin(xx / 70 + k)))
    pts += [(1300, HZ), (1300, H)]
    dr.polygon(pts, fill=col)
img = np.asarray(pil, float)
# sea
sea_t = np.clip((y * H - HZ) / (H - HZ), 0, 1)
st3 = sea_t[..., None]
seacol = np.array([42, 62, 92]) * (1 - st3) + np.array([18, 34, 52]) * st3
rip = fbm(W, H, 5, 6, 3)
rip = np.asarray(Image.fromarray((rip * 255).astype(np.uint8)).resize((W, H // 6)).resize((W, H)), float) / 255
seapix = seacol * (0.85 + 0.35 * rip[..., None])
glit = np.exp(-((x * W - sx) / (60 + 500 * sea_t)) ** 2) * (rng.random((H, W)) > 0.93) * (1 - sea_t * 0.6)
seapix += glit[..., None] * np.array([255, 210, 150]) * 1.2
seapix += (np.exp(-((x * W - sx) / (90 + 380 * sea_t)) ** 2) * 70 * (1 - sea_t))[..., None] * np.array([1, 0.7, 0.4])
img = np.where((y * H > HZ)[..., None], seapix, img)
pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
dr = ImageDraw.Draw(pil)
# headland with lighthouse (subject near the right-third / upper-third)
head = [(1180, HZ + 6), (1240, 1700), (1300, 1660), (1380, 1610), (1450, 1560), (1580, 1500), (1720, 1470), (1860, 1480), (2000, 1530), (2150, 1600), (2400, 1650), (2400, 2600), (1500, 2600), (1320, 2000), (1230, HZ + 40)]
dr.polygon(head, fill=(46, 40, 44))
cliffn = fbm(W, H, 5, 8, 5)
pil = Image.fromarray(np.clip(np.asarray(pil, float), 0, 255).astype(np.uint8))
arr = np.asarray(pil, float)
mask = np.asarray(Image.new("L", (W, H)), float)
mimg = Image.new("L", (W, H)); ImageDraw.Draw(mimg).polygon(head, fill=255); mask = np.asarray(mimg, float) / 255
rim = np.clip(1 - (y * H - 1470) / 500, 0, 1) * np.clip((x * W - 1150) / 1250, 0, 1)
cliffcol = np.array([58, 46, 48]) * (0.7 + 0.6 * cliffn[..., None]) + rim[..., None] * np.array([90, 50, 25])
arr = arr * (1 - mask[..., None]) + cliffcol * mask[..., None]
pil = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)); dr = ImageDraw.Draw(pil)
# lighthouse
lx, top, bot = 1720, 1090, 1480
for i in range(bot, top, -1):
    f = (bot - i) / (bot - top); hw = 62 - 22 * f
    band = int(((bot - i) // 70) % 2)
    col = (232, 222, 210) if band == 0 else (178, 58, 52)
    shade = 0.78
    dr.line([(lx - hw, i), (lx, i)], fill=tuple(int(c * shade) for c in col)); dr.line([(lx, i), (lx + hw, i)], fill=col)
dr.rectangle([lx - 52, top - 20, lx + 52, top], fill=(40, 36, 38))
dr.rectangle([lx - 34, top - 95, lx + 34, top - 20], fill=(255, 236, 170))
dr.polygon([(lx - 44, top - 95), (lx, top - 150), (lx + 44, top - 95)], fill=(48, 40, 42))
dr.rectangle([lx - 18, 1400, lx + 18, 1480], fill=(70, 50, 44))
arr = np.asarray(pil, float)
lamp = np.exp(-(((x * W - lx) ** 2 + (y * H - (top - 58)) ** 2) / 120 ** 2)) * 110
arr += lamp[..., None] * np.array([1, 0.85, 0.5])
# foreground grass bank
fg = Image.new("L", (W, H)); fd = ImageDraw.Draw(fg)
pts = [(0, 2250)] + [(xx, 2380 + 140 * np.sin(xx / 600) + 60 * np.sin(xx / 170)) for xx in range(0, W + 20, 20)] + [(W, H), (0, H)]
fd.polygon(pts, fill=255)
fgm = np.asarray(fg.filter(ImageFilter.GaussianBlur(3)), float) / 255
gt = np.clip((y * H - 2300) / 900, 0, 1)
g3 = gt[..., None]
grass = np.array([118, 112, 52]) * (1 - g3) + np.array([40, 46, 24]) * g3
gn = fbm(W, H, 6, 10, 9)
grass = grass * (0.7 + 0.6 * gn[..., None]) + (np.exp(-((x * W - sx) / 900) ** 2) * 50 * (1 - gt))[..., None] * np.array([1, 0.6, 0.2])
arr = arr * (1 - fgm[..., None]) + grass * fgm[..., None]
pil = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)); dr = ImageDraw.Draw(pil)
# grass blades for fine detail (so blur and grain read)
for _ in range(26000):
    gx = rng.uniform(0, W); gy = rng.uniform(2330, H)
    if fgm[int(min(gy, H - 1)), int(min(gx, W - 1))] < 0.5: continue
    k = (gy - 2300) / 900
    L = 18 + 90 * k + rng.uniform(0, 40)
    lean = rng.normal(0.25, 0.35) * L
    lum = rng.uniform(0.6, 1.25)
    base = np.array([196, 170, 88]) * (1 - k) + np.array([70, 84, 36]) * k
    col = tuple(int(min(255, c * lum)) for c in base)
    dr.line([(gx, gy), (gx + lean, gy - L)], fill=col, width=max(1, int(1 + 3 * k)))
# wildflowers
for _ in range(500):
    gx = rng.uniform(0, W); gy = rng.uniform(2420, H - 50)
    if fgm[int(gy), int(min(gx, W - 1))] < 0.5: continue
    r = 3 + 9 * (gy - 2400) / 800
    col = [(240, 220, 120), (236, 236, 228), (220, 120, 150)][rng.integers(0, 3)]
    dr.ellipse([gx - r, gy - r, gx + r, gy + r], fill=col)
# birds
for bx, by, s in [(760, 900, 26), (840, 860, 20), (690, 960, 16), (930, 930, 14)]:
    dr.line([(bx - s, by - s * 0.4), (bx, by), (bx + s, by - s * 0.45)], fill=(40, 30, 50), width=4)
arr = np.asarray(pil, float)
arr += rng.normal(0, 3.5, arr.shape)  # fine grain
out = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
out.save("DemoScene.jpg", quality=90)
out.resize((600, 800)).save("preview.png")
print("ok")
