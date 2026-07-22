import ReplayKit
import Photos

/// Console-style "instant replay": ReplayKit buffers the last ~15 seconds of
/// on-screen play invisibly (no recording indicator, no separate start/stop
/// step), and a single tap exports that buffer as a clip saved to Photos.
@MainActor
final class HighlightRecorder {
    static let shared = HighlightRecorder()

    private(set) var isBuffering = false
    private var isExporting = false

    var isAvailable: Bool { RPScreenRecorder.shared().isAvailable }

    func startBuffering() {
        guard !isBuffering, isAvailable else { return }
        RPScreenRecorder.shared().startClipBuffering { [weak self] error in
            Task { @MainActor in self?.isBuffering = (error == nil) }
        }
    }

    func stopBuffering() {
        guard isBuffering else { return }
        RPScreenRecorder.shared().stopClipBuffering { [weak self] _ in
            Task { @MainActor in self?.isBuffering = false }
        }
    }

    /// Exports the last `duration` seconds of buffered play and saves it to
    /// Photos. Safe to call repeatedly; ignored while an export is in flight.
    func saveHighlight(duration: TimeInterval = 15, completion: @escaping (Bool) -> Void) {
        guard !isExporting, isBuffering else {
            completion(false)
            return
        }
        isExporting = true
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("highlight-\(UUID().uuidString).mp4")

        RPScreenRecorder.shared().exportClip(to: url, duration: duration) { [weak self] error in
            guard error == nil else {
                Task { @MainActor in self?.isExporting = false; completion(false) }
                return
            }
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                guard status == .authorized || status == .limited else {
                    try? FileManager.default.removeItem(at: url)
                    Task { @MainActor in self?.isExporting = false; completion(false) }
                    return
                }
                PHPhotoLibrary.shared().performChanges({
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                }) { success, _ in
                    try? FileManager.default.removeItem(at: url)
                    Task { @MainActor in self?.isExporting = false; completion(success) }
                }
            }
        }
    }
}
