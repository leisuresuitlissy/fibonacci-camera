import UIKit

final class EditorViewController: UIViewController {
    private let image: UIImage
    init(image: UIImage) { self.image = image; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = Style.base
        let b = Style.chip("‹ CAMERA", on: false) { [weak self] in self?.dismiss(animated: true) }
        b.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(b)
        b.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12).isActive = true
        b.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12).isActive = true
    }
}
