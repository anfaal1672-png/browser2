import SwiftUI

/// Downloads list: what's coming in, and everything already saved.
struct DownloadsView: View {
    @ObservedObject var downloads: DownloadManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        GBSheet(
            title: loc("ダウンロード", "Downloads"),
            subtitle: downloads.activeCount > 0
                ? loc("\(downloads.activeCount)件 ダウンロード中", "\(downloads.activeCount) in progress")
                : nil,
            dismiss: { dismiss() }
        ) {
            Button(loc("すべて削除", "Clear"), role: .destructive) { downloads.clearFinished() }
                .font(GB.Font_.label)
                .disabled(downloads.items.allSatisfy(\.isActive))
        } content: {
            if downloads.items.isEmpty {
                GBEmptyState(
                    icon: "arrow.down.circle",
                    title: loc("ダウンロードはまだありません", "No downloads yet"),
                    message: loc("保存したファイルは「ファイル」アプリの GameBrowser からも開けます。",
                                 "Saved files are also in the Files app under GameBrowser.")
                )
                Spacer()
            } else {
                List {
                    ForEach(downloads.items) { item in
                        DownloadRow(item: item, downloads: downloads)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                            .listRowSeparatorTint(GB.border)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { downloads.loadFromDisk() }
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
                    .font(GB.Font_.rowTitle)
                    .foregroundStyle(GB.text)
                    .lineLimit(1)

                if item.isActive {
                    ProgressView(value: item.progress)
                        .tint(GB.accent)
                    Text(subtitle)
                        .font(GB.Font_.caption)
                        .foregroundStyle(GB.textDim)
                } else {
                    Text(subtitle)
                        .font(GB.Font_.caption)
                        .foregroundStyle(GB.textDim)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)

            if item.isActive {
                Button {
                    downloads.cancel(item)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(GB.textDim)
                }
                .buttonStyle(.plain)
            } else if let url = item.fileURL, item.state == .finished {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .foregroundStyle(GB.accent)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, GB.Space.m)
        .padding(.vertical, GB.Space.s)
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
        case .downloading: return GB.accent
        case .finished: return GB.success
        case .failed: return GB.danger
        case .cancelled: return GB.textDim
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
        .foregroundStyle(GB.text)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(GB.bgDeep.opacity(0.94), in: Capsule())
        .overlay(Capsule().stroke(GB.borderStrong, lineWidth: 0.5))
        .shadow(color: .black.opacity(0.35), radius: 8, y: 3)
        .padding(.horizontal, 20)
    }
}
