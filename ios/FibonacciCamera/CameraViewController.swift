import AVFoundation
import PhotosUI
import UIKit

/** Round icon button drawn in code (flash, double exposure, import, flip). */
final class IconButton: UIControl {
    enum Icon { case flashOff, flashAuto, flashOn, double, photo, flip }
    var icon: Icon { didSet { setNeedsDisplay() } }
    var tint: UIColor = Style.muted { didSet { setNeedsDisplay() } }
    var fill: UIColor = Style.elev { didSet { setNeedsDisplay() } }
    var glyph: CGFloat = 0.5

    init(_ icon: Icon) { self.icon = icon; super.init(frame: .zero); isOpaque = false; backgroundColor = .clear }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        c.setFillColor(fill.cgColor); c.fillEllipse(in: bounds)
        let s = min(bounds.width, bounds.height) * glyph / 24
        c.translateBy(x: bounds.midX - 12 * s, y: bounds.midY - 12 * s); c.scaleBy(x: s, y: s)
        c.setStrokeColor(tint.cgColor); c.setFillColor(tint.cgColor); c.setLineWidth(1.7); c.setLineCap(.round); c.setLineJoin(.round)
        func bolt() { c.move(to: CGPoint(x: 13, y: 2)); c.addLine(to: CGPoint(x: 5, y: 13.5)); c.addLine(to: CGPoint(x: 11.5, y: 13.5)); c.addLine(to: CGPoint(x: 10.5, y: 22)); c.addLine(to: CGPoint(x: 19, y: 10)); c.addLine(to: CGPoint(x: 12.5, y: 10)); c.closePath() }
        switch icon {
        case .flashOff: bolt(); c.strokePath(); c.move(to: CGPoint(x: 3, y: 3)); c.addLine(to: CGPoint(x: 21, y: 21)); c.strokePath()
        case .flashOn: bolt(); c.fillPath()
        case .flashAuto: bolt(); c.strokePath()
            let a = NSAttributedString(string: "A", attributes: [.font: UIFont.systemFont(ofSize: 8, weight: .bold), .foregroundColor: tint])
            UIGraphicsPushContext(c); a.draw(at: CGPoint(x: 17, y: 14)); UIGraphicsPopContext()
        case .double: c.strokeEllipse(in: CGRect(x: 3.5, y: 6, width: 12, height: 12)); c.strokeEllipse(in: CGRect(x: 8.5, y: 6, width: 12, height: 12))
        case .photo:
            c.addPath(UIBezierPath(roundedRect: CGRect(x: 3, y: 5, width: 18, height: 14), cornerRadius: 2.5).cgPath); c.strokePath()
            c.move(to: CGPoint(x: 4, y: 17)); c.addLine(to: CGPoint(x: 9.5, y: 11.5)); c.addLine(to: CGPoint(x: 13, y: 15)); c.addLine(to: CGPoint(x: 15.5, y: 12.5)); c.addLine(to: CGPoint(x: 20, y: 17)); c.strokePath()
            c.fillEllipse(in: CGRect(x: 14.5, y: 7.5, width: 3, height: 3))
        case .flip:
            c.addArc(center: CGPoint(x: 12, y: 12), radius: 7, startAngle: .pi * 0.15, endAngle: .pi * 0.95, clockwise: false); c.strokePath()
            c.addArc(center: CGPoint(x: 12, y: 12), radius: 7, startAngle: .pi * 1.15, endAngle: .pi * 1.95, clockwise: false); c.strokePath()
            c.move(to: CGPoint(x: 2.5, y: 11)); c.addLine(to: CGPoint(x: 5.2, y: 14.2)); c.addLine(to: CGPoint(x: 8, y: 11)); c.strokePath()
            c.move(to: CGPoint(x: 16, y: 13)); c.addLine(to: CGPoint(x: 18.8, y: 9.8)); c.addLine(to: CGPoint(x: 21.5, y: 13)); c.strokePath()
        }
    }
}

/** A small tile showing a spiral orientation or a triangle direction. */
final class TileView: UIControl {
    let quadrant: Int; let triangle: Bool; var on = false { didSet { setNeedsDisplay() } }
    init(quadrant: Int, triangle: Bool) { self.quadrant = quadrant; self.triangle = triangle; super.init(frame: .zero); isOpaque = false; backgroundColor = .clear }
    required init?(coder: NSCoder) { fatalError() }
    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let bg = UIBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), cornerRadius: 10)
        c.setFillColor((on ? Style.gold.withAlphaComponent(0.14) : Style.elev).cgColor); c.addPath(bg.cgPath); c.fillPath()
        c.setStrokeColor((on ? Style.gold.withAlphaComponent(0.8) : Style.line).cgColor); c.setLineWidth(1); c.addPath(bg.cgPath); c.strokePath()
        let col = (on ? Style.gold : Style.muted).cgColor
        let fh: CGFloat = bounds.height - 12, fw = fh * 0.62
        let fr = CGRect(x: bounds.midX - fw / 2, y: bounds.midY - fh / 2, width: fw, height: fh)
        c.setStrokeColor(col); c.setLineWidth(1); c.stroke(fr)
        if triangle {
            let (a, b) = quadrant == 0 ? (CGPoint(x: fr.minX, y: fr.minY), CGPoint(x: fr.maxX, y: fr.maxY)) : (CGPoint(x: fr.maxX, y: fr.minY), CGPoint(x: fr.minX, y: fr.maxY))
            c.move(to: a); c.addLine(to: b); c.strokePath()
        } else {
            let s = Spiral.of(quadrant), u = fr.height / s.bh
            let left = fr.minX + (fr.width - s.bw * u) / 2
            let eye = CGPoint(x: left - s.ox * u, y: fr.minY - s.oy * u)
            c.setLineWidth(1.2); c.move(to: CGPoint(x: eye.x + s.pts[0].x * u, y: eye.y + s.pts[0].y * u))
            for p in s.pts.dropFirst() { c.addLine(to: CGPoint(x: eye.x + p.x * u, y: eye.y + p.y * u)) }
            c.strokePath()
            c.setFillColor(col); c.fillEllipse(in: CGRect(x: eye.x - 2, y: eye.y - 2, width: 4, height: 4))
        }
    }
}

final class CameraViewController: UIViewController, PHPickerViewControllerDelegate {
    #if targetEnvironment(simulator)
    private let demo = DemoCamera()
    private lazy var cam: CameraSource = demo
    private var pickingScene = false
    #else
    private let cam: CameraSource = Camera()
    #endif
    private let tracker = Tracker()
    private let haptic = UIImpactFeedbackGenerator(style: .medium)
    private var subject: CGPoint?          // the tapped subject, followed silently (screen points)
    private var aligned = false
    private let d = UserDefaults.standard

    // views
    private let live = LiveView()
    private let overlay = OverlayView()
    private let stage = UIView()
    private let sourceLbl = Style.label("LIVE · REAR", Style.mono(10), Style.muted, kern: 1)
    private let flashBtn = IconButton(.flashOff)
    private let doubleBtn = IconButton(.double)
    private let tabRow = Style.hstack(spacing: 6)
    private let composeLayer = UIStackView(), artLayer = UIStackView(), exposureLayer = UIStackView(), blendLayer = UIStackView()
    private let guideRow = Style.hstack(), secondRow = Style.hstack()
    private let artRow = Style.hstack(), paramRow = Style.hstack(spacing: 12)
    private let artValueLbl = Style.label("", Style.mono(11), Style.gold)
    private let artSlider = Style.slider()
    private let evValue = Style.label("0.0 EV", Style.mono(12), Style.gold)
    private let evSlider = Style.slider()
    private let blendRow = Style.hstack()
    private let blendValue = Style.label("60%", Style.mono(12), Style.gold)
    private let blendSlider = Style.slider()
    private let zoomRow = UIStackView()
    private let thumb = UIImageView()
    private let shutter = UIButton()
    private let exposureTag = UIButton()
    private let gate = Style.label("", Style.medium(14), Style.text)
    private let whiteout = UIView()
    private let nice = Style.label("Nice", Style.medium(13), Style.gold)

    // state
    private var guide = Guide.spiral
    private var quadrant = 0
    private var triFlip = false
    private var layer = "COMPOSE"
    private var art = ArtMode.OFF
    private var artState = ArtState(values: ArtMode.OFF.defaults, follow: false)
    private var artValues: [Float] { get { artState.values } set { artState.values = newValue } }
    private var artParam = 0
    private var anchor = 0              // which thirds/φ point, or corner diagonal, a guide-linked focus uses
    private var guideHidden = false
    private var seed = Float.random(in: 0..<1000)   // stable until the next shot, so the saved leak matches the preview
    private var ev: Float = 0
    private var evRange: ClosedRange<Float> = -2...2
    private var flash = AVCaptureDevice.FlashMode.off
    private var front = false
    private var zoom = ZoomInfo()
    private var zoomDisplay: CGFloat = 1
    private var double = false
    private var first: UIImage?
    private var blend = Blend.screen
    private var strength: Float = 0.6
    private var saving = 0

    private var recipe: Recipe? {
        guard art != .OFF else { return nil }
        return Recipe(mode: art, values: artValues, seed: seed, follow: artState.follow, mask: liveMask())
    }
    private var lastFocus = CGPoint(x: 0.5, y: 0.5)

    private func guideCtx() -> GuideCtx? {
        let fr = live.frameRect
        return fr.isEmpty ? nil : GuideCtx(guide: guide, quadrant: quadrant, triFlip: triFlip, anchor: anchor, frame: fr, guideFrame: overlay.guideFrame)
    }
    /** The subject you tapped (0...1 across the frame), followed as you move. Nil before any tap. */
    private func liveSubject() -> CGPoint? {
        let fr = live.frameRect
        guard let s = subject, !fr.isEmpty else { return nil }
        return CGPoint(x: (s.x - fr.minX) / fr.width, y: (s.y - fr.minY) / fr.height)
    }
    /** The look's focus region right now (frame uv): the guide's geometry with Follow guide, otherwise its own. */
    private func liveMask() -> FocusMask { Focus.resolve(art, artValues, follow: artState.follow, guide: guideCtx(), subject: liveSubject()) }

    private func pushArt() { live.recipe = recipe; live.mask = liveMask() }
    private func refreshFocus() {
        guard art != .OFF else { return }
        let m = liveMask(), f = CGPoint(x: CGFloat(m.cx), y: CGFloat(m.cy))
        if hypot(f.x - lastFocus.x, f.y - lastFocus.y) > 0.004 { lastFocus = f; pushArt() }
    }
    /** The guide changed: a linked focus moves with it (and shows briefly); a manual one stays put. */
    private func guideMoved() {
        guard art != .OFF, art.follows, artState.follow else { return }
        pushArt(); showMask()
    }
    /** Briefly outlines the simulated focus region; never part of the photo. */
    private func showMask() {
        guard art != .OFF else { return }
        let m = liveMask()
        guard m.type != 0, art == .TILT_SHIFT || m.strength > 0 else { return }
        overlay.showMask(m)
    }
    private static var firstURL: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("first-exposure.jpg") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Style.base
        guide = Guide(rawValue: d.string(forKey: "guide") ?? "") ?? .spiral
        quadrant = d.integer(forKey: "quadrant"); triFlip = d.bool(forKey: "triFlip")
        // A fresh launch always opens on Compose with no look; slider positions per look are kept.
        layer = "COMPOSE"
        art = .OFF; artState = art.loadState()
        anchor = d.integer(forKey: "anchor"); guideHidden = d.bool(forKey: "guideHidden")
        flash = AVCaptureDevice.FlashMode(rawValue: d.integer(forKey: "flash")) ?? .off
        double = d.bool(forKey: "double")
        blend = (d.object(forKey: "blend") as? Int).flatMap { Blend(rawValue: $0) } ?? .screen   // Screen until the user picks another
        strength = d.object(forKey: "strength") as? Float ?? 0.6
        if double, let data = try? Data(contentsOf: Self.firstURL) { first = UIImage(data: data) }
        if layer == "BLEND" && !double { layer = "COMPOSE" }
        buildUi()
        applyGuide(); applyArtUi(); applyBlendUi(); applyLayer(); syncFlash(); syncDouble(); applyGhost()

        cam.onFrame = { [weak self] pb in
            guard let self else { return }
            self.live.push(pb)
            let st = self.tracker.process(pb)
            DispatchQueue.main.async { self.onTrack(st) }
        }
        cam.onReady = { [weak self] z, evr in self?.cameraReady(z, evr) }
        #if targetEnvironment(simulator)
        gate.isHidden = true; cam.start(front: front)          // no camera hardware here: the demo feed stands in
        #else
        Camera.requestAccess { [weak self] ok in
            guard let self else { return }
            if ok { self.gate.isHidden = true; self.cam.start(front: self.front) }
            else { self.gate.text = "Camera access is off. Turn it on in Settings › Fibonacci."; self.gate.isHidden = false }
        }
        #endif
        Log.info("camera screen ready · guide \(guide.rawValue) · double \(double)")
        #if targetEnvironment(simulator)
        runShotScript()
        #endif
        NotificationCenter.default.addObserver(self, selector: #selector(wake), name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sleep), name: UIApplication.didEnterBackgroundNotification, object: nil)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
    }

    @objc private func wake() {
        #if targetEnvironment(simulator)
        cam.start(front: front)
        #else
        if AVCaptureDevice.authorizationStatus(for: .video) == .authorized { cam.start(front: front) }
        #endif
    }
    @objc private func sleep() { cam.stop() }

    private func save() {
        d.set(guide.rawValue, forKey: "guide"); d.set(quadrant, forKey: "quadrant"); d.set(triFlip, forKey: "triFlip")
        d.set(layer, forKey: "layer"); d.set(art.rawValue, forKey: "art"); d.set(flash.rawValue, forKey: "flash")
        d.set(anchor, forKey: "anchor"); d.set(guideHidden, forKey: "guideHidden")
        d.set(double, forKey: "double"); d.set(blend.rawValue, forKey: "blend"); d.set(strength, forKey: "strength")
    }

    // ============================================================ layout
    private func pin(_ v: UIView, to p: UIView) {
        v.translatesAutoresizingMaskIntoConstraints = false; p.addSubview(v)
        NSLayoutConstraint.activate([v.leadingAnchor.constraint(equalTo: p.leadingAnchor), v.trailingAnchor.constraint(equalTo: p.trailingAnchor),
                                     v.topAnchor.constraint(equalTo: p.topAnchor), v.bottomAnchor.constraint(equalTo: p.bottomAnchor)])
    }
    private func size(_ v: UIView, _ w: CGFloat?, _ h: CGFloat?) {
        v.translatesAutoresizingMaskIntoConstraints = false
        if let w { v.widthAnchor.constraint(equalToConstant: w).isActive = true }
        if let h { v.heightAnchor.constraint(equalToConstant: h).isActive = true }
    }
    private func scroll(_ row: UIStackView) -> UIScrollView {
        let s = UIScrollView(); s.showsHorizontalScrollIndicator = false
        row.translatesAutoresizingMaskIntoConstraints = false; s.addSubview(row)
        NSLayoutConstraint.activate([row.leadingAnchor.constraint(equalTo: s.contentLayoutGuide.leadingAnchor), row.trailingAnchor.constraint(equalTo: s.contentLayoutGuide.trailingAnchor),
                                     row.topAnchor.constraint(equalTo: s.contentLayoutGuide.topAnchor), row.bottomAnchor.constraint(equalTo: s.contentLayoutGuide.bottomAnchor),
                                     row.heightAnchor.constraint(equalTo: s.frameLayoutGuide.heightAnchor)])
        return s
    }

    private func buildUi() {
        let root = UIStackView(); root.axis = .vertical; root.spacing = 0
        root.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(root)
        NSLayoutConstraint.activate([root.leadingAnchor.constraint(equalTo: view.leadingAnchor), root.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                                     root.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), root.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)])

        // top bar
        let titles = UIStackView(arrangedSubviews: [Style.label("Fibonacci Camera", .boldSystemFont(ofSize: 15), Style.text), sourceLbl])
        titles.axis = .vertical; titles.spacing = 3; titles.alignment = .leading
        size(doubleBtn, 40, 40); size(flashBtn, 40, 40)
        doubleBtn.accessibilityLabel = "Double exposure"; flashBtn.accessibilityLabel = "Flash"
        doubleBtn.addAction(UIAction { [weak self] _ in self?.toggleDouble() }, for: .touchUpInside)
        flashBtn.addAction(UIAction { [weak self] _ in self?.cycleFlash() }, for: .touchUpInside)
        #if targetEnvironment(simulator)
        sourceLbl.isHidden = true
        titles.addArrangedSubview(makeDemoButton())
        #endif
        let more = UIButton(type: .system)
        more.setImage(UIImage(systemName: "ellipsis"), for: .normal); more.tintColor = Style.muted
        more.backgroundColor = Style.elev; more.layer.cornerRadius = 20; size(more, 40, 40)
        more.accessibilityLabel = "Settings"
        more.showsMenuAsPrimaryAction = true
        more.menu = settingsMenu()
        settingsBtn = more
        let top = Style.hstack([titles, UIView(), doubleBtn, flashBtn, more], spacing: 8)
        size(top, nil, 56); root.addArrangedSubview(top)

        // stage
        stage.backgroundColor = .black; stage.clipsToBounds = true
        pin(live, to: stage); pin(overlay, to: stage)
        live.onFrame = { [weak self] fr in self?.frameChanged(fr) }
        overlay.onTap = { [weak self] p in self?.tapFocus(p) }
        overlay.onDoubleTap = { [weak self] in self?.doubleTap() }
        overlay.onPinch = { [weak self] s in self?.pinch(s) }
        overlay.onFocusDrag = { [weak self] dy in self?.focusDrag(dy) ?? false }
        var tc = UIButton.Configuration.filled()
        tc.baseBackgroundColor = Style.base.withAlphaComponent(0.7); tc.baseForegroundColor = Style.gold; tc.cornerStyle = .capsule
        tc.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        exposureTag.configuration = tc
        exposureTag.layer.borderColor = Style.gold.withAlphaComponent(0.6).cgColor; exposureTag.layer.borderWidth = 1; exposureTag.layer.cornerRadius = 14
        exposureTag.addAction(UIAction { [weak self] _ in self?.discardFirst() }, for: .touchUpInside)
        stage.addSubview(exposureTag)
        nice.textAlignment = .center; nice.backgroundColor = UIColor(red: 0.24, green: 0.18, blue: 0.08, alpha: 0.85)
        nice.layer.cornerRadius = 15; nice.layer.borderWidth = 1; nice.layer.borderColor = Style.gold.withAlphaComponent(0.85).cgColor
        nice.clipsToBounds = true; nice.alpha = 0; nice.isUserInteractionEnabled = false
        stage.addSubview(nice)
        whiteout.backgroundColor = .white; whiteout.alpha = 0; whiteout.isUserInteractionEnabled = false; pin(whiteout, to: stage)
        gate.numberOfLines = 0; gate.textAlignment = .center; gate.isHidden = true
        gate.translatesAutoresizingMaskIntoConstraints = false; stage.addSubview(gate)
        NSLayoutConstraint.activate([gate.centerYAnchor.constraint(equalTo: stage.centerYAnchor), gate.leadingAnchor.constraint(equalTo: stage.leadingAnchor, constant: 32),
                                     gate.trailingAnchor.constraint(equalTo: stage.trailingAnchor, constant: -32)])
        root.addArrangedSubview(stage)

        // layer tabs
        tabRow.distribution = .fillEqually; tabRow.alignment = .fill
        tabRow.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 6, leading: 16, bottom: 2, trailing: 16)
        size(tabRow, nil, 44); root.addArrangedSubview(tabRow)

        let layers = UIView(); size(layers, nil, 112)
        for (l, sp) in [(composeLayer, 0.0), (artLayer, 0.0), (exposureLayer, 0.0), (blendLayer, 0.0)] { l.axis = .vertical; l.spacing = sp; pin(l, to: layers) }
        // compose
        size(guideRow, nil, 48); size(secondRow, nil, 56)
        composeLayer.addArrangedSubview(scroll(guideRow)); composeLayer.arrangedSubviews.last.map { size($0, nil, 48) }
        composeLayer.addArrangedSubview(secondRow); composeLayer.addArrangedSubview(UIView())
        // art
        let artScroll = scroll(artRow); size(artScroll, nil, 48)
        let pScroll = scroll(paramRow); size(pScroll, nil, 34)
        artLayer.addArrangedSubview(artScroll); artLayer.addArrangedSubview(pScroll)
        artSlider.minimumValue = 0; artSlider.maximumValue = 1
        artSlider.addAction(UIAction { [weak self] _ in self?.onArtSlider() }, for: .valueChanged)
        artValueLbl.textAlignment = .right; size(artValueLbl, 32, nil)
        let artSliderRow = Style.hstack([artSlider, artValueLbl]); size(artSliderRow, nil, 34); artLayer.addArrangedSubview(artSliderRow)
        artLayer.addArrangedSubview(UIView())
        // exposure
        let evHead = Style.hstack([Style.label("EXPOSURE", Style.mono(10), Style.muted, kern: 1.3), UIView(), evValue]); size(evHead, nil, 32)
        evSlider.minimumValue = -2; evSlider.maximumValue = 2; evSlider.neutral = 0.5
        evSlider.addAction(UIAction { [weak self] _ in self?.onEvSlider() }, for: .valueChanged)
        let evRow = Style.hstack([evSlider]); size(evRow, nil, 36)
        let reset = Style.chip("Reset", on: false) { [weak self] in self?.setEv(0) }
        let evFoot = Style.hstack([Style.label("−2", Style.mono(11), Style.muted), UIView(), reset, UIView(), Style.label("+2", Style.mono(11), Style.muted)]); size(evFoot, nil, 40)
        [evHead, evRow, evFoot].forEach(exposureLayer.addArrangedSubview)
        // blend
        size(blendRow, nil, 48); blendLayer.addArrangedSubview(blendRow)
        let bHead = Style.hstack([Style.label("STRENGTH", Style.mono(10), Style.muted, kern: 1.3), UIView(), blendValue]); size(bHead, nil, 24)
        blendSlider.minimumValue = 0; blendSlider.maximumValue = 1
        blendSlider.addAction(UIAction { [weak self] _ in self?.onBlendSlider() }, for: .valueChanged)
        let bRow = Style.hstack([blendSlider]); size(bRow, nil, 36)
        [bHead, bRow, UIView()].forEach(blendLayer.addArrangedSubview)
        root.addArrangedSubview(layers)

        // zoom chips
        zoomRow.axis = .horizontal; zoomRow.spacing = 10; zoomRow.alignment = .center
        let zoomWrap = UIView(); size(zoomWrap, nil, 52)
        zoomRow.translatesAutoresizingMaskIntoConstraints = false; zoomWrap.addSubview(zoomRow)
        NSLayoutConstraint.activate([zoomRow.centerXAnchor.constraint(equalTo: zoomWrap.centerXAnchor), zoomRow.centerYAnchor.constraint(equalTo: zoomWrap.centerYAnchor)])
        root.addArrangedSubview(zoomWrap)

        // bottom bar: thumb · import · shutter · flip
        let bar = UIView(); size(bar, nil, 104)
        thumb.contentMode = .scaleAspectFill; thumb.clipsToBounds = true; thumb.layer.cornerRadius = 12; thumb.backgroundColor = Style.elev
        thumb.layer.borderColor = Style.line.cgColor; thumb.layer.borderWidth = 1; thumb.isUserInteractionEnabled = true
        thumb.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(openPhotos)))
        let importBtn = IconButton(.photo); importBtn.accessibilityLabel = "Import a photo"
        importBtn.addAction(UIAction { [weak self] _ in self?.pickPhoto() }, for: .touchUpInside)
        shutter.backgroundColor = .white; shutter.layer.cornerRadius = 36; shutter.layer.borderWidth = 4; shutter.layer.borderColor = Style.base.cgColor
        shutter.accessibilityLabel = "Take photo"
        let ring = UIView(); ring.isUserInteractionEnabled = false; ring.layer.cornerRadius = 41; ring.layer.borderWidth = 3; ring.layer.borderColor = UIColor.white.cgColor
        shutter.addAction(UIAction { [weak self] _ in self?.capture() }, for: .touchUpInside)
        let flip = IconButton(.flip); flip.glyph = 0.46; flip.tint = Style.text; flip.accessibilityLabel = "Switch camera"
        flip.addAction(UIAction { [weak self] _ in self?.flipCamera() }, for: .touchUpInside)
        for v in [thumb, importBtn, ring, shutter, flip] as [UIView] { v.translatesAutoresizingMaskIntoConstraints = false; bar.addSubview(v) }
        NSLayoutConstraint.activate([
            shutter.centerXAnchor.constraint(equalTo: bar.centerXAnchor), shutter.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            shutter.widthAnchor.constraint(equalToConstant: 72), shutter.heightAnchor.constraint(equalToConstant: 72),
            ring.centerXAnchor.constraint(equalTo: shutter.centerXAnchor), ring.centerYAnchor.constraint(equalTo: shutter.centerYAnchor),
            ring.widthAnchor.constraint(equalToConstant: 82), ring.heightAnchor.constraint(equalToConstant: 82),
            thumb.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 24), thumb.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            thumb.widthAnchor.constraint(equalToConstant: 52), thumb.heightAnchor.constraint(equalToConstant: 52),
            importBtn.trailingAnchor.constraint(equalTo: shutter.leadingAnchor, constant: -22), importBtn.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            importBtn.widthAnchor.constraint(equalToConstant: 48), importBtn.heightAnchor.constraint(equalToConstant: 48),
            flip.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -24), flip.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            flip.widthAnchor.constraint(equalToConstant: 60), flip.heightAnchor.constraint(equalToConstant: 60)])
        root.addArrangedSubview(bar)
    }

    private func frameChanged(_ fr: CGRect) {
        overlay.frameRect = fr
        let tag = exposureTag.intrinsicContentSize
        exposureTag.frame = CGRect(x: fr.minX + 12, y: fr.maxY - tag.height - 12, width: tag.width + 6, height: tag.height)
        nice.frame = CGRect(x: fr.midX - 34, y: fr.minY + 12, width: 68, height: 30)
    }

    // ============================================================ layers
    private func applyLayer() {
        tabRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let evText = abs(ev) < 0.05 ? "" : String(format: " · %+.1f", ev)
        var tabs = [("COMPOSE", "COMPOSE"), ("ART", "ART" + (art == .OFF ? "" : " · " + art.label)), ("EXPOSURE", "EXPOSURE" + evText)]
        if double { tabs.append(("BLEND", "BLEND")) }
        for (key, text) in tabs {
            let on = key == layer
            var c = UIButton.Configuration.plain()
            c.attributedTitle = AttributedString(text, attributes: AttributeContainer([.font: Style.medium(11), .kern: 0.6]))
            c.baseForegroundColor = on ? Style.gold : Style.muted
            c.titleLineBreakMode = .byTruncatingTail
            c.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 4, bottom: 6, trailing: 4)
            let b = UIButton(configuration: c, primaryAction: UIAction { [weak self] _ in self?.layer = key; self?.save(); self?.applyLayer() })
            b.layer.cornerRadius = 10; b.layer.borderWidth = 1
            b.layer.borderColor = (on ? Style.gold.withAlphaComponent(0.7) : Style.line).cgColor
            b.backgroundColor = on ? Style.gold.withAlphaComponent(0.14) : .clear
            tabRow.addArrangedSubview(b)
        }
        composeLayer.isHidden = layer != "COMPOSE"; artLayer.isHidden = layer != "ART"
        exposureLayer.isHidden = layer != "EXPOSURE"; blendLayer.isHidden = layer != "BLEND"
    }

    private func applyGuide() {
        guideRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for g in Guide.allCases {
            guideRow.addArrangedSubview(Style.chip(g.label, on: g == guide) { [weak self] in self?.guide = g; self?.save(); self?.applyGuide(); self?.guideMoved() })
        }
        secondRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        switch guide {
        case .spiral:
            secondRow.addArrangedSubview(Style.label("EYE", Style.mono(10), Style.muted, kern: 1.3)); secondRow.addArrangedSubview(UIView())
            for q in 0..<4 {
                let t = TileView(quadrant: q, triangle: false); t.on = q == quadrant; size(t, 44, 50)
                t.addAction(UIAction { [weak self] _ in self?.quadrant = q; self?.save(); self?.applyGuide(); self?.guideMoved() }, for: .touchUpInside)
                secondRow.addArrangedSubview(t)
            }
        case .triangles, .dynamic:
            secondRow.addArrangedSubview(Style.label("DIRECTION", Style.mono(10), Style.muted, kern: 1.3)); secondRow.addArrangedSubview(UIView())
            for f in [false, true] {
                let t = TileView(quadrant: f ? 1 : 0, triangle: true); t.on = f == triFlip; size(t, 44, 50)
                t.addAction(UIAction { [weak self] _ in self?.triFlip = f; self?.save(); self?.applyGuide(); self?.guideMoved() }, for: .touchUpInside)
                secondRow.addArrangedSubview(t)
            }
        case .thirds, .phiGrid, .diagonals:
            // which point (or corner diagonal) a guide-linked focus sits on
            secondRow.addArrangedSubview(Style.label(guide == .diagonals ? "DIAGONAL" : "POINT", Style.mono(10), Style.muted, kern: 1.3)); secondRow.addArrangedSubview(UIView())
            for (a, n) in (guide == .diagonals ? ["↘", "↙", "↗", "↖"] : ["↖", "↗", "↙", "↘"]).enumerated() {
                secondRow.addArrangedSubview(Style.chip(n, on: a == anchor) { [weak self] in self?.anchor = a; self?.save(); self?.applyGuide(); self?.guideMoved() })
            }
        default:
            secondRow.addArrangedSubview(Style.label(guide == .off ? "No guide" : "", Style.medium(11.5), Style.muted))
            secondRow.addArrangedSubview(UIView())
        }
        if guide != .off {
            secondRow.addArrangedSubview(Style.chip(guideHidden ? "SHOW" : "HIDE", on: guideHidden) { [weak self] in
                guard let self else { return }
                self.guideHidden.toggle(); self.save(); self.applyGuide()      // hiding never unlinks the focus
            })
        }
        overlay.hidden = guideHidden
        overlay.guide = guide; overlay.quadrant = quadrant; overlay.triFlip = triFlip
        lastFocus = CGPoint(x: -1, y: -1); refreshFocus()
    }

    private func doubleTap() {
        if guide == .spiral { quadrant = (quadrant + 1) % 4 } else if guide == .triangles || guide == .dynamic { triFlip.toggle() } else { return }
        save(); applyGuide(); guideMoved()
    }

    // ---- art
    private func applyArtUi() {
        artRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for m in ArtMode.allCases {
            artRow.addArrangedSubview(Style.chip(m.label, on: m == art) { [weak self] in
                guard let self else { return }
                // each look keeps its own settings; Off just pauses the look, nothing is forgotten
                self.art = m; self.artState = m.loadState(); self.artParam = 0
                self.save(); self.applyArtUi(); self.applyLayer()
            })
        }
        paramRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        overlay.squareGuide = art == .HOLGA
        pushArt()
        if art == .OFF {
            paramRow.addArrangedSubview(Style.label("Pick a look. The original is always kept; the look is saved as a copy.", Style.medium(11.5), Style.muted))
            artSlider.isHidden = true; artValueLbl.text = ""; return
        }
        for i in art.shown {
            let s = art.sliders[i]
            let b = UIButton(type: .system)
            b.setAttributedTitle(NSAttributedString(string: s.0.uppercased(), attributes: [.font: Style.mono(10), .kern: 1, .foregroundColor: i == artParam ? Style.gold : Style.muted]), for: .normal)
            b.addAction(UIAction { [weak self] _ in self?.artParam = i; self?.applyArtUi(); if self?.art.isFocusSlider(i) == true { self?.showMask() } }, for: .touchUpInside)
            paramRow.addArrangedSubview(b)
        }
        if art == .HOLGA {
            let st = min(max(Int(artValues[5].rounded()), 0), 5)
            paramRow.addArrangedSubview(miniChip("LEAK · " + LEAK_STYLES[st], on: st > 0) { [weak self] in
                guard let self else { return }
                let next = (st + 1) % LEAK_STYLES.count
                self.artValues[5] = Float(next)
                if next == 5 { self.seed = Float.random(in: 0..<1000) }          // Random: a fresh, stable variant
                self.art.saveState(self.artState); self.applyArtUi()
            })
        }
        if art.follows {
            paramRow.addArrangedSubview(miniChip("FOLLOW GUIDE", on: artState.follow) { [weak self] in
                guard let self else { return }
                if self.artState.follow && self.art == .TILT_SHIFT, let g = self.guideCtx(), let m = Focus.linked(g) {
                    // turning it off leaves the band where the guide put it, ready to adjust by hand
                    self.artValues[1] = min(max(m.cy, 0), 1)
                    if m.type == 1 { self.artValues[3] = min(max(m.angle / (.pi / 2) + 0.5, 0), 1) }
                }
                self.artState.follow.toggle()
                self.art.saveState(self.artState); self.applyArtUi(); self.showMask()
            })
        }
        paramRow.addArrangedSubview(miniChip("RESET", on: false) { [weak self] in
            guard let self else { return }
            self.artState = self.art.resetState(); self.artParam = 0; self.applyArtUi(); self.applyLayer()
        })
        if !art.about.isEmpty && artParam == 0 { paramRow.addArrangedSubview(Style.label(art.about, Style.medium(10.5), Style.muted)) }
        artSlider.isHidden = false; artSlider.value = artValues[artParam]
        artValueLbl.text = "\(Int((artValues[artParam] * 100).rounded()))"
    }

    private func miniChip(_ t: String, on: Bool, action: @escaping () -> Void) -> UIButton {
        var c = UIButton.Configuration.filled()
        c.baseBackgroundColor = on ? Style.gold.withAlphaComponent(0.16) : Style.elev
        c.baseForegroundColor = on ? Style.gold : Style.muted
        c.cornerStyle = .medium
        c.contentInsets = NSDirectionalEdgeInsets(top: 5, leading: 9, bottom: 5, trailing: 9)
        c.attributedTitle = AttributedString(t, attributes: AttributeContainer([.font: Style.mono(9.5), .kern: 1]))
        let b = UIButton(configuration: c, primaryAction: UIAction { _ in action() })
        b.layer.borderWidth = 1; b.layer.cornerRadius = 8
        b.layer.borderColor = (on ? Style.gold.withAlphaComponent(0.8) : Style.line).cgColor
        return b
    }

    private func onArtSlider() {
        artValues[artParam] = artSlider.value
        artValueLbl.text = "\(Int((artSlider.value * 100).rounded()))"
        art.saveState(artState); pushArt()
        if art.isFocusSlider(artParam) { showMask() }
    }

    /** In Art with Tilt Shift (not following the guide), a vertical drag on the picture moves the band. */
    private func focusDrag(_ dy: CGFloat) -> Bool {
        guard layer == "ART", art == .TILT_SHIFT, !artState.follow, !live.frameRect.isEmpty else { return false }
        artValues[1] = min(max(artValues[1] + Float(dy / live.frameRect.height), 0), 1)
        if artParam == 1 { artSlider.value = artValues[1]; artValueLbl.text = "\(Int((artValues[1] * 100).rounded()))" }
        art.saveState(artState); pushArt(); showMask()
        return true
    }

    // ---- settings
    private var settingsBtn: UIButton?
    private func settingsMenu() -> UIMenu {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        return UIMenu(title: "Fibonacci Camera \(v) (\(b))", children: [
            UIAction(title: "Haptic feedback", image: UIImage(systemName: "hand.tap"), state: Haptics.enabled ? .on : .off) { [weak self] _ in
                Haptics.enabled.toggle(); if Haptics.enabled { Haptics.confirm() }
                self?.settingsBtn?.menu = self?.settingsMenu()
            }])
    }

    // ---- exposure
    private func onEvSlider() {
        let snapped = (evSlider.value * 3).rounded() / 3       // thirds of a stop
        setEv(snapped)
    }
    private func setEv(_ v: Float) {
        ev = min(max(v, max(-2, evRange.lowerBound)), min(2, evRange.upperBound))
        evSlider.value = ev
        evValue.text = abs(ev) < 0.05 ? "0.0 EV" : String(format: "%+.1f EV", ev)
        cam.setBias(ev); applyLayer()
    }

    // ---- flash
    private func cycleFlash() {
        flash = flash == .off ? .auto : (flash == .auto ? .on : .off)
        save(); syncFlash()
    }
    private func syncFlash() {
        flashBtn.icon = flash == .auto ? .flashAuto : (flash == .on ? .flashOn : .flashOff)
        let on = flash != .off
        flashBtn.tint = on ? Style.gold : Style.muted; flashBtn.fill = on ? Style.gold.withAlphaComponent(0.18) : Style.elev
    }

    // ============================================================ double exposure
    private func syncDouble() {
        doubleBtn.tint = double ? Style.gold : Style.muted
        doubleBtn.fill = double ? Style.gold.withAlphaComponent(0.18) : Style.elev
        exposureTag.isHidden = !double
        var c = exposureTag.configuration
        c?.attributedTitle = AttributedString(first == nil ? "EXPOSURE 1 OF 2" : "EXPOSURE 2 OF 2  ✕", attributes: AttributeContainer([.font: Style.mono(10.5), .kern: 1]))
        c?.titleLineBreakMode = .byClipping
        exposureTag.configuration = c
        frameChanged(live.frameRect)
    }

    private func toggleDouble() {
        double.toggle()
        if double { layer = "BLEND"; if first == nil, let data = try? Data(contentsOf: Self.firstURL) { first = UIImage(data: data) } }
        else if layer == "BLEND" { layer = "COMPOSE" }
        save(); syncDouble(); applyGhost(); applyLayer()
    }

    private func applyBlendUi() {
        blendRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for b in Blend.allCases {
            blendRow.addArrangedSubview(Style.chip(b.label, on: b == blend) { [weak self] in self?.blend = b; self?.save(); self?.applyBlendUi(); self?.applyGhost() })
        }
        blendSlider.value = strength
        blendValue.text = "\(Int((strength * 100).rounded()))%"
    }
    private func onBlendSlider() {
        strength = blendSlider.value
        blendValue.text = "\(Int((strength * 100).rounded()))%"
        live.strength = strength; save()
    }

    /** The first exposure floats over the live picture with the chosen blend, so you compose the pair. */
    private func applyGhost() {
        live.blend = blend; live.strength = strength
        guard double, let f = first else { live.ghost = nil; return }
        DispatchQueue.global(qos: .userInitiated).async {
            let t = Renderer.upright(f, maxLong: 2048).flatMap { Renderer.shared.texture(from: $0) }
            DispatchQueue.main.async { if self.double && self.first === f { self.live.ghost = t } }
        }
    }

    private func setFirst(_ img: UIImage, data: Data?) {
        first = img
        try? (data ?? img.jpegData(compressionQuality: 0.92))?.write(to: Self.firstURL)
        syncDouble(); applyGhost()
    }

    private func discardFirst() {
        guard first != nil else { return }
        first = nil; try? FileManager.default.removeItem(at: Self.firstURL)
        syncDouble(); applyGhost()
    }

    // ============================================================ camera
    private func cameraReady(_ z: ZoomInfo, _ evr: ClosedRange<Float>) {
        zoom = z; zoomDisplay = 1; evRange = evr
        sourceLbl.text = front ? "LIVE · FRONT" : "LIVE · REAR"
        buildZoomChips(); setEv(ev)
    }

    private func flipCamera() {
        front.toggle(); tracker.clear(); aligned = false; cam.start(front: front)
    }

    private func tapFocus(_ p: CGPoint) {
        let fr = live.frameRect
        let n = CGPoint(x: (p.x - fr.minX) / fr.width, y: (p.y - fr.minY) / fr.height)
        cam.focus(at: n)
        tracker.target(n); aligned = false; haptic.prepare()      // the tap also names the subject to follow
    }

    /**
     * The subject is followed without anything on screen; when it lands on a guide's business end
     * (the spiral's eye, a power point, an intersection) the phone buzzes once. Moving well off re-arms it.
     */
    private func onTrack(_ st: Tracker.State?) {
        let fr = overlay.guideFrame
        guard let st, !st.lost, !fr.isEmpty else {
            subject = nil; aligned = false
            if st?.lost == true { nice.layer.removeAllAnimations(); nice.alpha = 0 }   // never "Nice" while lost
            return
        }
        let lf = live.frameRect
        let p = CGPoint(x: lf.minX + st.nx * lf.width, y: lf.minY + st.ny * lf.height)
        subject = p
        refreshFocus()
        guard let best = Geo.nearestGoal(guide, fr, quadrant: quadrant, triFlip: triFlip, p: p) else { aligned = false; return }
        let d = hypot(best.x - p.x, best.y - p.y)
        let r = max(14, 0.045 * min(fr.width, fr.height))
        if !aligned && d < r { aligned = true; Haptics.confirm(); showNice() }
        else if aligned && d > r * 1.8 { aligned = false }
    }

    private func pinch(_ s: CGFloat) { setZoom(zoomDisplay * s) }

    private func setZoom(_ z: CGFloat) {
        zoomDisplay = min(max(z, zoom.minDisplay), zoom.maxDisplay)
        cam.setZoom(display: zoomDisplay); syncZoomChips()
    }

    private func zoomLabel(_ z: CGFloat) -> String {
        if z < 1 { return String(format: ".%d×", Int((z * 10).rounded())) }
        return abs(z - z.rounded()) < 0.05 ? "\(Int(z.rounded()))×" : String(format: "%.1f×", z)
    }

    private func buildZoomChips() {
        zoomRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        guard zoom.stops.count > 1 else { return }
        for s in zoom.stops {
            let b = UIButton(type: .custom)
            b.tag = Int(s * 100); b.layer.cornerRadius = 21; b.accessibilityLabel = "Zoom \(zoomLabel(s))"
            b.addAction(UIAction { [weak self] _ in self?.setZoom(s) }, for: .touchUpInside)
            size(b, 42, 42); zoomRow.addArrangedSubview(b)
        }
        syncZoomChips()
    }

    /** The chip you're on, or the one just below a pinch, lights up and shows the live value. */
    private func syncZoomChips() {
        let chips = zoomRow.arrangedSubviews.compactMap { $0 as? UIButton }
        let active = chips.last(where: { CGFloat($0.tag) / 100 <= zoomDisplay + 0.02 }) ?? chips.first
        for b in chips {
            let stop = CGFloat(b.tag) / 100, on = b === active
            let text = on && abs(stop - zoomDisplay) > 0.04 ? zoomLabel(zoomDisplay) : zoomLabel(stop)
            b.setAttributedTitle(NSAttributedString(string: text, attributes: [.font: Style.mono(12), .foregroundColor: on ? Style.gold : Style.muted]), for: .normal)
            b.backgroundColor = on ? Style.gold.withAlphaComponent(0.16) : Style.elev.withAlphaComponent(0.9)
            b.layer.borderWidth = on ? 1 : 0; b.layer.borderColor = Style.gold.withAlphaComponent(0.5).cgColor
        }
    }

    private func rotationNow() -> CGFloat {
        switch UIDevice.current.orientation {
        case .landscapeLeft: return front ? 180 : 0
        case .landscapeRight: return front ? 0 : 180
        case .portraitUpsideDown: return 270
        default: return 90
        }
    }

    // ============================================================ capture
    private func capture() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        whiteout.alpha = 0.6; UIView.animate(withDuration: 0.25) { self.whiteout.alpha = 0 }
        let r = recipe, firstNow = double ? first : nil, takingFirst = double && first == nil
        let b = blend, st = strength
        Library.ensure { [weak self] ok in
            guard let self else { return }
            if !ok { self.toast("Photos access is off. Turn it on in Settings › Fibonacci."); return }
            self.cam.capture(flash: self.flash, rotation: self.rotationNow()) { data in
                guard let data, let img = UIImage(data: data) else { self.toast("Couldn't take that photo."); return }
                Library.saveOriginal(data)                                          // the original, always, untouched
                self.seed = Float.random(in: 0..<1000); self.pushArt()             // fresh leaks and grain for the next frame
                self.thumb.image = img
                if takingFirst { self.setFirst(img, data: data); return }          // exposure 1 now floats over the live view
                if firstNow != nil || r != nil { self.saveCopy(base: img, top: firstNow, blend: b, strength: st, recipe: r) }
                if firstNow != nil { self.discardFirst() }
            }
        }
    }

    private func saveCopy(base: UIImage, top: UIImage?, blend: Blend, strength: Float, recipe: Recipe?) {
        saving += 1
        DispatchQueue.global(qos: .userInitiated).async {
            var out: CGImage?
            if let bc = Renderer.upright(base) {
                let tc = top.flatMap { Renderer.upright($0, maxLong: CGFloat(max(bc.width, bc.height))) }
                out = Renderer.shared.renderStill(base: bc, top: tc, blend: blend, strength: strength, recipe: recipe)
                if let o = out, let r = recipe { out = Renderer.finish(o, r.mode) }
            }
            DispatchQueue.main.async {
                self.saving -= 1
                guard let out else { self.toast("Saved the original; the copy failed."); return }
                Library.saveCopy(out, date: Date())
                self.thumb.image = UIImage(cgImage: out)
                if top != nil { self.toast("Double exposure saved · both originals kept") }
            }
        }
    }

    private func showNice() {
        nice.layer.removeAllAnimations(); nice.alpha = 1
        UIView.animate(withDuration: 0.35, delay: 0.9, options: [.beginFromCurrentState]) { self.nice.alpha = 0 }
    }

    private func toast(_ s: String) {
        let l = Style.label(s, Style.medium(12), Style.gold)
        l.backgroundColor = Style.base.withAlphaComponent(0.85); l.textAlignment = .center; l.numberOfLines = 0
        l.layer.cornerRadius = 14; l.layer.borderColor = Style.gold.withAlphaComponent(0.8).cgColor; l.layer.borderWidth = 1; l.clipsToBounds = true
        let w = min(stage.bounds.width - 48, l.intrinsicContentSize.width + 28)
        l.frame = CGRect(x: (stage.bounds.width - w) / 2, y: stage.bounds.height - 60, width: w, height: 36)
        stage.addSubview(l)
        UIView.animate(withDuration: 0.3, delay: 2.4, options: []) { l.alpha = 0 } completion: { _ in l.removeFromSuperview() }
    }

    // ============================================================ import
    @objc private func openPhotos() {
        if let u = URL(string: "photos-redirect://") { UIApplication.shared.open(u) }
    }

    #if targetEnvironment(simulator)
    /** Simulator only: choose what the demo camera films, and whether it drifts like a hand. */
    private func makeDemoButton() -> UIButton {
        var c = UIButton.Configuration.plain()
        c.contentInsets = .zero
        c.attributedTitle = AttributedString("DEMO FEED ▾", attributes: AttributeContainer([.font: Style.mono(10), .kern: 1]))
        c.baseForegroundColor = Style.gold
        let b = UIButton(configuration: c)
        b.showsMenuAsPrimaryAction = true
        b.menu = demoMenu()
        b.accessibilityLabel = "Demo feed options"
        return b
    }
    private func demoMenu() -> UIMenu {
        UIMenu(title: "Simulator demo feed", children: [
            UIAction(title: "Built-in scene", image: UIImage(systemName: "photo"), state: demo.usingBuiltIn ? .on : .off) { [weak self] _ in
                self?.demo.useScene(nil); self?.refreshDemoMenu()
            },
            UIAction(title: "Film a photo from the library…", image: UIImage(systemName: "photo.on.rectangle"), state: demo.usingBuiltIn ? .off : .on) { [weak self] _ in
                self?.pickingScene = true; self?.pickPhoto()
            },
            UIAction(title: "Handheld drift", image: UIImage(systemName: "hand.raised"), state: demo.drift ? .on : .off) { [weak self] _ in
                guard let self else { return }
                self.demo.drift.toggle(); self.refreshDemoMenu()
            }])
    }
    /** For automated Simulator screenshots: launch with `-fibShot camera|art|double|editor`. */
    private func runShotScript() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-fibShot"), i + 1 < args.count else { return }
        let shot = args[i + 1]
        Log.info("shot script: \(shot)")
        guide = .spiral; quadrant = 1; applyGuide()
        switch shot {
        case "art":
            art = .CROSS_PROCESS; artValues = art.defaults; layer = "ART"; applyArtUi(); applyLayer()
        case "double":
            let mirrored = UIImage(cgImage: DemoCamera.builtIn(), scale: 1, orientation: .upMirrored)
            double = true; layer = "BLEND"; syncDouble(); applyLayer(); setFirst(mirrored, data: nil)
        case "editor":
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.openEditor(UIImage(cgImage: DemoCamera.builtIn())) }
        default: break
        }
    }

    private func refreshDemoMenu() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            for v in self.view.allSubviews() { if let b = v as? UIButton, b.showsMenuAsPrimaryAction { b.menu = self.demoMenu() } }
        }
    }
    #endif

    private func pickPhoto() {
        var c = PHPickerConfiguration(); c.filter = .images; c.selectionLimit = 1
        let p = PHPickerViewController(configuration: c); p.delegate = self
        present(p, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        #if targetEnvironment(simulator)
        if results.isEmpty { pickingScene = false }
        #endif
        guard let item = results.first?.itemProvider, item.canLoadObject(ofClass: UIImage.self) else { return }
        item.loadObject(ofClass: UIImage.self) { obj, _ in
            guard let img = obj as? UIImage else { return }
            DispatchQueue.main.async {
                #if targetEnvironment(simulator)
                if self.pickingScene { self.pickingScene = false; self.demo.useScene(img); self.refreshDemoMenu(); return }
                #endif
                if self.double { self.setFirst(img, data: nil) }
                else { self.openEditor(img) }
            }
        }
    }

    private func openEditor(_ img: UIImage) {
        let e = EditorViewController(image: img)
        e.modalPresentationStyle = .fullScreen
        present(e, animated: true)
    }
}

extension UIView {
    func allSubviews() -> [UIView] { subviews + subviews.flatMap { $0.allSubviews() } }
}
