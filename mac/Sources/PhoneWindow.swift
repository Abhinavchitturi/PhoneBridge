import AppKit
import UniformTypeIdentifiers
import SwiftUI

// MARK: - Data from the phone

struct SmsThread: Identifiable {
    let id: Int64
    let address: String
    let name: String?
    let snippet: String
    let date: Date
    let unread: Bool
    var title: String { name ?? address }
}

struct SmsMessage: Identifiable {
    let id = UUID()
    let body: String
    let date: Date
    let mine: Bool
}

struct PhonePhoto: Identifiable {
    let id: Int64
    let date: Date
    let name: String
    let thumb: NSImage?
    var isVideo = false
    var duration = 0 // seconds, videos only
}

struct DownloadProgress {
    var got: Int64
    var total: Int64
    let started: Date
    var fraction: Double { total > 0 ? min(1, Double(got) / Double(total)) : 0 }
    /// Megabytes per second so far.
    var speed: Double { got > 0 ? Double(got) / 1_000_000 / max(0.2, Date().timeIntervalSince(started)) : 0 }
}

enum MediaKind: String, CaseIterable, Identifiable {
    case all, photo, video
    var id: String { rawValue }
    var title: String { self == .all ? "All" : self == .photo ? "Photos" : "Videos" }
}

struct PhoneAlbum: Identifiable {
    let id: String // Gallery folder (BUCKET_ID)
    let name: String
    let count: Int
    let cover: NSImage?
}

struct CallEntry: Identifiable {
    let id = UUID()
    let number: String
    let name: String?
    let type: String
    let date: Date
    let duration: Int
}

struct Contact: Identifiable {
    let id = UUID()
    let name: String
    let number: String
}

/// Messages / Photos / Calls data, requested from the phone on demand.
@MainActor
final class PhoneStore: ObservableObject {
    enum Tab: Hashable { case messages, photos, calls, apps, clipboard, device, notifications, settings }

    @Published var tab: Tab = .messages
    @Published var threads: [SmsThread] = []
    @Published var selectedThread: Int64? {
        didSet {
            if let t = selectedThread {
                messages = []
                send(["t": "sms_thread_request", "thread": t])
                if let thread = threads.first(where: { $0.id == t }) { markRead(thread) }
            }
        }
    }

    /// The phone's own "unread" flag can't be cleared from here, so remember when each thread was last opened:
    /// a thread is unread only if the phone says so AND something newer than that arrived.
    private var readMarks: [String: Double] = UserDefaults.standard.dictionary(forKey: "smsReadMarks") as? [String: Double] ?? [:]

    func isUnread(_ t: SmsThread) -> Bool {
        t.unread && (readMarks[String(t.id)] ?? 0) < t.date.timeIntervalSince1970
    }

    func markRead(_ t: SmsThread) {
        readMarks[String(t.id)] = t.date.timeIntervalSince1970
        UserDefaults.standard.set(readMarks, forKey: "smsReadMarks")
        objectWillChange.send()
    }
    @Published var messages: [SmsMessage] = []
    @Published var draft = ""
    @Published var photos: [PhonePhoto] = []
    @Published var photoTotal = 0
    @Published var albums: [PhoneAlbum] = []
    /// nil = every photo.
    @Published var album: String?
    @Published var loadingPhotos = false
    @Published var selection: Set<Int64> = []
    @Published var downloaded: [Int64: URL] = [:]   // full-size copies in the cache
    @Published var downloading: Set<Int64> = []
    @Published var viewing: Int64?                   // photo open in the viewer
    /// Videos opened from the Device tab's "biggest videos" list (they aren't in the Photos grid).
    private var extraMedia: [Int64: PhonePhoto] = [:]
    @Published var selecting = false
    @Published var kind: MediaKind = .all
    @Published var hovered: Int64?
    @Published var dropTargeted = false
    @Published var hoverKey: String?
    // ⌘K command palette
    @Published var paletteOpen = false
    @Published var paletteQuery = "" { didSet { paletteIndex = 0 } }
    @Published var paletteIndex = 0
    // Saved one-tap replies
    @Published var quickReplies: [String] = UserDefaults.standard.stringArray(forKey: "quickReplies")
        ?? ["Can't talk now — I'll call you back", "On my way", "OK 👍", "Thanks!", "I'll get back to you soon"] {
        didSet { UserDefaults.standard.set(quickReplies, forKey: "quickReplies") }
    }
    @Published var draftQuickReply = ""
    // Missed-call auto-reply (settings live on the phone; this mirrors and edits them)
    @Published var autoReplyOn = false
    @Published var autoReplyText = ""
    @Published var autoReplyContactsOnly = true
    @Published var autoReplyDeclined = true
    // Device tab
    @Published var device: DeviceInfo?
    @Published var location: PhoneLocation?
    @Published var locationError: String?
    @Published var locating = false
    @Published var biggest: [BigFile] = []
    // Photo slideshow
    @Published var slideshow = false
    @Published var slideIndex = 0
    private var slideTimer: Timer?
    var speak: (String) -> Void = { _ in }
    @Published var threadSearch = ""
    @Published var showPanel = true
    @Published var progress: [Int64: DownloadProgress] = [:]
    private var dragWaiters: [Int64: [(URL?) -> Void]] = [:]
    var onSendFiles: ([URL]) -> Void = { _ in }
    private var saveWhenDownloaded: Set<Int64> = []
    private var savedBatch: [URL] = []
    var notify: (String, String) -> Void = { _, _ in }
    @Published var calls: [CallEntry] = []
    @Published var contacts: [Contact] = []
    @Published var dialNumber = ""
    @Published var contactSearch = ""

    var send: ([String: Any]) -> Void = { _ in }
    var onDial: (String?, String) -> Void = { _, _ in }

    private static func date(_ ms: Any?) -> Date { Date(timeIntervalSince1970: ((ms as? Double) ?? 0) / 1000) }

    // MARK: Requests

    func refresh(_ tab: Tab) {
        switch tab {
        case .messages: send(["t": "sms_threads_request"])
        case .photos:
            send(["t": "albums_request"])
            if photos.isEmpty { loadMorePhotos() }
        case .calls:
            send(["t": "call_log_request"])
            if contacts.isEmpty { send(["t": "contacts_request"]) }
        case .device:
            send(["t": "device_request"])
            send(["t": "biggest_request"])
        case .settings: send(["t": "autoreply_request"])
        case .apps, .clipboard, .notifications: break
        }
    }

    var currentThread: SmsThread? { threads.first { $0.id == selectedThread } }

    func sendDraft() {
        guard let thread = currentThread, !draft.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        send(["t": "sms_send", "to": thread.address, "text": draft])
        messages.insert(SmsMessage(body: draft, date: Date(), mine: true), at: 0)
        draft = ""
        // The phone writes sent SMS to its database a moment later; refresh to show the real copy.
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, let t = self.selectedThread else { return }
            self.send(["t": "sms_thread_request", "thread": t])
            self.send(["t": "sms_threads_request"])
        }
    }

    func loadMorePhotos(reset: Bool = false) {
        guard !loadingPhotos, reset || photos.isEmpty || photos.count < photoTotal else { return }
        loadingPhotos = true
        send(["t": "photos_request", "offset": reset ? 0 : photos.count, "limit": 60, "album": album ?? "", "kind": kind.rawValue])
    }

    func showKind(_ k: MediaKind) {
        guard k != kind else { return }
        kind = k
        photos = []
        photoTotal = 0
        selection = []
        loadingPhotos = false
        loadMorePhotos(reset: true)
    }

    func showAlbum(_ id: String?) {
        guard id != album else { return }
        album = id
        photos = []
        photoTotal = 0
        selection = []
        loadingPhotos = false
        loadMorePhotos(reset: true)
    }

    func photo(for id: Int64) -> PhonePhoto? { photos.first { $0.id == id } ?? extraMedia[id] }

    /// Opens a video from the Device tab in the same viewer.
    func viewBig(_ f: BigFile) {
        let p = PhonePhoto(id: f.id, date: f.date, name: f.name, thumb: f.thumb, isVideo: true, duration: f.duration / 1000)
        extraMedia[f.id] = p
        view(p)
    }

    /// Opens the photo in the in-app viewer, fetching the full-size image if needed.
    func view(_ photo: PhonePhoto) {
        viewing = photo.id
        fetch(photo.id)
        // Prefetch neighbours so ← → feel instant.
        if let i = photos.firstIndex(where: { $0.id == photo.id }) {
            // Only photos are worth prefetching; a video would steal bandwidth from the one being opened.
            for j in [i - 1, i + 1] where photos.indices.contains(j) && !photos[j].isVideo { fetch(photos[j].id) }
        }
    }

    func step(_ delta: Int) {
        guard let id = viewing, let i = photos.firstIndex(where: { $0.id == id }) else { return }
        let j = i + delta
        guard photos.indices.contains(j) else { return }
        view(photos[j])
        if j >= photos.count - 3 { loadMorePhotos() }
    }

    func fetch(_ id: Int64) {
        guard downloaded[id] == nil, !downloading.contains(id) else { return }
        downloading.insert(id)
        progress[id] = DownloadProgress(got: 0, total: -1, started: Date())
        send(["t": "photo_get", "id": id])
    }

    /// Saves full-size copies to ~/Downloads (fetching any that aren't here yet).
    func download(_ ids: [Int64]) {
        savedBatch = []
        for id in ids {
            if let url = downloaded[id] { saveToDownloads(url) } else { saveWhenDownloaded.insert(id); fetch(id) }
        }
        revealSavedIfDone()
    }

    private func saveToDownloads(_ cached: URL) {
        let dest = Transfers.uniqueURL(in: FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0],
                                       name: cached.lastPathComponent)
        if (try? FileManager.default.copyItem(at: cached, to: dest)) != nil { savedBatch.append(dest) }
    }

    private func revealSavedIfDone() {
        guard saveWhenDownloaded.isEmpty, !savedBatch.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting(savedBatch)
        notify("Saved to Downloads", "\(savedBatch.count) photo\(savedBatch.count == 1 ? "" : "s")")
        savedBatch = []
    }

    private var imageCache: [URL: NSImage] = [:]

    /// Decoded full-size images, cached so the viewer doesn't re-decode on every redraw.
    func image(at url: URL) -> NSImage? {
        if let img = imageCache[url] { return img }
        guard let img = NSImage(contentsOf: url) else { return nil }
        if imageCache.count > 12 { imageCache.removeAll() }
        imageCache[url] = img
        return img
    }

    func copy(_ id: Int64) {
        guard let url = downloaded[id] else { return }
        NSPasteboard.general.clearContents()
        if photo(for: id)?.isVideo == true {
            NSPasteboard.general.writeObjects([url as NSURL])
            notify("Video copied", "Paste it in Finder")
        } else if let image = NSImage(contentsOf: url) {
            NSPasteboard.general.writeObjects([image])
            notify("Photo copied", "Paste it anywhere")
        }
    }

    func delete(_ id: Int64) {
        selection = [id]
        deleteSelected()
    }

    func toggleSelection(_ id: Int64) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    /// Asks the phone to move the selected photos to its trash (the phone confirms; recoverable for 30 days).
    func deleteSelected() {
        let ids = Array(selection)
        guard !ids.isEmpty else { return }
        let alert = NSAlert()
        alert.messageText = "Delete \(ids.count) item\(ids.count == 1 ? "" : "s") from your phone?"
        alert.informativeText = "They move to the phone's trash, where they stay for 30 days. Your phone may ask you to confirm."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Delete")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        send(["t": "photos_delete", "ids": ids])
        notify("Check your phone", "Confirm deleting \(ids.count) item\(ids.count == 1 ? "" : "s") on the phone.")
    }

    func dial(_ number: String) {
        let n = number.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        send(["t": "dial", "number": n])
        onDial(name(for: n), n)
    }

    /// Best-known name for a number, from contacts, recent calls or message threads.
    func name(for number: String) -> String? {
        let digits = String(number.filter(\.isNumber).suffix(10))
        guard !digits.isEmpty else { return nil }
        func same(_ other: String) -> Bool { other.filter(\.isNumber).hasSuffix(digits) }
        return contacts.first { same($0.number) }?.name
            ?? calls.first { same($0.number) && $0.name != nil }?.name
            ?? threads.first { same($0.address) }?.name
    }

    // MARK: Messages from the phone

    func handle(_ m: [String: Any]) {
        switch m["t"] as? String {
        case "sms_threads":
            threads = (m["threads"] as? [[String: Any]] ?? []).compactMap { t in
                guard let id = (t["thread"] as? NSNumber)?.int64Value else { return nil }
                return SmsThread(id: id, address: t["address"] as? String ?? "", name: t["name"] as? String,
                                 snippet: t["snippet"] as? String ?? "", date: Self.date(t["date"]),
                                 unread: t["unread"] as? Bool ?? false)
            }
            if selectedThread == nil { selectedThread = threads.first?.id }
            // A message that lands in the conversation you're looking at is read straight away.
            else if tab == .messages, let open = currentThread { markRead(open) }
        case "sms_thread":
            guard (m["thread"] as? NSNumber)?.int64Value == selectedThread else { return }
            messages = (m["messages"] as? [[String: Any]] ?? []).map {
                SmsMessage(body: $0["body"] as? String ?? "", date: Self.date($0["date"]), mine: $0["mine"] as? Bool ?? false)
            }
        case "sms_new":
            send(["t": "sms_threads_request"])
            if let addr = m["address"] as? String, let cur = currentThread,
               addr.filter(\.isNumber).hasSuffix(String(cur.address.filter(\.isNumber).suffix(10))) {
                send(["t": "sms_thread_request", "thread": cur.id])
            }
        case "albums":
            albums = (m["albums"] as? [[String: Any]] ?? []).compactMap { a in
                guard let id = a["id"] as? String else { return nil }
                let cover = (a["cover"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
                return PhoneAlbum(id: id, name: a["name"] as? String ?? "Album", count: a["count"] as? Int ?? 0, cover: cover)
            }
        case "photos":
            // Ignore a late page from an album that's no longer selected.
            guard (m["album"] as? String ?? "") == (album ?? ""), (m["kind"] as? String ?? "all") == kind.rawValue else { return }
            loadingPhotos = false
            photoTotal = m["total"] as? Int ?? photoTotal
            let page: [PhonePhoto] = (m["items"] as? [[String: Any]] ?? []).compactMap { p in
                guard let id = (p["id"] as? NSNumber)?.int64Value else { return nil }
                let thumb = (p["thumb"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
                return PhonePhoto(id: id, date: Self.date(p["date"]), name: p["name"] as? String ?? "", thumb: thumb,
                                  isVideo: p["video"] as? Bool ?? false, duration: Int((p["duration"] as? NSNumber)?.int64Value ?? 0) / 1000)
            }
            if (m["offset"] as? Int ?? 0) == 0 { photos = page } else {
                let known = Set(photos.map(\.id))
                photos += page.filter { !known.contains($0.id) }
            }
        case "autoreply":
            autoReplyOn = m["on"] as? Bool ?? false
            autoReplyText = m["text"] as? String ?? ""
            autoReplyContactsOnly = m["contactsOnly"] as? Bool ?? true
            autoReplyDeclined = m["declined"] as? Bool ?? true
        case "device":
            func n(_ k: String) -> Int64 { (m[k] as? NSNumber)?.int64Value ?? 0 }
            device = DeviceInfo(total: n("total"), free: n("free"), images: n("images"), videos: n("videos"),
                                torch: m["torch"] as? Bool ?? false, ringer: m["ringer"] as? String ?? "normal")
        case "torch_state":
            device?.torch = m["on"] as? Bool ?? false
        case "biggest":
            biggest = (m["items"] as? [[String: Any]] ?? []).compactMap { f in
                guard let id = (f["id"] as? NSNumber)?.int64Value else { return nil }
                let thumb = (f["thumb"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
                return BigFile(id: id, name: f["name"] as? String ?? "Video", size: (f["size"] as? NSNumber)?.int64Value ?? 0,
                               date: Self.date(f["date"]), duration: Int((f["duration"] as? NSNumber)?.int64Value ?? 0), thumb: thumb)
            }
        case "loc":
            locating = false
            if let lat = m["lat"] as? Double, let lon = m["lon"] as? Double {
                location = PhoneLocation(lat: lat, lon: lon, accuracy: m["acc"] as? Double ?? 50,
                                         time: Self.date(m["time"] ?? (Date().timeIntervalSince1970 * 1000)))
                locationError = nil
            } else {
                switch m["error"] as? String {
                case "permission": locationError = "Allow location on the phone: PhoneBridge → Find my phone, then choose “All the time”."
                case "off": locationError = "Location is switched off on the phone."
                default: locationError = "No position yet. Make sure location is allowed “all the time” for PhoneBridge, then try again."
                }
            }
        case "photos_deleted":
            let ids = Set((m["ids"] as? [NSNumber] ?? []).map(\.int64Value))
            if m["ok"] as? Bool == true {
                if let v = viewing, ids.contains(v) {
                    if let i = photos.firstIndex(where: { $0.id == v }) {
                        let rest = photos.filter { !ids.contains($0.id) }
                        viewing = rest.isEmpty ? nil : rest[min(i, rest.count - 1)].id
                    } else {
                        viewing = nil // opened from the Device tab: just close it
                    }
                }
                photos.removeAll { ids.contains($0.id) }
                photoTotal = max(0, photoTotal - ids.count)
                selection.subtract(ids)
                biggest.removeAll { ids.contains($0.id) }
                send(["t": "device_request"])
                notify("Deleted from phone", "\(ids.count) item\(ids.count == 1 ? "" : "s") moved to the phone's trash.")
            } else {
                notify("Photos not deleted", "The phone didn't confirm the deletion.")
            }
        case "call_log":
            calls = (m["calls"] as? [[String: Any]] ?? []).map {
                CallEntry(number: $0["number"] as? String ?? "", name: $0["name"] as? String, type: $0["type"] as? String ?? "",
                          date: Self.date($0["date"]), duration: $0["duration"] as? Int ?? 0)
            }
        case "contacts":
            contacts = (m["contacts"] as? [[String: Any]] ?? []).compactMap {
                guard let name = $0["name"] as? String, let number = $0["number"] as? String else { return nil }
                return Contact(name: name, number: number)
            }
        default:
            break
        }
    }

    /// A full-size photo arrived (file transfer with kind "photo:<id>").
    func photoProgress(id: Int64, got: Int64, total: Int64) {
        guard downloaded[id] == nil else { return }
        let started = progress[id]?.started ?? Date()
        progress[id] = DownloadProgress(got: got, total: total, started: started)
    }

    /// Drag a photo or video out of the window: it's downloaded first if it isn't here yet (a file promise).
    func dragProvider(_ p: PhonePhoto) -> NSItemProvider {
        if let url = downloaded[p.id], let provider = NSItemProvider(contentsOf: url) {
            provider.suggestedName = p.name
            return provider
        }
        let type = UTType(filenameExtension: (p.name as NSString).pathExtension) ?? (p.isVideo ? .movie : .image)
        let provider = NSItemProvider()
        provider.suggestedName = (p.name as NSString).deletingPathExtension
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { [weak self] completion in
            let progress = Progress(totalUnitCount: 100)
            Task { @MainActor in
                guard let self else { return completion(nil, false, nil) }
                self.whenDownloaded(p.id) { url in
                    if let url { completion(url, false, nil) } else { completion(nil, false, CocoaError(.fileReadUnknown)) }
                    progress.completedUnitCount = 100
                }
            }
            return progress
        }
        return provider
    }

    private func whenDownloaded(_ id: Int64, _ done: @escaping (URL?) -> Void) {
        if let url = downloaded[id] { return done(url) }
        dragWaiters[id, default: []].append(done)
        fetch(id)
    }

    /// Reads the text out of a downloaded photo (Vision) and puts it on the clipboard.
    func copyText(_ id: Int64) {
        guard let url = downloaded[id] else { return }
        LiveText.recognize(url) { [weak self] text in
            Task { @MainActor in
                guard let self else { return }
                if text.isEmpty {
                    self.notify("No text found", "Nothing readable in this photo.")
                } else {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                    let lines = text.split(separator: "\n").count
                    self.notify("Text copied", "\(lines) line\(lines == 1 ? "" : "s") from the photo — paste anywhere")
                }
            }
        }
    }

    /// Asks for the PIN right here each time (never stored); leave it empty to just wake the screen and swipe up.
    func unlockPhone() {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Unlock your phone"
        alert.informativeText = "Type your phone's PIN to enter it for you, or leave it empty to only wake the screen and swipe up. PhoneBridge never saves it. Pattern and fingerprint locks can't be entered from here."
        let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 240, height: 24))
        field.placeholderString = "PIN (optional)"
        alert.accessoryView = field
        alert.addButton(withTitle: "Unlock")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        send(["t": "phone_unlock", "pin": field.stringValue])
    }

    func saveAutoReply() {
        send(["t": "autoreply_set", "on": autoReplyOn, "text": autoReplyText, "contactsOnly": autoReplyContactsOnly, "declined": autoReplyDeclined])
    }

    func locate() {
        locating = true
        locationError = nil
        send(["t": "loc_request"])
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in
            if self?.locating == true {
                self?.locating = false
                self?.locationError = "The phone didn't answer. Check it's awake and connected."
            }
        }
    }

    // MARK: Slideshow

    var slidePhotos: [PhonePhoto] { photos.filter { !$0.isVideo } }

    func startSlideshow() {
        guard !slidePhotos.isEmpty else { return }
        slideIndex = 0
        slideshow = true
        prefetchSlides()
        slideTimer?.invalidate()
        slideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.stepSlide(1) }
        }
    }

    func stepSlide(_ delta: Int) {
        let n = slidePhotos.count
        guard n > 0 else { return }
        slideIndex = (slideIndex + delta + n) % n
        prefetchSlides()
    }

    func stopSlideshow() {
        slideshow = false
        slideTimer?.invalidate()
        slideTimer = nil
    }

    private func prefetchSlides() {
        let list = slidePhotos
        guard !list.isEmpty else { return }
        for k in 0...2 { fetch(list[(slideIndex + k) % list.count].id) }
    }

    func photoReceived(id: Int64, url: URL) {
        downloading.remove(id)
        progress[id] = nil
        downloaded[id] = url
        dragWaiters.removeValue(forKey: id)?.forEach { $0(url) }
        if saveWhenDownloaded.remove(id) != nil {
            saveToDownloads(url)
            revealSavedIfDone()
        }
    }
}

// MARK: - Shared formatting

let relative: RelativeDateTimeFormatter = {
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .short
    return f
}()
