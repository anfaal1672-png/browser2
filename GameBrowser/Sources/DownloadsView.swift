import SwiftUI

/// Downloads list: what's coming in, and everything already saved.
struct DownloadsView: View {
    @ObservedObject var downloads: DownloadManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if downloads.items.isEmpty {
                    emptyState
                } else {
                    List {
                        ForEach(downloads.items) { item in
                            DownloadRow(item: item, downloads: downloads)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(loc("ダウンロード", "Downloads"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc("すべて削除", "Clear all"), role: .destructive) {
                        downloads.clearFinished()
                    }
                    .disabled(downloads.items.allSatisfy(\.isActive))
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc("完了", "Done")) { dismiss() }
                }
            }
            .onAppear { downloads.loadFromDisk() }
        }
        .presentationDetents([.medium, .large])
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "arrow.down.circle")
                .font(.system(size: 38))
                .foregroundStyle(.secondary)
            Text(loc("ダウンロードはまだありません", "No downloads yet"))
                .font(.system(size: 15, weight: .medium))
            Text(loc("保存したファイルは「ファイル」アプリの GameBrowser からも開けます",
                     "Saved files are also in the Files app under GameBrowser"))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }
}

/// One row: live progress while downloading, actions once it's on disk.
struct DownloadRow: View {
    @ObservedObject var item: DownloadManager.Item
    @ObservedObject var downloads: DownloadManager

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(tint)
                .frame(width: 26)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.filename)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(1)

                if item.isActive {
                    ProgressView(value: item.progress)
                        .tint(.cyan)
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                } else {
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)

            if item.isActive {
                Button {
                    downloads.cancel(item)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            } else if let url = item.fileURL, item.state == .finished {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
        .swipeActions {
            Button(role: .destructive) {
                downloads.delete(item)
            } label: {
                Label(loc("削除", "Delete"), systemImage: "trash")
            }
        }
    }

    private var icon: String {
        switch item.state {
        case .downloading: return "arrow.down.circle"
        case .finished: return "doc.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .cancelled: return "slash.circle"
        }
    }

    private var tint: Color {
        switch item.state {
        case .downloading: return .cyan
        case .finished: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }

    private var subtitle: String {
        switch item.state {
        case .downloading:
            let percent = Int(item.progress * 100)
            return item.sizeText.isEmpty
                ? "\(percent)%"
                : "\(percent)% ・ \(item.sizeText)"
        case .finished:
            return [item.sizeText, item.date.formatted(date: .abbreviated, time: .shortened)]
                .filter { !$0.isEmpty }
                .joined(separator: " ・ ")
        case .failed(let message):
            return message
        case .cancelled:
            return loc("キャンセルしました", "Cancelled")
        }
    }
}

/// Brief confirmation for things that otherwise happen invisibly — a finished
/// download, a copied link, a profile switching itself on.
struct ToastView: View {
    let text: String
    let icon: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
            Text(text)
                .font(.system(size: 13, weight: .medium))
                .lineLimit(2)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.black.opacity(0.82), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.15), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.35), radius: 8, y: 3)
        .padding(.horizontal, 20)
    }
}
