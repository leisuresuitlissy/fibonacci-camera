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

float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233)) + seed * 17.0) * 43758.5453); }
float3 satur(float3 c, float s) { return mix(float3(luma(c)), c, s); }
float3 contrast(float3 c, float k) { return clamp((c - 0.5) * k + 0.5, 0.0, 1.0); }
float4 px(float2 p) { return float4(content.eval(p)); }
float2 uvOf(float2 p) { return (p - org) / res; }
float vig(float2 p, float s) {
    float2 d = (uvOf(p) - 0.5) * float2(res.x / res.y, 1.0);
    float r = length(d) / length(float2(0.5 * res.x / res.y, 0.5));
    return 1.0 - s * smoothstep(0.25, 1.05, r);
}
float3 blurAt(float2 p, float r) {
    float3 acc = px(p).rgb;
    float j = hash(p) * 6.2831853;
    for (int i = 0; i < 16; i++) {
        float a = float(i) * 0.3926991 + j;
        float k = mod(float(i), 2.0) * 0.5 + 0.5;
        acc += px(p + float2(cos(a), sin(a)) * r * k).rgb;
    }
    return acc / 17.0;
}
float grainAt(float2 p) {
    float g = max(1.0, res.y / 900.0);
    return hash(floor(p / g)) - 0.5;
}
"""

    val BODIES: Map<ArtMode, String> = mapOf(
        ArtMode.TILT_SHIFT to """
half4 main(float2 p) {
    float4 o = px(p);
    float2 uv = uvOf(p);
    float ang = (p3 - 0.5) * 1.5708;
    float2 n = float2(-sin(ang), cos(ang));
    float d = abs(dot(uv - float2(0.5, p1), n));
    float w = 0.03 + p2 * 0.3;
    float b = smoothstep(w, w + 0.22, d);
    float r = b * (0.25 + 0.75 * amt) * 0.02 * min(res.x, res.y);
    float3 c = blurAt(p, r);
    c = contrast(satur(c, 1.0 + 0.5 * amt), 1.0 + 0.12 * amt);
    return half4(float4(mix(o.rgb, c, min(1.0, amt * 1.6)), o.a));
}
""",
        ArtMode.HOLGA to """
half4 main(float2 p) {
    float4 o = px(p);
    float2 uv = uvOf(p);
    float s = min(res.x, res.y);
    float2 sq = (p - org - 0.5 * (res - float2(s))) / s;
    float2 cd = uv - 0.5;
    float edge = length(cd) * 1.414;
    float r = (0.002 + p2 * 0.014) * s * smoothstep(0.15, 1.0, edge);
    float3 c = blurAt(p, r);
    float ca = 0.006 * s * edge;
    c.r = mix(c.r, px(p + cd * ca * 2.0).r, 0.7);
    c.b = mix(c.b, px(p - cd * ca * 2.0).b, 0.7);
    c = contrast(c, 1.2);
    c = c * float3(1.05, 1.0, 0.9) + float3(0.0, 0.02, 0.04) * (1.0 - luma(c));
    c *= vig(p, 0.35 + p1 * 0.6);
    float2 l1 = float2(0.15 + 0.7 * hash(float2(seed, 1.0)), 0.12);
    float leak = exp(-pow(length((uv - l1) * float2(1.0, 2.2)) * 3.0, 2.0));
    leak += 0.7 * exp(-pow((1.0 - uv.x) * 5.0, 2.0)) * hash(float2(seed, 7.0));
    c += p3 * leak * float3(1.0, 0.42, 0.12) * 0.9;
    float inside = step(0.0, sq.x) * step(sq.x, 1.0) * step(0.0, sq.y) * step(sq.y, 1.0);
    float3 fx = clamp(c, 0.0, 1.0) * mix(0.05, 1.0, inside);
    return half4(float4(mix(o.rgb, fx, amt), o.a));
}
""",
        ArtMode.PINHOLE to """
half4 main(float2 p) {
    float4 o = px(p);
    float2 uv = uvOf(p);
    float s = min(res.x, res.y);
    float edge = length(uv - 0.5) * 1.414;
    float3 c = blurAt(p, s * (0.0015 + p1 * 0.01) * (0.4 + edge));
    float l = mix(luma(c), 0.5, 0.1);
    float3 m = float3(l) * float3(1.03, 1.0, 0.94);
    m *= vig(p, 0.5 + p2 * 0.5);
    m += grainAt(p) * 0.03;
    return half4(float4(mix(o.rgb, clamp(m, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.GRAINY_FILM to """
half4 main(float2 p) {
    float4 o = px(p);
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
    float s = min(res.x, res.y);
    float3 soft = blurAt(p, s * (0.003 + p2 * 0.012));
    float3 wide = blurAt(p, s * 0.035);
    float3 glow = max(wide - 0.5, 0.0) * 2.0 * (0.3 + p1 * 1.5);
    float3 c = mix(o.rgb, soft, 0.3 + p2 * 0.5);
    c = 1.0 - (1.0 - c) * (1.0 - clamp(glow, 0.0, 1.0));
    c = mix(c, float3(luma(c)), 0.18) * float3(1.03, 1.0, 0.97) + 0.05;
    c = contrast(c, 0.9);
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.INFRARED to """
half4 main(float2 p) {
    float4 o = px(p);
    float3 c = o.rgb;
    float ir = c.g * 1.05 + c.r * 0.2 - c.b * 0.55 + 0.1;
    float foliage = smoothstep(0.02, 0.22, c.g - max(c.r, c.b) * 0.85);
    ir = mix(ir, 1.0, foliage * 0.6);
    ir = clamp((ir - 0.5) * (1.0 + p1 * 1.0) + 0.5, 0.0, 1.0);
    float glow = max(luma(blurAt(p, min(res.x, res.y) * 0.012)) - 0.6, 0.0) * 1.2;
    return half4(float4(mix(c, float3(clamp(ir + glow, 0.0, 1.0)), amt), o.a));
}
""",
        ArtMode.CYANOTYPE to """
half4 main(float2 p) {
    float4 o = px(p);
    float l = luma(o.rgb);
    l = clamp((l - 0.5) * (1.0 + p1 * 1.5) + 0.5, 0.0, 1.0);
    float3 c = mix(float3(0.03, 0.12, 0.33), float3(0.92, 0.94, 0.96), smoothstep(0.0, 1.0, l));
    float t = hash(floor(p / max(1.0, res.y / 500.0))) - 0.5;
    float fib = hash(floor(float2(p.x / max(1.0, res.y / 140.0), p.y / max(1.0, res.y / 1600.0)))) - 0.5;
    c += (t * 0.06 + fib * 0.05) * p2;
    c *= vig(p, 0.15);
    return half4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}
""",
        ArtMode.BLEACH_BYPASS to """
half4 main(float2 p) {
    float4 o = px(p);
    float3 c = o.rgb;
    float3 lum = float3(luma(c));
    float3 ov = mix(2.0 * c * lum, 1.0 - 2.0 * (1.0 - c) * (1.0 - lum), step(float3(0.5), lum));
    float3 x = mix(lum, ov, 0.2 + p1 * 0.8);
    x = contrast(x, 1.1 + p2 * 0.7) * float3(0.98, 1.0, 1.03);
    return half4(float4(mix(c, clamp(x, 0.0, 1.0), amt), o.a));
}
""",
    )

    fun source(mode: ArtMode) = COMMON + (BODIES[mode] ?: "")
}
