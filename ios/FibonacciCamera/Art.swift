import Foundation

/** Slider 0 is intensity; 1...3 feed p1...p3; 4 is Depth, shared by every look but Tilt Shift. Same looks as Android. */
enum ArtMode: String, CaseIterable {
    case OFF, TILT_SHIFT, HOLGA, PINHOLE, GRAINY_FILM, CROSS_PROCESS, DREAM_LENS, INFRARED, CYANOTYPE, BLEACH_BYPASS

    var label: String { rawValue.replacingOccurrences(of: "_", with: " ") }
    var fileTag: String { rawValue.lowercased().replacingOccurrences(of: "_", with: "-") }

    var sliders: [(String, Float)] {
        switch self {
        case .OFF: return []
        case .TILT_SHIFT: return [("Intensity", 0.7), ("Focus", 0.55), ("Width", 0.25), ("Angle", 0.5)]
        case .HOLGA: return [("Intensity", 0.7), ("Vignette", 0.6), ("Softness", 0.4), ("Leaks", 0.5), depth]
        case .PINHOLE: return [("Intensity", 0.85), ("Softness", 0.4), ("Vignette", 0.6), pad, depth]
        case .GRAINY_FILM: return [("Intensity", 0.9), ("Grain", 0.5), ("Contrast", 0.5), pad, depth]
        case .CROSS_PROCESS: return [("Intensity", 0.8), ("Colour", 0.5), pad, pad, depth]
        case .DREAM_LENS: return [("Intensity", 0.45), ("Glow", 0.45), ("Softness", 0.35), pad, depth]
        case .INFRARED: return [("Intensity", 0.9), ("Contrast", 0.5), ("Halation", 0.3), pad, depth]
        case .CYANOTYPE: return [("Intensity", 0.9), ("Contrast", 0.5), ("Paper", 0.5), pad, depth]
        case .BLEACH_BYPASS: return [("Intensity", 0.8), ("Saturation", 0.35), ("Contrast", 0.5), pad, depth]
        }
    }
    private var depth: (String, Float) { ("Depth", 0.35) }
    private var pad: (String, Float) { ("", 0) }
    var defaults: [Float] { (0..<5).map { $0 < sliders.count ? sliders[$0].1 : 0 } }
    /** Sliders to show; unnamed padding is skipped but indexes stay put. */
    var shown: [Int] { sliders.indices.filter { !sliders[$0].0.isEmpty } }
    var hasDepth: Bool { sliders.count > 4 }
    func pad(_ v: [Float]) -> [Float] { (0..<5).map { $0 < v.count ? v[$0] : defaults[$0] } }

    func loadValues() -> [Float] {
        guard let s = UserDefaults.standard.string(forKey: "artv:\(rawValue)") else { return defaults }
        let v = s.split(separator: ",").compactMap { Float($0) }
        return v.count >= 4 ? pad(v) : defaults
    }
    func saveValues(_ v: [Float]) { UserDefaults.standard.set(v.map { String($0) }.joined(separator: ","), forKey: "artv:\(rawValue)") }
}

/** A mode, its slider values, its randomness, and the point (0...1 across the photo) that stays sharp. */
struct Recipe {
    var mode: ArtMode
    var values: [Float]
    var seed: Float
    var fx: Float = 0.5
    var fy: Float = 0.5
    var isOn: Bool { mode != .OFF }
    func encode() -> String { "\(mode.rawValue)|\(values.map { String($0) }.joined(separator: ","))|\(seed)|\(fx),\(fy)" }
    static func decode(_ s: String?) -> Recipe? {
        guard let p = s?.split(separator: "|", omittingEmptySubsequences: false), p.count >= 3,
              let m = ArtMode(rawValue: String(p[0])), let sd = Float(p[2]) else { return nil }
        let v = p[1].split(separator: ",").compactMap { Float($0) }
        guard v.count >= 4 else { return nil }
        var r = Recipe(mode: m, values: m.pad(v), seed: sd)
        if p.count > 3 { let f = p[3].split(separator: ",").compactMap { Float($0) }; if f.count == 2 { r.fx = f[0]; r.fy = f[1] } }
        return r
    }
}

/** The four ways two exposures can meet. Index matches the composite shader. */
enum Blend: Int, CaseIterable {
    case normal, screen, lighten, multiply
    var label: String { ["NORMAL", "SCREEN", "LIGHTEN", "MULTIPLY"][rawValue] }
}
