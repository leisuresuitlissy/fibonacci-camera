import Photos
import UIKit

/** Everything goes into the photo library as a new item. Nothing already there is ever changed. */
enum Library {
    static func ensure(_ done: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { s in DispatchQueue.main.async { done(s == .authorized || s == .limited) } }
    }

    /** The camera's own file, untouched. */
    static func saveOriginal(_ data: Data, done: ((Bool) -> Void)? = nil) {
        PHPhotoLibrary.shared().performChanges({
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
        }) { ok, _ in DispatchQueue.main.async { done?(ok) } }
    }

    /** A derivative (look, double exposure, crop) as a new JPEG, dated like its original so it sorts beside it. */
    static func saveCopy(_ cg: CGImage, date: Date? = nil, done: ((Bool) -> Void)? = nil) {
        guard let jpg = UIImage(cgImage: cg).jpegData(compressionQuality: 0.95) else { done?(false); return }
        PHPhotoLibrary.shared().performChanges({
            let r = PHAssetCreationRequest.forAsset()
            r.addResource(with: .photo, data: jpg, options: nil)
            if let date { r.creationDate = date }
        }) { ok, _ in DispatchQueue.main.async { done?(ok) } }
    }
}

/** Shared look: colours and small builders, matching the Android app. */
enum Style {
    static let base = UIColor(red: 0x0B / 255, green: 0x0B / 255, blue: 0x0C / 255, alpha: 1)
    static let elev = UIColor(red: 0x1F / 255, green: 0x1F / 255, blue: 0x25 / 255, alpha: 1)
    static let line = UIColor(red: 0x2E / 255, green: 0x2E / 255, blue: 0x36 / 255, alpha: 1)
    static let text = UIColor(red: 0xF5 / 255, green: 0xF3 / 255, blue: 0xEF / 255, alpha: 1)
    static let muted = UIColor(red: 0xA3 / 255, green: 0xA0 / 255, blue: 0x9A / 255, alpha: 1)
    static let gold = GuidePainter.gold

    static func mono(_ size: CGFloat) -> UIFont { .monospacedSystemFont(ofSize: size, weight: .regular) }
    static func medium(_ size: CGFloat) -> UIFont { .systemFont(ofSize: size, weight: .medium) }

    static func label(_ t: String, _ font: UIFont, _ color: UIColor, kern: CGFloat = 0) -> UILabel {
        let l = UILabel(); l.font = font; l.textColor = color
        l.attributedText = NSAttributedString(string: t, attributes: [.kern: kern, .font: font, .foregroundColor: color])
        return l
    }

    /** A pill chip. */
    static func chip(_ t: String, on: Bool, action: @escaping () -> Void) -> UIButton {
        var c = UIButton.Configuration.filled()
        c.baseBackgroundColor = on ? gold.withAlphaComponent(0.18) : elev
        c.baseForegroundColor = on ? gold : muted
        c.cornerStyle = .capsule
        c.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12)
        c.attributedTitle = AttributedString(t, attributes: AttributeContainer([.font: medium(12), .kern: 0.5]))
        let b = UIButton(configuration: c, primaryAction: UIAction { _ in action() })
        if on { b.layer.borderColor = gold.withAlphaComponent(0.85).cgColor; b.layer.borderWidth = 1; b.layer.cornerRadius = 17 }
        return b
    }

    static func slider() -> UISlider {
        let s = UISlider(); s.minimumTrackTintColor = gold; s.maximumTrackTintColor = line; s.thumbTintColor = .white
        return s
    }

    static func hstack(_ views: [UIView] = [], spacing: CGFloat = 8) -> UIStackView {
        let s = UIStackView(arrangedSubviews: views); s.axis = .horizontal; s.spacing = spacing; s.alignment = .center
        s.isLayoutMarginsRelativeArrangement = true; s.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16)
        return s
    }
}
