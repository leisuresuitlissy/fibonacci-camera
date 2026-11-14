import PhotosUI
import UIKit
import simd

/**
 * Edit a copy: crop, straighten, add a look, lay a second photo over it. The photo you opened is only
 * ever read; every save is a new item in the library, so you can edit the same picture as many ways as you like.
 */
final class EditorViewController: UIViewController, PHPickerViewControllerDelegate {
    private var image: UIImage
    private let d = UserDefaults.standard
    private let r = Renderer.shared
    private let renderQ = DispatchQueue(label: "fibcam.editor")

    private let canvas = CropCanvas()
    private let readL = Style.label("", Style.mono(10), Style.muted)
    private let readR = Style.label("", Style.mono(9), Style.gold)
    private let tabRow = Style.hstack(spacing: 6)
    private let cropLayer = UIStackView(), artLayer = UIStackView(), blendLayer = UIStackView()
    private let ratioRow = Style.hstack(), eyeRow = Style.hstack()
    private let dial = DialView()
    private let artRow = Style.hstack(), paramRow = Style.hstack(spacing: 14)
    private let artSlider = Style.slider()
    private let blendActions = Style.hstack(), blendRow = Style.hstack()
    private let blendHead = Style.hstack()
    private let blendValue = Style.label("60%", Style.mono(11), Style.gold)
    private let blendSlider = Style.slider()
    private let saveBtn = UIButton(type: .custom)
    private let note = Style.label("", Style.medium(12), Style.gold)

    private var editTab = "CROP"
    private var recipe = Recipe(mode: .OFF, values: ArtMode.OFF.defaults, seed: Float.random(in: 0..<1000))
    private var artParam = 0
    private var ratio = "PHI"
    private var portrait = true
    private var scale: CGFloat = 1             // full px per display px

    private var baseTex: MTLTexture?
    private var second: UIImage?
    private var secondTex: MTLTexture?
    private var blend = Blend.screen
    private var strength: Float = 0.6
    private var busy = false
    private var pickingSecond = false
    private var rendering = false, dirty = false

    init(image: UIImage) { self.image = image; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Style.base
        buildUi()
        load(image)
    }

    private func load(_ img: UIImage) {
        image = img
        showNote("Loading…")
        renderQ.async {
            guard let cg = Renderer.upright(img, maxLong: 1800), let t = self.r.texture(from: cg) else {
                DispatchQueue.main.async { self.showNote("That photo couldn’t be opened.") }; return
            }
            let fullW = img.size.width * img.scale
            DispatchQueue.main.async {
                self.baseTex = t
                self.scale = fullW / CGFloat(cg.width)
                self.canvas.bw = CGFloat(cg.width); self.canvas.bh = CGFloat(cg.height)
                self.portrait = cg.height >= cg.width
                self.canvas.pic = cg
                self.canvas.setNeedsLayout(); self.canvas.layoutIfNeeded()
                self.applyRatio(reset: true); self.applyTab(); self.applyArtUi(); self.applyBlendUi(); self.showNote(nil)
                self.refresh()
            }
        }
    }

    // ============================================================ layout
    private func size(_ v: UIView, _ w: CGFloat?, _ h: CGFloat?) {
        v.translatesAutoresizingMaskIntoConstraints = false
        if let w { v.widthAnchor.constraint(equalToConstant: w).isActive = true }
        if let h { v.heightAnchor.constraint(equalToConstant: h).isActive = true }
    }
    private func pin(_ v: UIView, to p: UIView) {
        v.translatesAutoresizingMaskIntoConstraints = false; p.addSubview(v)
        NSLayoutConstraint.activate([v.leadingAnchor.constraint(equalTo: p.leadingAnchor), v.trailingAnchor.constraint(equalTo: p.trailingAnchor),
                                     v.topAnchor.constraint(equalTo: p.topAnchor), v.bottomAnchor.constraint(equalTo: p.bottomAnchor)])
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
        let root = UIStackView(); root.axis = .vertical
        root.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(root)
        NSLayoutConstraint.activate([root.leadingAnchor.constraint(equalTo: view.leadingAnchor), root.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                                     root.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), root.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)])
        let titles = UIStackView(arrangedSubviews: [Style.label("Edit a copy", .boldSystemFont(ofSize: 15), Style.text),
                                                    Style.label("ORIGINAL IS NEVER CHANGED", Style.mono(9.5), Style.muted, kern: 1)])
        titles.axis = .vertical; titles.spacing = 3
        let back = Style.chip("‹ CAMERA", on: false) { [weak self] in self?.dismiss(animated: true) }
        let other = Style.chip("OTHER PHOTO", on: false) { [weak self] in self?.pick(second: false) }
        let top = Style.hstack([back, titles, UIView(), other], spacing: 12); size(top, nil, 56)
        root.addArrangedSubview(top)

        let stage = UIView(); stage.backgroundColor = .black
        pin(canvas, to: stage)
        canvas.onChange = { [weak self] in self?.syncReadout(); self?.refreshIfArt() }
        canvas.onSecondChange = { [weak self] in self?.refresh() }
        note.backgroundColor = Style.base.withAlphaComponent(0.85); note.textAlignment = .center; note.layer.cornerRadius = 14
        note.layer.borderWidth = 1; note.layer.borderColor = Style.gold.withAlphaComponent(0.8).cgColor; note.clipsToBounds = true; note.isHidden = true
        note.translatesAutoresizingMaskIntoConstraints = false; stage.addSubview(note)
        NSLayoutConstraint.activate([note.centerXAnchor.constraint(equalTo: stage.centerXAnchor), note.bottomAnchor.constraint(equalTo: stage.bottomAnchor, constant: -16),
                                     note.heightAnchor.constraint(equalToConstant: 34), note.widthAnchor.constraint(lessThanOrEqualTo: stage.widthAnchor, constant: -40)])
        root.addArrangedSubview(stage)

        readR.backgroundColor = Style.gold.withAlphaComponent(0.12); readR.layer.cornerRadius = 10; readR.clipsToBounds = true; readR.textAlignment = .center
        size(readR, 96, 22)
        let read = Style.hstack([readL, UIView(), readR]); size(read, nil, 40); root.addArrangedSubview(read)

        tabRow.distribution = .fillEqually; tabRow.alignment = .fill
        tabRow.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 6, leading: 16, bottom: 2, trailing: 16)
        size(tabRow, nil, 44); root.addArrangedSubview(tabRow)

        let layers = UIView(); size(layers, nil, 168)
        for l in [cropLayer, artLayer, blendLayer] { l.axis = .vertical; pin(l, to: layers) }
        size(ratioRow, nil, 48); size(eyeRow, nil, 48)
        let rs = scroll(ratioRow); size(rs, nil, 48)
        dial.onChange = { [weak self] v in guard let self else { return }; self.canvas.theta = v * .pi / 180; self.canvas.refit() }
        size(dial, nil, 64)
        [rs, eyeRow, dial, UIView()].forEach(cropLayer.addArrangedSubview)
        let ars = scroll(artRow); size(ars, nil, 48)
        size(paramRow, nil, 32)
        artSlider.addAction(UIAction { [weak self] _ in self?.onArtSlider() }, for: .valueChanged)
        let asr = Style.hstack([artSlider]); size(asr, nil, 40)
        [ars, paramRow, asr, UIView()].forEach(artLayer.addArrangedSubview)
        size(blendActions, nil, 48); size(blendRow, nil, 48)
        let bs = scroll(blendRow); size(bs, nil, 48)
        blendHead.addArrangedSubview(Style.label("BLEND STRENGTH", Style.mono(10), Style.muted, kern: 1.3)); blendHead.addArrangedSubview(UIView()); blendHead.addArrangedSubview(blendValue)
        size(blendHead, nil, 22)
        blendSlider.addAction(UIAction { [weak self] _ in self?.onBlendSlider() }, for: .valueChanged)
        let bsr = Style.hstack([blendSlider]); size(bsr, nil, 36)
        [blendActions, bs, blendHead, bsr, UIView()].forEach(blendLayer.addArrangedSubview)
        root.addArrangedSubview(layers)

        let reset = Style.chip("Reset", on: false) { [weak self] in
            guard let self else { return }
            self.dial.value = 0; self.canvas.theta = 0; self.applyRatio(reset: true)
        }
        saveBtn.backgroundColor = Style.gold; saveBtn.layer.cornerRadius = 14
        saveBtn.setTitleColor(Style.base, for: .normal); saveBtn.titleLabel?.font = .boldSystemFont(ofSize: 14)
        saveBtn.addAction(UIAction { [weak self] _ in self?.save() }, for: .touchUpInside)
        size(saveBtn, nil, 50)
        let bar = Style.hstack([reset, saveBtn], spacing: 10); size(bar, nil, 70)
        root.addArrangedSubview(bar)
    }

    private func showNote(_ s: String?) {
        guard let s else { note.isHidden = true; return }
        note.text = "   \(s)   "; note.isHidden = false
    }

    // ============================================================ tabs
    private func applyTab() {
        tabRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let tabs = [("CROP", "CROP"), ("ART", "ART" + (recipe.isOn ? " · " + recipe.mode.label : "")),
                    ("BLEND", second != nil ? "BLEND · " + blend.label : "DOUBLE")]
        for (key, text) in tabs {
            let on = key == editTab
            var c = UIButton.Configuration.plain()
            c.attributedTitle = AttributedString(text, attributes: AttributeContainer([.font: Style.medium(11), .kern: 0.6]))
            c.baseForegroundColor = on ? Style.gold : Style.muted; c.titleLineBreakMode = .byTruncatingTail
            let b = UIButton(configuration: c, primaryAction: UIAction { [weak self] _ in self?.editTab = key; self?.applyTab() })
            b.layer.cornerRadius = 10; b.layer.borderWidth = 1
            b.layer.borderColor = (on ? Style.gold.withAlphaComponent(0.7) : Style.line).cgColor
            b.backgroundColor = on ? Style.gold.withAlphaComponent(0.14) : .clear
            tabRow.addArrangedSubview(b)
        }
        cropLayer.isHidden = editTab != "CROP"; artLayer.isHidden = editTab != "ART"; blendLayer.isHidden = editTab != "BLEND"
        canvas.editingSecond = editTab == "BLEND"      // in Blend your fingers move the second photo; elsewhere, the crop
    }

    // ============================================================ ratio + guide
    private func longOverShort(_ r: String) -> CGFloat {
        switch r { case "PHI": return CGFloat(PHI); case "4:5": return 1.25; case "1:1": return 1; case "3:2": return 1.5
        default: return max(canvas.bw, canvas.bh) / min(canvas.bw, canvas.bh) }
    }
    private func frameGuide() -> Guide {
        let g = Guide(rawValue: d.string(forKey: "guide") ?? "") ?? .thirds
        return g == .spiral || g == .off ? .thirds : g
    }
    private func applyRatio(reset: Bool) {
        let los = longOverShort(ratio)
        canvas.aspect = portrait ? 1 / los : los
        canvas.guide = ratio == "PHI" ? .spiral : frameGuide()
        if reset { canvas.quadrant = d.integer(forKey: "quadrant") }
        canvas.triFlip = d.bool(forKey: "triFlip")
        if reset { canvas.reset() } else { canvas.refit() }
        buildRows()
    }
    private func buildRows() {
        ratioRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (key, lab) in [("PHI", "φ"), ("ORIGINAL", "ORIGINAL"), ("4:5", "4:5"), ("1:1", "1:1"), ("3:2", "3:2")] {
            ratioRow.addArrangedSubview(Style.chip(lab, on: key == ratio) { [weak self] in self?.ratio = key; self?.applyRatio(reset: false) })
        }
        ratioRow.addArrangedSubview(Style.chip(portrait ? "↕" : "↔", on: false) { [weak self] in self?.portrait.toggle(); self?.applyRatio(reset: false) })
        eyeRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if ratio == "PHI" {
            eyeRow.addArrangedSubview(Style.label("EYE", Style.mono(10), Style.muted, kern: 1.3)); eyeRow.addArrangedSubview(UIView())
            for (q, n) in ["↖", "↗", "↙", "↘"].enumerated() {
                eyeRow.addArrangedSubview(Style.chip(n, on: q == canvas.quadrant) { [weak self] in self?.canvas.quadrant = q; self?.buildRows() })
            }
        } else {
            eyeRow.addArrangedSubview(Style.label("GUIDE · " + canvas.guide.label, Style.mono(10), Style.muted, kern: 1.3))
        }
        saveBtn.setTitle(ratio == "PHI" ? "Save φ copy" : "Save copy", for: .normal)
        syncReadout()
    }
    private func ratioText() -> String {
        switch ratio {
        case "PHI": return portrait ? "1 : 1.618" : "1.618 : 1"
        case "4:5": return portrait ? "4 : 5" : "5 : 4"
        case "3:2": return portrait ? "2 : 3" : "3 : 2"
        case "1:1": return "1 : 1"
        default: return String(format: portrait ? "1 : %.3f" : "%.3f : 1", longOverShort("ORIGINAL"))
        }
    }
    private func syncReadout() {
        let w = Int((canvas.cw * scale).rounded()), h = Int((canvas.ch * scale).rounded())
        readL.text = (ratio == "PHI" ? "φ CROP · " : "CROP · ") + "\(w) × \(h) px"
        readR.text = ratioText()
    }

    // ============================================================ art
    private func applyArtUi() {
        artRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for m in ArtMode.allCases {
            artRow.addArrangedSubview(Style.chip(m.label, on: m == recipe.mode) { [weak self] in
                guard let self else { return }
                self.recipe = Recipe(mode: m, values: m.loadValues(), seed: self.recipe.seed); self.artParam = 0
                if m == .HOLGA && self.ratio != "1:1" { self.ratio = "1:1"; self.applyRatio(reset: false) }   // Holga is a square camera
                self.applyArtUi(); self.applyTab(); self.refresh()
            })
        }
        paramRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if !recipe.isOn {
            paramRow.addArrangedSubview(Style.label("Pick a look. It's saved as a copy, like the crop.", Style.medium(11.5), Style.muted))
            artSlider.isHidden = true; return
        }
        for (i, s) in recipe.mode.sliders.enumerated() {
            let b = UIButton(type: .system)
            b.setAttributedTitle(NSAttributedString(string: s.0.uppercased(), attributes: [.font: Style.mono(10), .kern: 1, .foregroundColor: i == artParam ? Style.gold : Style.muted]), for: .normal)
            b.addAction(UIAction { [weak self] _ in self?.artParam = i; self?.applyArtUi() }, for: .touchUpInside)
            paramRow.addArrangedSubview(b)
        }
        paramRow.addArrangedSubview(UIView())
        paramRow.addArrangedSubview(Style.label("\(Int((recipe.values[artParam] * 100).rounded()))", Style.mono(11), Style.gold))
        artSlider.isHidden = false; artSlider.value = recipe.values[artParam]
    }
    private func onArtSlider() {
        recipe.values[artParam] = artSlider.value
        (paramRow.arrangedSubviews.last as? UILabel)?.text = "\(Int((artSlider.value * 100).rounded()))"
        recipe.mode.saveValues(recipe.values); refresh()
    }

    // ============================================================ double exposure
    private func applyBlendUi() {
        blendActions.arrangedSubviews.forEach { $0.removeFromSuperview() }
        blendRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if second == nil {
            blendActions.addArrangedSubview(Style.chip("+ ADD SECOND PHOTO", on: true) { [weak self] in self?.pick(second: true) })
            blendRow.addArrangedSubview(Style.label("Lay another photo over this one, then crop and style the pair.", Style.medium(11.5), Style.muted))
            blendHead.isHidden = true; blendSlider.isHidden = true
            return
        }
        blendActions.addArrangedSubview(Style.chip("REPLACE", on: false) { [weak self] in self?.pick(second: true) })
        blendActions.addArrangedSubview(Style.chip("FIT", on: false) { [weak self] in self?.canvas.resetSecond() })
        blendActions.addArrangedSubview(Style.chip("REMOVE", on: false) { [weak self] in self?.removeSecond() })
        blendActions.addArrangedSubview(UIView())
        blendActions.addArrangedSubview(Style.label("DRAG · PINCH · TWIST", Style.mono(9), Style.muted, kern: 1))
        for b in Blend.allCases {
            blendRow.addArrangedSubview(Style.chip(b.label, on: b == blend) { [weak self] in self?.blend = b; self?.applyBlendUi(); self?.applyTab(); self?.refresh() })
        }
        blendHead.isHidden = false; blendSlider.isHidden = false
        blendSlider.value = strength; blendValue.text = "\(Int((strength * 100).rounded()))%"
    }
    private func onBlendSlider() {
        strength = blendSlider.value; blendValue.text = "\(Int((strength * 100).rounded()))%"; refresh()
    }
    private func removeSecond() {
        second = nil; secondTex = nil; canvas.secondSize = nil
        applyBlendUi(); applyTab(); refresh()
    }
    private func setSecond(_ img: UIImage) {
        showNote("Adding the second photo…")
        renderQ.async {
            let cg = Renderer.upright(img, maxLong: 1800)
            let t = cg.flatMap { self.r.texture(from: $0) }
            DispatchQueue.main.async {
                guard let cg, let t else { self.showNote("That photo couldn’t be opened."); return }
                self.second = img; self.secondTex = t
                self.canvas.secondSize = CGSize(width: cg.width, height: cg.height)
                self.canvas.resetSecond()
                self.applyBlendUi(); self.applyTab(); self.showNote(nil); self.refresh()
            }
        }
    }

    // ============================================================ preview
    private func refreshIfArt() { if recipe.isOn { refresh() } }

    /** Re-renders the pair (and the look, measured against the crop) at display size. Coalesces bursts. */
    private func refresh() {
        guard let base = baseTex else { return }
        if rendering { dirty = true; return }
        rendering = true
        let top = secondTex, b = blend, st = strength, rc = recipe.isOn ? recipe : nil
        let m = top.map { _ in Renderer.place(out: CGSize(width: base.width, height: base.height), top: canvas.secondSize ?? .zero,
                                               c: CGPoint(x: canvas.s2x, y: canvas.s2y), widthFrac: canvas.s2w, rotDeg: canvas.s2r) } ?? matrix_identity_float3x3
        let box = canvas.cropBox
        renderQ.async {
            let cg = self.r.renderDisplay(base: base, top: top, m: m, blend: b, strength: st, recipe: rc, measure: box)
            DispatchQueue.main.async {
                if let cg { self.canvas.pic = cg }
                self.rendering = false
                if self.dirty { self.dirty = false; self.refresh() }
            }
        }
    }

    // ============================================================ pick + save
    private func pick(second: Bool) {
        pickingSecond = second
        var c = PHPickerConfiguration(); c.filter = .images; c.selectionLimit = 1
        let p = PHPickerViewController(configuration: c); p.delegate = self
        present(p, animated: true)
    }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let item = results.first?.itemProvider, item.canLoadObject(ofClass: UIImage.self) else { return }
        let forSecond = pickingSecond
        item.loadObject(ofClass: UIImage.self) { obj, _ in
            guard let img = obj as? UIImage else { return }
            DispatchQueue.main.async {
                if forSecond { self.setSecond(img) }
                else { self.second = nil; self.secondTex = nil; self.canvas.secondSize = nil; self.recipe.mode = .OFF; self.load(img) }
            }
        }
    }

    private func save() {
        guard !busy, baseTex != nil else { return }
        busy = true; showNote("Saving a copy…")
        let c = canvas, f = scale
        let cx = c.cx, cy = c.cy, cw = c.cw, ch = c.ch, th = c.theta
        let s2 = (c.s2x, c.s2y, c.s2w, c.s2r)
        let rc = recipe.isOn ? recipe : nil, img = image, sec = second, b = blend, st = strength
        Library.ensure { ok in
            guard ok else { self.busy = false; self.showNote("Photos access is off. Turn it on in Settings › Fibonacci."); return }
            self.renderQ.async {
                var out: CGImage? = nil
                autoreleasepool {
                    guard var full = Renderer.upright(img) else { return }
                    if let sec, let top = Renderer.upright(sec, maxLong: CGFloat(max(full.width, full.height))) {
                        // the pair first, at full size, exactly as placed on screen; then crop, then the look
                        let m = Renderer.place(out: CGSize(width: full.width, height: full.height), top: CGSize(width: top.width, height: top.height),
                                               c: CGPoint(x: s2.0 * f, y: s2.1 * f), widthFrac: s2.2, rotDeg: s2.3)
                        if let pair = self.r.renderStill(base: full, top: top, m: m, blend: b, strength: st, recipe: nil) { full = pair }
                    }
                    let ow = max(16, (cw * f).rounded()), oh = max(16, (ch * f).rounded())
                    let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1; fmt.opaque = true
                    let cropped = UIGraphicsImageRenderer(size: CGSize(width: ow, height: oh), format: fmt).image { ctx in
                        let g = ctx.cgContext
                        UIColor.black.setFill(); g.fill(CGRect(x: 0, y: 0, width: ow, height: oh))
                        g.translateBy(x: ow / 2, y: oh / 2); g.rotate(by: -th); g.translateBy(x: -cx * f, y: -cy * f)   // crop and straighten in one step
                        UIImage(cgImage: full).draw(at: .zero)
                    }.cgImage
                    guard var o = cropped else { return }
                    if let rc, let styled = self.r.renderStill(base: o, recipe: rc) { o = Renderer.finish(styled, rc.mode) }
                    out = o
                }
                DispatchQueue.main.async {
                    guard let out else { self.busy = false; self.showNote("Couldn’t save that copy."); return }
                    Library.saveCopy(out, date: Date()) { ok in
                        self.busy = false
                        self.showNote(ok ? "Saved a \(out.width) × \(out.height) copy · original untouched" : "Couldn’t save that copy.")
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.6) { self.showNote(nil) }
                    }
                }
            }
        }
    }
}
