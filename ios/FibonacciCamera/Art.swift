import Foundation

/** Slider 0 is always intensity; sliders 1...3 feed the shader's p1...p3. Same looks as the Android app. */
enum ArtMode: String, CaseIterable {
    case OFF, TILT_SHIFT, HOLGA, PINHOLE, GRAINY_FILM, CROSS_PROCESS, DREAM_LENS, INFRARED, CYANOTYPE, BLEACH_BYPASS

    var label: String { rawValue.replacingOccurrences(of: "_", with: " ") }
    var fileTag: String { rawValue.lowercased().replacingOccurrences(of: "_", with: "-") }

    var sliders: [(String, Float)] {
        switch self {
        case .OFF: return []
        case .TILT_SHIFT: return [("Intensity", 0.7), ("Focus", 0.55), ("Width", 0.25), ("Angle", 0.5)]
        case .HOLGA: return [("Intensity", 0.7), ("Vignette", 0.6), ("Softness", 0.4), ("Leaks", 0.5)]
        case .PINHOLE: return [("Intensity", 0.85), ("Softness", 0.4), ("Vignette", 0.6)]
        case .GRAINY_FILM: return [("Intensity", 0.9), ("Grain", 0.5), ("Contrast", 0.5)]
        case .CROSS_PROCESS: return [("Intensity", 0.8), ("Colour", 0.5)]
        case .DREAM_LENS: return [("Intensity", 0.45), ("Glow", 0.45), ("Softness", 0.35)]
        case .INFRARED: return [("Intensity", 0.9), ("Contrast", 0.5), ("Halation", 0.3)]
        case .CYANOTYPE: return [("Intensity", 0.9), ("Contrast", 0.5), ("Paper", 0.5)]
        case .BLEACH_BYPASS: return [("Intensity", 0.8), ("Saturation", 0.35), ("Contrast", 0.5)]
        }
    }
    var defaults: [Float] { (0..<4).map { $0 < sliders.count ? sliders[$0].1 : 0 } }

    func loadValues() -> [Float] {
        guard let s = UserDefaults.standard.string(forKey: "artv:\(rawValue)") else { return defaults }
        let v = s.split(separator: ",").compactMap { Float($0) }
        return v.count == 4 ? v : defaults
    }
    func saveValues(_ v: [Float]) { UserDefaults.standard.set(v.map { String($0) }.joined(separator: ","), forKey: "artv:\(rawValue)") }
}

/** A mode, its slider values and the randomness it was shot with: enough to redo the look. */
struct Recipe {
    var mode: ArtMode
    var values: [Float]
    var seed: Float
    var isOn: Bool { mode != .OFF }
    func encode() -> String { "\(mode.rawValue)|\(values.map { String($0) }.joined(separator: ","))|\(seed)" }
    static func decode(_ s: String?) -> Recipe? {
        guard let p = s?.split(separator: "|", omittingEmptySubsequences: false), p.count == 3,
              let m = ArtMode(rawValue: String(p[0])), let sd = Float(p[2]) else { return nil }
        let v = p[1].split(separator: ",").compactMap { Float($0) }
        return v.count == 4 ? Recipe(mode: m, values: v, seed: sd) : nil
    }
}

/** The four ways two exposures can meet. Index matches the composite shader. */
enum Blend: Int, CaseIterable {
    case normal, screen, lighten, multiply
    var label: String { ["NORMAL", "SCREEN", "LIGHTEN", "MULTIPLY"][rawValue] }
}
