import UIKit

/**
 * The photo, fixed and fitted, with a crop box over it. The box keeps its ratio, can be moved, pinched
 * or pulled by a corner, tilts with the straighten dial, and never leaves the photo. Crop numbers are in
 * display-image pixels; saving scales them to full size. In Blend, fingers move the second photo instead.
 */
final class CropCanvas: UIView {
    var onChange: (() -> Void)?
    var onSecondChange: (() -> Void)?

    /** What to draw: the (combined, styled) picture at display size. */
    var pic: CGImage? { didSet { setNeedsDisplay() } }
    var bw: CGFloat = 1, bh: CGFloat = 1
    var secondSize: CGSize?

    var cx: CGFloat = 0, cy: CGFloat = 0, cw: CGFloat = 0
    var aspect: CGFloat = CGFloat(1 / PHI)
    var theta: CGFloat = 0
    var guide = Guide.spiral { didSet { setNeedsDisplay() } }
    var quadrant = 0 { didSet { setNeedsDisplay() } }
    var triFlip = false
    var ch: CGFloat { cw / aspect }

    var s2x: CGFloat = 0, s2y: CGFloat = 0, s2w: CGFloat = 1, s2r: CGFloat = 0
    var editingSecond = false { didSet { setNeedsDisplay() } }

    private var k: CGFloat = 1, ox: CGFloat = 0, oy: CGFloat = 0

    override init(frame: CGRect) { super.init(frame: frame); backgroundColor = .black; isMultipleTouchEnabled = true; contentMode = .redraw }
    required init?(coder: NSCoder) { fatalError() }

    private func fit() {
        let pad: CGFloat = 20
        k = min((bounds.width - 2 * pad) / bw, (bounds.height - 2 * pad) / bh)
        ox = (bounds.width - bw * k) / 2; oy = (bounds.height - bh * k) / 2
    }
    override func layoutSubviews() { super.layoutSubviews(); fit(); setNeedsDisplay() }
    private func toScreen(_ p: CGPoint) -> CGPoint { CGPoint(x: ox + p.x * k, y: oy + p.y * k) }

    func corners(_ x: CGFloat? = nil, _ y: CGFloat? = nil, _ w: CGFloat? = nil, _ t: CGFloat? = nil) -> [CGPoint] {
        let x = x ?? cx, y = y ?? cy, w = w ?? cw, t = t ?? theta
        let h = w / aspect, c = cos(t), s = sin(t)
        return [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)].map { CGPoint(x: x + $0.0 * c - $0.1 * s, y: y + $0.0 * s + $0.1 * c) }
    }
    func fits(_ x: CGFloat? = nil, _ y: CGFloat? = nil, _ w: CGFloat? = nil) -> Bool {
        corners(x, y, w).allSatisfy { $0.x >= -0.5 && $0.y >= -0.5 && $0.x <= bw + 0.5 && $0.y <= bh + 0.5 }
    }
    func maxWidth(_ x: CGFloat? = nil, _ y: CGFloat? = nil) -> CGFloat {
        var lo: CGFloat = 0, hi = max(bw, bh) * 2
        for _ in 0..<30 { let mid = (lo + hi) / 2; if fits(x, y, mid) { lo = mid } else { hi = mid } }
        return lo
    }
    func reset() { cx = bw / 2; cy = bh / 2; cw = maxWidth(); changed() }
    func refit() {
        if !fits() {
            var mw = maxWidth()
            if mw < min(bw, bh) * 0.25 { cx = bw / 2; cy = bh / 2; mw = maxWidth() }
            cw = min(cw, mw)
        }
        changed()
    }
    private func changed() { setNeedsDisplay(); onChange?() }

    /** The crop's bounding box in display px: the look is measured against it, as on Android. */
    var cropBox: CGRect {
        let q = corners(); let xs = q.map(\.x), ys = q.map(\.y)
        return CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
    }

    func resetSecond() {
        guard let t = secondSize else { return }
        s2x = bw / 2; s2y = bh / 2; s2r = 0
        s2w = max(1, (bh / t.height) * t.width / bw)
        setNeedsDisplay(); onSecondChange?()
    }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext(), let pic, k > 0 else { return }
        UIImage(cgImage: pic).draw(in: CGRect(x: ox, y: oy, width: bw * k, height: bh * k))
        let q = corners().map(toScreen)
        let crop = UIBezierPath(); crop.move(to: q[0]); q.dropFirst().forEach { crop.addLine(to: $0) }; crop.close()
        let dim = UIBezierPath(rect: bounds); dim.append(crop); dim.usesEvenOddFillRule = true
        UIColor(red: 0.03, green: 0.03, blue: 0.05, alpha: 0.74).setFill(); dim.fill()
        UIColor(white: 1, alpha: 0.9).setStroke(); crop.lineWidth = 1.2; crop.stroke()
        // guide, in the crop's own tilted frame
        let sc = toScreen(CGPoint(x: cx, y: cy)), w = cw * k, h = ch * k
        c.saveGState(); c.translateBy(x: sc.x, y: sc.y); c.rotate(by: theta)
        let fr = CGRect(x: -w / 2, y: -h / 2, width: w, height: h)
        c.clip(to: fr)
        GuidePainter.draw(c, fr, guide, quadrant: quadrant, triFlip: triFlip, trueSpiral: true)
        c.restoreGState()
        if editingSecond, let t = secondSize {
            let g = s2w * bw / t.width, r = s2r * .pi / 180
            let pts = [CGPoint(x: -t.width / 2, y: -t.height / 2), CGPoint(x: t.width / 2, y: -t.height / 2), CGPoint(x: t.width / 2, y: t.height / 2), CGPoint(x: -t.width / 2, y: t.height / 2)]
                .map { p -> CGPoint in let x = p.x * g, y = p.y * g; return toScreen(CGPoint(x: s2x + x * cos(r) - y * sin(r), y: s2y + x * sin(r) + y * cos(r))) }
            let o = UIBezierPath(); o.move(to: pts[0]); pts.dropFirst().forEach { o.addLine(to: $0) }; o.close()
            o.lineWidth = 1.4; o.setLineDash([6, 4], count: 2, phase: 0); Style.gold.withAlphaComponent(0.9).setStroke(); o.stroke()
        } else {
            for p in q {
                let r = CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12)
                UIColor(white: 1, alpha: 0.95).setFill(); c.fillEllipse(in: r)
                c.setStrokeColor(UIColor(white: 0.05, alpha: 0.55).cgColor); c.setLineWidth(1); c.strokeEllipse(in: r)
            }
        }
    }

    // ------------------------------------------------------------ touch
    private enum Mode { case none, move, corner, pinch }
    private var mode = Mode.none
    private var active: [UITouch] = []
    private var last = CGPoint.zero
    private var p0Dist: CGFloat = 1, p0Ang: CGFloat = 0, p0Mid = CGPoint.zero
    private var w0: CGFloat = 0, r0: CGFloat = 0, x0: CGFloat = 0, y0: CGFloat = 0

    private func setWidth(_ w: CGFloat) {
        let minW = min(bw, bh) * 0.08
        cw = min(max(w, minW), max(minW, maxWidth())); changed()
    }
    private func inside(_ s: CGPoint) -> Bool {
        let px = (s.x - ox) / k - cx, py = (s.y - oy) / k - cy
        let c = cos(-theta), sn = sin(-theta)
        return abs(px * c - py * sn) <= cw / 2 && abs(px * sn + py * c) <= ch / 2
    }
    private func tryMove(_ dx: CGFloat, _ dy: CGFloat) {
        if fits(cx + dx, cy + dy) { cx += dx; cy += dy }
        else if fits(cx + dx, cy) { cx += dx }
        else if fits(cx, cy + dy) { cy += dy }
        else {
            var lo: CGFloat = 0, hi: CGFloat = 1
            for _ in 0..<16 { let t = (lo + hi) / 2; if fits(cx + dx * t, cy + dy * t) { lo = t } else { hi = t } }
            cx += dx * lo; cy += dy * lo
        }
        changed()
    }

    private func twoStart() {
        let a = active[0].location(in: self), b = active[1].location(in: self)
        p0Dist = max(1, hypot(b.x - a.x, b.y - a.y)); p0Ang = atan2(b.y - a.y, b.x - a.x) * 180 / .pi
        p0Mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
        w0 = editingSecond ? s2w : cw; r0 = s2r; x0 = s2x; y0 = s2y
        mode = .pinch
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard pic != nil else { return }
        active.append(contentsOf: touches)
        if active.count == 1 {
            let p = active[0].location(in: self); last = p
            if editingSecond && secondSize != nil { mode = .move; return }
            let near = corners().map(toScreen).contains { hypot($0.x - p.x, $0.y - p.y) < 30 }
            mode = near ? .corner : (inside(p) ? .move : .none)
        } else if active.count == 2 { twoStart() }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if mode == .pinch, active.count >= 2 {
            let a = active[0].location(in: self), b = active[1].location(in: self)
            let dist = hypot(b.x - a.x, b.y - a.y)
            if editingSecond && secondSize != nil {
                let ang = atan2(b.y - a.y, b.x - a.x) * 180 / .pi
                let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                s2w = min(max(w0 * dist / p0Dist, 0.1), 6)
                var r = r0 + (ang - p0Ang); while r > 180 { r -= 360 }; while r < -180 { r += 360 }
                s2r = abs(r) < 1.5 ? 0 : r                          // sticks at level
                s2x = x0 + (mid.x - p0Mid.x) / k; s2y = y0 + (mid.y - p0Mid.y) / k
                setNeedsDisplay(); onSecondChange?()
            } else { setWidth(w0 * dist / p0Dist) }
            return
        }
        guard active.count == 1 else { return }
        let p = active[0].location(in: self)
        switch mode {
        case .move:
            if editingSecond && secondSize != nil { s2x += (p.x - last.x) / k; s2y += (p.y - last.y) / k; setNeedsDisplay(); onSecondChange?() }
            else { tryMove((p.x - last.x) / k, (p.y - last.y) / k) }
        case .corner:
            let sc = toScreen(CGPoint(x: cx, y: cy))
            let before = hypot(last.x - sc.x, last.y - sc.y), after = hypot(p.x - sc.x, p.y - sc.y)
            if before > 1 { setWidth(cw * after / before) }
        default: break
        }
        last = p
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        active.removeAll { touches.contains($0) }
        if active.count == 1 { last = active[0].location(in: self); mode = (editingSecond && secondSize != nil) ? .move : .none }
        if active.isEmpty { mode = .none }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { touchesEnded(touches, with: event) }
}

/** Straighten dial: ±45°, half-degree steps, sticks at 0, double-tap resets. */
final class DialView: UIView {
    var value: CGFloat = 0 { didSet { setNeedsDisplay() } }
    var onChange: ((CGFloat) -> Void)?
    private var start: CGFloat = 0
    private var raw: CGFloat = 0
    private let pxPerDeg: CGFloat = 9

    override init(frame: CGRect) {
        super.init(frame: frame); isOpaque = false; backgroundColor = .clear
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
        let dt = UITapGestureRecognizer(target: self, action: #selector(reset)); dt.numberOfTapsRequired = 2; addGestureRecognizer(dt)
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func reset() { value = 0; raw = 0; onChange?(0) }
    @objc private func pan(_ g: UIPanGestureRecognizer) {
        if g.state == .began { start = value; raw = value }
        raw = min(45, max(-45, start - g.translation(in: self).x / pxPerDeg))   // unrounded, so slow drags still move
        var v = (raw * 2).rounded() / 2
        if abs(raw) < 0.75 { v = 0 }
        if v != value { value = v; onChange?(v) }
    }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let mid = bounds.midX, base = bounds.height - 14
        for d in -45...45 {
            let x = mid + (CGFloat(d) - value) * pxPerDeg
            if x < -2 || x > bounds.width + 2 { continue }
            let major = d % 5 == 0
            c.setStrokeColor((d == 0 ? Style.gold : Style.muted.withAlphaComponent(major ? 0.9 : 0.45)).cgColor); c.setLineWidth(1)
            c.move(to: CGPoint(x: x, y: base)); c.addLine(to: CGPoint(x: x, y: base - (major ? 16 : 9))); c.strokePath()
        }
        c.setStrokeColor(Style.gold.cgColor); c.setLineWidth(2)
        c.move(to: CGPoint(x: mid, y: base + 4)); c.addLine(to: CGPoint(x: mid, y: base - 22)); c.strokePath()
        let t = String(format: "%.1f°", value) as NSString
        let a: [NSAttributedString.Key: Any] = [.font: Style.mono(11), .foregroundColor: value == 0 ? Style.muted : Style.gold]
        let sz = t.size(withAttributes: a)
        t.draw(at: CGPoint(x: mid - sz.width / 2, y: 2), withAttributes: a)
    }
}
