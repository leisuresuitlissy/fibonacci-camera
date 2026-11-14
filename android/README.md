# Fibonacci Camera — Android

Native CameraX build of the Shoot screen.

- 4:3 viewfinder, letterboxed so the guide sits exactly on what gets captured
- Guides: spiral (stretched to the frame, eye in any of four quarters), thirds, φ grid,
  golden triangles, diagonals, dynamic symmetry, centre, 4:5 / 1:1 crop marks
- Tap a subject: it's focused on and tracked (patch matching, no model). When it lands on the
  spiral's eye or a power point, the pill goes gold and the phone gives a confirm haptic
- Coach mode: one line telling you which way to pan/tilt, plus level from the gravity sensor
- Live aperture, shutter and ISO from the camera's capture results; zoom chips from the camera's
  real range, including the ultra-wide where the camera exposes it
- Photos save full-resolution to Pictures/Fibonacci Camera

**Install:** https://github.com/leisuresuitlissy/fibonacci-camera/releases/download/android-latest/fibonacci-camera.apk
— built by GitHub Actions on every push to `android/`. Signed with the committed `fibcam.keystore`
so each build installs over the last.

Not in this build yet: the Crop screen (true-φ crop, straighten dial).
