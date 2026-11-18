// GENERATED from android/.../ArtShaders.kt by tools/gen_metal.py — edit the Kotlin, then regenerate.
#include <metal_stdlib>
using namespace metal;

struct U { float2 org_; float2 res_; float amt_; float p1_; float p2_; float p3_; float seed_; float2 vpo_; float2 vps_; float4 msk_; float4 msz_; float lks_; float p4_; float vgn_; };
struct VOut { float4 pos [[position]]; float2 uv; };
constexpr sampler smp(coord::normalized, address::clamp_to_edge, filter::linear);

#define org uu.org_
#define res uu.res_
#define amt uu.amt_
#define p1 uu.p1_
#define p2 uu.p2_
#define p3 uu.p3_
#define p4 uu.p4_
#define vgn uu.vgn_
#define seed uu.seed_
#define msk uu.msk_
#define msz uu.msz_
#define lks uu.lks_

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
// Vignette, shared by every Look: 0 = none; darkens smoothly from the middle to the frame's corners,
// up to nearly black at 100%. It follows Intensity (none at 0, full from 50% up).
static float4 vignette_(texture2d<float> T, constant U& uu, float2 p, float4 c) {
    if (vgn <= 0.0001) return c;
    float2 d = (p - org) / res - 0.5;
    float e = length(d * 2.0) * 0.7071;
    float k = vgn * min(1.0, amt * 2.0) * 0.95 * pow(smoothstep(0.18, 1.0, e), 1.3);
    return float4(c.rgb * (1.0 - k), c.a);
}
#define vignette(...) vignette_(T, uu, __VA_ARGS__)
static float3 contrast(float3 c, float k) { return clamp((c - 0.5) * k + 0.5, 0.0, 1.0); }
static float3 scurve(float3 c, float k) { float3 x = clamp(c, 0.0, 1.0); return clamp(mix(x, x * x * (3.0 - 2.0 * x), k), 0.0, 1.0); }
static float3 screen(float3 a, float3 b) { return 1.0 - (1.0 - a) * (1.0 - clamp(b, 0.0, 1.0)); }
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
// A wider, smoother disc for big lens blur (Tilt Shift): 40 taps.
static float3 blurWide_(texture2d<float> T, constant U& uu, float2 p, float r) {
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
#define blurWide(...) blurWide_(T, uu, __VA_ARGS__)
// The focus mask: 0 inside the sharp region, rising smoothly to 1 across the feather.
// Measured in frame heights from the mask's centre (radial) or centre line (band).
// Tilt Shift's band (type 3) takes Feather as a share of the room left between the sharp band and the
// frame's far corner, so even full Feather leaves a fully blurred region (12% of the room at 0, 72% at 100%).
static float bandFeather_(texture2d<float> T, constant U& uu) {
    float a = res.x / res.y;
    float2 n = float2(-sin(msk.z), cos(msk.z));
    float2 c = (msk.xy - 0.5) * float2(a, 1.0);
    float far = max(max(abs(dot(float2(-0.5 * a, -0.5) - c, n)), abs(dot(float2(0.5 * a, -0.5) - c, n))),
                    max(abs(dot(float2(-0.5 * a, 0.5) - c, n)), abs(dot(float2(0.5 * a, 0.5) - c, n))));
    return max(far - msz.x, 0.05) * (0.12 + 0.6 * msz.y);
}
#define bandFeather() bandFeather_(T, uu)
static float maskSoft_(texture2d<float> T, constant U& uu, float2 p) {
    if (msk.w < 0.5) return 0.0;
    float2 d = (uvOf(p) - msk.xy) * float2(res.x / res.y, 1.0);
    bool band = msk.w < 1.5 || msk.w > 2.5;
    float dist = band ? abs(dot(d, float2(-sin(msk.z), cos(msk.z)))) : length(d);
    float f = msk.w > 2.5 ? bandFeather() : 0.03 + msz.y * 0.55;
    float t = clamp((dist - msz.x) / f, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
#define maskSoft(...) maskSoft_(T, uu, __VA_ARGS__)
// Tilt Shift: the blur radius at full distance from the band (Depth × Intensity), shared with Kotlin's TiltFx.
static float tiltRamp_(texture2d<float> T, constant U& uu) { return amt < 0.5 ? amt * 2.0 : 1.0 + (amt - 0.5) * 0.5; }
#define tiltRamp() tiltRamp_(T, uu)
static float tiltR_(texture2d<float> T, constant U& uu) { return min(res.x, res.y) * 0.11 * msz.z * tiltRamp(); }
#define tiltR() tiltR_(T, uu)
// Tilt Shift's miniature grade: a little more colour, and crisper local contrast only where it's sharp.
static float3 tiltGrade_(texture2d<float> T, constant U& uu, float2 p, float3 c, float soft) {
    float k = amt;
    float3 local = blurAt(p, min(res.x, res.y) * 0.004);
    c = clamp(c + (c - local) * 0.45 * k * (1.0 - soft), 0.0, 1.0);
    return satur(scurve(c, 0.18 * k), 1.0 + 0.3 * k);
}
#define tiltGrade(...) tiltGrade_(T, uu, __VA_ARGS__)
// Depth: gently softer away from the sharp region, like a fast lens. msz.z is the strength.
static float depthR_(texture2d<float> T, constant U& uu, float2 p) {
    if (msz.z <= 0.001) return 0.0;
    float s = maskSoft(p);
    return s * s * msz.z * 0.03 * min(res.x, res.y);
}
#define depthR(...) depthR_(T, uu, __VA_ARGS__)
static float3 focusAt_(texture2d<float> T, constant U& uu, float2 p, float3 c) {
    float r = depthR(p);
    return r < 0.6 ? c : blurAt(p, r);
}
#define focusAt(...) focusAt_(T, uu, __VA_ARGS__)
// Highlights spread out, with a smooth threshold: a halo around bright things, never a wash.
static float3 bloomAt_(texture2d<float> T, constant U& uu, float2 p, float r, float thr) {
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
// Film grain that clumps a little and sits mostly in the midtones; sized to the frame so preview and export match.
static float filmGrain_(texture2d<float> T, constant U& uu, float2 p, float l) {
    float g = max(1.0, res.y / 900.0);
    float fine = hash(floor(p / g)) - 0.5;
    float clump = vnoise(p / (g * 2.6)) - 0.5;
    return (fine * 0.75 + clump * 0.9) * (1.0 - abs(l - 0.45) * 1.1);
}
#define filmGrain(...) filmGrain_(T, uu, __VA_ARGS__)

static float4 fx_TILT_SHIFT(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    // Single-pass version (photo editor preview, iOS). On Android the camera and saved photos use TiltFx:
    // the same band, radius and grade, with a layered GPU blur. Blur grows from 0 at the band's edge to
    // tiltR() at the end of the feather; Intensity scales the blur and the grade, never a ghost of the original.
    float soft = maskSoft(p);
    float r = pow(soft, 1.5) * 1.15 * tiltR();
    float3 base = r < 0.6 ? o.rgb : blurWide(p, r);
    return float4(vignette(p, float4(tiltGrade(p, base, soft), o.a)));
}

fragment float4 art_TILT_SHIFT(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_TILT_SHIFT(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

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
static float4 fx_HOLGA(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    float a = 1.0 - pow(1.0 - amt, 1.5);
    float s = min(res.x, res.y);
    float2 sq = (p - org - 0.5 * (res - float2(s))) / s;
    float inside = step(0.0, sq.x) * step(sq.x, 1.0) * step(0.0, sq.y) * step(sq.y, 1.0);
    float2 cd = sq - 0.5;
    float wob = vnoise(sq * 3.0 + seed) - 0.5;
    float edge = length(cd) * 1.414 * (1.0 + wob * 0.18);
    // Softness: the plastic lens, soft all over and softer towards the edges. All of it comes from the
    // control (0 = none). It adds to Depth's spatial blur rather than hiding behind it.
    float rad = s * p2 * (0.005 + 0.015 * pow(smoothstep(0.1, 1.1, edge), 1.3)) * (0.35 + 0.65 * a);
    float dr = depthR(p);
    float3 c = blurAt(p, sqrt(rad * rad + dr * dr));
    // Colour fringing: a faint red/blue halo in the outer edge only, about a third of what it was. The
    // offset copies are taken from the same softened picture, so they never read as a sharp ghost image.
    float fr = smoothstep(0.6, 1.15, edge);
    if (fr > 0.01) {
        float ca = s * 0.0035 * fr * a;
        float rr = sqrt(rad * rad + dr * dr);
        c.r = mix(c.r, blurAt(p + cd * ca, rr).r, 0.5);
        c.b = mix(c.b, blurAt(p - cd * ca, rr).b, 0.5);
    }
    // Saturation (p4): 50% sits just under the original, so the contrast curve doesn't push reds over;
    // 0 is close to muted, 100% richer than the original.
    c = satur(scurve(c, 0.5 * a), 1.0 + (0.45 + p4 - 1.0) * a);
    float l = luma(c);
    c += (float3(-0.025, 0.02, 0.045) * (1.0 - l) + float3(0.035, 0.018, -0.03) * l) * a;
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
    return float4(vignette(p, float4(mix(o.rgb, fx, a), o.a)));
}

fragment float4 art_HOLGA(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_HOLGA(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_PINHOLE(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    float a = amt;
    float s = min(res.x, res.y);
    float e = edgeOf(p);
    // Softness: from the control alone (0 = none), more towards the edges; added to Depth's blur.
    float rad = s * p1 * (0.006 + e * e * 0.016) * (0.3 + 0.7 * a);
    float dr = depthR(p);
    float3 c = blurAt(p, sqrt(rad * rad + dr * dr));
    c = mix(c, blurAt(p, s * 0.012), 0.1 * a);
    c *= 1.0 + (vnoise(uvOf(p) * 2.5 + seed) - 0.5) * 0.06 * a;
    c = satur(c, 1.0 - 0.18 * a) * mix(float3(1.0), float3(1.03, 1.0, 0.94), a);
    // Vignette: from the control alone (0 = none), smooth from the centre to the corners.
    c += filmGrain(p, luma(c)) * 0.025 * a;
    return float4(vignette(p, float4(mix(o.rgb, clamp(c, 0.0, 1.0), min(1.0, a * 2.0)), o.a)));
}

fragment float4 art_PINHOLE(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_PINHOLE(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_GRAINY_FILM(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    o.rgb = focusAt(p, o.rgb);
    float a = amt;
    float l = luma(o.rgb);
    l = (l - 0.5) * (1.0 + (0.3 + p2 * 1.1) * a) + 0.5;
    l = smoothstep(-0.06, 1.04, l);
    l += filmGrain(p, l) * (0.04 + p1 * 0.24) * a;
    l = clamp(l, 0.0, 1.0);
    float3 mono = float3(l);
    // Saturation (p3): 0 is black and white; higher brings the scene's own colour back under the same
    // contrast curve and grain, up to a little richer than the original at 100%.
    float3 col = clamp(o.rgb * (l / max(luma(o.rgb), 0.02)), 0.0, 1.0);
    col = satur(col, 0.6 + 0.6 * p3);
    float3 film = mix(mono, col, smoothstep(0.0, 1.0, p3));
    return float4(vignette(p, float4(mix(o.rgb, film, pow(a, 0.7)), o.a)));
}

fragment float4 art_GRAINY_FILM(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_GRAINY_FILM(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_CROSS_PROCESS(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    x = satur(contrast(x, 1.0 + 0.15 * a + (p2 - 0.5) * 0.6 * a), 1.0 + 0.3 * a);
    return float4(vignette(p, float4(mix(c, clamp(x, 0.0, 1.0), pow(a, 0.8)), o.a)));
}

fragment float4 art_CROSS_PROCESS(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_CROSS_PROCESS(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_DREAM_LENS(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    float a = 1.0 - pow(1.0 - amt, 1.4);
    float s = min(res.x, res.y);
    float3 base = focusAt(p, o.rgb);
    // Softness: a soft-focus lens, a blurred image laid over the sharp one; from the control alone (0 = none).
    float3 soft = blurAt(p, s * (0.002 + p2 * 0.008));
    float3 c = mix(base, soft, p2 * (0.3 + 0.55 * a));
    float3 halo = bloomAt(p, s * (0.012 + p1 * 0.035), 0.55);
    c = screen(c, halo * (0.9 + 1.8 * p1) * a);
    c = mix(c, c * 0.96 + 0.015, a);
    c = satur(c, 1.0 - 0.08 * a);
    return float4(vignette(p, float4(clamp(c, 0.0, 1.0), o.a)));
}

fragment float4 art_DREAM_LENS(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_DREAM_LENS(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_INFRARED(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(vignette(p, float4(mix(c, float3(clamp(ir, 0.0, 1.0)), a), o.a)));
}

fragment float4 art_INFRARED(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_INFRARED(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_CYANOTYPE(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(vignette(p, float4(mix(o.rgb, clamp(c, 0.0, 1.0), pow(a, 0.8)), o.a)));
}

fragment float4 art_CYANOTYPE(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_CYANOTYPE(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_BLEACH_BYPASS(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(vignette(p, float4(mix(c, clamp(x, 0.0, 1.0), a), o.a)));
}

fragment float4 art_BLEACH_BYPASS(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_BLEACH_BYPASS(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_KINO(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(vignette(p, float4(clamp(c, 0.0, 1.0), o.a)));
}

fragment float4 art_KINO(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_KINO(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

float3 drift(float v, float band) {
    // band 0 shadows, 1 mids, 2 highlights; families: 0 warm, 1 magenta, 2 cool
    if (v < 0.5) return band < 0.5 ? float3(0.06, -0.02, 0.03) : (band < 1.5 ? float3(0.0, 0.03, -0.045) : float3(0.05, 0.03, -0.07));
    if (v < 1.5) return band < 0.5 ? float3(0.07, -0.035, 0.05) : (band < 1.5 ? float3(-0.01, 0.03, -0.012) : float3(0.05, 0.005, 0.0));
    return band < 0.5 ? float3(-0.04, 0.02, 0.065) : (band < 1.5 ? float3(-0.02, 0.04, 0.0) : float3(0.025, 0.025, -0.035));
}
static float4 fx_EXPIRED(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    float a = amt;
    float age = p1;
    float3 c = focusAt(p, o.rgb);
    float l = luma(c);
    float k = a * (0.75 + 0.7 * age);
    float fam = lks;
    float mixFam = 0.0;
    if (fam > 2.5) { fam = floor(fract(seed * 0.381) * 3.0); mixFam = fract(seed * 0.773) * 0.6; }
    float fam2 = fmod(fam + 1.0, 3.0);
    float ws = pow(1.0 - l, 1.6); float wh = smoothstep(0.55, 1.0, l); float wm = 1.0 - max(ws, wh);
    float3 d = drift(fam, 0.0) * ws + drift(fam, 1.0) * wm + drift(fam, 2.0) * wh;
    float3 d2 = drift(fam2, 0.0) * ws + drift(fam2, 1.0) * wm + drift(fam2, 2.0) * wh;
    float2 uv = uvOf(p);
    float fog = vnoise(uv * float2(1.4, 1.8) + seed * 3.1);
    c = c * (1.0 - 0.16 * k) + 0.09 * k;
    c = mix(c, float3(luma(c)), 0.32 * k);
    c = mix(c, smoothstep(float3(-0.15), float3(1.15), c), -0.4 * k);
    c += mix(d, d2, mixFam) * pow(a, 0.8) * (1.5 + 1.1 * age) * (1.0 + 0.6 * (fog - 0.5) * age);
    float3 fogCol = mix(drift(fam, 2.0), drift(fam2, 0.0), fog) * 4.0 + 0.6;
    c = screen(c, clamp(fogCol, 0.0, 1.0) * pow(fog, 2.2) * 0.26 * age * a);
    float g = filmGrain(p, l) * (0.02 + 0.08 * p2) * a * (0.7 + 0.6 * age);
    float gc = (hash(floor(p / max(1.0, res.y / 700.0)) + 5.3) - 0.5) * 0.02 * age * a;
    c += g + float3(gc, -gc * 0.5, gc * 0.3);
    return float4(vignette(p, float4(clamp(c, 0.0, 1.0), o.a)));
}

fragment float4 art_EXPIRED(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_EXPIRED(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_REDSCALE(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(vignette(p, float4(mix(o.rgb, clamp(r, 0.0, 1.0), a), o.a)));
}

fragment float4 art_REDSCALE(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_REDSCALE(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

static float4 fx_SOLARISED(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
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
    return float4(vignette(p, float4(mix(base, clamp(silver, 0.0, 1.0), pow(a, 0.8)), o.a)));
}

fragment float4 art_SOLARISED(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_SOLARISED(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}

// Vintage SX-70 instant film. Intensity blends the whole look in; Warmth turns the film's white balance from
// cool green-teal (0) through creamy (50%) to honey (100%); Fade lifts and tints the blacks, flattens contrast
// and rolls the highlights off further; Texture is fine clumping grain plus faint chemical unevenness.
static float4 fx_INSTANT(texture2d<float> T, constant U& uu, float2 p) {
    float4 o = px(p);
    if (!inFrame(p)) return float4(o);
    float a = 1.0 - pow(1.0 - amt, 1.3);
    float s = min(res.x, res.y);
    float3 base = focusAt(p, o.rgb);
    // the plastic optics: a touch of softness, never a blur (p3 stays at its calibrated 0.4)
    float3 c = mix(base, blurAt(p, s * 0.0012 * (0.6 + p3)), 0.45);
    // halation: bright areas glow warmly into their surroundings; screened, so it can't clip
    float3 halo = bloomAt(p, s * 0.024, 0.7);
    c = screen(c, halo * float3(1.0, 0.82, 0.62) * (0.22 + 0.22 * p2));
    // dye response: muted but characterful. Blues lean teal, greens lean olive, reds lean brick
    c = float3(dot(c, float3(0.90, 0.12, -0.02)), dot(c, float3(0.05, 0.88, 0.07)), dot(c, float3(0.03, 0.17, 0.80)));
    float l = luma(c);
    c = satur(c, 0.92);
    // a yellow-green shift in the midtones, green-teal in the shadows (the cream comes later, in the whites)
    float mid = clamp(1.0 - abs(l - 0.45) * 1.6, 0.0, 1.0);
    c += float3(0.008, 0.022, -0.022) * mid;
    c += float3(-0.012, 0.012, 0.016) * (1.0 - l) * (1.0 - l);
    // warmth as white balance (it multiplies, so the blacks keep their own tint)
    // (cool side leans green-teal like aged SX-70, not blue)
    float w = p1 * 2.0 - 1.0;
    c *= 1.0 + (w >= 0.0 ? float3(0.10, 0.035, -0.13) * w : float3(-0.09, 0.02, 0.05) * -w);
    c = clamp(c, 0.0, 1.0);
    // softer contrast: a shoulder so highlights roll off, then a little flatter
    float k = 0.2 + 0.3 * p2;
    c = c / (1.0 + c * k) * (1.0 + k);
    float fl = 0.04 + 0.2 * p2;
    c = clamp(c * (1.0 - fl) + 0.5 * fl, 0.0, 1.0);
    // lifted, tinted blacks and creamy whites that stop short of paper white
    float3 blk = mix(float3(0.07, 0.085, 0.095), float3(0.11, 0.09, 0.065), p1) * ((0.03 + 0.2 * p2) / 0.09);
    float3 wht = mix(float3(0.93, 0.95, 0.93), float3(0.985, 0.945, 0.83), p1) * (0.995 - 0.06 * p2);
    c = blk + c * (wht - blk);
    // texture: fine grain that clumps a little, and faint unevenness in the developer spread
    c += filmGrain(p, luma(c)) * (0.005 + 0.055 * p4);
    float2 u = uvOf(p) * float2(res.x / res.y, 1.0);
    c *= 1.0 + (vnoise(u * 9.0 + 3.1) - 0.5) * 0.06 * p4;
    c = mix(base, clamp(c, 0.0, 1.0), a);
    return float4(vignette(p, float4(c, o.a)));
}

fragment float4 art_INSTANT(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {
    return fx_INSTANT(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}
