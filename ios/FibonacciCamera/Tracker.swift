import CoreVideo
import Foundation

/**
 * Follows whatever you tapped. A 15×15 patch around the tap is matched frame to frame on a 192-px-wide
 * greyscale copy of the feed, using zero-mean SAD so exposure changes don't throw it. No model, no network.
 * Coordinates are normalised (0...1) in the picture as shown (portrait, mirrored for the front camera).
 */
final class Tracker {
    struct State { let lost: Bool; let nx: CGFloat; let ny: CGFloat }

    private let tw = 192, pr = 7, sr = 14
    private var pn: Int { (2 * pr + 1) * (2 * pr + 1) }
    private let lock = NSLock()
    private var request: CGPoint?
    private var clearRequested = false

    private var gray = [Float]()
    private var w = 0, h = 0
    private var tpl: [Float]?
    private var u = 0, v = 0, bad = 0, lost = false

    func target(_ n: CGPoint) { lock.lock(); request = n; lock.unlock() }
    func clear() { lock.lock(); clearRequested = true; request = nil; lock.unlock() }

    /** Frame thread. Nil when nothing is being followed. */
    func process(_ pb: CVPixelBuffer) -> State? {
        lock.lock(); let req = request; request = nil; let clr = clearRequested; clearRequested = false; lock.unlock()
        if clr { tpl = nil }
        if tpl == nil && req == nil { return nil }
        sample(pb)
        if let req {
            u = min(max(Int((req.x * CGFloat(w)).rounded()), pr), w - pr - 1)
            v = min(max(Int((req.y * CGFloat(h)).rounded()), pr), h - pr - 1)
            tpl = patchAt(u, v); bad = 0; lost = false
        } else { step() }
        return State(lost: lost, nx: CGFloat(u) / CGFloat(w), ny: CGFloat(v) / CGFloat(h))
    }

    private func sample(_ pb: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pb, .readOnly); defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb)?.assumingMemoryBound(to: UInt8.self) else { return }
        let sw = CVPixelBufferGetWidth(pb), sh = CVPixelBufferGetHeight(pb), rs = CVPixelBufferGetBytesPerRow(pb)
        let nw = tw, nh = max(24, Int((Float(tw) * Float(sh) / Float(sw)).rounded()))
        if nw != w || nh != h { w = nw; h = nh; gray = [Float](repeating: 0, count: w * h) }
        let fx = Float(sw) / Float(w), fy = Float(sh) / Float(h)
        for y in 0..<h {
            let sy = min(sh - 1, Int((Float(y) + 0.5) * fy))
            let row = base + sy * rs
            for x in 0..<w {
                let sx = min(sw - 1, Int((Float(x) + 0.5) * fx))
                let p = row + sx * 4                                   // BGRA
                gray[y * w + x] = 0.114 * Float(p[0]) + 0.587 * Float(p[1]) + 0.299 * Float(p[2])
            }
        }
    }

    private func patchAt(_ x: Int, _ y: Int) -> [Float] {
        var p = [Float](repeating: 0, count: pn); var k = 0; var s: Float = 0
        for dy in -pr...pr { for dx in -pr...pr { let g = gray[(y + dy) * w + (x + dx)]; p[k] = g; k += 1; s += g } }
        s /= Float(pn)
        for i in 0..<pn { p[i] -= s }
        return p
    }

    private func step() {
        guard var t = tpl else { return }
        let r = lost ? sr * 2 : sr
        let x0 = max(pr, u - r), x1 = min(w - pr - 1, u + r), y0 = max(pr, v - r), y1 = min(h - pr - 1, v + r)
        guard x0 <= x1, y0 <= y1 else { return }
        var best = Float.greatestFiniteMagnitude, bx = u, by = v
        let n = Float(pn)
        gray.withUnsafeBufferPointer { g in
            for y in y0...y1 {
                for x in x0...x1 {
                    var m: Float = 0
                    for dy in -pr...pr { let row = (y + dy) * w + x; for dx in -pr...pr { m += g[row + dx] } }
                    m /= n
                    var sad: Float = 0, k = 0
                    let cap = best * n
                    var dy = -pr
                    while dy <= pr && sad < cap {
                        let row = (y + dy) * w + x
                        for dx in -pr...pr { sad += abs(g[row + dx] - m - t[k]); k += 1 }
                        dy += 1
                    }
                    if dy <= pr { continue }          // bailed early: already worse than best
                    sad /= n
                    if sad < best { best = sad; bx = x; by = y }
                }
            }
        }
        if best < 16 {
            u = bx; v = by; bad = 0; lost = false
            let cur = patchAt(bx, by)                  // drift slowly with the subject's look
            for i in 0..<pn { t[i] = t[i] * 0.92 + cur[i] * 0.08 }
            tpl = t
        } else { bad += 1; if bad > 8 { lost = true } }
    }
}
