# Writes the lab's render list: for every look, its default plus each control at 0 and 100% on the desk
# scene; defaults and Intensity 100% on the photo fixtures; Tilt Shift band placements on the outdoor ones.
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from looks import LOOKS
lab = sys.argv[1]
have = set(os.listdir(lab))
photos = [f for f in ["k04.jpg", "k07.jpg", "k13.jpg", "k16.jpg", "k19.jpg", "k22.jpg", "k23.jpg", "k24.jpg", "k08.jpg", "k11.jpg"] if f in have]
out = []
for f in ["desk.jpg"] + photos:
    out.append(f"ref__{f[:-4]}|{f}|OFF|")
for m, slots in LOOKS.items():
    out.append(f"{m}__desk__def|desk.jpg|{m}|")
    for s in slots:
        for v in (0, 100):
            out.append(f"{m}__desk__s{s}_{v}|desk.jpg|{m}|{s}={v}")
    for f in photos:
        out.append(f"{m}__{f[:-4]}__def|{f}|{m}|")
        out.append(f"{m}__{f[:-4]}__i100|{f}|{m}|0=100")
# Tilt Shift band placements on outdoor scenes
for f in photos:
    for tag, kv in [("lowband", "1=62"), ("diag", "3=70"), ("narrowsoft", "2=20,4=100"), ("wide", "2=80")]:
        out.append(f"TILT_SHIFT__{f[:-4]}__{tag}|{f}|TILT_SHIFT|{kv}")
# scale check: the desk at saved-photo size
for m in LOOKS:
    out.append(f"{m}__deskbig__def|deskbig.jpg|{m}|")
print("\n".join(out))
