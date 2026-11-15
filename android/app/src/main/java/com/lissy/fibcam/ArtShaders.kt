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
uniform float seed;
uniform float4 msk;
uniform float3 msz;
uniform float lks;

float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233)) + seed * 17.0) * 43758.5453); }
float3 satur(float3 c, float s) { return mix(float3(luma(c)), c, s); }
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
float maskSoft(float2 p) {
    if (msk.w < 0.5) return 0.0;
    float2 d = (uvOf(p) - msk.xy) * float2(res.x / res.y, 1.0);
    float dist = msk.w < 1.5 ? abs(dot(d, float2(-sin(msk.z), cos(msk.z)))) : length(d);
    float f = 0.03 + msz.y * 0.55;
    float t = clamp((dist - msz.x) / f, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
// Depth: gently softer away from the sharp region, like a fast lens. msz.z is the strength.
float depthR(float2 p) {
    if (msz.z <= 0.001) return 0.0;
    float s = maskSoft(p);
    return s * s * msz.z * 0.02 * min(res.x, res.y);
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
"""

    val BODIES: Map<ArtMode, String> = mapOf(
        // Intensity drives the blur; the mask (band by default, or the guide's shape) says where it's sharp.
        ArtMode.TILT_SHIFT to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float A = amt;
    float s = min(res.x, res.y);
    float soft = maskSoft(p);
    float r = pow(soft, 1.35) * s * 0.055 * pow(A, 1.2);
    float3 base = r < 0.6 ? o.rgb : blurWide(p, r);
    float3 c = satur(scurve(base, 0.35 * A), 1.0 + 0.32 * A);
    return half4(float4(c, o.a));
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
    float rad = s * (0.0008 + (0.006 + p2 * 0.03) * pow(smoothstep(0.2, 1.1, edge), 1.3)) * a;
    float3 c = blurAt(p, max(rad, depthR(p)));
    float ca = s * 0.005 * edge * edge * a;
    c.r = mix(c.r, px(p + cd * ca).r, 0.6);
    c.b = mix(c.b, px(p - cd * ca).b, 0.6);
    c = satur(scurve(c, 0.5 * a), 1.0 + 0.22 * a);
    float l = luma(c);
    c += (float3(-0.025, 0.02, 0.045) * (1.0 - l) + float3(0.05, 0.02, -0.04) * l) * a;
    c *= 1.0 - (0.45 + p1 * 0.55) * a * pow(smoothstep(0.28, 1.12, edge), 1.5);
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
    c += filmGrain(p, l) * (0.012 + 0.06 * a);
    float3 fx = clamp(c, 0.0, 1.0) * inside;
    return half4(float4(mix(o.rgb, fx, max(a, 1.0 - inside)), o.a));
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
    float rad = s * (0.0012 + p1 * 0.005 + e * e * (0.004 + p1 * 0.014)) * (0.3 + 0.7 * a);
    float3 c = blurAt(p, max(rad, depthR(p)));
    c = mix(c, blurAt(p, s * 0.012), 0.1 * a);
    c *= 1.0 + (vnoise(uvOf(p) * 2.5 + seed) - 0.5) * 0.06 * a;
    c = satur(c, 1.0 - 0.18 * a) * mix(float3(1.0), float3(1.03, 1.0, 0.94), a);
    c *= 1.0 - (0.35 + p2 * 0.4) * a * pow(smoothstep(0.1, 1.2, e), 1.7);
    c += filmGrain(p, luma(c)) * 0.025 * a;
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), min(1.0, a * 2.0)), o.a));
}
""",
        // Pushed black-and-white film: colour drains away as intensity rises, contrast and organic grain build.
        ArtMode.GRAINY_FILM to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    float l = luma(o.rgb);
    l = (l - 0.5) * (1.0 + (0.3 + p2 * 1.1) * a) + 0.5;
    l = smoothstep(-0.06, 1.04, l);
    l += filmGrain(p, l) * (0.04 + p1 * 0.24) * a;
    float3 mono = float3(clamp(l, 0.0, 1.0));
    return half4(float4(mix(o.rgb, mono, pow(a, 0.7)), o.a));
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
    float k = 0.55 + 0.45 * a;
    float3 x = float3(mix(c.r, smoothstep(0.05, 0.9, c.r), k), clamp(c.g * (1.0 + 0.08 * k) + 0.03 * k, 0.0, 1.0), mix(c.b, c.b * 0.6 + 0.15, k));
    x.g = mix(x.g, smoothstep(0.0, 1.0, x.g), 0.6 * k);
    float l = luma(c);
    float bias = (p1 - 0.5) * 2.0;
    x += float3(-0.05, 0.06, 0.02) * (1.0 - l) * (1.0 + bias) * k;
    x += float3(0.07, 0.05, -0.09) * l * k;
    x += float3(0.05, -0.05, 0.07) * max(-bias, 0.0) * (1.0 - l) * k;
    x = satur(contrast(x, 1.0 + 0.15 * a), 1.0 + 0.3 * a);
    return half4(float4(mix(c, clamp(x, 0.0, 1.0), pow(a, 0.8)), o.a));
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
    float3 soft = blurAt(p, s * (0.0015 + p2 * 0.0055));
    float3 c = mix(base, soft, (0.18 + 0.42 * p2) * a);
    float3 halo = bloomAt(p, s * (0.012 + p1 * 0.035), 0.55);
    c = screen(c, halo * (0.9 + 1.8 * p1) * a);
    c = mix(c, c * 0.96 + 0.015, a);
    c = satur(c, 1.0 - 0.08 * a);
    return half4(float4(clamp(c, 0.0, 1.0), o.a));
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
    ir = ir + veg * (0.32 + 0.38 * l);
    ir = ir * (1.0 - 0.55 * sky);
    ir = (ir - 0.5) * (0.92 + p1 * 0.6) + 0.5;
    ir = smoothstep(-0.08, 1.06, ir);
    float glow = luma(bloomAt(p, min(res.x, res.y) * 0.02, 0.62));
    ir = 1.0 - (1.0 - ir) * (1.0 - glow * p2 * 0.7);
    return half4(float4(mix(c, float3(clamp(ir, 0.0, 1.0)), a), o.a));
}
""",
        // A cyanotype print: Prussian-blue shadows, warm paper whites, a little paper grain.
        ArtMode.CYANOTYPE to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    float l = luma(o.rgb);
    l = clamp((l - 0.5) * (0.95 + p1 * 0.8 + a * 0.3) + 0.5, 0.0, 1.0);
    l = mix(l, smoothstep(0.0, 1.0, l), 0.45);
    float3 sh = float3(0.02, 0.16, 0.40);
    float3 md = float3(0.17, 0.40, 0.66);
    float3 pp = float3(0.95, 0.93, 0.86);
    float3 c = mix(mix(sh, md, clamp(l * 2.0, 0.0, 1.0)), pp, clamp(l * 2.0 - 1.0, 0.0, 1.0));
    float g = res.y / 160.0;
    float n = vnoise(p / g) * 0.6 + vnoise(p / (g * 0.35)) * 0.4;
    float fib = vnoise(float2(p.x / (g * 0.12), p.y / (g * 2.5)));
    c *= 1.0 + ((n - 0.5) * 0.08 + (fib - 0.5) * 0.03) * p2 * (0.4 + 0.6 * l);
    c *= vig(p, 0.12);
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), pow(a, 0.8)), o.a));
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
    x = scurve(x, 0.5 + p2 * 0.6);
    x = max(x - 0.02, 0.0) / 0.98;
    x = mix(x, 1.0 - (1.0 - x) * (1.0 - x) * 1.02, 0.12);
    x *= float3(0.98, 1.0, 1.03);
    x += filmGrain(p, luma(x)) * 0.03;
    return half4(float4(mix(c, clamp(x, 0.0, 1.0), a), o.a));
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
    float cool = (0.35 + 0.65 * p2) * a * (1.0 - warmLight);
    c += float3(-0.10, 0.01, 0.11) * shadow * cool;
    c += float3(-0.05, 0.0, 0.045) * (1.0 - shadow) * (1.0 - l) * cool;
    c = mix(c, c * float3(0.93, 0.98, 1.06), cool * (1.0 - l) * 0.8);
    c += float3(0.05, 0.015, -0.035) * warmLight * a;
    c = satur(c, 1.0 - 0.18 * a + 0.25 * warmLight * a);
    c = mix(c, smoothstep(float3(-0.04), float3(1.1), c), 0.7 * a);
    float3 b = bloomAt(p, s * (0.012 + 0.035 * p1), 0.66);
    float glow = luma(b) * (1.0 + 2.6 * p1) * a;
    c = screen(c, float3(1.0, 0.28, 0.08) * glow * (1.0 - 0.55 * l));
    c += filmGrain(p, l) * (0.008 + 0.06 * p3) * a;
    return half4(float4(clamp(c, 0.0, 1.0), o.a));
}
""",
        // Aged colour film: lifted, faded density; shadows, mids and highlights drifting in different
        // directions; slow patches of fog; irregular grain. The variation picks the family of drift.
        ArtMode.EXPIRED to """
float3 drift(float v, float band) {
    // band 0 shadows, 1 mids, 2 highlights; families: 0 warm, 1 magenta, 2 cool
    if (v < 0.5) return band < 0.5 ? float3(0.06, -0.02, 0.03) : (band < 1.5 ? float3(0.0, 0.03, -0.045) : float3(0.05, 0.03, -0.07));
    if (v < 1.5) return band < 0.5 ? float3(0.07, -0.035, 0.05) : (band < 1.5 ? float3(0.045, -0.02, 0.025) : float3(0.035, 0.02, -0.045));
    return band < 0.5 ? float3(-0.04, 0.02, 0.065) : (band < 1.5 ? float3(-0.02, 0.04, 0.0) : float3(0.025, 0.025, -0.035));
}
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = amt;
    float age = p1;
    float3 c = focusAt(p, o.rgb);
    float l = luma(c);
    float k = a * (0.75 + 0.7 * age);
    float fam = lks;
    float mixFam = 0.0;
    if (fam > 2.5) { fam = floor(fract(seed * 0.381) * 3.0); mixFam = fract(seed * 0.773) * 0.6; }
    float fam2 = mod(fam + 1.0, 3.0);
    float ws = pow(1.0 - l, 1.6); float wh = smoothstep(0.55, 1.0, l); float wm = 1.0 - max(ws, wh);
    float3 d = drift(fam, 0.0) * ws + drift(fam, 1.0) * wm + drift(fam, 2.0) * wh;
    float3 d2 = drift(fam2, 0.0) * ws + drift(fam2, 1.0) * wm + drift(fam2, 2.0) * wh;
    float2 uv = uvOf(p);
    float fog = vnoise(uv * float2(1.4, 1.8) + seed * 3.1);
    c = c * (1.0 - 0.16 * k) + 0.09 * k;
    c = mix(c, float3(luma(c)), 0.32 * k);
    c = mix(c, smoothstep(float3(-0.15), float3(1.15), c), -0.4 * k);
    c += mix(d, d2, mixFam) * k * (2.4 + 1.2 * (fog - 0.5) * age);
    float3 fogCol = mix(drift(fam, 2.0), drift(fam2, 0.0), fog) * 4.0 + 0.6;
    c = screen(c, clamp(fogCol, 0.0, 1.0) * pow(fog, 2.2) * 0.26 * age * a);
    float g = filmGrain(p, l) * (0.02 + 0.08 * p2) * a * (0.7 + 0.6 * age);
    float gc = (hash(floor(p / max(1.0, res.y / 700.0)) + 5.3) - 0.5) * 0.02 * age * a;
    c += g + float3(gc, -gc * 0.5, gc * 0.3);
    return half4(float4(clamp(c, 0.0, 1.0), o.a));
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
    float L = 0.52 * c.r + 0.42 * c.g + 0.06 * c.b;
    L = pow(clamp(L, 0.0, 1.0), mix(1.35, 0.75, p1));
    float3 sh = mix(float3(0.16, 0.02, 0.015), float3(0.24, 0.06, 0.02), p1);
    float3 md = mix(float3(0.72, 0.20, 0.04), float3(0.86, 0.40, 0.08), p1);
    float3 hi = mix(float3(0.98, 0.62, 0.22), float3(1.0, 0.82, 0.42), p1);
    float3 top = float3(1.0, 0.93, 0.74);
    float3 r = mix(sh, md, smoothstep(0.0, 0.42, L));
    r = mix(r, hi, smoothstep(0.38, 0.78, L));
    r = mix(r, top, smoothstep(0.82, 1.0, L));
    r.g += 0.14 * max(c.g - c.r, 0.0) * (1.0 - L);
    r *= 1.0 - 0.25 * max(c.b - max(c.r, c.g), 0.0);
    r.r = min(r.r, 0.985);
    r += filmGrain(p, L) * (0.01 + 0.05 * p2);
    return half4(float4(mix(o.rgb, clamp(r, 0.0, 1.0), a), o.a));
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
    float t = 0.3 + 0.45 * p2;
    float fold = smoothstep(t - 0.1, t + 0.1, l);
    float y = mix(l, abs(2.0 * t - l), fold * (0.35 + 0.65 * p1));
    y = clamp(y, 0.0, 1.0);
    y = smoothstep(0.02, 0.92, mix(y, pow(y, 0.85), 0.5));
    float d = max(1.0, res.y / 900.0) * 1.5;
    float gx = luma(px(p + float2(d, 0.0)).rgb) - luma(px(p - float2(d, 0.0)).rgb);
    float gy = luma(px(p + float2(0.0, d)).rgb) - luma(px(p - float2(0.0, d)).rgb);
    float edge = smoothstep(0.03, 0.18, length(float2(gx, gy)));
    float near = exp(-pow((l - t) / 0.09, 2.0));
    y = clamp(y + edge * near * p3 * 0.55, 0.0, 1.0);
    float3 silver = float3(y) * float3(0.97, 0.99, 1.03);
    float3 base = mix(o.rgb, float3(luma(o.rgb)), smoothstep(0.0, 0.7, a));
    return half4(float4(mix(base, clamp(silver, 0.0, 1.0), pow(a, 0.8)), o.a));
}
""",
        // Instant film: a little soft, creamy highlights, lifted blacks with a cool cast, gentle warmth
        // in the mids, muted colour.
        ArtMode.INSTANT to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float a = 1.0 - pow(1.0 - amt, 1.3);
    float s = min(res.x, res.y);
    float3 c = focusAt(p, o.rgb);
    c = mix(c, blurAt(p, s * (0.001 + p3 * 0.004)), (0.4 + 0.5 * p3) * a);
    float l = luma(c);
    c = satur(c, 1.0 - 0.3 * a);
    c += float3(0.05, 0.018, -0.05) * (p1 * 2.0 - 0.3) * a * (1.0 - abs(l - 0.5) * 1.2);
    c = mix(c, c * 0.84 + float3(0.17, 0.15, 0.09), smoothstep(0.45, 1.0, l) * a);
    c = c * (1.0 - 0.2 * p2 * a) + float3(0.07, 0.09, 0.1) * p2 * a;
    c = mix(c, smoothstep(float3(-0.1), float3(1.1), c), -0.3 * a);
    c *= vig(p, 0.16 * a);
    c += filmGrain(p, l) * 0.015 * a;
    return half4(float4(clamp(c, 0.0, 1.0), o.a));
}
""",
    )

    fun source(mode: ArtMode) = COMMON + (BODIES[mode] ?: "")
}
