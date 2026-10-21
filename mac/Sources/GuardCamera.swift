import AVFoundation
import AppKit
import ImageIO
import UniformTypeIdentifiers

/// Takes a few quick photos with the Mac's camera. The green camera light comes on while it does; macOS doesn't allow hiding it.
final class GuardCamera: NSObject, AVCapturePhotoCaptureDelegate {
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "phonebridge.guard.camera")
    private var current: DispatchSemaphore?
    private var latest: Data?

    static var status: AVAuthorizationStatus { AVCaptureDevice.authorizationStatus(for: .video) }

    static func authorize(_ done: @escaping (Bool) -> Void) {
        switch status {
        case .authorized: done(true)
        case .notDetermined: AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { done(ok) } }
        default: done(false)
        }
    }

    /// Calls [done] on the main queue with JPEGs (possibly none, e.g. no camera or the lid is shut).
    func snap(count: Int, spacing: TimeInterval, done: @escaping ([Data]) -> Void) {
        queue.async {
            var photos: [Data] = []
            defer { DispatchQueue.main.async { done(photos) } }
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device) else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = .high
            if self.session.canAddInput(input) { self.session.addInput(input) }
            if self.session.canAddOutput(self.output) { self.session.addOutput(self.output) }
            self.session.commitConfiguration()
            self.session.startRunning()
            defer {
                self.session.stopRunning()
                self.session.inputs.forEach { self.session.removeInput($0) }
                self.session.outputs.forEach { self.session.removeOutput($0) }
            }
            Thread.sleep(forTimeInterval: 1.3) // let the exposure settle
            for i in 0..<count {
                let sem = DispatchSemaphore(value: 0)
                self.current = sem
                self.latest = nil
                self.output.capturePhoto(with: AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg]), delegate: self)
                _ = sem.wait(timeout: .now() + 4)
                if let raw = self.latest, let small = Self.compress(raw) { photos.append(small) }
                if i < count - 1 { Thread.sleep(forTimeInterval: spacing) }
            }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        latest = error == nil ? photo.fileDataRepresentation() : nil
        current?.signal()
    }

    /// Shrinks to at most 1024 px and re-encodes as JPEG, so an alert stays small.
    static func compress(_ data: Data, maxSide: Int = 1024, quality: Double = 0.7) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let img = CGImageSourceCreateThumbnailAtIndex(src, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                                     kCGImageSourceCreateThumbnailWithTransform: true,
                                                                     kCGImageSourceThumbnailMaxPixelSize: maxSide] as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, img, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}
