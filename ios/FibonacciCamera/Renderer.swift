import Metal
import MetalKit
import CoreVideo
import UIKit
import simd

/** Uniforms shared with Shaders.metal (layout must match `struct U`). */
struct ArtUniforms {
    var org = SIMD2<Float>(0, 0)
    var res = SIMD2<Float>(1, 1)
    var amt: Float = 0, p1: Float = 0, p2: Float = 0, p3: Float = 0, seed: Float = 0
    var vpo = SIMD2<Float>(0, 0)
    var vps = SIMD2<Float>(1, 1)
    var foc = SIMD2<Float>(0.5, 0.5)
    var dep: Float = 0
}

/** Layout must match `struct B` in Shaders.metal. */
struct BlendUniforms {
    var m = matrix_identity_float3x3
    var baseSize = SIMD2<Float>(1, 1)
    var topSize = SIMD2<Float>(1, 1)
    var strength: Float = 0
    var mode: Int32 = 0
    var hasTop: Int32 = 0
}

/**
 * One GPU path for everything: the live viewfinder, the full-size saved copies and the editor all
 * go through the same composite (two exposures) and art (the look) shaders, so what you see is what's saved.
 */
final class Renderer {
    static let shared = Renderer()

    let device: MTLDevice
    let queue: MTLCommandQueue
    private let library: MTLLibrary
    private var pipes: [String: MTLRenderPipelineState] = [:]
    private(set) var textureCache: CVMetalTextureCache?
    private let loader: MTKTextureLoader

    private init() {
        guard let dev = MTLCreateSystemDefaultDevice() else { fatalError("[Fibcam] This Mac/iPhone has no Metal GPU, so the viewfinder can't run.") }
        device = dev
        guard let q = dev.makeCommandQueue() else { fatalError("[Fibcam] Couldn't create a Metal command queue.") }
        queue = q
        guard let lib = dev.makeDefaultLibrary() else { fatalError("[Fibcam] Shaders.metal didn't compile into the app (default.metallib missing).") }
        library = lib
        loader = MTKTextureLoader(device: dev)
        let ok = CVMetalTextureCacheCreate(nil, nil, dev, nil, &textureCache)
        Log.info("Metal \(dev.name) · shaders \(lib.functionNames.count) · camera texture cache \(ok == kCVReturnSuccess ? "ok" : "unavailable (will upload frames instead)")")
    }

    func pipe(_ fragment: String) -> MTLRenderPipelineState? {
        if let p = pipes[fragment] { return p }
        let d = MTLRenderPipelineDescriptor()
        d.vertexFunction = library.makeFunction(name: "vfull")
        guard let f = library.makeFunction(name: fragment) else { return nil }
        d.fragmentFunction = f
        d.colorAttachments[0].pixelFormat = .bgra8Unorm
        var p: MTLRenderPipelineState?
        do { p = try device.makeRenderPipelineState(descriptor: d) } catch { Log.error("pipeline \(fragment): \(error)") }
        pipes[fragment] = p
        return p
    }

    func makeTarget(_ w: Int, _ h: Int) -> MTLTexture? {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: max(1, w), height: max(1, h), mipmapped: false)
        d.usage = [.renderTarget, .shaderRead]
        d.storageMode = .private          // the Simulator refuses shared textures; pixels come back through a buffer instead
        return device.makeTexture(descriptor: d)
    }

    func texture(from pb: CVPixelBuffer) -> MTLTexture? {
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        if let cache = textureCache {
            var t: CVMetalTexture?
            CVMetalTextureCacheCreateTextureFromImage(nil, cache, pb, nil, .bgra8Unorm, w, h, 0, &t)
            if let tex = t.flatMap({ CVMetalTextureGetTexture($0) }) { return tex }
        }
        return upload(pb, w, h)       // fallback: copy the frame in through a buffer
    }

    private var uploadTex: MTLTexture?
    private func upload(_ pb: CVPixelBuffer, _ w: Int, _ h: Int) -> MTLTexture? {
        CVPixelBufferLockBaseAddress(pb, .readOnly); defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return nil }
        let row = CVPixelBufferGetBytesPerRow(pb)
        guard let buf = device.makeBuffer(bytes: base, length: row * h, options: .storageModeShared) else { return nil }
        if uploadTex == nil || uploadTex!.width != w || uploadTex!.height != h {
            let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
            d.usage = [.shaderRead]; d.storageMode = .private
            uploadTex = device.makeTexture(descriptor: d)
        }
        guard let tex = uploadTex, let cmd = queue.makeCommandBuffer(), let blit = cmd.makeBlitCommandEncoder() else { return nil }
        blit.copy(from: buf, sourceOffset: 0, sourceBytesPerRow: row, sourceBytesPerImage: row * h,
                  sourceSize: MTLSize(width: w, height: h, depth: 1), to: tex, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin())
        blit.endEncoding(); cmd.commit(); cmd.waitUntilCompleted()
        return tex
    }

    /** Draws the image upright (EXIF orientation applied), optionally shrunk so its long side is <= maxLong. */
    static func upright(_ img: UIImage, maxLong: CGFloat = 0) -> CGImage? {
        var size = CGSize(width: img.size.width * img.scale, height: img.size.height * img.scale)
        if maxLong > 0 { let k = min(1, maxLong / max(size.width, size.height)); size = CGSize(width: (size.width * k).rounded(), height: (size.height * k).rounded()) }
        if img.imageOrientation == .up, maxLong == 0, let cg = img.cgImage { return cg }
        let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = true
        return UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }.cgImage
    }

    func texture(from cg: CGImage) -> MTLTexture? {
        try? loader.newTexture(cgImage: cg, options: [.SRGB: false, .textureUsage: MTLTextureUsage.shaderRead.rawValue, .textureStorageMode: MTLStorageMode.private.rawValue])
    }

    // ---------------------------------------------------------------- passes
    func encodeComposite(_ cmd: MTLCommandBuffer, base: MTLTexture, top: MTLTexture?, m: simd_float3x3, blend: Blend, strength: Float, into target: MTLTexture) {
        let rp = MTLRenderPassDescriptor()
        rp.colorAttachments[0].texture = target; rp.colorAttachments[0].loadAction = .dontCare; rp.colorAttachments[0].storeAction = .store
        guard let enc = cmd.makeRenderCommandEncoder(descriptor: rp), let p = pipe("composite") else { return }
        var b = BlendUniforms(m: m, baseSize: SIMD2(Float(target.width), Float(target.height)),
                              topSize: SIMD2(Float(top?.width ?? 1), Float(top?.height ?? 1)),
                              strength: strength, mode: Int32(blend.rawValue), hasTop: top == nil ? 0 : 1)
        enc.setRenderPipelineState(p)
        enc.setFragmentTexture(base, index: 0); enc.setFragmentTexture(top ?? base, index: 1)
        enc.setFragmentBytes(&b, length: MemoryLayout<BlendUniforms>.stride, index: 0)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        enc.endEncoding()
    }

    /** Runs the look (or a plain copy) from src into a pass, inside `viewport` (px). The look is measured against src. */
    func encodeArt(_ cmd: MTLCommandBuffer, src: MTLTexture, recipe: Recipe?, pass rp: MTLRenderPassDescriptor, viewport: CGRect, measure: CGRect? = nil) {
        guard let enc = cmd.makeRenderCommandEncoder(descriptor: rp) else { return }
        enc.setViewport(MTLViewport(originX: Double(viewport.minX), originY: Double(viewport.minY), width: Double(viewport.width), height: Double(viewport.height), znear: 0, zfar: 1))
        if let r = recipe, r.isOn, let p = pipe("art_\(r.mode.rawValue)") {
            var u = ArtUniforms()
            u.res = SIMD2(Float(src.width), Float(src.height))
            u.dep = r.values.count > 4 ? r.values[4] : 0
            u.foc = SIMD2(r.fx, r.fy)
            if let m = measure {          // the look is measured against this box; the focus point moves into it
                u.org = SIMD2(Float(m.minX), Float(m.minY)); u.res = SIMD2(Float(m.width), Float(m.height))
                u.foc = SIMD2((r.fx * Float(src.width) - Float(m.minX)) / Float(m.width), (r.fy * Float(src.height) - Float(m.minY)) / Float(m.height))
            }
            u.amt = r.values[0]; u.p1 = r.values[1]; u.p2 = r.values[2]; u.p3 = r.values[3]; u.seed = r.seed
            u.vpo = SIMD2(Float(viewport.minX), Float(viewport.minY))
            u.vps = SIMD2(Float(src.width) / Float(viewport.width), Float(src.height) / Float(viewport.height))
            enc.setRenderPipelineState(p)
            enc.setFragmentBytes(&u, length: MemoryLayout<ArtUniforms>.stride, index: 0)
        } else if let p = pipe("copyFrag") {
            enc.setRenderPipelineState(p)
        }
        enc.setFragmentTexture(src, index: 0)
        enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        enc.endEncoding()
    }

    // ---------------------------------------------------------------- placement
    /** Output px -> top px, with top scaled to cover the output and centred (how the viewfinder shows exposure 1). */
    static func cover(out: CGSize, top: CGSize) -> simd_float3x3 {
        let s = Float(max(out.width / top.width, out.height / top.height))
        let tx = Float(top.width) / 2 - Float(out.width) / (2 * s), ty = Float(top.height) / 2 - Float(out.height) / (2 * s)
        return simd_float3x3(columns: (SIMD3(1 / s, 0, 0), SIMD3(0, 1 / s, 0), SIMD3(tx, ty, 1)))
    }

    /** Output px -> top px for a free placement: top's centre at c (output px), width = widthFrac of output width, rotated. */
    static func place(out: CGSize, top: CGSize, c: CGPoint, widthFrac: CGFloat, rotDeg: CGFloat) -> simd_float3x3 {
        let g = Float(widthFrac * out.width / top.width)
        let r = Float(-rotDeg * .pi / 180), cs = cos(r), sn = sin(r)
        // t = (1/g) * R(-rot) * (o - c) + topCentre
        let a = cs / g, b = sn / g
        let cx = Float(c.x), cy = Float(c.y), tcx = Float(top.width) / 2, tcy = Float(top.height) / 2
        let tx = -(a * cx - b * cy) + tcx, ty = -(b * cx + a * cy) + tcy
        return simd_float3x3(columns: (SIMD3(a, b, 0), SIMD3(-b, a, 0), SIMD3(tx, ty, 1)))
    }

    // ---------------------------------------------------------------- stills
    /** Full-size, offscreen: base, optionally with a second exposure on top, then the look. */
    func renderStill(base: CGImage, top: CGImage? = nil, m: simd_float3x3? = nil, blend: Blend = .screen, strength: Float = 0.6, recipe: Recipe?) -> CGImage? {
        guard let bt = texture(from: base) else { return nil }
        let w = bt.width, h = bt.height
        guard let mid = makeTarget(w, h), let out = makeTarget(w, h), let cmd = queue.makeCommandBuffer() else { return nil }
        let tt = top.flatMap { texture(from: $0) }
        let mm = m ?? (tt != nil ? Renderer.cover(out: CGSize(width: w, height: h), top: CGSize(width: tt!.width, height: tt!.height)) : matrix_identity_float3x3)
        encodeComposite(cmd, base: bt, top: tt, m: mm, blend: blend, strength: strength, into: mid)
        let rp = MTLRenderPassDescriptor()
        rp.colorAttachments[0].texture = out; rp.colorAttachments[0].loadAction = .dontCare; rp.colorAttachments[0].storeAction = .store
        encodeArt(cmd, src: mid, recipe: recipe, pass: rp, viewport: CGRect(x: 0, y: 0, width: w, height: h))
        return finishReadback(cmd, out)
    }

    /** Editor preview: the pair at display size, with the look measured against the crop box. */
    func renderDisplay(base: MTLTexture, top: MTLTexture?, m: simd_float3x3, blend: Blend, strength: Float, recipe: Recipe?, measure: CGRect?) -> CGImage? {
        let w = base.width, h = base.height
        guard let mid = makeTarget(w, h), let out = makeTarget(w, h), let cmd = queue.makeCommandBuffer() else { return nil }
        encodeComposite(cmd, base: base, top: top, m: m, blend: blend, strength: strength, into: mid)
        let rp = MTLRenderPassDescriptor()
        rp.colorAttachments[0].texture = out; rp.colorAttachments[0].loadAction = .dontCare; rp.colorAttachments[0].storeAction = .store
        encodeArt(cmd, src: mid, recipe: recipe, pass: rp, viewport: CGRect(x: 0, y: 0, width: w, height: h), measure: measure)
        return finishReadback(cmd, out)
    }

    /** Copies the result into a CPU-visible buffer in the same pass (works on iPhone and in the Simulator), then wraps it as an image. */
    private func finishReadback(_ cmd: MTLCommandBuffer, _ t: MTLTexture) -> CGImage? {
        let w = t.width, h = t.height, row = w * 4
        guard let buf = device.makeBuffer(length: row * h, options: .storageModeShared), let blit = cmd.makeBlitCommandEncoder() else {
            Log.error("readback: no buffer for \(w)×\(h)"); cmd.commit(); return nil
        }
        blit.copy(from: t, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(), sourceSize: MTLSize(width: w, height: h, depth: 1),
                  to: buf, destinationOffset: 0, destinationBytesPerRow: row, destinationBytesPerImage: row * h)
        blit.endEncoding()
        cmd.commit(); cmd.waitUntilCompleted()
        if let e = cmd.error { Log.error("GPU: \(e)"); return nil }
        let data = Data(bytes: buf.contents(), count: row * h)
        guard let prov = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: row,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!,
                       bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue),
                       provider: prov, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /** Holga is a square camera: its copies come out 1:1. */
    static func finish(_ cg: CGImage, _ mode: ArtMode) -> CGImage {
        guard mode == .HOLGA, cg.width != cg.height else { return cg }
        let s = min(cg.width, cg.height)
        return cg.cropping(to: CGRect(x: (cg.width - s) / 2, y: (cg.height - s) / 2, width: s, height: s)) ?? cg
    }
}
