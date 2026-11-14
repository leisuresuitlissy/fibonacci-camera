# Generates ios/FibonacciCamera/Shaders.metal from the Android AGSL source, so both apps share one look.
import re, sys
kt = open(sys.argv[1]).read()
common = re.search(r'const val COMMON = """(.*?)"""', kt, re.S).group(1)
bodies = re.findall(r'ArtMode\.(\w+) to """(.*?)"""', kt, re.S)
common = "\n".join(l for l in common.splitlines() if not l.strip().startswith("uniform "))
helpers = ["hash", "px", "uvOf", "inFrame", "edgeOf", "vig", "blurAt", "depthR", "focusAt", "bloomAt", "grainAt", "vnoise"]
out_common = []
lines = common.splitlines()
i = 0
src = common
for h in helpers:
    # rename the definition and give it the texture + uniforms
    pat = re.compile(r'^(\w+)\s+' + h + r'\((.*?)\)\s*\{', re.M)
    m = pat.search(src)
    assert m, h
    ret, args = m.group(1), m.group(2)
    new = f"static {ret} {h}_(texture2d<float> T, constant U& uu, {args}) {{"
    # find end of function: matching brace
    start = m.start(); j = m.end(); depth = 1
    while depth:
        if src[j] == "{": depth += 1
        elif src[j] == "}": depth -= 1
        j += 1
    body = src[m.end():j]
    src = src[:start] + new + body + f"\n#define {h}(...) {h}_(T, uu, __VA_ARGS__)" + src[j:]
src = src.replace("float4(content.eval(p))", "T.sample(smp, p / float2(T.get_width(), T.get_height()))")
for f in ["luma", "satur", "contrast", "scurve"]:
    src = re.sub(r'^(float3?) ' + f + r'\(', r'static \1 ' + f + '(', src, flags=re.M)
head = """// GENERATED from android/.../ArtShaders.kt by tools/gen_metal.py — edit the Kotlin, then regenerate.
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
"""
out = head + src + "\n"
for name, body in bodies:
    b = body.replace("half4 main(float2 p) {", f"static float4 fx_{name}(texture2d<float> T, constant U& uu, float2 p) {{")
    b = b.replace("half4(", "float4(")
    out += b + f"""
fragment float4 art_{name}(VOut in [[stage_in]], texture2d<float> T [[texture(0)]], constant U& uu [[buffer(0)]]) {{
    return fx_{name}(T, uu, (in.pos.xy - uu.vpo_) * uu.vps_);
}}
"""
open(sys.argv[2], "w").write(out)
print(len(bodies), "modes")
