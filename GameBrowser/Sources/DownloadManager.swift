import Foundation
import WebKit

/// Downloads. The app could not save a file at all before: tapping a download
/// link either did nothing or navigated to a page WebKit couldn't render, so
/// game mods, save files and screenshots were simply out of reach.
///
/// Files land in Documents/Downloads, which `UIFileSharingEnabled` exposes in
/// the Files app, so a finished download is reachable from outside the app too.
@MainActor
final class DownloadManager: NSObject, ObservableObject {

    /// One download — live or already on disk.
    final class Item: NSObject, Identifiable, ObservableObject {
        enum State: Equatable {
            case downloading, finished, failed(String), cancelled
        }

        let id = UUID()
        @Published var filename: String
        @Published var progress: Double = 0
        @Published var state: State
        @Published var bytes: Int64 = 0
        /// Where it came from, when we know.
        let source: URL?
        var fileURL: URL?
        var date: Date
        /// Held so the download can be cancelled; nil once it has finished.
        weak var download: WKDownload?
        private var observer: NSKeyValueObservation?

        init(filename: String, source: URL?, state: State = .downloading,
             fileURL: URL? = nil, date: Date = Date(), bytes: Int64 = 0) {
            self.filename = filename
            self.source = source
            self.state = state
            self.fileURL = fileURL
            self.date = date
            self.bytes = bytes
        }

        var isActive: Bool { state == .downloading }

        var sizeText: String {
            guard bytes > 0 else { return "" }
            return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        }

        /// Mirror WKDownload's Progress into the published values the row draws.
        func track(_ download: WKDownload) {
            self.download = download
            let progress = download.progress
            observer = progress.observe(\.fractionCompleted) { [weak self] progress, _ in
                Task { @MainActor in
                    self?.progress = progress.fractionCompleted
                    if progress.totalUnitCount > 0 { self?.bytes = progress.totalUnitCount }
                }
            }
        }

        func stopTracking() {
            observer?.invalidate()
            observer = nil
            download = nil
        }
    }

    @Published private(set) var items: [Item] = []
    /// Set when a download finishes, for the toast.
    @Published var lastFinished: String?
    /// Published rather than computed: an item finishing changes its own
    /// state, not the array, so nothing else would notice.
    @Published private(set) var activeCount = 0

    private func refreshActiveCount() {
        activeCount = items.filter(\.isActive).count
    }

    static let directory: URL = {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }()

    override init() {
        super.init()
        loadFromDisk()
    }

    /// The folder is the source of truth for finished downloads, so files
    /// deleted from the Files app don't linger in the list.
    func loadFromDisk() {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(
            at: Self.directory, includingPropertiesForKeys: keys)) ?? []
        let existing = items.filter(\.isActive)
        let finished = files
            .filter { !$0.hasDirectoryPath }
            .map { url -> Item in
                let values = try? url.resourceValues(forKeys: Set(keys))
                return Item(
                    filename: url.lastPathComponent,
                    source: nil,
                    state: .finished,
                    fileURL: url,
                    date: values?.contentModificationDate ?? Date(),
                    bytes: Int64(values?.fileSize ?? 0)
                )
            }
            .sorted { $0.date > $1.date }
        items = existing + finished
        refreshActiveCount()
    }

    // MARK: - Starting

    func begin(_ download: WKDownload, suggested: String?, source: URL?) {
        let item = Item(filename: suggested ?? source?.lastPathComponent ?? "download",
                        source: source)
        item.track(download)
        items.insert(item, at: 0)
        refreshActiveCount()
    }

    func cancel(_ item: Item) {
        item.download?.cancel()
        item.stopTracking()
        item.state = .cancelled
        refreshActiveCount()
    }

    func delete(_ item: Item) {
        if item.isActive { cancel(item) }
        if let url = item.fileURL { try? FileManager.default.removeItem(at: url) }
        items.removeAll { $0.id == item.id }
        refreshActiveCount()
    }

    func clearFinished() {
        for item in items where !item.isActive {
            if let url = item.fileURL { try? FileManager.default.removeItem(at: url) }
        }
        items.removeAll { !$0.isActive }
        refreshActiveCount()
    }

    private func item(for download: WKDownload) -> Item? {
        items.first { $0.download === download }
    }

    /// A name that doesn't collide with something already downloaded.
    private func uniqueURL(for filename: String) -> URL {
        let safe = filename.isEmpty ? "download" : filename
            .replacingOccurrences(of: "/", with: "-")
        var candidate = Self.directory.appendingPathComponent(safe)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }

        let ext = candidate.pathExtension
        let stem = candidate.deletingPathExtension().lastPathComponent
        var index = 2
        repeat {
            let name = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
            candidate = Self.directory.appendingPathComponent(name)
            index += 1
        } while FileManager.default.fileExists(atPath: candidate.path) && index < 1000
        return candidate
    }
}

// MARK: - WKDownloadDelegate

extension DownloadManager: WKDownloadDelegate {

    nonisolated func download(_ download: WKDownload,
                              decideDestinationUsing response: URLResponse,
                              suggestedFilename: String,
                              completionHandler: @escaping (URL?) -> Void) {
        MainActor.assumeIsolated {
            let destination = uniqueURL(for: suggestedFilename)
            let entry = item(for: download)
            entry?.filename = destination.lastPathComponent
            entry?.fileURL = destination
            if response.expectedContentLength > 0 {
                entry?.bytes = response.expectedContentLength
            }
            completionHandler(destination)
        }
    }

    nonisolated func downloadDidFinish(_ download: WKDownload) {
        MainActor.assumeIsolated {
            guard let entry = item(for: download) else { return }
            entry.stopTracking()
            entry.progress = 1
            entry.state = .finished
            entry.date = Date()
            if let url = entry.fileURL,
               let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                entry.bytes = Int64(size)
            }
            lastFinished = entry.filename
            refreshActiveCount()
        }
    }

    nonisolated func download(_ download: WKDownload, didFailWithError error: Error,
                              resumeData: Data?) {
        MainActor.assumeIsolated {
            guard let entry = item(for: download) else { return }
            entry.stopTracking()
            // A cancel arrives here as an error too; don't call it a failure.
            if entry.state != .cancelled {
                entry.state = .failed((error as NSError).localizedDescription)
            }
            refreshActiveCount()
        }
    }
}
