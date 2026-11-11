# Fibonacci Camera

Golden-ratio framing guides over a live viewfinder.

**<https://leisuresuitlissy.github.io/fibonacci-camera/>**

- **`index.html`** — the live-camera version, served at the link above. One
  self-contained file. Needs **https://** (or `localhost`) or the camera stays off.
- **`no-camera.html`** — a sandboxed build with no live viewfinder. Same guide
  engine, works on photos taken with the system camera or picked from the library.

## Putting it on your phone

The camera API refuses to run on `file://`. Opening the file by double-clicking it
will show everything except the viewfinder, and the page says so on screen.

**Drag-and-drop host (fastest).** Go to <https://app.netlify.com/drop> and drag
`index.html` onto the page. You get an `https://…netlify.app` address in a few
seconds. Open that on your phone.

**GitHub Pages.** Put the file in a repo, Settings → Pages → deploy from `main`,
then open `https://<you>.github.io/<repo>/`.

**Cloudflare Pages / Vercel.** Both take a drag-and-drop or a repo and give HTTPS.

Once it is loading over HTTPS, use **Share → Add to Home Screen** on the phone. It
then opens full-screen with no browser chrome, like an app.

### Testing locally

From the folder holding the file:

```
python3 -m http.server 8731
```

Then open <http://localhost:8731/>. `localhost` counts as a secure origin, so the
camera works there. That address will **not** work from a phone — the phone needs
the HTTPS host above.

## Using it

| Gesture | What it does |
|---|---|
| Pinch, in the viewfinder | Zooms the lens, where the lens supports it |
| Pinch, on a photo | Resizes the guide — no lens to zoom once the shot is taken |
| Twist two fingers, on a photo | Rotates the guide |
| Drag inside the guide | Moves it — put the eye on your subject |
| Drag a corner | Sizes it, uniformly only |
| Drag the top handle | Rotates it, pivoting on the eye |
| Double-tap | Steps through the eight orientations |

Rotation grips the cardinals: within 3° of 0/90/180/270 it snaps, so square is
reachable without fighting it.

The lock hides the handles and holds the guide still. It never engages itself.

**Overlays:** off, φ grid (0.382 / 0.618), the spiral, golden triangles, thirds.
Only the spiral is placeable; the others are fixed to the frame.

**Saving** goes through the system share sheet where the browser offers one, so on
iOS "Save Image" puts it straight in Photos. Otherwise it downloads.
`Save φ crop` crops to the guide's φ rectangle and straightens any rotation in the
same step. `Save full frame` (in the Adjust sheet) keeps the whole photo and draws
the guide onto it.

## Things worth knowing

- The viewfinder is **letterboxed, not filled**. You see the whole sensor frame, so
  what you compose is exactly what gets captured. A filled preview would crop
  invisibly and the guide would lie.
- The default guide is **92%**, not 100%. The φ rectangle is 3.4% taller than the
  spiral's own bounding box, so a guide fitted flush to the frame would push its
  crop off the edge of the photo.
- The spiral's bounding box is **1.565:1** at 2.25 turns, *not* φ. A finite arc of a
  true logarithmic spiral is not a golden rectangle. The crop rectangle is built as
  an exact φ rectangle with the spiral inscribed in it.
- Your orientation, turns, size and lock are remembered between visits. Position is
  not — it belongs to a subject, and the next photo has a different one.
