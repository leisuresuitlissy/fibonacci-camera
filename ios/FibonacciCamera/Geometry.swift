import CoreGraphics
import Foundation

let PHI = 1.6180339887498949

enum Guide: String, CaseIterable {
    case off, thirds, phiGrid, spiral, triangles, diagonals, dynamic, centre, marks
    var label: String {
        switch self {
        case .off: return "OFF"; case .thirds: return "THIRDS"; case .phiGrid: return "φ GRID"; case .spiral: return "SPIRAL"
        case .triangles: return "TRIANGLES"; case .diagonals: return "DIAGONALS"; case .dynamic: return "DYNAMIC"
        case .centre: return "CENTRE"; case .marks: return "4:5 · 1:1"
        }
    }
}

/**
 * A golden spiral with its eye at the origin, longest box side normalised to 1.
 * Quadrant 0..3 = eye top-left, top-right, bottom-left, bottom-right; the long side is vertical
 * unless `wide`, so it matches a portrait frame.
 */
final class Spiral {
    let pts: [CGPoint]
    let bw: CGFloat, bh: CGFloat, ox: CGFloat, oy: CGFloat
    var eyeFx: CGFloat { -ox / bw }
    var eyeFy: CGFloat { -oy / bh }

    init(pts: [CGPoint], bw: CGFloat, bh: CGFloat, ox: CGFloat, oy: CGFloat) {
        self.pts = pts; self.bw = bw; self.bh = bh; self.ox = ox; self.oy = oy
    }

    private static var cache: [Int: Spiral] = [:]
    static func of(_ quadrant: Int, wide: Bool = false, turns: Double = 2.25) -> Spiral {
        let key = quadrant + (wide ? 4 : 0)
        if let s = cache[key] { return s }
        let s = build(quadrant, wide, turns); cache[key] = s; return s
    }

    private static func build(_ quadrant: Int, _ wide: Bool, _ turns: Double) -> Spiral {
        let b = log(PHI) / (Double.pi / 2)
        let n = 600
        var xs = [Double](repeating: 0, count: n + 1), ys = xs
        let flipX = quadrant == 1 || quadrant == 3, flipY = quadrant == 2 || quadrant == 3
        for i in 0...n {
            let th = turns * 2 * Double.pi * Double(i) / Double(n)
            let r = exp(b * th)
            var x = r * cos(th), y = r * sin(th)
            if wide { swap(&x, &y) }
            if flipX { x = -x }
            if flipY { y = -y }
            xs[i] = x; ys[i] = y
        }
        let ex = xs[0], ey = ys[0]
        let mnx = xs.min()!, mxx = xs.max()!, mny = ys.min()!, mxy = ys.max()!
        let bw = mxx - mnx, bh = mxy - mny, k = 1.0 / max(bw, bh)
        var pts = (0...n).map { CGPoint(x: (xs[$0] - ex) * k, y: (ys[$0] - ey) * k) }
        var s = Spiral(pts: pts, bw: bw * k, bh: bh * k, ox: (mnx - ex) * k, oy: (mny - ey) * k)
        // Normalise so the eye lands in the named quadrant whatever the trig did.
        let fixX = (s.eyeFx < 0.5) != !flipX, fixY = (s.eyeFy < 0.5) != !flipY
        if fixX || fixY {
            pts = pts.map { CGPoint(x: fixX ? -$0.x : $0.x, y: fixY ? -$0.y : $0.y) }
            s = Spiral(pts: pts, bw: s.bw, bh: s.bh, ox: fixX ? -(s.ox + s.bw) : s.ox, oy: fixY ? -(s.oy + s.bh) : s.oy)
        }
        return s
    }
}

enum Geo {
    static func footOf(_ a: CGPoint, _ b: CGPoint, _ p: CGPoint) -> CGPoint {
        let vx = b.x - a.x, vy = b.y - a.y
        let t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / (vx * vx + vy * vy)
        return CGPoint(x: a.x + vx * t, y: a.y + vy * t)
    }

    /** Where a ray from p in direction (dx, dy) leaves the frame. */
    static func exit(_ p: CGPoint, _ dx: CGFloat, _ dy: CGFloat, _ fr: CGRect) -> CGPoint {
        var t = CGFloat.greatestFiniteMagnitude
        if dx > 0 { t = min(t, (fr.maxX - p.x) / dx) } else if dx < 0 { t = min(t, (fr.minX - p.x) / dx) }
        if dy > 0 { t = min(t, (fr.maxY - p.y) / dy) } else if dy < 0 { t = min(t, (fr.minY - p.y) / dy) }
        return CGPoint(x: p.x + dx * t, y: p.y + dy * t)
    }

    /** Shoot: the true spiral, unstretched, spanning the frame top to bottom, centred. Returns eye, scale, box left. */
    static func shootSpiral(_ s: Spiral, _ fr: CGRect) -> (eye: CGPoint, u: CGFloat, left: CGFloat) {
        let u = fr.height / s.bh
        let left = fr.minX + (fr.width - s.bw * u) / 2
        return (CGPoint(x: left - s.ox * u, y: fr.minY - s.oy * u), u, left)
    }

    /** The true spiral at the largest size that fits inside the frame, centred (crop screen). */
    static func phiEye(_ s: Spiral, _ fr: CGRect) -> (eye: CGPoint, u: CGFloat) {
        let u = min(fr.width / s.bw, fr.height / s.bh)
        let left = fr.minX + (fr.width - s.bw * u) / 2, top = fr.minY + (fr.height - s.bh * u) / 2
        return (CGPoint(x: left - s.ox * u, y: top - s.oy * u), u)
    }
}
