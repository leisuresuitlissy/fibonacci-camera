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
        [tap, dbl, pinch].forEach(addGestureRecognizer)
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: self)
        guard frameRect.contains(p) else { return }
        showFocus(p); onTap?(p)
    }
    @objc private func doubled() { onDoubleTap?() }
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
        c.saveGState(); c.clip(to: g)
        GuidePainter.draw(c, g, guide, quadrant: quadrant, triFlip: triFlip, trueSpiral: false)
        c.restoreGState()
        if squareGuide {
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
}
