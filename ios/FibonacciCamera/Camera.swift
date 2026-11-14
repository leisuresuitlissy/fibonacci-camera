import AVFoundation
import UIKit

/** Zoom in the numbers people know (0.5×, 1×, 5×), mapped onto the device's own zoom factors. */
struct ZoomInfo {
    var stops: [CGFloat] = [1]
    var minDisplay: CGFloat = 1
    var maxDisplay: CGFloat = 1
    /** device factor × k = displayed zoom */
    var k: CGFloat = 1
}

/**
 * The camera. Uses Apple's multi-lens "virtual" device where there is one, so zooming hands over
 * between the real lenses on its own, and the switch-over points tell us exactly which zooms are real glass.
 */
final class Camera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let q = DispatchQueue(label: "fibcam.camera")
    private let frameQ = DispatchQueue(label: "fibcam.frames")
    private(set) var device: AVCaptureDevice?
    private var input: AVCaptureDeviceInput?
    private let photo = AVCapturePhotoOutput()
    private let video = AVCaptureVideoDataOutput()
    private var pending: [Int64: (Data?) -> Void] = [:]
    private(set) var front = false
    private(set) var zoom = ZoomInfo()

    var onFrame: ((CVPixelBuffer) -> Void)?
    /** Main thread, after each (re)configuration. */
    var onReady: ((ZoomInfo, ClosedRange<Float>) -> Void)?

    static func requestAccess(_ done: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { done(ok) } }
    }

    func start(front: Bool) {
        q.async { self.configure(front: front); if !self.session.isRunning { self.session.startRunning() } }
    }
    func stop() { q.async { if self.session.isRunning { self.session.stopRunning() } } }

    private func pick(front: Bool) -> AVCaptureDevice? {
        if front { return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) }
        let d = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera],
                                                 mediaType: .video, position: .back)
        let order: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
        for t in order { if let dev = d.devices.first(where: { $0.deviceType == t }) { return dev } }
        return AVCaptureDevice.default(for: .video)
    }

    private func configure(front: Bool) {
        self.front = front
        session.beginConfiguration()
        session.sessionPreset = .photo
        if let i = input { session.removeInput(i) }
        guard let dev = pick(front: front), let inp = try? AVCaptureDeviceInput(device: dev), session.canAddInput(inp) else {
            session.commitConfiguration(); return
        }
        session.addInput(inp); input = inp; device = dev
        if !session.outputs.contains(photo), session.canAddOutput(photo) { session.addOutput(photo) }
        if !session.outputs.contains(video), session.canAddOutput(video) {
            video.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            video.alwaysDiscardsLateVideoFrames = true
            video.setSampleBufferDelegate(self, queue: frameQ)
            session.addOutput(video)
        }
        video.deliversPreviewSizedOutputBuffers = true
        photo.maxPhotoQualityPrioritization = .quality
        if let dims = dev.activeFormat.supportedMaxPhotoDimensions.last { photo.maxPhotoDimensions = dims }
        if let c = video.connection(with: .video) {
            Camera.rotate(c, 90)
            if c.isVideoMirroringSupported { c.automaticallyAdjustsVideoMirroring = false; c.isVideoMirrored = front }
        }
        if let c = photo.connection(with: .video), c.isVideoMirroringSupported {
            c.automaticallyAdjustsVideoMirroring = false; c.isVideoMirrored = front   // saved picture matches the viewfinder
        }
        session.commitConfiguration()

        zoom = Camera.zoomInfo(dev)
        try? dev.lockForConfiguration()
        dev.videoZoomFactor = min(max(1 / zoom.k, dev.minAvailableVideoZoomFactor), dev.maxAvailableVideoZoomFactor)   // start at 1×
        if dev.isFocusModeSupported(.continuousAutoFocus) { dev.focusMode = .continuousAutoFocus }
        if dev.isExposureModeSupported(.continuousAutoExposure) { dev.exposureMode = .continuousAutoExposure }
        dev.unlockForConfiguration()
        let ev = dev.minExposureTargetBias...dev.maxExposureTargetBias
        let z = zoom
        DispatchQueue.main.async { self.onReady?(z, ev) }
    }

    static func rotate(_ c: AVCaptureConnection, _ deg: CGFloat) {
        if #available(iOS 17.0, *) {
            if c.isVideoRotationAngleSupported(deg) { c.videoRotationAngle = deg }
        } else if c.isVideoOrientationSupported {
            switch deg { case 0: c.videoOrientation = .landscapeRight; case 180: c.videoOrientation = .landscapeLeft
            case 270: c.videoOrientation = .portraitUpsideDown; default: c.videoOrientation = .portrait }
        }
    }

    /** Real lenses only: the ultra-wide, 1×, full-resolution sensor crops (the 2×), and each telephoto. */
    static func zoomInfo(_ dev: AVCaptureDevice) -> ZoomInfo {
        var z = ZoomInfo()
        let switches = dev.virtualDeviceSwitchOverVideoZoomFactors.map { CGFloat(truncating: $0) }
        let hasUltra = dev.constituentDevices.first?.deviceType == .builtInUltraWideCamera
        z.k = hasUltra && !switches.isEmpty ? 1 / switches[0] : 1
        z.minDisplay = dev.minAvailableVideoZoomFactor * z.k
        let realMax = dev.maxAvailableVideoZoomFactor * z.k
        var stops: [CGFloat] = [z.minDisplay, 1]
        for s in switches { stops.append(s * z.k) }
        for s in dev.activeFormat.secondaryNativeResolutionZoomFactors { stops.append(CGFloat(s) * z.k) }
        let teleMax = stops.max() ?? 1
        z.maxDisplay = min(realMax, max(10, teleMax * 3))
        func tidy(_ v: CGFloat) -> CGFloat { v < 1 ? (v * 10).rounded() / 10 : (abs(v - v.rounded()) < 0.15 ? v.rounded() : (v * 10).rounded() / 10) }
        var seen = Set<Int>()
        z.stops = stops.map(tidy).filter { $0 <= realMax + 0.01 }.sorted().filter { seen.insert(Int($0 * 100)).inserted }
        return z
    }

    // ------------------------------------------------------------ controls
    func setZoom(display: CGFloat) {
        q.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            d.videoZoomFactor = min(max(display / self.zoom.k, d.minAvailableVideoZoomFactor), d.maxAvailableVideoZoomFactor)
            d.unlockForConfiguration()
        }
    }

    /** n: point in the portrait picture, 0...1 from the top-left. */
    func focus(at n: CGPoint) {
        q.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            let poi = self.front ? CGPoint(x: n.y, y: n.x) : CGPoint(x: n.y, y: 1 - n.x)
            if d.isFocusPointOfInterestSupported { d.focusPointOfInterest = poi; d.focusMode = d.isFocusModeSupported(.continuousAutoFocus) ? .continuousAutoFocus : .autoFocus }
            if d.isExposurePointOfInterestSupported { d.exposurePointOfInterest = poi; d.exposureMode = .continuousAutoExposure }
            d.isSubjectAreaChangeMonitoringEnabled = true
            d.unlockForConfiguration()
        }
    }

    func setBias(_ ev: Float) {
        q.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            d.setExposureTargetBias(min(max(ev, d.minExposureTargetBias), d.maxExposureTargetBias), completionHandler: nil)
            d.unlockForConfiguration()
        }
    }

    var hasFlash: Bool { device?.hasFlash ?? false }

    /** Delivers the photo file exactly as the camera made it (with its metadata): the original. */
    func capture(flash: AVCaptureDevice.FlashMode, rotation: CGFloat, done: @escaping (Data?) -> Void) {
        q.async {
            if let c = self.photo.connection(with: .video) { Camera.rotate(c, rotation) }
            let s = AVCapturePhotoSettings()
            if self.photo.supportedFlashModes.contains(flash) { s.flashMode = flash }
            s.photoQualityPrioritization = .quality
            s.maxPhotoDimensions = self.photo.maxPhotoDimensions
            self.pending[s.uniqueID] = done
            self.photo.capturePhoto(with: s, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto p: AVCapturePhoto, error: Error?) {
        let data = error == nil ? p.fileDataRepresentation() : nil
        let id = p.resolvedSettings.uniqueID
        q.async {
            let cb = self.pending.removeValue(forKey: id)
            DispatchQueue.main.async { cb?(data) }
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sb: CMSampleBuffer, from connection: AVCaptureConnection) {
        if let pb = CMSampleBufferGetImageBuffer(sb) { onFrame?(pb) }
    }
}
