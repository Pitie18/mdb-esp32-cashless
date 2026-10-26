import SwiftUI
import AVFoundation

/// Native barcode scanner using AVFoundation camera.
/// Detects EAN-13, EAN-8, UPC-A, UPC-E, Code-128, Code-39, QR codes.
struct BarcodeScannerView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onScanned: { code in
            onScanned(code)
            dismiss()
        })
    }

    class Coordinator: NSObject, ScannerViewControllerDelegate {
        let onScanned: (String) -> Void
        init(onScanned: @escaping (String) -> Void) {
            self.onScanned = onScanned
        }
        func didScanBarcode(_ code: String) {
            onScanned(code)
        }
    }
}

protocol ScannerViewControllerDelegate: AnyObject {
    func didScanBarcode(_ code: String)
}

class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    weak var delegate: ScannerViewControllerDelegate?
    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var hasScanned = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        hasScanned = false
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.stopRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    private func setupCamera() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            showNoCameraLabel()
            return
        }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [
                .ean13, .ean8, .upce,
                .code128, .code39, .code93,
                .qr, .dataMatrix, .interleaved2of5
            ]
        }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.layer.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview

        // Scan region indicator overlay
        addScanRegionOverlay()
    }

    private func addScanRegionOverlay() {
        let overlay = UIView()
        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.layer.borderColor = UIColor.white.withAlphaComponent(0.6).cgColor
        overlay.layer.borderWidth = 2
        overlay.layer.cornerRadius = 12
        overlay.isUserInteractionEnabled = false
        view.addSubview(overlay)

        NSLayoutConstraint.activate([
            overlay.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            overlay.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -20),
            overlay.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.75),
            overlay.heightAnchor.constraint(equalToConstant: 120)
        ])

        let label = UILabel()
        label.text = String(localized: "Scan barcode")
        label.textColor = .white.withAlphaComponent(0.8)
        label.font = .systemFont(ofSize: 14, weight: .medium)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.topAnchor.constraint(equalTo: overlay.bottomAnchor, constant: 16)
        ])
    }

    private func showNoCameraLabel() {
        let label = UILabel()
        label.text = String(localized: "Camera not available")
        label.textColor = .white
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
    }

    // MARK: - AVCaptureMetadataOutputObjectsDelegate

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !hasScanned,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue else { return }

        hasScanned = true

        // Haptic feedback
        HapticFeedback.success.fire()

        delegate?.didScanBarcode(code)
    }
}
