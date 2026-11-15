import UIKit

/** The app's haptics: short and light, all switched off by the in-app setting. A no-op in the Simulator. */
enum Haptics {
    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "haptics") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "haptics") }
    }
    private static let selection = UISelectionFeedbackGenerator()
    private static let firm = UIImpactFeedbackGenerator(style: .rigid)
    private static let land = UIImpactFeedbackGenerator(style: .medium)

    static func prepare() { guard enabled else { return }; selection.prepare(); firm.prepare() }
    /** A slider detent. */
    static func tick() { guard enabled else { return }; selection.selectionChanged(); selection.prepare() }
    /** A slider's neutral point (0 EV): a touch firmer. */
    static func neutral() { guard enabled else { return }; firm.impactOccurred(intensity: 0.7); firm.prepare() }
    /** The subject landed on a guide point. */
    static func confirm() { guard enabled else { return }; land.impactOccurred(); land.prepare() }
}

/**
 * Dial-like detents for a continuous slider: a tick each time the thumb crosses a tenth of its travel
 * (and at both ends), in either direction, with hysteresis so hovering on a line doesn't buzz.
 * Ticks only while the user is dragging; values set in code never tick. The slider never snaps.
 */
final class DetentSlider: UISlider {
    /** Normalised position (0...1) that gets the firmer neutral tick instead, e.g. 0 EV. */
    var neutral: Float?
    private let h: Float = 0.15
    private var cell = 0
    private var end = -1

    override init(frame: CGRect) { super.init(frame: frame); setup() }
    required init?(coder: NSCoder) { super.init(coder: coder); setup() }
    private func setup() {
        addTarget(self, action: #selector(began), for: .touchDown)
        addTarget(self, action: #selector(moved), for: .valueChanged)
    }
    private var norm: Float { maximumValue > minimumValue ? (value - minimumValue) / (maximumValue - minimumValue) : 0 }
    private func endOf(_ x: Float) -> Int { x <= 0.001 ? 0 : (x >= 9.999 ? 10 : -1) }

    @objc private func began() {
        let x = norm * 10
        cell = min(max(Int(x.rounded(.down)), 0), 10); end = endOf(x)
        Haptics.prepare()
    }
    @objc private func moved() {
        guard isTracking else { return }
        let x = min(max(norm * 10, 0), 10)
        let e = endOf(x)
        if e >= 0 && e != end { end = e; cell = e; Haptics.tick(); return }
        if e < 0 { end = -1 }
        guard x >= Float(cell) + 1 + h || x < Float(cell) - h else { return }
        let old = cell
        cell = min(max(Int(x.rounded(.down)), 0), 10)
        if let n = neutral.map({ $0 * 10 }), n >= Float(min(old, cell) + 1) - 0.0001, n <= Float(max(old, cell)) + 0.0001 { Haptics.neutral() } else { Haptics.tick() }
    }
}
