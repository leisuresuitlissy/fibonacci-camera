# Turns the lab's renders into labelled contact sheets: per look, the desk sweep (default and each control
# at 0/100%), the photo fixtures (default and Intensity 100%), and 1:1 crops of fine detail.
import os, sys, glob
sys.path.insert(0, os.path.dirname(__file__))
from looks import LOOKS, NAMES
from PIL import Image, ImageDraw, ImageFont
src, lab, dst = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(dst, exist_ok=True)
try: F = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 15)
except Exception: F = ImageFont.load_default()
def tile(path, w, h, label):
    t = Image.new("RGB", (w, h + 20), (20, 20, 20))
    if os.path.exists(path):
        im = Image.open(path).convert("RGB"); im.thumbnail((w, h)); t.paste(im, ((w - im.width) // 2, 20))
    else:
        ImageDraw.Draw(t).text((6, 40), "missing", fill=(255, 80, 80), font=F)
    ImageDraw.Draw(t).text((4, 2), label, fill=(255, 255, 255), font=F)
    return t
def grid(tiles, cols, out):
    w, h = tiles[0].size; rows = (len(tiles) + cols - 1) // cols
    s = Image.new("RGB", (w * cols, h * rows), (0, 0, 0))
    for i, t in enumerate(tiles): s.paste(t, ((i % cols) * w, (i // cols) * h))
    s.save(out, quality=85)
def crop(path, box):
    if not os.path.exists(path): return Image.new("RGB", (box[2] - box[0], box[3] - box[1]))
    return Image.open(path).convert("RGB").crop(box)
P = lambda n: os.path.join(src, n + ".jpg")
photos = sorted({n.split("__")[1] for n in os.listdir(src) if n.startswith("ref__k")})
for m, slots in LOOKS.items():
    ts = [tile(P("ref__desk"), 400, 300, "original"), tile(P(f"{m}__desk__def"), 400, 300, "DEFAULT")]
    for s in slots:
        for v in (0, 100):
            ts.append(tile(P(f"{m}__desk__s{s}_{v}"), 400, 300, f"{NAMES[m][s]} {v}%"))
    grid(ts, 4, os.path.join(dst, f"{m}_1_desk.jpg"))
    ts = []
    for k in photos:
        ts += [tile(P(f"ref__{k}"), 300, 200, k), tile(P(f"{m}__{k}__def"), 300, 200, "default"), tile(P(f"{m}__{k}__i100"), 300, 200, "intensity 100")]
    if ts: grid(ts, 6, os.path.join(dst, f"{m}_2_photos.jpg"))
    # 1:1 detail: lettering + heart, keyboard, lid; default, then the strongest setting of each control
    boxes = [(300, 480, 760, 900), (760, 840, 1220, 1160), (230, 180, 690, 400)]
    names = ["def"] + [f"s{s}_100" for s in slots]
    cols = []
    for n in ["ref"] + names:
        p = P("ref__desk") if n == "ref" else P(f"{m}__desk__{n}")
        col = Image.new("RGB", (460, 420 + 320 + 220 + 22), (0, 0, 0)); y = 22
        for b in boxes:
            c = crop(p, b); col.paste(c, (0, y)); y += c.height
        ImageDraw.Draw(col).text((4, 2), n if n in ("ref", "def") else NAMES[m][int(n[1:].split('_')[0])] + " 100", fill=(255, 255, 255), font=F)
        cols.append(col)
    s = Image.new("RGB", (460 * len(cols), cols[0].height))
    for i, c in enumerate(cols): s.paste(c, (i * 460, 0))
    s.save(os.path.join(dst, f"{m}_3_crops.jpg"), quality=88)
# saved-photo size: default at 3200 px, same crop scaled 2x, beside the 1600 px one
for m in LOOKS:
    a = crop(P(f"{m}__desk__def"), (300, 480, 760, 900)).resize((920, 840))
    b = crop(P(f"{m}__deskbig__def"), (600, 960, 1520, 1800))
    s = Image.new("RGB", (1840, 840)); s.paste(a, (0, 0)); s.paste(b, (920, 0))
    s.save(os.path.join(dst, f"{m}_4_scale.jpg"), quality=85)
# the fixtures
ts = [tile(os.path.join(lab, f), 300, 220, f) for f in sorted(os.listdir(lab)) if f.endswith(".jpg")]
grid(ts, 6, os.path.join(dst, "fixtures.jpg"))
# Tilt Shift band placements
ts = []
for k in photos:
    for tag in ["def", "lowband", "diag", "narrowsoft", "wide"]:
        ts.append(tile(P(f"TILT_SHIFT__{k}__{tag}"), 300, 200, f"{k} {tag}"))
if ts: grid(ts, 5, os.path.join(dst, "TILT_SHIFT_5_bands.jpg"))
