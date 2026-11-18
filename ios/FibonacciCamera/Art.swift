import CoreGraphics
import Foundation

/**
 * Values per mode, always six (same as Android): 0 intensity, 1...3 the mode's own sliders (p1...p3),
 * 4 Feather (Tilt Shift) or Depth (the others), 5 Holga's leak style. Unnamed sliders are hidden.
 * Every mode starts at 50% intensity; the shaders map that through each mode's own curve.
 */
enum ArtMode: String, CaseIterable {
    case OFF, TILT_SHIFT, HOLGA, PINHOLE, GRAINY_FILM, CROSS_PROCESS, DREAM_LENS, INFRARED, CYANOTYPE, BLEACH_BYPASS
    case KINO, EXPIRED, REDSCALE, SOLARISED, INSTANT

    var label: String { rawValue.replacingOccurrences(of: "_", with: " ") }
    var fileTag: String { rawValue.lowercased().replacingOccurrences(of: "_", with: "-") }

    var sliders: [(String, Float)] {
        switch self {
        case .OFF: return []
        case .TILT_SHIFT: return [("Intensity", 0.5), ("Focus", 0.55), ("Width", 0.22), ("Angle", 0.5), ("Feather", 0.5)]
        case .HOLGA: return [("Intensity", 0.5), ("Vignette", 0.55), ("Softness", 0.4), ("Leaks", 0.45), ("Depth", 0.3)]
        case .PINHOLE: return [("Intensity", 0.5), ("Softness", 0.4), ("Vignette", 0.5), ("", 0), ("Depth", 0)]
        case .GRAINY_FILM: return [("Intensity", 0.5), ("Grain", 0.5), ("Contrast", 0.55), ("", 0), ("Depth", 0.35)]
        case .CROSS_PROCESS: return [("Intensity", 0.5), ("Colour", 0.5), ("Contrast", 0.5), ("", 0), ("Depth", 0.35)]
        case .DREAM_LENS: return [("Intensity", 0.5), ("Glow", 0.5), ("Softness", 0.4), ("", 0), ("Depth", 0.35)]
        case .INFRARED: return [("Intensity", 0.5), ("Contrast", 0.5), ("Halation", 0.3), ("", 0), ("Depth", 0.35)]
        case .CYANOTYPE: return [("Intensity", 0.5), ("Contrast", 0.5), ("Paper", 0.5), ("", 0), ("Depth", 0.35)]
        case .BLEACH_BYPASS: return [("Intensity", 0.5), ("Saturation", 0.35), ("Contrast", 0.5), ("", 0), ("Depth", 0.35)]
        // the newer looks are colour and tone first: Depth starts at 0 and stays optional
        case .KINO: return [("Intensity", 0.5), ("Halation", 0.5), ("Coolness", 0.5), ("Grain", 0.35), ("Depth", 0)]
        case .EXPIRED: return [("Intensity", 0.5), ("Age", 0.5), ("Grain", 0.45), ("", 0), ("Depth", 0)]
        case .REDSCALE: return [("Intensity", 0.5), ("Tone", 0.45), ("Grain", 0.3), ("", 0), ("Depth", 0)]
        case .SOLARISED: return [("Intensity", 0.5), ("Reversal", 0.7), ("Threshold", 0.45), ("Contour", 0.35), ("Depth", 0)]
        case .INSTANT: return [("Intensity", 0.5), ("Warmth", 0.62), ("Fade", 0.5), ("Softness", 0.4), ("Depth", 0)]
        }
    }
    /** Named variations kept in values[5]: Holga's leak styles, Expired's colour drift. */
    var variations: [String] {
        switch self {
        case .HOLGA: return ["OFF", "WARM", "COOL", "NEUTRAL", "MIXED", "RANDOM"]
        case .EXPIRED: return ["WARM", "MAGENTA", "COOL", "RANDOM"]
        default: return []
        }
    }
    var variationLabel: String { self == .HOLGA ? "LEAK" : "COLOUR" }
    var defaultVariation: Int { (self == .HOLGA || self == .EXPIRED) ? 1 : 0 }
    func isRandom(_ i: Int) -> Bool { i < variations.count && variations[i] == "RANDOM" }
    var about: String { self == .INFRARED ? "Infrared-inspired: a simulation from visible light, not real infrared." : "" }
    /** A fourth look parameter (Holga grain, Instant texture), shared with Android; iOS keeps it at its calibrated default. */
    var p4Default: Float { self == .HOLGA ? 0.3 : (self == .INSTANT ? 0.4 : 0) }
    var defaults: [Float] { (0..<6).map { i in i == 5 ? Float(defaultVariation) : (i < sliders.count ? sliders[i].1 : 0) } }
    /** Sliders to show; unnamed padding is skipped but indexes stay put. */
    var shown: [Int] { sliders.indices.filter { !sliders[$0].0.isEmpty } }
    var hasDepth: Bool { sliders.count > 4 && sliders[4].0 == "Depth" }
    /** Follow guide decides where the sharp region sits, never how strong it is (Depth 0 stays 0). */
    var follows: Bool { self == .TILT_SHIFT || hasDepth }
    func isFocusSlider(_ i: Int) -> Bool { self == .TILT_SHIFT ? (1...4).contains(i) : (i == 4 && hasDepth) }
    func pad(_ v: [Float]) -> [Float] { (0..<6).map { $0 < v.count ? v[$0] : defaults[$0] } }

    // ---- per-look shooting preferences
    func loadState() -> ArtState {
        let d = UserDefaults.standard
        if let s = d.string(forKey: "art2:\(rawValue)") {
            let p = s.split(separator: "|", omittingEmptySubsequences: false)
            let v = p[0].split(separator: ",").compactMap { Float($0) }
            if v.count >= 4 { return ArtState(values: pad(v), follow: p.count > 1 && p[1] == "1") }
        }
        // settings from before per-look state: the look's own sliders carry over, intensity starts at the new 50%
        if let old = d.string(forKey: "artv:\(rawValue)") {
            let v = old.split(separator: ",").compactMap { Float($0) }
            var out = defaults
            for i in 1...4 where i < v.count && i < sliders.count && !sliders[i].0.isEmpty { out[i] = v[i] }
            return ArtState(values: out, follow: false)
        }
        return ArtState(values: defaults, follow: false)
    }
    func saveState(_ st: ArtState) {
        guard self != .OFF else { return }
        UserDefaults.standard.set(st.values.map { String($0) }.joined(separator: ",") + "|" + (st.follow ? "1" : "0"), forKey: "art2:\(rawValue)")
    }
    func resetState() -> ArtState {
        UserDefaults.standard.removeObject(forKey: "art2:\(rawValue)")
        return ArtState(values: defaults, follow: false)
    }
}


struct ArtState {
    var values: [Float]
    var follow: Bool
}

/**
 * Where a look stays sharp. Positions 0...1 across the frame the look is measured against; size
 * (half-width of a band, or radius) and feather in frame heights; angle in radians.
 * type: 0 none, 1 band, 2 radial. strength is Depth's blur (Tilt Shift uses its intensity instead).
 */
struct FocusMask {
    var type = 0
    var cx: Float = 0.5, cy: Float = 0.5, angle: Float = 0
    var size: Float = 0.08, feather: Float = 1, strength: Float = 0
    func encode() -> String { "\(type),\(cx),\(cy),\(angle),\(size),\(feather),\(strength)" }
    static func decode(_ s: Substring?) -> FocusMask? {
        guard let f = s?.split(separator: ",").compactMap({ Float($0) }), f.count == 7 else { return nil }
        return FocusMask(type: Int(f[0]), cx: f[1], cy: f[2], angle: f[3], size: f[4], feather: f[5], strength: f[6])
    }
}

/** What a guide offers the focus mask, worked out on a frame. */
struct GuideCtx {
    var guide: Guide, quadrant: Int, triFlip: Bool, anchor: Int
    var frame: CGRect, guideFrame: CGRect
}

enum Focus {
    /**
     * The geometry a guide suggests, in frame uv: the spiral's eye, a chosen thirds or φ point, the centre
     * (radial); the dominant diagonal of the triangles or dynamic symmetry, or a chosen corner diagonal (band).
     * Nil for guides with no meaningful focal geometry.
     */
    static func linked(_ g: GuideCtx) -> FocusMask? {
        let fr = g.frame, gf = g.guideFrame
        guard !fr.isEmpty else { return nil }
        func uv(_ x: CGFloat, _ y: CGFloat) -> (Float, Float) { (Float((x - fr.minX) / fr.width), Float((y - fr.minY) / fr.height)) }
        func radial(_ x: CGFloat, _ y: CGFloat) -> FocusMask { let u = uv(x, y); return FocusMask(type: 2, cx: u.0, cy: u.1) }
        func band(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) -> FocusMask {
            let m = uv((x0 + x1) / 2, (y0 + y1) / 2)
            return FocusMask(type: 1, cx: m.0, cy: m.1, angle: Float(atan2(y1 - y0, x1 - x0)))
        }
        switch g.guide {
        case .spiral:
            return Geo.goals(.spiral, gf, quadrant: g.quadrant, triFlip: g.triFlip).first.map { radial($0.x, $0.y) }
        case .thirds, .phiGrid:
            let a: CGFloat = g.guide == .thirds ? 1.0 / 3.0 : CGFloat(1 - 1 / PHI)
            let x = g.anchor % 2 == 0 ? gf.minX + a * gf.width : gf.maxX - a * gf.width
            let y = g.anchor < 2 ? gf.minY + a * gf.height : gf.maxY - a * gf.height
            return radial(x, y)
        case .centre: return radial(gf.midX, gf.midY)
        case .triangles, .dynamic:
            return g.triFlip ? band(gf.maxX, gf.minY, gf.minX, gf.maxY) : band(gf.minX, gf.minY, gf.maxX, gf.maxY)
        case .diagonals:
            let a = min(max(g.anchor, 0), 3)
            let c = CGPoint(x: a % 2 == 0 ? gf.minX : gf.maxX, y: a < 2 ? gf.minY : gf.maxY)
            let e = Geo.exit(c, a % 2 == 0 ? 1 : -1, a < 2 ? 1 : -1, gf)
            return band(c.x, c.y, e.x, e.y)
        default: return nil
        }
    }

    /** The mask a mode uses right now (same rules as Android). */
    static func resolve(_ mode: ArtMode, _ v: [Float], follow: Bool, guide g: GuideCtx?, subject: CGPoint?) -> FocusMask {
        let link = (follow && mode.follows) ? g.flatMap(linked) : nil
        if mode == .TILT_SHIFT {
            let size = 0.02 + v[2] * 0.3
            if var l = link { l.size = l.type == 2 ? size * 0.8 + 0.04 : size; l.feather = v[4]; return l }
            return FocusMask(type: 1, cx: 0.5, cy: v[1], angle: (v[3] - 0.5) * .pi / 2, size: size, feather: v[4], strength: 0)
        }
        if mode.hasDepth {
            if var l = link { l.size = l.type == 2 ? 0.1 : 0.05; l.feather = 0.9; l.strength = v[4]; return l }
            return FocusMask(type: 2, cx: Float(subject?.x ?? 0.5), cy: Float(subject?.y ?? 0.5), angle: 0, size: 0.08, feather: 1, strength: v[4])
        }
        return FocusMask()
    }
}

/** A photo's look: mode and values, randomness, Follow guide, and the focus mask in the photo's own coordinates. */
struct Recipe {
    var mode: ArtMode
    var values: [Float]
    var seed: Float
    var follow = false
    var mask = FocusMask()
    var isOn: Bool { mode != .OFF }
    func encode() -> String { "\(mode.rawValue)|\(values.map { String($0) }.joined(separator: ","))|\(seed)|v2|\(follow ? 1 : 0)|\(mask.encode())" }
    static func decode(_ s: String?) -> Recipe? {
        guard let p = s?.split(separator: "|", omittingEmptySubsequences: false), p.count >= 3,
              let m = ArtMode(rawValue: String(p[0])), let sd = Float(p[2]) else { return nil }
        let v = m.pad(p[1].split(separator: ",").compactMap { Float($0) })
        if p.count >= 6, p[3] == "v2" { return Recipe(mode: m, values: v, seed: sd, follow: p[4] == "1", mask: FocusMask.decode(p[5]) ?? FocusMask()) }
        return Recipe(mode: m, values: v, seed: sd, follow: false, mask: Focus.resolve(m, v, follow: false, guide: nil, subject: nil))
    }
}

/** The four ways two exposures can meet. Index matches the composite shader. */
enum Blend: Int, CaseIterable {
    case normal, screen, lighten, multiply
    var label: String { ["NORMAL", "SCREEN", "LIGHTEN", "MULTIPLY"][rawValue] }
}
