# Draws the synthetic "desk" fixture used by the look lab: a dark scratched cylinder with a worn red lid,
# small white cut-out letters and a red heart, a white keyboard with printed keys, a green packet, paper.
import numpy as np, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
W, H = 1600, 1200
rng = np.random.default_rng(7)
y, x = np.mgrid[0:H, 0:W] / np.array([H, W])[:, None, None]
# desk: warm grey wood with grain
grain = (np.sin(x * 90 + np.sin(y * 7) * 3) * 0.5 + 0.5) * 0.06
base = np.stack([0.50 + grain, 0.42 + grain * 0.9, 0.34 + grain * 0.7], -1) * (0.75 + 0.35 * y[..., None])
base += rng.normal(0, 0.012, base.shape)
img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
d = ImageDraw.Draw(img)
def font(sz, bold=True):
    for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]:
        try: return ImageFont.truetype(f, sz)
        except Exception: pass
    return ImageFont.load_default()
# paper sheet (bright white, slight shadow)
d.rectangle([980, 120, 1520, 520], fill=(40, 34, 30))
img = img.filter(ImageFilter.GaussianBlur(0))
d = ImageDraw.Draw(img)
d.rectangle([970, 105, 1505, 505], fill=(244, 242, 236))
for i, line in enumerate(["The quick brown fox", "jumps over the lazy", "dog. 0123456789", "Small type, fine detail"]):
    d.text((1000, 140 + i * 42), line, fill=(30, 30, 34), font=font(28, False))
# green packet
d.polygon([(1080, 560), (1480, 540), (1500, 760), (1100, 790)], fill=(46, 140, 70))
d.text((1140, 610), "GREEN TEA", fill=(240, 236, 220), font=font(46))
d.text((1150, 680), "20 bags", fill=(220, 230, 200), font=font(26, False))
img = np.asarray(img).astype(np.float32) / 255
# black cylinder with highlights, scratches and texture
cx0, cx1, top, bot = 250, 720, 300, 1080
xs = (np.arange(W) - cx0) / (cx1 - cx0)
shade = np.clip(0.05 + 0.10 * np.exp(-((xs - 0.32) / 0.08) ** 2) + 0.035 * np.sin(np.clip(xs, 0, 1) * np.pi), 0, 1)
cyl = np.zeros((H, W)); cyl[top:bot, cx0:cx1] = 1
tex = rng.normal(0, 0.012, (H, W))
col = np.stack([shade * 0.95, shade, shade * 1.08], -1)[None, :, :].repeat(H, 0) + tex[..., None]
img = np.where(cyl[..., None] > 0, col, img)
pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)); d = ImageDraw.Draw(pil)
for i in range(70):  # fine scratches
    sx = rng.uniform(cx0 + 10, cx1 - 10); sy = rng.uniform(top + 10, bot - 10); L = rng.uniform(15, 90); a = rng.uniform(-0.6, 0.6)
    g = int(rng.uniform(55, 95)); d.line([sx, sy, sx + L * np.cos(a), sy + L * np.sin(a)], fill=(g, g, g + 4), width=1)
# worn red lid
d.ellipse([cx0 - 12, top - 70, cx1 + 12, top + 50], fill=(150, 28, 26))
d.rectangle([cx0 - 12, top - 10, cx1 + 12, top + 40], fill=(150, 28, 26))
d.ellipse([cx0 - 12, top - 100, cx1 + 12, top + 10], fill=(190, 40, 34))
for i in range(160):  # wear
    wx = rng.uniform(cx0, cx1); wy = rng.uniform(top - 95, top + 35); r = rng.uniform(1, 6)
    c = (int(rng.uniform(90, 200)), int(rng.uniform(40, 90)), int(rng.uniform(35, 70)))
    d.ellipse([wx - r, wy - r * 0.6, wx + r, wy + r * 0.6], fill=c)
# small white cut-out letters and a red heart on the cylinder
d.text((330, 520), "HOLD", fill=(236, 234, 228), font=font(64))
d.text((340, 610), "fast to what is good", fill=(226, 224, 218), font=font(24, False))
hx, hy = 470, 780
heart = [(hx, hy + 60)]
for t in np.linspace(0, 2 * np.pi, 80):
    heart.append((hx + 40 * 16 * np.sin(t) ** 3 / 16, hy - 40 * (13 * np.cos(t) - 5 * np.cos(2 * t) - 2 * np.cos(3 * t) - np.cos(4 * t)) / 16))
d.polygon(heart, fill=(205, 30, 40))
# white keyboard
kx, ky = 760, 860
d.rectangle([kx - 10, ky - 10, kx + 820, ky + 300], fill=(205, 205, 200))
keys = "QWERTYUIOPASDFGHJKLZXCVBNM"
k = 0
for r in range(3):
    for c in range(10 - r):
        x0 = kx + 12 + r * 30 + c * 78; y0 = ky + 12 + r * 92
        d.rectangle([x0 + 3, y0 + 5, x0 + 70, y0 + 84], fill=(150, 150, 146))
        d.rectangle([x0, y0, x0 + 66, y0 + 78], fill=(248, 248, 244))
        if k < len(keys): d.text((x0 + 20, y0 + 20), keys[k], fill=(70, 70, 74), font=font(28, False)); k += 1
img = np.asarray(pil).astype(np.float32) / 255
img = np.clip(img + rng.normal(0, 0.006, img.shape), 0, 1)
Image.fromarray((img * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)).save(sys.argv[1] if len(sys.argv) > 1 else "desk.png")
