@preconcurrency import AVFoundation
import Observation
import SwiftUI
import UIKit

struct CapturedPhoto: Sendable {
    let jpegData: Data
}

enum SomaCameraError: LocalizedError {
    case permissionDenied
    case unavailable
    case configurationFailed
    case captureFailed
    case imageTooLarge

    var errorDescription: String? {
        switch self {
        case .permissionDenied:
            "Camera access is needed to capture a photo."
        case .unavailable:
            "A camera isn’t available on this device."
        case .configurationFailed:
            "The camera could not be prepared."
        case .captureFailed:
            "The photo could not be captured."
        case .imageTooLarge:
            "The captured photo is too large to save."
        }
    }
}

actor CameraCaptureService {
    nonisolated(unsafe) let session = AVCaptureSession()

    private let output = AVCapturePhotoOutput()
    private var configured = false
    private var delegates: [Int64: PhotoCaptureDelegate] = [:]

    func start() throws {
        if !configured {
            try configure()
        }
        if !session.isRunning {
            session.startRunning()
        }
    }

    func stop() {
        if session.isRunning {
            session.stopRunning()
        }
    }

    func capture() async throws -> CapturedPhoto {
        guard configured, session.isRunning else {
            throw SomaCameraError.configurationFailed
        }

        let settings = AVCapturePhotoSettings(
            format: [AVVideoCodecKey: AVVideoCodecType.jpeg]
        )
        settings.photoQualityPrioritization = .speed
        if output.supportedFlashModes.contains(.auto) {
            settings.flashMode = .auto
        }

        if let connection = output.connection(with: .video),
           connection.isVideoRotationAngleSupported(90)
        {
            connection.videoRotationAngle = 90
        }

        return try await withCheckedThrowingContinuation { continuation in
            let identifier = settings.uniqueID
            let delegate = PhotoCaptureDelegate(
                completion: { result in
                    continuation.resume(with: result)
                },
                cleanup: { [weak self] in
                    Task { await self?.releaseDelegate(identifier: identifier) }
                }
            )
            delegates[identifier] = delegate
            output.capturePhoto(with: settings, delegate: delegate)
        }
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        session.sessionPreset = .photo
        guard
            let device = AVCaptureDevice.default(
                .builtInWideAngleCamera,
                for: .video,
                position: .back
            ),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input),
            session.canAddOutput(output)
        else {
            throw SomaCameraError.unavailable
        }

        session.addInput(input)
        session.addOutput(output)
        output.maxPhotoQualityPrioritization = .speed

        if output.isResponsiveCaptureSupported {
            output.isResponsiveCaptureEnabled = true
        }
        if output.isFastCapturePrioritizationSupported {
            output.isFastCapturePrioritizationEnabled = true
        }
        if output.isZeroShutterLagSupported {
            output.isZeroShutterLagEnabled = true
        }

        if #available(iOS 26.0, *) {
            session.automaticallyRunsDeferredStart = true
            if output.isDeferredStartSupported {
                output.isDeferredStartEnabled = true
            }
        }

        configured = true
    }

    private func releaseDelegate(identifier: Int64) {
        delegates[identifier] = nil
    }
}

private final class PhotoCaptureDelegate:
    NSObject,
    AVCapturePhotoCaptureDelegate,
    @unchecked Sendable
{
    private static let maximumJPEGBytes = 24 * 1_024 * 1_024

    private let lock = NSLock()
    private let completion: @Sendable (Result<CapturedPhoto, Error>) -> Void
    private let cleanup: @Sendable () -> Void
    private var result: Result<CapturedPhoto, Error>?
    private var completed = false

    init(
        completion: @escaping @Sendable (Result<CapturedPhoto, Error>) -> Void,
        cleanup: @escaping @Sendable () -> Void
    ) {
        self.completion = completion
        self.cleanup = cleanup
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        lock.withLock {
            if let error {
                result = .failure(error)
            } else if let data = photo.fileDataRepresentation() {
                result = data.count <= Self.maximumJPEGBytes
                    ? .success(CapturedPhoto(jpegData: data))
                    : .failure(SomaCameraError.imageTooLarge)
            } else {
                result = .failure(SomaCameraError.captureFailed)
            }
        }
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
        error: Error?
    ) {
        let finalResult: Result<CapturedPhoto, Error> = lock.withLock {
            guard !completed else {
                return .failure(SomaCameraError.captureFailed)
            }
            completed = true
            return result ?? error.map(Result.failure) ?? .failure(SomaCameraError.captureFailed)
        }
        completion(finalResult)
        cleanup()
    }
}

@MainActor
@Observable
final class CameraCaptureModel {
    enum Status: Equatable {
        case starting
        case ready
        case denied
        case unavailable
    }

    private let service = CameraCaptureService()
    private(set) var status: Status = .starting
    private(set) var isCapturing = false
    var errorMessage: String?

    var session: AVCaptureSession { service.session }

    func start() async {
        status = .starting
        let granted: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            granted = true
        case .notDetermined:
            granted = await AVCaptureDevice.requestAccess(for: .video)
        default:
            granted = false
        }

        guard granted else {
            status = .denied
            return
        }

        do {
            try await service.start()
            status = .ready
        } catch {
            status = .unavailable
            errorMessage = error.localizedDescription
        }
    }

    func capture() async throws -> CapturedPhoto {
        guard status == .ready, !isCapturing else {
            throw SomaCameraError.captureFailed
        }
        isCapturing = true
        defer { isCapturing = false }
        return try await service.capture()
    }

    func stop() {
        Task { await service.stop() }
    }
}

struct OneShotCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var camera = CameraCaptureModel()

    let onCaptured: (CapturedPhoto) async throws -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            CameraPreview(session: camera.session)
                .ignoresSafeArea()
                .opacity(camera.status == .denied || camera.status == .unavailable ? 0 : 1)

            VStack(spacing: 0) {
                HStack {
                    Button("Close", systemImage: "xmark") { dismiss() }
                        .labelStyle(.iconOnly)
                        .frame(width: 46, height: 46)
                        .background(.ultraThinMaterial, in: .circle)
                        .disabled(camera.isCapturing)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)

                Spacer()

                cameraStatus
                    .padding(.horizontal, 24)

                shutter
                    .padding(.top, 18)
                    .padding(.bottom, 28)
            }
        }
        .task { await camera.start() }
        .onDisappear { camera.stop() }
        .sensoryFeedback(.impact(weight: .medium), trigger: camera.isCapturing)
        .alert("Camera", isPresented: .constant(camera.errorMessage != nil)) {
            Button("OK") { camera.errorMessage = nil }
        } message: {
            Text(camera.errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var cameraStatus: some View {
        switch camera.status {
        case .starting:
            ProgressView("Starting camera…")
                .tint(.white)
                .foregroundStyle(.white)
        case .denied:
            VStack(spacing: 12) {
                Text("Camera access is off")
                    .font(.headline)
                Text("Allow access in Settings to capture photos in Soma.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Open Settings") {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                    openURL(url)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(22)
            .background(.regularMaterial, in: .rect(cornerRadius: 20))
        case .unavailable:
            ContentUnavailableView(
                "Camera unavailable",
                systemImage: "camera.slash",
                description: Text("Connect an iPhone with an available camera.")
            )
            .foregroundStyle(.white)
        case .ready:
            EmptyView()
        }
    }

    private var shutter: some View {
        Button {
            Task {
                do {
                    let photo = try await camera.capture()
                    try await onCaptured(photo)
                    dismiss()
                } catch {
                    camera.errorMessage = error.localizedDescription
                }
            }
        } label: {
            ZStack {
                Circle()
                    .stroke(.white, lineWidth: 5)
                    .frame(width: 72, height: 72)
                Circle()
                    .fill(.white)
                    .frame(width: 58, height: 58)
                    .scaleEffect(camera.isCapturing ? 0.88 : 1)
            }
        }
        .buttonStyle(.plain)
        .disabled(camera.status != .ready || camera.isCapturing)
        .opacity(camera.status == .ready ? 1 : 0.35)
        .accessibilityLabel("Take photo")
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewView {
        CameraPreviewView(session: session)
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {}
}

private final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    private var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    init(session: AVCaptureSession) {
        super.init(frame: .zero)
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
        if #available(iOS 26.0, *), previewLayer.isDeferredStartSupported {
            previewLayer.isDeferredStartEnabled = false
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard
            let connection = previewLayer.connection,
            connection.isVideoRotationAngleSupported(90)
        else {
            return
        }
        connection.videoRotationAngle = 90
    }
}
