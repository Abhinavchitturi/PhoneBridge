import AppKit
import CryptoKit
import UserNotifications

/// Shows phone notifications as native macOS notifications, and routes the user's
/// reply / dismiss back to the phone. (Calls use CallPanel.)
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private let iconDir = FileManager.default.temporaryDirectory.appendingPathComponent("PhoneBridgeIcons")
    var send: ([String: Any]) -> Void = { _ in }
    var onDismiss: (String) -> Void = { _ in }
    var onCallBack: (String) -> Void = { _ in }
    var onTextBack: (String) -> Void = { _ in }
    /// Base categories + one per distinct set of app buttons ("Mark as read", "Archive", …).
    private var categories: [String: UNNotificationCategory] = [:]
    private let replyAction = UNTextInputNotificationAction(identifier: "reply", title: "Reply", options: [],
                                                            textInputButtonTitle: "Send", textInputPlaceholder: "Message")

    func start() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if !granted { NSLog("PhoneBridge: notifications not allowed \(String(describing: error))") }
        }
        for c in [
            UNNotificationCategory(identifier: "msg", actions: [replyAction], intentIdentifiers: [], options: [.customDismissAction]),
            UNNotificationCategory(identifier: "plain", actions: [], intentIdentifiers: [], options: [.customDismissAction]),
            UNNotificationCategory(identifier: "file", actions: [], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: "callend", actions: [
                UNNotificationAction(identifier: "textback", title: "Text back", options: [.foreground]),
                UNNotificationAction(identifier: "callback", title: "Call back", options: []),
            ], intentIdentifiers: [], options: []),
        ] { categories[c.identifier] = c }
        center.setNotificationCategories(Set(categories.values))
        try? FileManager.default.createDirectory(at: iconDir, withIntermediateDirectories: true)
    }

    // MARK: From the phone

    func show(_ m: [String: Any], suggestions: [String] = []) {
        guard let id = m["id"] as? String else { return }
        let c = UNMutableNotificationContent()
        c.title = m["title"] as? String ?? ""
        c.subtitle = m["app"] as? String ?? ""
        c.body = m["text"] as? String ?? ""
        c.sound = .default
        c.threadIdentifier = m["pkg"] as? String ?? ""
        c.categoryIdentifier = category(reply: m["reply"] as? Bool ?? false, buttons: m["actions"] as? [String] ?? [], suggestions: suggestions)
        c.userInfo = ["id": id, "suggestions": suggestions]
        if let b64 = m["icon"] as? String, let png = Data(base64Encoded: b64) {
            let url = iconDir.appendingPathComponent(UUID().uuidString + ".png")
            if (try? png.write(to: url)) != nil,
               let att = try? UNNotificationAttachment(identifier: "icon", url: url, options: nil) {
                c.attachments = [att]
            }
        }
        center.add(UNNotificationRequest(identifier: "n:" + id, content: c, trigger: nil))
    }

    /// Category for this mix of Reply + the app's own buttons, registered on first use.
    private func category(reply: Bool, buttons: [String], suggestions: [String] = []) -> String {
        let suggestions = reply ? suggestions : []
        if buttons.isEmpty && suggestions.isEmpty { return reply ? "msg" : "plain" }
        let id = (reply ? "r|" : "|") + buttons.joined(separator: "|") + "##" + suggestions.joined(separator: "|")
        if categories[id] == nil {
            if categories.count > 80 { categories = categories.filter { ["msg", "plain", "file", "callend"].contains($0.key) } }
            var actions: [UNNotificationAction] = reply ? [replyAction] : []
            actions += suggestions.enumerated().map { UNNotificationAction(identifier: "sug:\($0.offset)", title: "💬 " + $0.element) }
            actions += buttons.enumerated().map { UNNotificationAction(identifier: "act:\($0.offset)", title: $0.element) }
            categories[id] = UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [], options: [.customDismissAction])
            center.setNotificationCategories(Set(categories.values))
        }
        return id
    }

    func remove(_ id: String) {
        center.removeDeliveredNotifications(withIdentifiers: ["n:" + id])
    }

    func info(_ title: String, _ body: String, revealing file: URL? = nil) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.categoryIdentifier = "file"
        if let file { c.userInfo = ["reveal": file.path] }
        center.add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    func callEnded(title: String, body: String, number: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.categoryIdentifier = "callend"
        c.userInfo = ["number": number]
        center.add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    // MARK: From the user

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let id = info["id"] as? String
        switch response.actionIdentifier {
        case let action where action.hasPrefix("sug:"):
            if let id, let index = Int(action.dropFirst(4)), let list = info["suggestions"] as? [String], list.indices.contains(index) {
                send(["t": "reply", "id": id, "text": list[index]])
            }
        case "textback":
            if let n = info["number"] as? String { onTextBack(n) }
        case "callback":
            if let n = info["number"] as? String { onCallBack(n) }
        case "reply":
            if let id, let text = (response as? UNTextInputNotificationResponse)?.userText, !text.isEmpty {
                send(["t": "reply", "id": id, "text": text])
            }
        case let action where action.hasPrefix("act:"):
            if let id, let index = Int(action.dropFirst(4)) { send(["t": "notif_action", "id": id, "index": index]) }
        case UNNotificationDismissActionIdentifier:
            if let id {
                send(["t": "dismiss", "id": id])
                onDismiss(id)
            }
        case UNNotificationDefaultActionIdentifier:
            if let path = info["reveal"] as? String {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            }
        default:
            break
        }
        done()
    }
}

/// Recent phone notifications, kept on disk so you can find ones you swiped away.
@MainActor
final class NotificationHistory: ObservableObject {
    struct Item: Identifiable, Codable {
        var id = UUID()
        var key: String?          // phone notification key (for reply/dismiss)
        var canReply = false
        var active = false        // still showing on the phone → appears in the sidebar feed
        let app: String
        let title: String
        let text: String
        let date: Date
    }

    @Published private(set) var items: [Item] = []
    @Published var search = ""
    private let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("PhoneBridge/history.json")

    /// History is encrypted at rest; the key lives in the Keychain.
    private static let key: SymmetricKey = {
        if let d = Keychain.get("historyKey") { return SymmetricKey(data: d) }
        let k = SymmetricKey(size: .bits256)
        Keychain.set(k.withUnsafeBytes { Data($0) }, "historyKey")
        return k
    }()

    init() {
        let raw = try? Data(contentsOf: file)
        // Decrypt; an older version stored plain JSON, which gets re-saved encrypted below.
        let data = raw.flatMap { try? AES.GCM.open(AES.GCM.SealedBox(combined: $0), using: Self.key) } ?? raw
        if let data, let saved = try? JSONDecoder().decode([Item].self, from: data) {
            // After a restart we don't know what's still on the phone; the feed refills as notifications arrive.
            items = saved.map { var i = $0; i.active = false; return i }
            save()
        }
    }

    var filtered: [Item] {
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return items }
        return items.filter { $0.app.localizedCaseInsensitiveContains(q) || $0.title.localizedCaseInsensitiveContains(q)
            || $0.text.localizedCaseInsensitiveContains(q) }
    }

    /// Notifications currently showing on the phone, newest first.
    var feed: [Item] { items.filter(\.active) }

    func add(_ m: [String: Any]) {
        let key = m["id"] as? String
        let item = Item(key: key, canReply: m["reply"] as? Bool ?? false, active: true,
                        app: m["app"] as? String ?? "", title: m["title"] as? String ?? "",
                        text: m["text"] as? String ?? "", date: Date())
        // Apps update one notification as a chat grows; keep only its newest version.
        if let key, let i = items.firstIndex(where: { $0.key == key }) {
            items.remove(at: i)
        }
        items.insert(item, at: 0)
        if items.count > 500 { items.removeLast(items.count - 500) }
        save()
    }

    func markRemoved(_ key: String) {
        for i in items.indices where items[i].key == key { items[i].active = false }
    }

    func clear() {
        items = []
        save()
    }

    private func save() {
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let json = try? JSONEncoder().encode(items),
              let sealed = try? AES.GCM.seal(json, using: Self.key).combined else { return }
        try? sealed.write(to: file, options: .atomic)
    }
}
