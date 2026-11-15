import AVFoundation
import UIKit

/** What the camera screen needs from a camera: the real one on an iPhone, the demo feed in the Simulator. */
protocol CameraSource: AnyObject {
    var onFrame: ((CVPixelBuffer) -> Void)? { get set }
    var onReady: ((ZoomInfo, ClosedRange<Float>) -> Void)? { get set }
    func start(front: Bool)
    func stop()
    func setZoom(display: CGFloat)
    func focus(at n: CGPoint)
    func setBias(_ ev: Float)
    func capture(flash: AVCaptureDevice.FlashMode, rotation: CGFloat, done: @escaping (Data?) -> Void)
}

extension Camera: CameraSource {}

#if targetEnvironment(simulator)
/**
 * Simulator stand-in for the camera. A still photo (the built-in scene, or any photo you choose) is
 * filmed by a virtual handheld camera: it drifts slightly like a hand would, zooms through 0.5×–3× like
 * the real lenses, brightens and darkens with exposure, and mirrors for the front camera. Frames go
 * through exactly the same path as real ones, so guides, tracking, looks, Depth and double exposure all behave
 * as they will on a phone. The shutter "takes" the current framing at full resolution.
 */
final class DemoCamera: CameraSource {
    var onFrame: ((CVPixelBuffer) -> Void)?
    var onReady: ((ZoomInfo, ClosedRange<Float>) -> Void)?

    /** Gentle handheld movement. Off holds the frame perfectly still. */
    var drift = true
    private(set) var usingBuiltIn = true

    private let q = DispatchQueue(label: "fibcam.demo")
    private var timer: DispatchSourceTimer?
    private var scene: CGImage
    private var zoom: CGFloat = 1
    private var bias: Float = 0
    private var front = false
    private var t: Double = 0
    private var pool: CVPixelBufferPool?
    private let outW = 1080, outH = 1440        // 3:4 like the real viewfinder feed

    static let zoomInfo = ZoomInfo(stops: [0.5, 1, 2, 3], minDisplay: 0.5, maxDisplay: 6, k: 1)

    init() {
        scene = DemoCamera.builtIn()
        let attrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: outW, kCVPixelBufferHeightKey as String: outH,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
            kCVPixelBufferMetalCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true]
        CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool)
        Log.info("Simulator: no camera hardware, using the demo feed (\(scene.width)×\(scene.height) scene)")
    }

    static func builtIn() -> CGImage {
        if let url = Bundle.main.url(forResource: "DemoScene", withExtension: "jpg"),
           let img = UIImage(contentsOfFile: url.path), let cg = img.cgImage { return cg }
        Log.error("DemoScene.jpg missing from the app bundle; drawing a plain gradient instead")
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 1600), format: f).image { ctx in
            let g = CGGradient(colorsSpace: nil, colors: [UIColor.systemIndigo.cgColor, UIColor.systemOrange.cgColor] as CFArray, locations: nil)!
            ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: 1600), options: [])
        }.cgImage!
    }

    /** Film a different still: any photo, e.g. from the Simulator's Photos (drag your own pictures onto the Simulator to add them). */
    func useScene(_ img: UIImage?) {
        let cg = img.flatMap { Renderer.upright($0, maxLong: 3200) } ?? DemoCamera.builtIn()
        q.async { self.scene = cg; self.usingBuiltIn = img == nil }
    }

    func start(front: Bool) {
        q.async {
            self.front = front
            if self.timer == nil {
                let tm = DispatchSource.makeTimerSource(queue: self.q)
                tm.schedule(deadline: .now(), repeating: 1.0 / 30)
                tm.setEventHandler { [weak self] in self?.tick() }
                tm.resume(); self.timer = tm
            }
        }
        DispatchQueue.main.async { self.onReady?(DemoCamera.zoomInfo, -2...2) }
    }
    func stop() { q.async { self.timer?.cancel(); self.timer = nil } }
    func setZoom(display: CGFloat) { q.async { self.zoom = display } }
    func focus(at n: CGPoint) {}
    func setBias(_ ev: Float) { q.async { self.bias = ev } }

    /** The part of the scene the virtual camera sees right now, in scene pixels (CG coordinates, origin top-left). */
    private func framing() -> CGRect {
        let sw = CGFloat(scene.width), sh = CGFloat(scene.height), aspect = CGFloat(outW) / CGFloat(outH)
        // 0.5× sees the largest 3:4 piece of the scene; each zoom step narrows it like a longer lens
        var bw = sw, bh = sw / aspect
        if bh > sh { bh = sh; bw = sh * aspect }
        let s = min(1, 0.5 / max(zoom, 0.5))
        let w = bw * s, h = bh * s
        var cx = sw / 2, cy = sh / 2
        if drift {
            cx += (sin(t * 0.23) * 0.05 + sin(t * 0.71) * 0.008) * w
            cy += (cos(t * 0.17) * 0.04 + sin(t * 0.53) * 0.008) * h
        }
        cx = min(max(cx, w / 2), sw - w / 2); cy = min(max(cy, h / 2), sh - h / 2)
        return CGRect(x: cx - w / 2, y: cy - h / 2, width: w, height: h)
    }

    private func draw(_ ctx: CGContext, _ w: Int, _ h: Int, _ r: CGRect) {
        let sw = CGFloat(scene.width), sh = CGFloat(scene.height)
        let k = CGFloat(w) / r.width
        ctx.interpolationQuality = .medium
        if front { ctx.translateBy(x: CGFloat(w), y: 0); ctx.scaleBy(x: -1, y: 1) }   // selfie mirror, like the real front camera
        // CG draws with the origin bottom-left
        ctx.draw(scene, in: CGRect(x: -r.minX * k, y: -(sh - r.maxY) * k, width: sw * k, height: sh * k))
        if bias > 0.01 {        // exposure, roughly: lift towards white or sink towards black
            ctx.setBlendMode(.screen); ctx.setFillColor(UIColor(white: 1, alpha: CGFloat(bias) * 0.2).cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        } else if bias < -0.01 {
            ctx.setBlendMode(.normal); ctx.setFillColor(UIColor(white: 0, alpha: CGFloat(-bias) * 0.24).cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        }
    }

    private func tick() {
        t += 1.0 / 30
        guard let pool else { return }
        var pb: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
        guard let pb else { return }
        CVPixelBufferLockBaseAddress(pb, [])
        if let base = CVPixelBufferGetBaseAddress(pb),
           let ctx = CGContext(data: base, width: outW, height: outH, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
                               space: CGColorSpace(name: CGColorSpace.sRGB)!,
                               bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
            draw(ctx, outW, outH, framing())
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        onFrame?(pb)
    }

    /** "Takes" the current framing at the scene's full resolution, as a JPEG like a real camera file. */
    func capture(flash: AVCaptureDevice.FlashMode, rotation: CGFloat, done: @escaping (Data?) -> Void) {
        q.async {
            let r = self.framing()
            let w = max(1200, Int(r.width.rounded())), h = Int((CGFloat(w) * CGFloat(self.outH) / CGFloat(self.outW)).rounded())
            var data: Data?
            if let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                   bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
                self.draw(ctx, w, h, r)
                data = ctx.makeImage().flatMap { UIImage(cgImage: $0).jpegData(compressionQuality: 0.95) }
            }
            DispatchQueue.main.async { done(data) }
        }
    }
}
#endif
