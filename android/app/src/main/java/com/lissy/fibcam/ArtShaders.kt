package com.lissy.fibcam

/**
 * Art modes as AGSL. The same source runs live on the viewfinder (as a RenderEffect) and on the
 * full-resolution photo (offscreen), so the saved picture matches what you saw.
 *
 * Every shader shares these uniforms:
 *   org, res  – the image's top-left and size, in the coordinates the shader is drawn in
 *   amt       – intensity, 0..1
 *   p1..p3    – the mode's own sliders, 0..1
 *   seed      – per-photo randomness (light leaks, grain)
 *
 * Each mode defines fx(); the shared main() only runs it inside the image, so the letterbox
 * around the viewfinder stays black.
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
uniform float2 foc;
uniform float dep;

float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233)) + seed * 17.0) * 43758.5453); }
float3 satur(float3 c, float s) { return mix(float3(luma(c)), c, s); }
float3 contrast(float3 c, float k) { return clamp((c - 0.5) * k + 0.5, 0.0, 1.0); }
float3 scurve(float3 c, float k) { float3 x = clamp(c, 0.0, 1.0); return clamp(mix(x, x * x * (3.0 - 2.0 * x), k), 0.0, 1.0); }
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
// Depth: sharp around the focus point (your subject), gently softer with distance, like a fast lens.
// dep 0 = off; the default is subtle. Returns a blur radius in px.
float depthR(float2 p) {
    if (dep <= 0.001) return 0.0;
    float2 d = (uvOf(p) - foc) * float2(res.x / res.y, 1.0);
    float f = smoothstep(0.1, 0.8, length(d));
    return f * f * dep * 0.02 * min(res.x, res.y);
}
float3 focusAt(float2 p, float3 c) {
    float r = depthR(p);
    return r < 0.6 ? c : blurAt(p, r);
}
// Only what's brighter than thr spreads out: a highlight halo, not a wash.
float3 bloomAt(float2 p, float r, float thr) {
    float3 acc = float3(0.0);
    float j = hash(p + 1.7) * 6.2831853;
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float a = fi * 2.3999632 + j;
        float k = sqrt((fi + 0.5) / 16.0);
        acc += max(px(p + float2(cos(a), sin(a)) * r * k).rgb - thr, 0.0);
    }
    return acc / (16.0 * (1.0 - thr));
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
"""

    val BODIES: Map<ArtMode, String> = mapOf(
        ArtMode.TILT_SHIFT to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    float2 uv = uvOf(p);
    float ang = (p3 - 0.5) * 1.5708;
    float2 n = float2(-sin(ang), cos(ang));
    float d = abs(dot(uv - float2(0.5, p1), n));
    float w = 0.02 + p2 * 0.25;
    float b = smoothstep(w, w + 0.15, d);
    float far = smoothstep(w, w + 0.55, d);
    float r = (b * 0.012 + far * 0.032) * amt * min(res.x, res.y);
    float3 base = mix(o.rgb, blurAt(p, r), smoothstep(0.0, 0.25, b));
    float3 c = satur(scurve(base, 0.5 * amt), 1.0 + 0.65 * amt);
    return half4(float4(mix(o.rgb, c, min(1.0, amt * 1.4)), o.a));
}
""",
        ArtMode.HOLGA to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float s = min(res.x, res.y);
    float2 sq = (p - org - 0.5 * (res - float2(s))) / s;
    float inside = step(0.0, sq.x) * step(sq.x, 1.0) * step(0.0, sq.y) * step(sq.y, 1.0);
    float2 cd = sq - 0.5;
    float edge = length(cd) * 1.414;
    float k = amt;
    float rad = s * (0.0008 + (0.006 + p2 * 0.03) * pow(smoothstep(0.2, 1.1, edge), 1.3)) * (0.3 + 0.7 * k);
    float3 c = blurAt(p, max(rad, depthR(p)));
    float ca = s * 0.006 * edge * edge * (0.4 + k);
    c.r = mix(c.r, px(p + cd * ca).r, 0.55);
    c.b = mix(c.b, px(p - cd * ca).b, 0.55);
    c = satur(scurve(c, 0.55 + 0.45 * k), 1.2 + 0.3 * k);
    float l = luma(c);
    c += float3(-0.03, 0.02, 0.05) * (1.0 - l) + float3(0.06, 0.02, -0.05) * l;
    c *= 1.0 - (0.45 + p1 * 0.55) * pow(smoothstep(0.3, 1.05, edge), 1.5) * (0.55 + 0.45 * k);
    float2 l1 = float2(0.15 + 0.7 * hash(float2(seed, 1.0)), 0.12);
    float leak = exp(-pow(length((sq - l1) * float2(1.0, 2.2)) * 3.0, 2.0));
    leak += 0.7 * exp(-pow((1.0 - sq.x) * 5.0, 2.0)) * hash(float2(seed, 7.0));
    c += p3 * leak * float3(1.0, 0.42, 0.12) * 0.9;
    c += grainAt(p) * (0.015 + 0.075 * k);
    float3 fx = clamp(c, 0.0, 1.0) * inside;
    return half4(float4(mix(o.rgb, fx, max(amt, 1.0 - inside)), o.a));
}
""",
        ArtMode.PINHOLE to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float s = min(res.x, res.y);
    float e = edgeOf(p);
    float3 c = blurAt(p, max(s * (0.0015 + p1 * 0.004 + e * e * (0.006 + p1 * 0.022)), depthR(p)));
    c = mix(c, blurAt(p, s * 0.022), 0.2);
    c += bloomAt(p, s * 0.03, 0.72) * 0.25;
    c = satur(c, 1.18) * float3(1.06, 1.0, 0.9) + float3(0.012, 0.004, 0.0);
    c *= 1.0 - (0.5 + p2 * 0.45) * pow(smoothstep(0.0, 1.15, e), 1.7);
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.GRAINY_FILM to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float l = luma(o.rgb);
    l = clamp((l - 0.5) * (1.0 + p2 * 1.1) + 0.5, 0.0, 1.0);
    l = smoothstep(0.04, 0.99, l);
    float g = grainAt(p) + 0.6 * (hash(floor(p / max(1.0, res.y / 420.0)) + 3.1) - 0.5);
    l += g * (0.05 + p1 * 0.24) * (1.0 - abs(l - 0.5) * 0.9);
    return half4(float4(mix(o.rgb, float3(clamp(l, 0.0, 1.0)), amt), o.a));
}
""",
        ArtMode.CROSS_PROCESS to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float3 c = o.rgb;
    float3 x = float3(smoothstep(0.05, 0.85, c.r), clamp(c.g * 1.08 + 0.04, 0.0, 1.0), c.b * 0.6 + 0.15);
    x.g = mix(x.g, smoothstep(0.0, 1.0, x.g), 0.6);
    float l = luma(c);
    float bias = (p1 - 0.5) * 2.0;
    x += float3(-0.05, 0.07, -0.06) * (1.0 - l) * (1.0 + bias);
    x += float3(0.07, 0.05, -0.09) * l;
    x += float3(0.06, -0.06, 0.06) * max(-bias, 0.0);
    x = satur(contrast(x, 1.15), 1.3);
    return half4(float4(mix(c, clamp(x, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.DREAM_LENS to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float s = min(res.x, res.y);
    float3 halo = bloomAt(p, s * (0.012 + p1 * 0.05), 0.55) * (0.9 + p1 * 1.8);
    float3 soft = mix(o.rgb, blurAt(p, s * (0.0015 + p2 * 0.005)), 0.85);
    float3 c = mix(o.rgb, soft, 0.15 + 0.5 * p2);
    c = c + clamp(halo, 0.0, 1.0) * (1.0 - c);
    c = mix(c, c * 0.93 + 0.035, 0.7);
    c = satur(c, 0.93);
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.INFRARED to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float3 c = o.rgb;
    float l = luma(c);
    float veg = smoothstep(-0.02, 0.16, c.g - 0.5 * (c.r + c.b));
    float sky = smoothstep(0.0, 0.18, c.b - max(c.r, c.g) + 0.02) * smoothstep(0.15, 0.5, l);
    float ir = l * 0.85 + 0.08 + veg * (0.4 + 0.4 * l) - sky * 0.55;
    ir = clamp((ir - 0.5) * (1.0 + p1 * 1.0) + 0.5, 0.0, 1.0);
    ir += luma(bloomAt(p, min(res.x, res.y) * 0.02, 0.62)) * p2 * 1.6;
    return half4(float4(mix(c, float3(clamp(ir, 0.0, 1.0)), amt), o.a));
}
""",
        ArtMode.CYANOTYPE to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float l = luma(o.rgb);
    l = clamp((l - 0.5) * (0.95 + p1 * 0.8 + amt * 0.35) + 0.5, 0.0, 1.0);
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
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.BLEACH_BYPASS to """
half4 main(float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return half4(o);
    o.rgb = focusAt(p, o.rgb);
    float3 c = o.rgb;
    float3 lum = float3(luma(c));
    float3 ov = mix(2.0 * c * lum, 1.0 - 2.0 * (1.0 - c) * (1.0 - lum), step(float3(0.5), lum));
    float3 x = mix(lum, ov, 0.08 + p1 * 0.6);
    x = scurve(x, 0.55 + p2 * 0.6);
    x = max(x - 0.025, 0.0) / 0.975;
    x = mix(x, 1.0 - (1.0 - x) * (1.0 - x) * 1.02, 0.12);
    x *= float3(0.98, 1.0, 1.03);
    x += grainAt(p) * 0.03;
    return half4(float4(mix(c, clamp(x, 0.0, 1.0), amt), o.a));
}
""",
    )

    fun source(mode: ArtMode) = COMMON + (BODIES[mode] ?: "")
}
