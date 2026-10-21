import AppKit
import SwiftUI
import AVKit
import UniformTypeIdentifiers

// MARK: - Photos & videos: whole library, grouped by day, select → download / delete, drag out to Finder

struct PhotosView: View {
    @ObservedObject var store: PhoneStore

    private static let dayFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .full
        f.doesRelativeDateFormatting = true
        return f
    }()

    /// Items grouped by day, in order.
    private var sections: [(String, [PhonePhoto])] {
        var out: [(String, [PhonePhoto])] = []
        for p in store.photos {
            let day = Self.dayFormat.string(from: p.date)
            if out.last?.0 == day { out[out.count - 1].1.append(p) } else { out.append((day, [p])) }
        }
        return out
    }

    var body: some View {
        ZStack {
            HStack(spacing: 0) {
                albumRail.frame(width: 236)
                Divider()
                grid
            }
            if store.slideshow { SlideshowView(store: store).transition(.opacity) }
        }
    }

    // MARK: Albums

    private var albumRail: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    albumRow(id: nil, name: "Library", count: nil, cover: nil)
                    Text("ALBUMS").font(.caption2.weight(.semibold)).tracking(1.2).foregroundStyle(.secondary)
                        .padding(.horizontal, 10).padding(.top, 14).padding(.bottom, 4)
                    ForEach(store.albums) { a in albumRow(id: a.id, name: a.name, count: a.count, cover: a.cover) }
                }
                .padding(10)
            }
            dropZone
        }
        .background(Color.primary.opacity(0.035))
    }

    private func albumRow(id: String?, name: String, count: Int?, cover: NSImage?) -> some View {
        let selected = store.album == id
        return Button { store.showAlbum(id) } label: {
            HStack(spacing: 10) {
                Group {
                    if let cover {
                        Image(nsImage: cover).resizable().scaledToFill()
                    } else {
                        ZStack {
                            LinearGradient(colors: [Theme.accent, Theme.accent.opacity(0.55)], startPoint: .topLeading, endPoint: .bottomTrailing)
                            Image(systemName: "photo.stack.fill").font(.system(size: 16)).foregroundStyle(.white)
                        }
                    }
                }
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                        .foregroundStyle(selected ? Theme.accent : .primary)
                    if let count { Text("\(count.formatted())").font(.caption).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 0)
            }
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(selected ? Theme.accent.opacity(0.15) : .clear))
            .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).strokeBorder(selected ? Theme.accent.opacity(0.35) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Drop files here to send them to the phone.
    private var dropZone: some View {
        let active = store.dropTargeted
        return VStack(spacing: 4) {
            Image(systemName: "arrow.up.doc.fill").font(.title3)
            Text("Drop files to send to phone").font(.caption.weight(.medium))
        }
        .foregroundStyle(active ? Theme.accent : .secondary)
        .frame(maxWidth: .infinity).padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(active ? Theme.accent.opacity(0.14) : Color.primary.opacity(0.04)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .strokeBorder(active ? Theme.accent : Color.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
        .padding(10)
        .animation(.easeOut(duration: 0.12), value: active)
        .onDrop(of: [UTType.fileURL], isTargeted: Binding(get: { store.dropTargeted }, set: { store.dropTargeted = $0 })) { providers in
            for p in providers {
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url { Task { @MainActor in store.onSendFiles([url]) } }
                }
            }
            return true
        }
    }

    // MARK: Grid

    private var albumName: String {
        store.album.flatMap { id in store.albums.first { $0.id == id }?.name } ?? "Library"
    }

    private func pill(_ title: String, _ icon: String? = nil, prominent: Bool = false, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let icon { Image(systemName: icon) }
                if !title.isEmpty { Text(title).lineLimit(1) }
            }
            .fixedSize()
            .font(.system(size: 12.5, weight: .semibold))
            .padding(.horizontal, 12).padding(.vertical, 6)
            .foregroundStyle(prominent ? Color.white : destructive ? Color.red : Color.primary)
            .background(Capsule().fill(prominent ? Theme.accent : destructive ? Color.red.opacity(0.14) : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }

    private var kindPicker: some View {
        HStack(spacing: 2) {
            ForEach(MediaKind.allCases) { k in
                Button { store.showKind(k) } label: {
                    Text(k.title).font(.system(size: 12.5, weight: .semibold)).lineLimit(1).fixedSize()
                        .padding(.horizontal, 14).padding(.vertical, 5)
                        .foregroundStyle(store.kind == k ? Color.white : .primary)
                        .background(Capsule().fill(store.kind == k ? Theme.accent : .clear))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(Capsule().fill(Color.primary.opacity(0.08)))
        .fixedSize()
    }

    private var grid: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(store.selecting ? "\(store.selection.count) selected" : albumName)
                            .font(.system(size: 22, weight: .bold, design: .rounded)).lineLimit(1)
                        Text(store.selecting ? "Download or delete the selection" : store.photoTotal > 0 ? "\(store.photoTotal.formatted()) items · drag to Finder to save" : "Loading…")
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    pill("Slideshow", "play.rectangle.fill") { store.startSlideshow() }.disabled(store.slidePhotos.isEmpty)
                    pill("", "arrow.clockwise") { store.loadMorePhotos(reset: true) }.help("Refresh")
                    if store.selecting {
                        pill("Download", "arrow.down.circle.fill", prominent: true) { store.download(Array(store.selection)) }
                            .disabled(store.selection.isEmpty)
                        pill("Delete", "trash", destructive: true) { store.deleteSelected() }
                            .disabled(store.selection.isEmpty)
                        pill("Done") { store.selecting = false; store.selection.removeAll() }
                    } else {
                        pill("Select", "checkmark.circle") { store.selecting = true }
                    }
                }
                if !store.selecting { kindPicker }
            }
            .padding(.horizontal, 18).padding(.vertical, 12)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6, pinnedViews: [.sectionHeaders]) {
                    ForEach(sections, id: \.0) { day, items in
                        Section {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 240), spacing: 8)], spacing: 8) {
                                ForEach(items) { p in cell(p) }
                            }
                            .padding(.horizontal, 18).padding(.bottom, 14)
                        } header: {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(day).font(.system(size: 15, weight: .bold, design: .rounded))
                                Text("\(items.count)").font(.caption.weight(.medium)).foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(.horizontal, 18).padding(.vertical, 8)
                            .background(.ultraThinMaterial)
                        }
                    }
                    if store.photos.isEmpty && !store.loadingPhotos {
                        VStack(spacing: 8) {
                            Image(systemName: "photo.on.rectangle.angled").font(.system(size: 36)).foregroundStyle(.secondary)
                            Text("Nothing here yet").foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity).padding(.top, 80)
                    }
                    if store.loadingPhotos || store.photos.count < store.photoTotal {
                        ProgressView().frame(maxWidth: .infinity).padding()
                            .onAppear { store.loadMorePhotos() }
                    }
                }
            }
        }
    }

    private func cell(_ p: PhonePhoto) -> some View {
        let selected = store.selection.contains(p.id)
        let hovering = store.hovered == p.id
        return Color.primary.opacity(0.07)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let img = p.thumb { Image(nsImage: img).resizable().scaledToFill() }
            }
            .overlay(alignment: .bottom) {
                if p.isVideo {
                    LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom).frame(height: 56)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if p.isVideo {
                    HStack(spacing: 4) {
                        Image(systemName: "play.fill").font(.system(size: 9))
                        if p.duration > 0 { Text(String(format: "%d:%02d", p.duration / 60, p.duration % 60)).font(.system(size: 11, weight: .semibold).monospacedDigit()) }
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.ultraThinMaterial.opacity(0.9), in: Capsule())
                    .environment(\.colorScheme, .dark)
                    .padding(8)
                }
            }
            .overlay { if selected { Theme.accent.opacity(0.28) } }
            .overlay(alignment: .topLeading) {
                if selected {
                    Image(systemName: "checkmark.circle.fill").font(.title3).foregroundStyle(.white, Theme.accent).padding(8)
                } else if store.selecting || hovering {
                    Image(systemName: "circle").font(.title3).foregroundStyle(.white.opacity(0.9)).shadow(radius: 2).padding(8)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(selected ? Theme.accent : .white.opacity(hovering ? 0.25 : 0.06), lineWidth: selected ? 3 : 1))
            .scaleEffect(hovering ? 1.03 : 1)
            .shadow(color: .black.opacity(hovering ? 0.32 : 0.10), radius: hovering ? 12 : 3, y: hovering ? 6 : 1)
            .zIndex(hovering ? 1 : 0)
            .animation(.easeOut(duration: 0.14), value: hovering)
            .contentShape(Rectangle())
            .onHover { inside in
                if inside { store.hovered = p.id } else if store.hovered == p.id { store.hovered = nil }
            }
            .onTapGesture {
                if store.selecting || NSEvent.modifierFlags.contains(.command) {
                    store.selecting = true
                    store.toggleSelection(p.id)
                } else {
                    store.view(p)
                }
            }
            .contextMenu {
                Button("Open") { store.view(p) }
                Button("Download") { store.download([p.id]) }
                Button("Delete from phone…", role: .destructive) {
                    store.selection = [p.id]
                    store.deleteSelected()
                }
            }
            .onDrag { store.dragProvider(p) }
    }
}

/// Plays a downloaded video (starts automatically, with the standard macOS controls).
struct VideoPlayerView: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> AVPlayerView {
        let v = AVPlayerView()
        v.controlsStyle = .floating
        v.showsFullScreenToggleButton = true
        v.player = AVPlayer(url: url)
        v.player?.play()
        return v
    }

    func updateNSView(_ v: AVPlayerView, context: Context) {}

    static func dismantleNSView(_ v: AVPlayerView, coordinator: ()) {
        v.player?.pause()
        v.player = nil
    }
}

/// Full-size photo or video over the grid, with Download / Copy / Open / Delete, drag-out and ← → to browse.
struct PhotoViewer: View {
    @ObservedObject var store: PhoneStore
    let photo: PhonePhoto

    private static let dateFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .long
        f.timeStyle = .short
        return f
    }()

    private var fileURL: URL? { store.downloaded[photo.id] }
    private var full: NSImage? { photo.isVideo ? nil : fileURL.flatMap { store.image(at: $0) } }
    private var ready: Bool { fileURL != nil }

    var body: some View {
        ZStack {
            Color.black.opacity(0.94)
            if let thumb = photo.thumb {
                Image(nsImage: thumb).resizable().scaledToFill().blur(radius: 70).opacity(0.28).clipped()
            }
            VStack(spacing: 0) {
                toolbar.padding(14)
                media
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.horizontal, 70).padding(.bottom, 24)
            }
            if store.photos.contains(where: { $0.id == photo.id }) {
                HStack {
                    arrow("chevron.left", .leftArrow) { store.step(-1) }
                    Spacer()
                    arrow("chevron.right", .rightArrow) { store.step(1) }
                }
                .padding(.horizontal, 16)
            }
        }
        .clipped()
    }

    // MARK: Pieces

    private var toolbar: some View {
        HStack(spacing: 10) {
            Button { store.viewing = nil } label: {
                Image(systemName: "xmark").font(.system(size: 13, weight: .bold))
                    .frame(width: 34, height: 34).background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .keyboardShortcut(.cancelAction)
            .help("Close (Esc)")

            VStack(alignment: .leading, spacing: 1) {
                Text(Self.dateFormat.string(from: photo.date)).font(.system(size: 14, weight: .semibold))
                Text(photo.name + (photo.isVideo && photo.duration > 0 ? String(format: " · %d:%02d", photo.duration / 60, photo.duration % 60) : ""))
                    .font(.caption).foregroundStyle(.white.opacity(0.6))
            }
            Spacer()

            HStack(spacing: 6) {
                Image(systemName: photo.isVideo ? "film" : "photo")
                Text("Drag to save")
            }
            .font(.system(size: 12, weight: .semibold))
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(.ultraThinMaterial, in: Capsule())
            .onDrag { store.dragProvider(photo) }
            .help("Drag this into Finder, Mail, a chat…")

            action(photo.isVideo ? "Open in Player" : "Open in Preview", "arrow.up.forward.app") {
                if let url = fileURL { NSWorkspace.shared.open(url) }
            }
            if !photo.isVideo { action("Copy text from photo", "text.viewfinder") { store.copyText(photo.id) } }
            action("Download", "arrow.down.circle") { store.download([photo.id]) }
            action("Copy", "doc.on.doc") { store.copy(photo.id) }
                .keyboardShortcut("c", modifiers: .command)
            action("Delete", "trash", destructive: true) { store.delete(photo.id) }
        }
        .foregroundStyle(.white)
    }

    private func action(_ title: String, _ icon: String, destructive: Bool = false, run: @escaping () -> Void) -> some View {
        let enabled = destructive || ready
        return Button(action: run) {
            Label(title, systemImage: icon).labelStyle(.iconOnly)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 36, height: 34)
                .foregroundStyle(destructive ? Color.red : .white)
                .background(.ultraThinMaterial, in: Capsule())
                .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .help(title)
    }

    @ViewBuilder private var media: some View {
        if photo.isVideo, let url = fileURL {
            VideoPlayerView(url: url)
                .id(photo.id)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .shadow(color: .black.opacity(0.5), radius: 24, y: 10)
        } else if let full {
            Image(nsImage: full).resizable().scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .shadow(color: .black.opacity(0.5), radius: 24, y: 10)
                .onDrag { store.dragProvider(photo) }
        } else {
            ZStack {
                if let thumb = photo.thumb {
                    Image(nsImage: thumb).resizable().scaledToFit().blur(radius: 8).opacity(0.55)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                loadingBadge
            }
        }
    }

    private var loadingBadge: some View {
        let p = store.progress[photo.id]
        return VStack(spacing: 10) {
            ZStack {
                Circle().stroke(.white.opacity(0.18), lineWidth: 5)
                if let p, p.total > 0 {
                    Circle().trim(from: 0, to: p.fraction).stroke(Theme.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Text("\(Int(p.fraction * 100))%").font(.system(size: 14, weight: .bold).monospacedDigit())
                } else {
                    ProgressView().controlSize(.small)
                }
            }
            .frame(width: 62, height: 62)
            if let p, p.total > 0 {
                Text(String(format: "%.1f of %.1f MB", Double(p.got) / 1_000_000, Double(p.total) / 1_000_000))
                    .font(.caption.monospacedDigit())
                if p.got > 0 { Text(String(format: "%.1f MB/s", p.speed)).font(.caption2.monospacedDigit()).foregroundStyle(.white.opacity(0.6)) }
            } else {
                Text("Fetching from your phone…").font(.caption)
            }
        }
        .foregroundStyle(.white)
        .padding(22)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .environment(\.colorScheme, .dark)
    }

    private func arrow(_ symbol: String, _ key: KeyEquivalent, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 18, weight: .bold)).foregroundStyle(.white)
                .frame(width: 46, height: 46)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .keyboardShortcut(key, modifiers: [])
    }
}
