import MetalKit
import simd

/**
 * The viewfinder. Camera frames (already portrait) are composited with the first exposure, then the
 * look is run over the pair, letterboxed into a 3:4 frame. Guides are drawn above it by OverlayView.
 */
final class LiveView: MTKView, MTKViewDelegate {
    private let r = Renderer.shared
    private let lock = NSLock()
    private var pending: CVPixelBuffer?
    private var mid: MTLTexture?

    var ghost: MTLTexture?
    var blend: Blend = .screen
    var strength: Float = 0.6
    var recipe: Recipe?
    /** Width / height of the camera picture (3:4 portrait). */
    var aspect: CGFloat = 3.0 / 4.0 { didSet { if aspect != oldValue { setNeedsLayout() } } }
    var onFrame: ((CGRect) -> Void)?
    private(set) var frameRect: CGRect = .zero

    init() {
        super.init(frame: .zero, device: Renderer.shared.device)
        delegate = self
        colorPixelFormat = .bgra8Unorm
        framebufferOnly = true
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        preferredFramesPerSecond = 30
        backgroundColor = .black
        isOpaque = true
    }
    required init(coder: NSCoder) { fatalError() }

    func push(_ pb: CVPixelBuffer) {
        lock.lock(); pending = pb; lock.unlock()
        let a = CGFloat(CVPixelBufferGetWidth(pb)) / CGFloat(CVPixelBufferGetHeight(pb))
        if abs(a - aspect) > 0.01 { DispatchQueue.main.async { self.aspect = a } }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        guard b.width > 0, b.height > 0 else { return }
        var fr: CGRect
        if b.width / b.height > aspect { let w = b.height * aspect; fr = CGRect(x: (b.width - w) / 2, y: 0, width: w, height: b.height) }
        else { let h = b.width / aspect; fr = CGRect(x: 0, y: (b.height - h) / 2, width: b.width, height: h) }
        fr = fr.integral
        if fr != frameRect { frameRect = fr; onFrame?(fr) }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        lock.lock(); let pb = pending; lock.unlock()
        guard let pb, let cam = r.texture(from: pb), let rp = currentRenderPassDescriptor, let drawable = currentDrawable,
              let cmd = r.queue.makeCommandBuffer() else { return }
        if mid == nil || mid!.width != cam.width || mid!.height != cam.height { mid = r.makeTarget(cam.width, cam.height) }
        guard let mid else { return }
        let m = ghost.map { Renderer.cover(out: CGSize(width: cam.width, height: cam.height), top: CGSize(width: $0.width, height: $0.height)) } ?? matrix_identity_float3x3
        r.encodeComposite(cmd, base: cam, top: ghost, m: m, blend: blend, strength: strength, into: mid)
        rp.colorAttachments[0].loadAction = .clear
        let k = contentScaleFactor
        let vp = CGRect(x: frameRect.minX * k, y: frameRect.minY * k, width: frameRect.width * k, height: frameRect.height * k)
        r.encodeArt(cmd, src: mid, recipe: recipe, pass: rp, viewport: vp)
        cmd.present(drawable)
        cmd.commit()
    }
}
