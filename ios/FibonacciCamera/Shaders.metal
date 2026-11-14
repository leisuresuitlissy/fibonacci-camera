// GENERATED from android/.../ArtShaders.kt by tools/gen_metal.py — edit the Kotlin, then regenerate.
#include <metal_stdlib>
using namespace metal;

struct U { float2 org_; float2 res_; float amt_; float p1_; float p2_; float p3_; float seed_; float2 vpo_; float2 vps_; float2 foc_; float dep_; };
struct VOut { float4 pos [[position]]; float2 uv; };
constexpr sampler smp(coord::normalized, address::clamp_to_edge, filter::linear);

#define org uu.org_
#define res uu.res_
#define amt uu.amt_
#define p1 uu.p1_
#define p2 uu.p2_
#define p3 uu.p3_
#define seed uu.seed_
#define foc uu.foc_
#define dep uu.dep_

vertex VOut vfull(uint vid [[vertex_id]]) {
    float2 p = float2((vid << 1) & 2, vid & 2);
    VOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); o.uv = float2(p.x, 1.0 - p.y); return o;
}

fragment float4 copyFrag(VOut in [[stage_in]], texture2d<float> T [[texture(0)]]) {
    return T.sample(smp, in.uv);
}

// Two exposures: `base` fills the output; `top` is placed by matrix m (output px -> top px) and blended.
struct B { float3x3 m; float2 baseSize; float2 topSize; float strength; int mode; int hasTop; };
fragment float4 composite(VOut in [[stage_in]], texture2d<float> base [[texture(0)]], texture2d<float> top [[texture(1)]], constant B& b [[buffer(0)]]) {
    float4 a = base.sample(smp, in.uv);
    if (b.hasTop == 0) return a;
    float2 tp = (b.m * float3(in.uv * b.baseSize, 1.0)).xy;
    float2 tuv = tp / b.topSize;
    if (tuv.x < 0.0 || tuv.y < 0.0 || tuv.x > 1.0 || tuv.y > 1.0) return a;
    float3 t = top.sample(smp, tuv).rgb;
    float3 r = t;
    if (b.mode == 1) r = 1.0 - (1.0 - a.rgb) * (1.0 - t);
    else if (b.mode == 2) r = max(a.rgb, t);
    else if (b.mode == 3) r = a.rgb * t;
    return float4(mix(a.rgb, r, b.strength), 1.0);
}


static float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
static float hash_(texture2d<float> T, constant U& uu, float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233)) + seed * 17.0) * 43758.5453); }
#define hash(...) hash_(T, uu, __VA_ARGS__)
static float3 satur(float3 c, float s) { return mix(float3(luma(c)), c, s); }
static float3 contrast(float3 c, float k) { return clamp((c - 0.5) * k + 0.5, 0.0, 1.0); }
static float3 scurve(float3 c, float k) { float3 x = clamp(c, 0.0, 1.0); return clamp(mix(x, x * x * (3.0 - 2.0 * x), k), 0.0, 1.0); }
static float4 px_(texture2d<float> T, constant U& uu, float2 p) { return T.sample(smp, p / float2(T.get_width(), T.get_height())); }
#define px(...) px_(T, uu, __VA_ARGS__)
static float2 uvOf_(texture2d<float> T, constant U& uu, float2 p) { return (p - org) / res; }
#define uvOf(...) uvOf_(T, uu, __VA_ARGS__)
static bool inFrame_(texture2d<float> T, constant U& uu, float2 p) { float2 u = uvOf(p); return u.x >= 0.0 && u.y >= 0.0 && u.x <= 1.0 && u.y <= 1.0; }
#define inFrame(...) inFrame_(T, uu, __VA_ARGS__)
static float edgeOf_(texture2d<float> T, constant U& uu, float2 p) {
    float2 d = (uvOf(p) - 0.5) * float2(res.x / res.y, 1.0);
    return length(d) / length(float2(0.5 * res.x / res.y, 0.5));
}
#define edgeOf(...) edgeOf_(T, uu, __VA_ARGS__)
static float vig_(texture2d<float> T, constant U& uu, float2 p, float s) { return 1.0 - s * smoothstep(0.25, 1.05, edgeOf(p)); }
#define vig(...) vig_(T, uu, __VA_ARGS__)
// Disc blur: 24 taps on a golden-angle spiral, rotated per pixel so large radii stay smooth.
static float3 blurAt_(texture2d<float> T, constant U& uu, float2 p, float r) {
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
#define blurAt(...) blurAt_(T, uu, __VA_ARGS__)
// Depth: sharp around the focus point (your subject), gently softer with distance, like a fast lens.
// dep 0 = off; the default is subtle. Returns a blur radius in px.
static float depthR_(texture2d<float> T, constant U& uu, float2 p) {
    if (dep <= 0.001) return 0.0;
    float2 d = (uvOf(p) - foc) * float2(res.x / res.y, 1.0);
    float f = smoothstep(0.1, 0.8, length(d));
    return f * f * dep * 0.02 * min(res.x, res.y);
}
#define depthR(...) depthR_(T, uu, __VA_ARGS__)
static float3 focusAt_(texture2d<float> T, constant U& uu, float2 p, float3 c) {
    float r = depthR(p);
    return r < 0.6 ? c : blurAt(p, r);
}
#define focusAt(...) focusAt_(T, uu, __VA_ARGS__)
// Only what's brighter than thr spreads out: a highlight halo, not a wash.
static float3 bloomAt_(texture2d<float> T, constant U& uu, float2 p, float r, float thr) {
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
#define bloomAt(...) bloomAt_(T, uu, __VA_ARGS__)
static float grainAt_(texture2d<float> T, constant U& uu, float2 p) {
    float g = max(1.0, res.y / 900.0);
    return hash(floor(p / g)) - 0.5;
}
#define grainAt(...) grainAt_(T, uu, __VA_ARGS__)
static float vnoise_(texture2d<float> T, constant U& uu, float2 x) {
    float2 i = floor(x); float2 f = fract(x); f = f * f * (3.0 - 2.0 * f);
    float a = hash(i); float b = hash(i + float2(1.0, 0.0)); float c = hash(i + float2(0.0, 1.0)); float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
#define vnoise(...) vnoise_(T, uu, __VA_ARGS__)

static float4 fx_TILT_SHIFT(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(float4(mix(o.rgb, c, min(1.0, amt * 1.4)), o.a));
}

fragment float4 art_TILT_SHIFT(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_TILT_SHIFT(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_HOLGA(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(float4(mix(o.rgb, fx, max(amt, 1.0 - inside)), o.a));
}

fragment float4 art_HOLGA(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_HOLGA(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_PINHOLE(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    o.rgb = focusAt(p, o.rgb);
    float s = min(res.x, res.y);
    float e = edgeOf(p);
    float3 c = blurAt(p, max(s * (0.0015 + p1 * 0.004 + e * e * (0.006 + p1 * 0.022)), depthR(p)));
    c = mix(c, blurAt(p, s * 0.022), 0.2);
    c += bloomAt(p, s * 0.03, 0.72) * 0.25;
    c = satur(c, 1.18) * float3(1.06, 1.0, 0.9) + float3(0.012, 0.004, 0.0);
    c *= 1.0 - (0.5 + p2 * 0.45) * pow(smoothstep(0.0, 1.15, e), 1.7);
    return float4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}

fragment float4 art_PINHOLE(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_PINHOLE(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_GRAINY_FILM(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    o.rgb = focusAt(p, o.rgb);
    float l = luma(o.rgb);
    l = clamp((l - 0.5) * (1.0 + p2 * 1.1) + 0.5, 0.0, 1.0);
    l = smoothstep(0.04, 0.99, l);
    float g = grainAt(p) + 0.6 * (hash(floor(p / max(1.0, res.y / 420.0)) + 3.1) - 0.5);
    l += g * (0.05 + p1 * 0.24) * (1.0 - abs(l - 0.5) * 0.9);
    return float4(float4(mix(o.rgb, float3(clamp(l, 0.0, 1.0)), amt), o.a));
}

fragment float4 art_GRAINY_FILM(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_GRAINY_FILM(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_CROSS_PROCESS(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(float4(mix(c, clamp(x, 0.0, 1.0), amt), o.a));
}

fragment float4 art_CROSS_PROCESS(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_CROSS_PROCESS(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_DREAM_LENS(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    o.rgb = focusAt(p, o.rgb);
    float s = min(res.x, res.y);
    float3 halo = bloomAt(p, s * (0.012 + p1 * 0.05), 0.55) * (0.9 + p1 * 1.8);
    float3 soft = mix(o.rgb, blurAt(p, s * (0.0015 + p2 * 0.005)), 0.85);
    float3 c = mix(o.rgb, soft, 0.15 + 0.5 * p2);
    c = c + clamp(halo, 0.0, 1.0) * (1.0 - c);
    c = mix(c, c * 0.93 + 0.035, 0.7);
    c = satur(c, 0.93);
    return float4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}

fragment float4 art_DREAM_LENS(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_DREAM_LENS(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_INFRARED(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    o.rgb = focusAt(p, o.rgb);
    float3 c = o.rgb;
    float l = luma(c);
    float veg = smoothstep(-0.02, 0.16, c.g - 0.5 * (c.r + c.b));
    float sky = smoothstep(0.0, 0.18, c.b - max(c.r, c.g) + 0.02) * smoothstep(0.15, 0.5, l);
    float ir = l * 0.85 + 0.08 + veg * (0.4 + 0.4 * l) - sky * 0.55;
    ir = clamp((ir - 0.5) * (1.0 + p1 * 1.0) + 0.5, 0.0, 1.0);
    ir += luma(bloomAt(p, min(res.x, res.y) * 0.02, 0.62)) * p2 * 1.6;
    return float4(float4(mix(c, float3(clamp(ir, 0.0, 1.0)), amt), o.a));
}

fragment float4 art_INFRARED(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_INFRARED(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_CYANOTYPE(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(float4(mix(o.rgb, clamp(c, 0.0, 1.0), amt), o.a));
}

fragment float4 art_CYANOTYPE(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_CYANOTYPE(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_BLEACH_BYPASS(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(float4(mix(c, clamp(x, 0.0, 1.0), amt), o.a));
}

fragment float4 art_BLEACH_BYPASS(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_BLEACH_BYPASS(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}
