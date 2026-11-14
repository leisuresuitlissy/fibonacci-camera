import UIKit

/** Draws any guide into a rectangle: Shoot (spiral full height) or Crop (`trueSpiral`: largest fit). */
enum GuidePainter {
    static let gold = UIColor(red: 0xE0 / 255, green: 0xB2 / 255, blue: 0x52 / 255, alpha: 1)

    static func draw(_ c: CGContext, _ fr: CGRect, _ guide: Guide, quadrant: Int, triFlip: Bool, trueSpiral: Bool) {
        c.saveGState(); defer { c.restoreGState() }
        c.setLineCap(.round); c.setLineJoin(.round)
        switch guide {
        case .off: break
        case .thirds: grid(c, fr, [CGFloat(1) / 3, CGFloat(2) / 3])
        case .phiGrid: grid(c, fr, [CGFloat(1 - 1 / PHI), CGFloat(1 / PHI)])
        case .spiral: spiral(c, fr, quadrant, trueSpiral)
        case .triangles: triangles(c, fr, triFlip)
        case .diagonals: diagonals(c, fr)
        case .dynamic: dynamic(c, fr)
        case .centre: centre(c, fr)
        case .marks: marks(c, fr)
        }
    }

    private static func lineStyle(_ c: CGContext) { c.setStrokeColor(UIColor(white: 1, alpha: 0.38).cgColor); c.setLineWidth(1); c.setLineDash(phase: 0, lengths: []) }
    private static func seg(_ c: CGContext, _ a: CGPoint, _ b: CGPoint) { lineStyle(c); c.move(to: a); c.addLine(to: b); c.strokePath() }
    private static func power(_ c: CGContext, _ p: CGPoint, _ r: CGFloat = 4) {
        c.setStrokeColor(gold.cgColor); c.setLineWidth(1.5); c.setLineDash(phase: 0, lengths: [])
        c.strokeEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
    }
    private static func corners(_ fr: CGRect) -> (CGPoint, CGPoint, CGPoint, CGPoint) {
        (CGPoint(x: fr.minX, y: fr.minY), CGPoint(x: fr.maxX, y: fr.minY), CGPoint(x: fr.maxX, y: fr.maxY), CGPoint(x: fr.minX, y: fr.maxY))
    }

    private static func grid(_ c: CGContext, _ fr: CGRect, _ fs: [CGFloat]) {
        for f in fs {
            let x = fr.minX + f * fr.width, y = fr.minY + f * fr.height
            seg(c, CGPoint(x: x, y: fr.minY), CGPoint(x: x, y: fr.maxY)); seg(c, CGPoint(x: fr.minX, y: y), CGPoint(x: fr.maxX, y: y))
        }
        for a in fs { for b in fs { power(c, CGPoint(x: fr.minX + a * fr.width, y: fr.minY + b * fr.height)) } }
    }

    private static func spiral(_ c: CGContext, _ fr: CGRect, _ quadrant: Int, _ trueSpiral: Bool) {
        let s = Spiral.of(quadrant, wide: trueSpiral && fr.width > fr.height)
        let eye: CGPoint, u: CGFloat
        if trueSpiral { (eye, u) = Geo.phiEye(s, fr) } else {
            let r = Geo.shootSpiral(s, fr); eye = r.eye; u = r.u
            c.setStrokeColor(gold.withAlphaComponent(0.47).cgColor); c.setLineWidth(1); c.setLineDash(phase: 0, lengths: [5, 5])
            c.stroke(CGRect(x: r.left, y: fr.minY + 0.5, width: s.bw * u, height: fr.height - 1))
            c.setLineDash(phase: 0, lengths: [])
        }
        c.setStrokeColor(UIColor(white: 1, alpha: 0.86).cgColor); c.setLineWidth(1.4)
        c.move(to: CGPoint(x: eye.x + s.pts[0].x * u, y: eye.y + s.pts[0].y * u))
        for p in s.pts.dropFirst() { c.addLine(to: CGPoint(x: eye.x + p.x * u, y: eye.y + p.y * u)) }
        c.strokePath()
        power(c, eye, 11)
        c.setFillColor(gold.cgColor); c.fillEllipse(in: CGRect(x: eye.x - 2.5, y: eye.y - 2.5, width: 5, height: 5))
    }

    private static func triangles(_ c: CGContext, _ fr: CGRect, _ flip: Bool) {
        let (tl, tr, br, bl) = corners(fr)
        let (a, b, c1, c2) = flip ? (tr, bl, tl, br) : (tl, br, tr, bl)
        let f1 = Geo.footOf(a, b, c1), f2 = Geo.footOf(a, b, c2)
        seg(c, a, b); seg(c, c1, f1); seg(c, c2, f2); power(c, f1); power(c, f2)
    }

    private static func diagonals(_ c: CGContext, _ fr: CGRect) {
        let (tl, tr, br, bl) = corners(fr)
        for (p, dx, dy) in [(tl, CGFloat(1), CGFloat(1)), (tr, -1, 1), (bl, 1, -1), (br, -1, -1)] {
            let q = Geo.exit(p, dx, dy, fr); seg(c, p, q); power(c, q, 3.2)
        }
    }

    private static func dynamic(_ c: CGContext, _ fr: CGRect) {
        let (tl, tr, br, bl) = corners(fr)
        seg(c, tl, br); seg(c, tr, bl)
        func recip(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) {
            let f = Geo.footOf(a, b, p)
            let dx = f.x - p.x, dy = f.y - p.y, n = hypot(dx, dy)
            seg(c, p, Geo.exit(p, dx / n, dy / n, fr)); power(c, f)
        }
        recip(tl, tr, bl); recip(br, tr, bl); recip(tr, tl, br); recip(bl, tl, br)
    }

    private static func centre(_ c: CGContext, _ fr: CGRect) {
        seg(c, CGPoint(x: fr.midX, y: fr.minY), CGPoint(x: fr.midX, y: fr.maxY))
        seg(c, CGPoint(x: fr.minX, y: fr.midY), CGPoint(x: fr.maxX, y: fr.midY))
        lineStyle(c); c.strokeEllipse(in: CGRect(x: fr.midX - 18, y: fr.midY - 18, width: 36, height: 36))
        c.setFillColor(gold.cgColor); c.fillEllipse(in: CGRect(x: fr.midX - 2.5, y: fr.midY - 2.5, width: 5, height: 5))
    }

    private static func marks(_ c: CGContext, _ fr: CGRect) {
        c.setStrokeColor(UIColor(white: 1, alpha: 0.59).cgColor); c.setLineWidth(1); c.setLineDash(phase: 0, lengths: [6, 5])
        for ar: CGFloat in [1.25, 1] {
            let h = fr.width * ar
            if h > fr.height { continue }
            c.stroke(CGRect(x: fr.minX + 1, y: fr.minY + (fr.height - h) / 2, width: fr.width - 2, height: h))
        }
        c.setLineDash(phase: 0, lengths: [])
    }
}
