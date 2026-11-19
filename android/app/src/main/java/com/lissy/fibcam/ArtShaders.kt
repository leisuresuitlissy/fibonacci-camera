package com.lissy.fibcam

/**
 * Art modes as AGSL. The same source runs live on the viewfinder (as a RenderEffect), on the
 * full-resolution photo (offscreen) and, translated to Metal by tools/gen_metal.py, on iOS — so the
 * saved picture matches what you saw.
 *
 * Shared uniforms:
 *   org, res  – the photo's top-left and size (the frame the look is measured against, never the screen)
 *   amt       – the intensity slider, 0..1. Each mode maps it through its own curve (A) so 50% looks right.
 *   p1..p3    – the mode's own sliders, 0..1
 *   seed      – per-photo randomness (leak placement, grain). Stable until the next shot.
 *   msk, msz  – the focus mask: centre (x, y in 0..1 of the frame), angle (radians), type
 *               (0 none, 1 band, 2 radial); size (half-width or radius, in frame heights), feather, strength
 *   lks       – Holga leak style: 0 off, 1 warm, 2 cool, 3 neutral, 4 mixed, 5 random
 *
 * Every mode starts with `if (!inFrame(p)) return` so letterboxing is never touched, and every curve
 * returns the untouched photo at 0%.
 */
object ArtShaders {
    const val COMMON = """
uniform shader content;
uniform float2 org;
uniform float2 res;
uniform float amt;
uniform float p1;
uniform float p2;
uniform float p3;
uniform float p4;
uniform float vgn;
uniform float seed;
uniform float4 msk;
uniform float3 msz;
uniform float lks;

float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233)) + seed * 17.0) * 43758.5453); }
float3 satur(float3 c, float s) { return mix(float3(luma(c)), c, s); }
// Vignette, shared by every Look: 0 = none; darkens smoothly from the middle to the frame's corners,
// up to nearly black at 100%. It follows Intensity (none at 0, full from 50% up).
float4 vignette(float2 p, float4 c) {
    if (vgn <= 0.0001) return c;
    float2 d = (p - org) / res - 0.5;
    float e = length(d * 2.0) * 0.7071;
    float k = vgn * min(1.0, amt * 2.0) * 0.95 * pow(smoothstep(0.18, 1.0, e), 1.3);
    return float4(c.rgb * (1.0 - k), c.a);
}
float3 contrast(float3 c, float k) { return clamp((c - 0.5) * k + 0.5, 0.0, 1.0); }
float3 scurve(float3 c, float k) { float3 x = clamp(c, 0.0, 1.0); return clamp(mix(x, x * x * (3.0 - 2.0 * x), k), 0.0, 1.0); }
float3 screen(float3 a, float3 b) { return 1.0 - (1.0 - a) * (1.0 - clamp(b, 0.0, 1.0)); }
float4 px(float2 p) { return float4(content.eval(p)); }
float2 uvOf(float2 p) { return (p - org) / res; }
bool inFrame(float2 p) { float2 u = uvOf(p); return u.x >= 0.0 && u.y >= 0.0 && u.x <= 1.0 && u.y <= 1.0; }
float edgeOf(float2 p) {
    float2 d = (uvOf(p) - 0.5) * float2(res.x / res.y, 1.0);
    return length(d) / length(float2(0.5 * res.x / res.y, 0.5));
}
float vig(float2 p, float s) { return 1.0 - s * smoothstep(0.25, 1.05, edgeOf(p)); }
// Disc blur: 24 taps on a golden-angle spiral, rotated per pixel so large radii stay smooth.
float3 blurAt(float2 p, float r) {
    float3 acc = px(p).rgb;
    float j = hash(p) * 6.2831853;
    for (int i = 0; i < 24; i++) {
        float fi = float(i);
        float a = fi * 2.3999632 + j;
        float k = sqrt((fi + 0.5) / 24.0);
        acc += px(p + float2(cos(a), sin(a)) * r * k).rgb;
    }
    return acc / 25.0;
}
// A wider, smoother disc for big lens blur (Tilt Shift): 40 taps.
float3 blurWide(float2 p, float r) {
    float3 acc = px(p).rgb;
    float j = hash(p + 3.3) * 6.2831853;
    for (int i = 0; i < 40; i++) {
        float fi = float(i);
        float a = fi * 2.3999632 + j;
        float k = sqrt((fi + 0.5) / 40.0);
        acc += px(p + float2(cos(a), sin(a)) * r * k).rgb;
    }
    return acc / 41.0;
}
// The focus mask: 0 inside the sharp region, rising smoothly to 1 across the feather.
// Measured in frame heights from the mask's centre (radial) or centre line (band).
// Tilt Shift's band (type 3) takes Feather as a share of the room left between the sharp band and the
// frame's far corner, so even full Feather leaves a fully blurred region (20% of the room at 0, 72% at 100%).
float bandFeather() {
    float a = res.x / res.y;
    float2 n = float2(-sin(msk.z), cos(msk.z));
    float2 c = (msk.xy - 0.5) * float2(a, 1.0);
    float far = max(max(abs(dot(float2(-0.5 * a, -0.5) - c, n)), abs(dot(float2(0.5 * a, -0.5) - c, n))),
                    max(abs(dot(float2(-0.5 * a, 0.5) - c, n)), abs(dot(float2(0.5 * a, 0.5) - c, n))));
    return max(far - msz.x, 0.05) * (0.2 + 0.52 * msz.y);
}
float maskSoft(float2 p) {
    if (msk.w < 0.5) return 0.0;
    float2 d = (uvOf(p) - msk.xy) * float2(res.x / res.y, 1.0);
    bool band = msk.w < 1.5 || msk.w > 2.5;
    float dist = band ? abs(dot(d, float2(-sin(msk.z), cos(msk.z)))) : length(d);
    float f = msk.w > 2.5 ? bandFeather() : 0.03 + msz.y * 0.55;
    float t = clamp((dist - msz.x) / f, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
// Tilt Shift: the blur radius at full distance from the band (Depth × Intensity), shared with Kotlin's TiltFx.
float tiltRamp() { return amt < 0.5 ? amt * 2.0 : 1.0 + (amt - 0.5) * 0.5; }
float tiltR() { return min(res.x, res.y) * 0.11 * msz.z * tiltRamp(); }
// Tilt Shift's miniature grade: a little more colour, and crisper local contrast only where it's sharp.
float3 tiltGrade(float2 p, float3 c, float soft) {
    float k = amt;
    float3 local = blurAt(p, min(res.x, res.y) * 0.004);
    c = clamp(c + (c - local) * 0.4 * k * (1.0 - soft), 0.0, 1.0);
    // vibrance: muted colours gain more than ones that are already strong (a red lid stays in check)
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    return satur(scurve(c, 0.15 * k), 1.0 + 0.32 * k * (1.0 - sat));
}
// Depth: gently softer away from the sharp region, like a fast lens. msz.z is the strength.
float depthR(float2 p) {
    if (msz.z <= 0.001) return 0.0;
    float s = maskSoft(p);
    return s * s * msz.z * 0.03 * min(res.x, res.y);
}
float3 focusAt(float2 p, float3 c) {
    float r = depthR(p);
    return r < 0.6 ? c : blurAt(p, r);
}
// Highlights spread out, with a smooth threshold: a halo around bright things, never a wash.
float3 bloomAt(float2 p, float r, float thr) {
    float3 acc = float3(0.0);
    float j = hash(p + 1.7) * 6.2831853;
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float a = fi * 2.3999632 + j;
        float k = sqrt((fi + 0.5) / 16.0);
        float3 s = px(p + float2(cos(a), sin(a)) * r * k).rgb;
        acc += s * smoothstep(thr, thr + 0.22, luma(s));
    }
    return acc / 16.0;
}
float grainAt(float2 p) {
    float g = max(1.0, res.y / 900.0);
    return hash(floor(p / g)) - 0.5;
}
float vnoise(float2 x) {
    float2 i = floor(x); float2 f = fract(x); f = f * f * (3.0 - 2.0 * f);
    float a = hash(i); float b = hash(i + float2(1.0, 0.0)); float c = hash(i + float2(0.0, 1.0)); float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
// Film grain that clumps a little and sits mostly in the midtones; sized to the frame so preview and export match.
float filmGrain(float2 p, float l) {
    float g = max(1.0, res.y / 900.0);
    float fine = hash(floor(p / g)) - 0.5;
    float clump = vnoise(p / (g * 2.6)) - 0.5;
    return (fine * 0.75 + clump * 0.9) * (1.0 - abs(l - 0.45) * 1.1);
}
// A smooth contrast curve around mid-grey that never clips: k is the steepness (near 1 = unchanged,
// 3 = punchy) and the ends roll off instead of crushing, so dark and bright detail survives.
float thn(float x) { float e = exp(2.0 * clamp(x, -9.0, 9.0)); return (e - 1.0) / (e + 1.0); }
float tcurve(float x, float k) { k = max(k, 0.05); return 0.5 + 0.5 * thn(k * (x - 0.5)) / thn(k * 0.5); }
float3 tcurve3(float3 c, float k) { return float3(tcurve(c.r, k), tcurve(c.g, k), tcurve(c.b, k)); }
// Highlight shoulder: unchanged below k, then eases towards 1 without reaching it.
float shoulder(float x, float k) { return x < k ? x : k + (1.0 - k) * (1.0 - exp(-(x - k) / (1.0 - k))); }
float3 shoulder3(float3 c, float k) { return float3(shoulder(c.r, k), shoulder(c.g, k), shoulder(c.b, k)); }
// Fine film grain: smaller than filmGrain and softly correlated, strongest in the midtones and fading to
// nothing in pure black and white, so it never reads as static on bright areas or shifts the exposure.
float fineGrain(float2 p, float l) {
    float g = max(1.0, res.y / 1400.0);
    float2 q = floor(p / g);
    float n = (hash(q) + hash(q + float2(1.0, 0.0)) * 0.5 + hash(q + float2(0.0, 1.0)) * 0.5) * 0.5 - 0.5;
    float clump = vnoise(p / (g * 1.8)) - 0.5;
    float w = pow(clamp(4.0 * l * (1.0 - l), 0.0, 1.0), 0.6);
    return (n * 1.1 + clump * 0.6) * w;
}
"""

    val BODIES: Map<ArtMode, String> = mapOf(
        // Intensity drives the blur; the mask (band by default, or the guide's shape) says where it's sharp.
        ArtMode.TILT_SHIFT to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    // Single-pass version (photo editor preview, iOS). On Android the camera and saved photos use TiltFx:
    // the same band, radius and grade, with a layered GPU blur. Blur grows from 0 at the band's edge to
    // tiltR() at the end of the feather; Intensity scales the blur and the grade, never a ghost of the original.
    float soft = maskSoft(p);
    float r = pow(soft, 1.5) * 1.15 * tiltR();
    float3 base = r < 0.6 ? o.rgb : blurWide(p, r);
    return half4(vignette(p, float4(tiltGrade(p, base, soft), o.a)));
}
""",
        // A plastic lens on a square camera: soft, uneven edges, colour fringing, a vignette that isn't
        // perfectly round, film colour, grain and the odd light leak creeping in from an edge.
        ArtMode.HOLGA to """
float3 leakColour(float style, float along, float depth, float pick) {
    float3 warm = mix(float3(1.0, 0.36, 0.10), float3(1.0, 0.72, 0.30), along);
    float3 cool = mix(float3(0.20, 0.55, 1.0), float3(0.45, 0.92, 1.0), along);
    float3 neut = float3(1.0, 0.95, 0.84);
    if (style < 1.5) return warm;
    if (style < 2.5) return cool;
    if (style < 3.5) return neut;
    if (style < 4.5) return mix(warm, cool, smoothstep(0.25, 0.9, depth));
    float3 a = pick < 0.33 ? warm : (pick < 0.66 ? float3(1.0, 0.30, 0.42) : cool);
    float3 b = pick < 0.5 ? neut : float3(1.0, 0.62, 0.20);
    return mix(a, b, along * 0.6);
}
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = 1.0 - pow(1.0 - amt, 1.5);
    float s = min(res.x, res.y);
    float2 sq = (p - org - 0.5 * (res - float2(s))) / s;
    float inside = step(0.0, sq.x) * step(sq.x, 1.0) * step(0.0, sq.y) * step(sq.y, 1.0);
    float2 cd = sq - 0.5;
    float wob = vnoise(sq * 3.0 + seed) - 0.5;
    float edge = length(cd) * 1.414 * (1.0 + wob * 0.18);
    // Softness: the plastic lens. Barely any in the middle, rising steeply towards the edges, so the
    // subject stays readable even when the corners smear. 0 = none; it adds to Depth's blur.
    float ef = smoothstep(0.3, 1.1, edge);
    float rad = s * p2 * (0.0012 + 0.022 * ef * ef) * (0.35 + 0.65 * a);
    float dr = depthR(p);
    float rr = sqrt(rad * rad + dr * dr);
    float3 c = rr < 0.6 ? o.rgb : blurAt(p, rr);
    // Colour fringing in the outer edge only, taken from the same softened picture (never a sharp ghost).
    float fr = smoothstep(0.6, 1.15, edge);
    if (fr > 0.01) {
        float ca = s * 0.0035 * fr * a;
        c.r = mix(c.r, blurAt(p + cd * ca, rr).r, 0.5);
        c.b = mix(c.b, blurAt(p - cd * ca, rr).b, 0.5);
    }
    // The toy-camera print: punchy midtones without clipping, cyan-green shadows, warm yellowish highlights.
    c = tcurve3(c, 1.0 + 2.6 * a);
    float l = luma(c);
    c += (float3(-0.06, 0.035, 0.085) * (1.0 - l) * (1.0 - l) + float3(0.08, 0.045, -0.08) * l * l) * a * 1.25;
    // Saturation (p4): 50% sits just under the original (the curve already adds punch); 0 muted, 100% rich.
    c = satur(c, 1.0 + (0.45 + p4 - 1.0) * a);
    // Imperfect exposure: a hot centre and uneven density across the frame.
    c *= (1.0 + 0.14 * a * (1.0 - smoothstep(0.0, 0.8, edge))) * (1.0 + (vnoise(sq * 2.2 + seed * 1.7) - 0.5) * 0.16 * a);
    // The lens barely covers the frame: an irregular fall-off on top of the round Vignette (and tied to it,
    // so Vignette 0 really means none).
    c *= 1.0 - 0.5 * a * min(1.0, vgn / 0.3) * pow(smoothstep(0.45, 1.3, edge), 1.4);
    c += fineGrain(p, luma(c)) * 0.05 * a;
    if (lks > 0.5 && p3 > 0.001) {
        float side = floor(hash(float2(seed, 3.0)) * 4.0);
        float2 q = side < 1.0 ? sq : (side < 2.0 ? float2(sq.y, 1.0 - sq.x) : (side < 3.0 ? float2(1.0 - sq.x, 1.0 - sq.y) : float2(1.0 - sq.y, sq.x)));
        float along = q.x;
        float reach = 0.10 + 0.22 * hash(float2(seed, 5.0));
        float ragged = (vnoise(float2(along * 4.0, seed)) - 0.5) * 0.14 + (vnoise(q * 9.0 + seed) - 0.5) * 0.05;
        float d = max(q.y + ragged, 0.0) / reach;
        float body = exp(-pow(d, 1.6)) * (0.45 + 0.55 * vnoise(float2(along * 2.2, seed * 1.3)));
        float2 core = float2(0.15 + 0.7 * hash(float2(seed, 9.0)), 0.0);
        body += 0.55 * exp(-pow(length((q - core) * float2(1.4, 3.2)) * 3.2, 2.0));
        float corner = exp(-pow(length(1.0 - q) * 2.6, 2.0)) * step(0.5, hash(float2(seed, 11.0)));
        float pick = fract(seed * 0.618);
        float3 lc = leakColour(lks, along, d, pick) * body + leakColour(lks, 1.0 - along, 0.9, pick) * corner * 0.6;
        c = screen(c, lc * p3 * (0.55 + 0.45 * a));
    }
    // Holga saves a square. Outside it the viewfinder keeps the picture, a little dimmer, rather than going
    // black, so the controls over the top and bottom of the viewfinder still sit on the picture.
    float3 fx = clamp(c, 0.0, 1.0) * mix(0.72, 1.0, inside);
    return half4(vignette(p, float4(mix(o.rgb, fx, a), o.a)));
}
""",
        // A lensless camera: soft all over, softer and darker towards the edges, slightly muted, fine grain.
        ArtMode.PINHOLE to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = amt;
    float s = min(res.x, res.y);
    float e = edgeOf(p);
    // Softness: a pinhole is soft all over, but here only lightly in the middle and much more towards the
    // rim, so the subject stays legible. 0 = none; it adds to Depth's blur.
    float rad = s * p1 * (0.0008 + 0.02 * pow(smoothstep(0.2, 1.0, e), 2.0)) * (0.3 + 0.7 * a);
    float dr = depthR(p);
    float rr = sqrt(rad * rad + dr * dr);
    float3 c = rr < 0.6 ? o.rgb : blurAt(p, rr);
    // Subdued tonality: muted, warm, nearly monochrome colour; gentle contrast with soft, lifted blacks.
    c = satur(c, 1.0 - 0.75 * a);
    c *= mix(float3(1.0), float3(1.05, 1.0, 0.88), a);
    c = mix(c, tcurve3(c, 0.6), a);
    c = c * (1.0 - 0.05 * a) + 0.035 * a;
    // Uneven exposure: patchy density from the long exposure, and the round cos⁴ fall-off of a pinhole,
    // tied to Vignette so 0 really means none.
    c *= 1.0 + (vnoise(uvOf(p) * 1.8 + seed * 2.3) - 0.5) * 0.16 * a;
    float f4 = pow(cos(atan(e * 1.25)), 4.0);
    c *= mix(1.0, f4, 0.7 * a * min(1.0, vgn / 0.3));
    c += fineGrain(p, luma(c)) * 0.035 * a;
    return half4(vignette(p, float4(mix(o.rgb, clamp(c, 0.0, 1.0), min(1.0, a * 2.0)), o.a)));
}
""",
        // Pushed black-and-white film: colour drains away as intensity rises, contrast and organic grain build.
        ArtMode.GRAINY_FILM to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    float l0 = luma(o.rgb);
    // Contrast (p2): a steep curve that rolls off at both ends instead of clipping, and keeps a little of
    // the original separation in the deep shadows so dark surfaces hold their texture.
    float l = tcurve(l0, 1.2 + (1.4 + 4.4 * p2) * a);
    l = mix(l, l0, 0.2 * (1.0 - smoothstep(0.0, 0.35, l0)));
    // Grain (p1): fine, film-like, strongest in the midtones and gone in pure black and white.
    l = clamp(l + fineGrain(p, l) * (0.04 + p1 * 0.24) * a, 0.0, 1.0);
    float3 mono = float3(l);
    // Saturation (p3): 0 is black and white; higher brings the scene's own colour back under the same
    // contrast curve and grain, up to a little richer than the original at 100%.
    float3 col = clamp(o.rgb * (l / max(l0, 0.02)), 0.0, 1.0);
    col = satur(col, 0.6 + 0.6 * p3);
    float3 film = mix(mono, col, smoothstep(0.0, 1.0, p3));
    return half4(vignette(p, float4(mix(o.rgb, film, pow(a, 0.7)), o.a)));
}
""",
        // Film in the wrong chemistry: yellow-green highlights, blue-cyan shadows, a film-like curve.
        ArtMode.CROSS_PROCESS to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    float3 c = o.rgb;
    float k = 0.55 + 0.45 * a - 0.2 * max(a - 0.5, 0.0);
    float3 x = float3(mix(c.r, smoothstep(0.0, 0.95, c.r), k), clamp(c.g * (1.0 + 0.08 * k) + 0.03 * k, 0.0, 1.0), mix(c.b, c.b * 0.6 + 0.15, k));
    x.g = mix(x.g, smoothstep(0.0, 1.0, x.g), 0.5 * k);
    float l = luma(c);
    float bias = (p1 - 0.5) * 2.0;
    x += float3(-0.05, 0.06, 0.02) * (1.0 - l) * (1.0 + bias) * k;
    x += float3(0.07, 0.05, -0.09) * l * k;
    x += float3(0.05, -0.05, 0.07) * max(-bias, 0.0) * (1.0 - l) * k;
    // Contrast (p2) and saturation grow with Intensity but level off: the curve never clips, and a highlight
    // shoulder keeps pale areas from collapsing into flat yellow.
    x = tcurve3(x, 0.3 + 1.3 * a + (p2 - 0.5) * 2.0 * a);
    x = satur(x, 1.0 + 0.3 * a * (1.0 - 0.4 * a));
    x = shoulder3(x, 0.8);
    return half4(vignette(p, float4(mix(c, clamp(x, 0.0, 1.0), pow(a, 0.8)), o.a)));
}
""",
        // A diffusion filter: the picture stays sharp underneath; light from bright things spills softly
        // into its surroundings, local contrast relaxes. No overall brightening.
        ArtMode.DREAM_LENS to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = 1.0 - pow(1.0 - amt, 1.4);
    float s = min(res.x, res.y);
    float3 base = focusAt(p, o.rgb);
    // Softness (p2): a soft-focus lens keeps the sharp picture and adds a diffuse halo, so light bleeds into
    // darker surroundings (a lighten blend) while edges and lettering stay readable. 0 = none.
    float3 soft = blurAt(p, s * (0.004 + p2 * 0.012));
    float3 c = mix(base, max(base, soft), p2 * (0.35 + 0.25 * a));
    c = mix(c, soft, 0.1 * p2 * a);
    // Glow (p1): bloom out of the brightest areas into what's around them, not piled onto areas that are
    // already pale (which is what turned them to fog).
    float3 halo = bloomAt(p, s * (0.016 + p1 * 0.03), 0.58);
    float room = 1.0 - luma(c);
    c = screen(c, halo * (0.3 + 1.0 * p1) * a * (0.3 + 0.7 * room));
    c = mix(c, c * 0.97 + 0.012, a);
    c = satur(c, 1.0 - 0.06 * a);
    return half4(vignette(p, float4(clamp(c, 0.0, 1.0), o.a)));
}
""",
        // Infrared-inspired: foliage glows, blue skies go dark, smooth tonal separation, optional halation.
        ArtMode.INFRARED to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    float3 c = o.rgb;
    float l = luma(c);
    float veg = smoothstep(-0.06, 0.24, c.g - 0.52 * (c.r + c.b)) * smoothstep(0.03, 0.3, l);
    float sky = smoothstep(-0.04, 0.26, c.b - 0.5 * (c.r + c.g)) * smoothstep(0.08, 0.45, l);
    float ir = 0.42 * c.r + 0.5 * c.g + 0.08 * c.b;
    // Foliage glows: brighter by a share of its own value, so leaves keep their differences.
    ir = ir * (1.0 + veg * (0.6 + 0.4 * l)) + veg * (0.16 + 0.18 * l);
    ir *= 1.0 - 0.55 * sky;
    // Roll the highlights off before white, then a contrast curve (p1) that never clips at either end.
    ir = tcurve(shoulder(ir, 0.7), 1.4 + p1 * 3.2);
    // Put back fine detail (leaf veins, the edges between overlapping leaves) the tone curve compressed.
    float det = l - luma(blurAt(p, min(res.x, res.y) * 0.006));
    ir += det * (0.45 + 0.5 * veg);
    // Halation: a soft glow, held back on areas that are already near white.
    float glow = luma(bloomAt(p, min(res.x, res.y) * 0.02, 0.62));
    ir = 1.0 - (1.0 - ir) * (1.0 - glow * p2 * 0.7 * clamp(1.0 - ir, 0.0, 1.0));
    // Intensity: most of the way to infrared by 50%, fully there at 100%.
    return half4(vignette(p, float4(mix(c, float3(clamp(ir, 0.0, 1.0)), 1.0 - pow(1.0 - a, 2.5)), o.a)));
}
""",
        // A cyanotype print: Prussian-blue shadows, warm paper whites, a little paper grain.
        ArtMode.CYANOTYPE to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    // Contrast (p1): a curve that never clips, worked on a lifted luminance so dark objects keep blue-on-blue
    // separation, scratches and edges instead of filling in.
    float l = tcurve(pow(luma(o.rgb), 0.8), 1.2 + (2.2 + 3.2 * p1) * a);
    // Prussian blue print: deep navy through blue to the paper, in four stops so the darks still separate.
    float3 dk = float3(0.02, 0.10, 0.31);
    float3 sh = float3(0.04, 0.20, 0.47);
    float3 md = float3(0.18, 0.42, 0.68);
    // Paper (p2): cool white at 0, warm cream at 100%, and how much texture and fibre it carries.
    float3 pp = mix(float3(0.96, 0.96, 0.94), float3(0.95, 0.90, 0.77), p2);
    float3 c = l < 0.3 ? mix(dk, sh, l / 0.3) : (l < 0.62 ? mix(sh, md, (l - 0.3) / 0.32) : mix(md, pp, (l - 0.62) / 0.38));
    float g = res.y / 160.0;
    float n = vnoise(p / g) * 0.6 + vnoise(p / (g * 0.35)) * 0.4;
    float fib = vnoise(float2(p.x / (g * 0.12), p.y / (g * 2.5)));
    c *= 1.0 + ((n - 0.5) * 0.16 + (fib - 0.5) * 0.06) * p2 * (0.45 + 0.55 * l);
    c *= vig(p, 0.12);
    return half4(vignette(p, float4(mix(o.rgb, clamp(c, 0.0, 1.0), pow(a, 0.8)), o.a)));
}
""",
        // Bleach bypass: colour pulled back, silvery highlights, dense but not crushed blacks.
        ArtMode.BLEACH_BYPASS to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = 1.0 - pow(1.0 - amt, 1.3);
    float3 c = o.rgb;
    float3 lum = float3(luma(c));
    float3 ov = mix(2.0 * c * lum, 1.0 - 2.0 * (1.0 - c) * (1.0 - lum), step(float3(0.5), lum));
    float3 x = mix(lum, ov, 0.12 + p1 * 0.6);
    // Hard photographic contrast (p2) from a curve that never clips, and a toe instead of a cut-off black,
    // so deep shadows stay dense but keep a trace of texture.
    x = tcurve3(x, 1.2 + p2 * 2.2);
    x = x * x / (x + 0.02) * 1.02;
    x = mix(x, 1.0 - (1.0 - x) * (1.0 - x) * 1.02, 0.12);
    x *= float3(0.98, 1.0, 1.03);
    x += filmGrain(p, luma(x)) * 0.03;
    return half4(vignette(p, float4(mix(c, clamp(x, 0.0, 1.0), a), o.a)));
}
""",
        // Tungsten cinema film at night: cool surroundings, warm lights, a smooth shoulder and red halation
        // around strong light sources (only where bright meets dark), fine grain.
        ArtMode.KINO to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = 1.0 - pow(1.0 - amt, 1.3);
    float s = min(res.x, res.y);
    float3 c = focusAt(p, o.rgb);
    float l = luma(c);
    float warmLight = smoothstep(0.45, 0.85, l) * smoothstep(0.02, 0.2, c.r - c.b);
    float shadow = pow(1.0 - l, 2.0);
    // Coolness (p2): 0 leaves the shadows neutral, 50% is the look's teal, 100% about twice that.
    float cool = 1.35 * p2 * a * (1.0 - warmLight);
    c += float3(-0.10, 0.01, 0.11) * shadow * cool;
    c += float3(-0.05, 0.0, 0.045) * (1.0 - shadow) * (1.0 - l) * cool;
    c = mix(c, c * float3(0.93, 0.98, 1.06), clamp(cool * (1.0 - l) * 0.8, 0.0, 1.0));
    c += float3(0.05, 0.015, -0.035) * warmLight * a;
    c = satur(c, 1.0 - 0.18 * a + 0.25 * warmLight * a);
    c = mix(c, smoothstep(float3(-0.04), float3(1.1), c), 0.7 * a);
    // Halation (p1): 0 = none, up to about twice the default; a red glow that spreads around bright edges
    // and stays off the bright areas themselves, so pale keys keep their edges.
    float3 b = bloomAt(p, s * (0.012 + 0.035 * p1), 0.66);
    float near = luma(bloomAt(p, s * 0.01, 0.66));
    float glow = max(luma(b) - near - 0.04, 0.0) * 4.6 * p1 * a;
    c = screen(c, float3(1.0, 0.28, 0.08) * glow * (0.15 + 0.85 * pow(1.0 - l, 0.7)));
    // Grain (p3): 0 = none.
    c += fineGrain(p, l) * 0.11 * p3 * a;
    return half4(vignette(p, float4(clamp(c, 0.0, 1.0), o.a)));
}
""",
        // Aged colour film: lifted, faded density; shadows, mids and highlights drifting in different
        // directions; slow patches of fog; irregular grain. The variation picks the family of drift.
        ArtMode.EXPIRED to """
float3 drift(float v, float band) {
    // band 0 shadows, 1 mids, 2 highlights; families: 0 warm, 1 magenta, 2 cool
    if (v < 0.5) return band < 0.5 ? float3(0.06, -0.02, 0.03) : (band < 1.5 ? float3(0.0, 0.03, -0.045) : float3(0.05, 0.03, -0.07));
    if (v < 1.5) return band < 0.5 ? float3(0.07, -0.035, 0.05) : (band < 1.5 ? float3(-0.01, 0.03, -0.012) : float3(0.05, 0.005, 0.0));
    return band < 0.5 ? float3(-0.04, 0.02, 0.065) : (band < 1.5 ? float3(-0.02, 0.04, 0.0) : float3(0.025, 0.025, -0.035));
}
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = amt;
    float age = p1;
    float3 c = focusAt(p, o.rgb);
    float l = luma(c);
    float k = a * (0.6 + 0.6 * age);
    float fam = lks;
    float mixFam = 0.0;
    if (fam > 2.5) { fam = floor(fract(seed * 0.381) * 3.0); mixFam = fract(seed * 0.773) * 0.6; }
    float fam2 = mod(fam + 1.0, 3.0);
    float ws = pow(1.0 - l, 1.6); float wh = smoothstep(0.55, 1.0, l); float wm = 1.0 - max(ws, wh);
    float3 d = drift(fam, 0.0) * ws + drift(fam, 1.0) * wm + drift(fam, 2.0) * wh;
    float3 d2 = drift(fam2, 0.0) * ws + drift(fam2, 1.0) * wm + drift(fam2, 2.0) * wh;
    float2 uv = uvOf(p);
    // The damage is uneven: some parts of the print faded and shifted much more than others.
    float blotch = vnoise(uv * float2(2.2, 2.8) + seed * 5.0);
    float uneven = 0.5 + blotch;
    float fog = vnoise(uv * float2(1.4, 1.8) + seed * 3.1);
    c = c * (1.0 - 0.15 * k * uneven) + 0.075 * k * uneven;
    c = mix(c, float3(luma(c)), 0.3 * k);
    // Flatten the contrast less in the shadows, so dark objects and fur keep their separation.
    c = mix(c, smoothstep(float3(-0.15), float3(1.15), c), -0.32 * k * mix(0.55, 1.0, smoothstep(0.1, 0.45, l)));
    c += mix(d, d2, mixFam) * pow(a, 0.8) * (1.5 + 1.1 * age) * (0.55 + 0.9 * blotch);
    // Chemical fog in patches, not one veil over everything.
    float3 fogCol = mix(drift(fam, 2.0), drift(fam2, 0.0), fog) * 4.0 + 0.6;
    c = screen(c, clamp(fogCol, 0.0, 1.0) * smoothstep(0.5, 0.95, fog) * 0.32 * age * a);
    // Grain (p2): distressed film grain, a little uneven in colour, never a uniform digital noise.
    float gm = fineGrain(p, l);
    float3 gc = float3(fineGrain(p + 17.3, l), fineGrain(p + 41.7, l), fineGrain(p + 73.1, l));
    c += mix(float3(gm), gc, 0.3) * (0.03 + 0.11 * p2) * a * (0.7 + 0.5 * age);
    return half4(vignette(p, float4(clamp(c, 0.0, 1.0), o.a)));
}
""",
        // Colour negative exposed through its base: blue suppressed, a warm ramp from deep red-brown
        // through burnt orange to gold, with source colours still pulling it (greens towards yellow, skies darker).
        ArtMode.REDSCALE to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = 1.0 - pow(1.0 - amt, 1.2);
    float3 c = focusAt(p, o.rgb);
    float L0 = 0.52 * c.r + 0.42 * c.g + 0.06 * c.b;
    float L = pow(clamp(L0, 0.0, 1.0), mix(1.3, 0.85, p1));
    float3 sh = mix(float3(0.16, 0.02, 0.015), float3(0.24, 0.06, 0.02), p1);
    float3 md = mix(float3(0.72, 0.20, 0.04), float3(0.86, 0.40, 0.08), p1);
    float3 hi = mix(float3(0.96, 0.58, 0.20), float3(0.99, 0.76, 0.38), p1);
    float3 top = float3(1.0, 0.88, 0.64);
    float3 r = mix(sh, md, smoothstep(0.0, 0.42, L));
    r = mix(r, hi, smoothstep(0.38, 0.8, L));
    r = mix(r, top, smoothstep(0.86, 1.08, L));
    r.g += 0.14 * max(c.g - c.r, 0.0) * (1.0 - L);
    r *= 1.0 - 0.25 * max(c.b - max(c.r, c.g), 0.0);
    // Things that were red stay a deeper red, distinct from the orange around them.
    float red = clamp((c.r - max(c.g, c.b)) * 2.2, 0.0, 1.0) * (1.0 - smoothstep(0.75, 1.0, L));
    r = mix(r, r * float3(1.0, 0.55, 0.6), red * 0.7);
    // Keep texture in the bright half (white surfaces, fur) instead of a flat yellow field.
    float3 bl = blurAt(p, min(res.x, res.y) * 0.004);
    float det = L0 - (0.52 * bl.r + 0.42 * bl.g + 0.06 * bl.b);
    r += det * 0.6 * smoothstep(0.45, 0.9, L);
    r = shoulder3(r, 0.86);
    r += filmGrain(p, L) * (0.01 + 0.05 * p2);
    return half4(vignette(p, float4(mix(o.rgb, clamp(r, 0.0, 1.0), a), o.a)));
}
""",
        // Darkroom solarisation (Sabattier): tones above a threshold fold back down, smoothly, giving silver
        // and charcoal with positive and negative mixed; faint Mackie lines where tones cross the fold.
        ArtMode.SOLARISED to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = amt;
    float3 c = focusAt(p, o.rgb);
    float l = luma(c);
    // Threshold (p2) is where the tones turn over; Reversal (p1) how far the tones above it fold back:
    // 0 = the photograph, 100% = a full fold.
    float t = 0.3 + 0.45 * p2;
    float fold = smoothstep(t - 0.12, t + 0.12, l);
    float y = mix(l, abs(2.0 * t - l), fold * p1);
    // Keep the photograph's own detail through the fold, so letters stay darker than their keys and a face
    // keeps its features instead of becoming a filled shape with an outline.
    float lb = luma(blurAt(p, min(res.x, res.y) * 0.003));
    y += (l - lb) * (0.7 + 0.8 * p1) * fold;
    y = clamp(y, 0.0, 1.0);
    y = smoothstep(0.02, 0.94, mix(y, pow(y, 0.85), 0.5));
    // Contour (p3): Mackie lines, thin bright edges where the image crosses the threshold.
    float d = max(1.0, res.y / 900.0) * 1.5;
    float gx = luma(px(p + float2(d, 0.0)).rgb) - luma(px(p - float2(d, 0.0)).rgb);
    float gy = luma(px(p + float2(0.0, d)).rgb) - luma(px(p - float2(0.0, d)).rgb);
    float edge = smoothstep(0.03, 0.18, length(float2(gx, gy)));
    float near = exp(-pow((l - t) / 0.1, 2.0));
    y = clamp(y + edge * near * p3 * 0.5, 0.0, 1.0);
    float3 silver = float3(y) * float3(0.97, 0.99, 1.03);
    // A trace of the original colour where the tones weren't folded.
    silver += (c - float3(l)) * 0.3 * (1.0 - fold * p1);
    float3 base = mix(o.rgb, float3(luma(o.rgb)), smoothstep(0.0, 0.7, a));
    return half4(vignette(p, float4(mix(base, clamp(silver, 0.0, 1.0), pow(a, 0.8)), o.a)));
}
""",
        // Instant film: a little soft, creamy highlights, lifted blacks with a cool cast, gentle warmth
        // in the mids, muted colour.
        ArtMode.INSTANT to """
// Vintage SX-70 instant film. Intensity blends the whole look in; Warmth turns the film's white balance from
// cool green-teal (0) through creamy (50%) to honey (100%); Fade lifts and tints the blacks, flattens contrast
// and rolls the highlights off further; Texture is fine clumping grain plus faint chemical unevenness.
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = 1.0 - pow(1.0 - amt, 1.3);
    float s = min(res.x, res.y);
    float3 base = focusAt(p, o.rgb);
    // the plastic optics: a touch of softness, never a blur (p3 stays at its calibrated 0.4)
    float3 c = mix(base, blurAt(p, s * 0.0012 * (0.6 + p3)), 0.45);
    // halation: bright areas glow warmly into their surroundings; screened, so it can't clip
    float3 halo = bloomAt(p, s * 0.024, 0.7);
    c = screen(c, halo * float3(1.0, 0.82, 0.62) * (0.12 + 0.25 * p2));
    // dye response: muted but characterful. Blues lean teal, greens lean olive, reds lean brick
    c = float3(dot(c, float3(0.90, 0.12, -0.02)), dot(c, float3(0.05, 0.88, 0.07)), dot(c, float3(0.03, 0.17, 0.80)));
    float l = luma(c);
    c = satur(c, 0.95);
    // a yellow-green shift in the midtones, green-teal in the shadows (the cream comes later, in the whites)
    float mid = clamp(1.0 - abs(l - 0.45) * 1.6, 0.0, 1.0);
    c += float3(0.008, 0.022, -0.022) * mid;
    c += float3(-0.012, 0.012, 0.016) * (1.0 - l) * (1.0 - l);
    // warmth as white balance (it multiplies, so the blacks keep their own tint)
    // (cool side leans green-teal like aged SX-70, not blue)
    float w = p1 * 2.0 - 1.0;
    c *= 1.0 + (w >= 0.0 ? float3(0.10, 0.035, -0.13) * w : float3(-0.09, 0.02, 0.05) * -w);
    c = clamp(c, 0.0, 1.0);
    // Fade (p2) is quadratic: a fresh print keeps solid midtone contrast and near-black blacks; the heavy,
    // milky fade lives at the top of the control. A shoulder rolls the highlights off either way.
    float f2 = p2 * p2;
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.35 * (1.0 - p2));
    float k = 0.15 + 0.3 * p2;
    c = c / (1.0 + c * k) * (1.0 + k);
    float fl = 0.02 + 0.22 * f2;
    c = clamp(c * (1.0 - fl) + 0.5 * fl, 0.0, 1.0);
    // lifted, tinted blacks and creamy whites that stop short of paper white
    float3 blk = mix(float3(0.07, 0.085, 0.095), float3(0.11, 0.09, 0.065), p1) * ((0.012 + 0.2 * f2) / 0.09);
    float3 wht = mix(float3(0.94, 0.955, 0.94), float3(0.985, 0.95, 0.84), p1) * (0.995 - 0.06 * f2);
    c = blk + c * (wht - blk);
    // texture: fine grain that clumps a little, and faint unevenness in the developer spread
    c += filmGrain(p, luma(c)) * (0.005 + 0.055 * p4);
    float2 u = uvOf(p) * float2(res.x / res.y, 1.0);
    c *= 1.0 + (vnoise(u * 9.0 + 3.1) - 0.5) * 0.06 * p4;
    c = mix(base, clamp(c, 0.0, 1.0), a);
    return half4(vignette(p, float4(c, o.a)));
}
""",
    )

    fun source(mode: ArtMode) = COMMON + (BODIES[mode] ?: "")

    /** TiltFx, pass 1: the frame's edge pixels repeat outward, so the blur never pulls in what lies outside the frame. */
    val TILT_CLAMP get() = COMMON + """
half4 main(float2 p) { return content.eval(clamp(p, org + 0.5, org + res - 0.5)); }
"""

    /**
     * TiltFx, a blur layer: the picture (lyr 0, sharp) or a blur level (lyr 1, 2) with the alpha that crossfades it into
     * the next stronger level as you move away from the band. Outside the frame the sharp original stays.
     */
    val TILT_LAYER get() = COMMON + """
uniform float lyr;
half4 main(float2 p) {
    float4 c = px(p);
    float t = inFrame(p) ? maskSoft(p) * 3.0 : 0.0;
    return half4(c * (1.0 - clamp(t - lyr, 0.0, 1.0)));
}
"""

    /** TiltFx, last pass: the miniature grade and the vignette over the blended layers. */
    val TILT_GRADE get() = COMMON + """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    return half4(vignette(p, float4(tiltGrade(p, o.rgb, maskSoft(p)), o.a)));
}
"""
}
