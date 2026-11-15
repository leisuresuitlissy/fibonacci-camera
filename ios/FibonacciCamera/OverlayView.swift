import UIKit

/** Guides over the frame, plus a tap-to-focus ring that shrinks and fades. Passes taps and pinches up. */
final class OverlayView: UIView {
    var frameRect: CGRect = .zero { didSet { setNeedsDisplay() } }
    /** Holga: guides are worked out on the square the photo will be cropped to. */
    var squareGuide = false { didSet { setNeedsDisplay() } }
    var guide: Guide = .spiral { didSet { setNeedsDisplay() } }
    var quadrant = 0 { didSet { setNeedsDisplay() } }
    var triFlip = false { didSet { setNeedsDisplay() } }

    var onTap: ((CGPoint) -> Void)?
    var onDoubleTap: (() -> Void)?
    var onPinch: ((CGFloat) -> Void)?
    /** A vertical drag on the picture; return true if it moved the focus region. */
    var onFocusDrag: ((CGFloat) -> Bool)?
    var onFocusDragEnd: (() -> Void)?
    /** Hide the guide lines (the guide still drives any linked focus). */
    var hidden = false { didSet { setNeedsDisplay() } }
    private var mask: FocusMask?
    private var maskAlpha: CGFloat = 0
    private var maskShown: CFTimeInterval = 0
    private var maskLink: CADisplayLink?

    private var focusAt: CGPoint?
    private var focusT: CGFloat = 0
    private var link: CADisplayLink?
    private var focusStart: CFTimeInterval = 0

    var guideFrame: CGRect {
        guard squareGuide else { return frameRect }
        let s = min(frameRect.width, frameRect.height)
        return CGRect(x: frameRect.midX - s / 2, y: frameRect.midY - s / 2, width: s, height: s)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear; contentMode = .redraw
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let dbl = UITapGestureRecognizer(target: self, action: #selector(doubled)); dbl.numberOfTapsRequired = 2
        tap.require(toFail: dbl)
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:))); pan.maximumNumberOfTouches = 1
        [tap, dbl, pinch, pan].forEach(addGestureRecognizer)
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: self)
        guard frameRect.contains(p) else { return }
        showFocus(p); onTap?(p)
    }
    @objc private func doubled() { onDoubleTap?() }
    @objc private func panned(_ g: UIPanGestureRecognizer) {
        if g.state == .changed { let dy = g.translation(in: self).y; if onFocusDrag?(dy) == true { g.setTranslation(.zero, in: self) } }
        if g.state == .ended || g.state == .cancelled { onFocusDragEnd?() }
    }

    /** Shows the simulated focus region while it's being adjusted; it fades away by itself and is never saved. */
    func showMask(_ m: FocusMask) {
        mask = m; maskShown = CACurrentMediaTime(); maskAlpha = 1
        if maskLink == nil { maskLink = CADisplayLink(target: self, selector: #selector(maskTick)); maskLink?.add(to: .main, forMode: .common) }
        setNeedsDisplay()
    }
    @objc private func maskTick() {
        let t = CACurrentMediaTime() - maskShown
        maskAlpha = t < 1.4 ? 1 : max(0, 1 - CGFloat((t - 1.4) / 0.5))
        if maskAlpha <= 0 { maskLink?.invalidate(); maskLink = nil }
        setNeedsDisplay()
    }
    @objc private func pinched(_ g: UIPinchGestureRecognizer) {
        if g.state == .changed { onPinch?(g.scale); g.scale = 1 }
    }

    func showFocus(_ p: CGPoint) {
        focusAt = p; focusStart = CACurrentMediaTime()
        link?.invalidate()
        link = CADisplayLink(target: self, selector: #selector(tick)); link?.add(to: .main, forMode: .common)
    }
    @objc private func tick() {
        focusT = CGFloat((CACurrentMediaTime() - focusStart) / 1.1)
        if focusT >= 1 { focusAt = nil; link?.invalidate(); link = nil }
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext(), !frameRect.isEmpty else { return }
        let g = guideFrame
        if !hidden {
            c.saveGState(); c.clip(to: g)
            GuidePainter.draw(c, g, guide, quadrant: quadrant, triFlip: triFlip, trueSpiral: false)
            c.restoreGState()
        }
        if let m = mask, maskAlpha > 0.01 { drawMask(c, m) }
        if squareGuide && !hidden {
            c.setStrokeColor(GuidePainter.gold.withAlphaComponent(0.5).cgColor); c.setLineWidth(1); c.setLineDash(phase: 0, lengths: [5, 5])
            c.stroke(g.insetBy(dx: 0.5, dy: 0.5)); c.setLineDash(phase: 0, lengths: [])
        }
        if let p = focusAt {
            let t = min(1, focusT)
            let r = 38 - 12 * min(1, t * 2.5)
            let a = t < 0.6 ? 1 : max(0, 1 - (t - 0.6) / 0.4)
            c.setStrokeColor(GuidePainter.gold.withAlphaComponent(a).cgColor); c.setLineWidth(1.5)
            c.strokeEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
        }
    }

    private func drawMask(_ c: CGContext, _ m: FocusMask) {
        let fr = frameRect, h = fr.height
        let cx = fr.minX + CGFloat(m.cx) * fr.width, cy = fr.minY + CGFloat(m.cy) * h
        let inner = CGFloat(m.size) * h, outer = CGFloat(m.size + 0.03 + m.feather * 0.55) * h
        c.saveGState(); c.clip(to: fr)
        let solid = GuidePainter.gold.withAlphaComponent(0.86 * maskAlpha).cgColor, soft = GuidePainter.gold.withAlphaComponent(0.55 * maskAlpha).cgColor
        if m.type == 2 {
            c.setStrokeColor(solid); c.setLineWidth(1.3); c.setLineDash(phase: 0, lengths: [])
            c.strokeEllipse(in: CGRect(x: cx - inner, y: cy - inner, width: inner * 2, height: inner * 2))
            c.strokeEllipse(in: CGRect(x: cx - 3, y: cy - 3, width: 6, height: 6))
            c.setStrokeColor(soft); c.setLineWidth(1); c.setLineDash(phase: 0, lengths: [4, 5])
            c.strokeEllipse(in: CGRect(x: cx - outer, y: cy - outer, width: outer * 2, height: outer * 2))
        } else if m.type == 1 {
            let ux = CGFloat(cos(m.angle)), uy = CGFloat(sin(m.angle)), nx = -uy, ny = ux, L = fr.width + fr.height
            for (off, dashed) in [(inner, false), (-inner, false), (outer, true), (-outer, true)] {
                c.setStrokeColor(dashed ? soft : solid); c.setLineWidth(dashed ? 1 : 1.3); c.setLineDash(phase: 0, lengths: dashed ? [4, 5] : [])
                let ox = cx + nx * off, oy = cy + ny * off
                c.move(to: CGPoint(x: ox - ux * L, y: oy - uy * L)); c.addLine(to: CGPoint(x: ox + ux * L, y: oy + uy * L)); c.strokePath()
            }
        }
        c.setLineDash(phase: 0, lengths: [])
        c.restoreGState()
    }
}
