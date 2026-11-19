# The looks and the slots their controls drive (mirrors ArtMode.controls in Art.kt; Depth is left out
# so the lab judges each look on its own). Slot 5 variations are listed separately.
LOOKS = {
    "HOLGA": [0, 2, 8, 3, 6],
    "PINHOLE": [0, 1, 8],
    "TILT_SHIFT": [0, 1, 2, 3, 4, 8, 7],
    "GRAINY_FILM": [0, 1, 2, 3, 8],
    "CROSS_PROCESS": [0, 1, 2, 8],
    "DREAM_LENS": [0, 1, 2, 8],
    "INFRARED": [0, 1, 8],
    "CYANOTYPE": [0, 1, 2, 8],
    "BLEACH_BYPASS": [0, 1, 2, 8],
    "KINO": [0, 1, 2, 3, 8],
    "EXPIRED": [0, 1, 2, 8],
    "REDSCALE": [0, 1, 2, 8],
    "SOLARISED": [0, 1, 2, 3, 8],
    "INSTANT": [0, 1, 2, 6, 8],
}
NAMES = {
    "HOLGA": {0: "Intensity", 2: "Softness", 8: "Vignette", 3: "Leaks", 6: "Saturation"},
    "PINHOLE": {0: "Intensity", 1: "Softness", 8: "Vignette"},
    "TILT_SHIFT": {0: "Intensity", 1: "Focus", 2: "Width", 3: "Angle", 4: "Feather", 8: "Vignette", 7: "Depth"},
    "GRAINY_FILM": {0: "Intensity", 1: "Grain", 2: "Contrast", 3: "Saturation", 8: "Vignette"},
    "CROSS_PROCESS": {0: "Intensity", 1: "Colour", 2: "Contrast", 8: "Vignette"},
    "DREAM_LENS": {0: "Intensity", 1: "Glow", 2: "Softness", 8: "Vignette"},
    "INFRARED": {0: "Intensity", 1: "Contrast", 8: "Vignette"},
    "CYANOTYPE": {0: "Intensity", 1: "Contrast", 2: "Paper", 8: "Vignette"},
    "BLEACH_BYPASS": {0: "Intensity", 1: "Saturation", 2: "Contrast", 8: "Vignette"},
    "KINO": {0: "Intensity", 1: "Halation", 2: "Coolness", 3: "Grain", 8: "Vignette"},
    "EXPIRED": {0: "Intensity", 1: "Age", 2: "Grain", 8: "Vignette"},
    "REDSCALE": {0: "Intensity", 1: "Tone", 2: "Grain", 8: "Vignette"},
    "SOLARISED": {0: "Intensity", 1: "Reversal", 2: "Threshold", 3: "Contour", 8: "Vignette"},
    "INSTANT": {0: "Intensity", 1: "Warmth", 2: "Fade", 6: "Texture", 8: "Vignette"},
}
