# Fixtures: the synthetic desk at phone-preview-ish and saved-photo size, Kodak photos upscaled 2x.
import os, sys, glob
from PIL import Image
lab = sys.argv[1]
d = Image.open(os.path.join(lab, "desk.png")).convert("RGB")
d.save(os.path.join(lab, "desk.jpg"), quality=95)
d.resize((3200, 2400), Image.LANCZOS).save(os.path.join(lab, "deskbig.jpg"), quality=92)
os.remove(os.path.join(lab, "desk.png"))
for f in glob.glob(os.path.join(lab, "k*.png")):
    im = Image.open(f).convert("RGB")
    im.resize((im.width * 2, im.height * 2), Image.LANCZOS).save(f[:-4] + ".jpg", quality=95)
    os.remove(f)
