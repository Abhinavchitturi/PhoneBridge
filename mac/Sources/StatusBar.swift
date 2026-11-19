import AppKit
import Combine
import SwiftUI

/// Menu bar icon: click for the menu, drop files on it to send them to the phone.
@MainActor
final class StatusBar {
    private var item: NSStatusItem?
    private var watch: AnyCancellable?

    func start(bridge: Bridge) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.menu = NSHostingMenu(rootView: MenuContent(bridge: bridge, streamer: bridge.streamer))
        if let button = item.button {
            let drop = DropView(frame: button.bounds)
            drop.autoresizingMask = [.width, .height]
            drop.onDrop = { urls in bridge.sendFiles(urls) }
            button.addSubview(drop)
        }
        // Follows the chosen theme: its logo, dimmed while the phone isn't connected.
        watch = Publishers.CombineLatest(bridge.$connected, ThemeStore.shared.$current).sink { [weak item, weak self] up, theme in
            item?.button?.image = self?.icon(for: theme, connected: up)
        }
        self.item = item
    }

    /// Midnight is a plain template image (black or white to match the menu bar); the character themes keep their colours.
    private func icon(for theme: AppTheme, connected: Bool) -> NSImage? {
        let template = theme.motif == .arch
        let glyph = ThemeGlyph(theme: theme, color: template ? Color.black : nil)
            .frame(width: 20, height: 20)
            .opacity(connected ? 1 : 0.4)
        let renderer = ImageRenderer(content: glyph)
        renderer.scale = NSScreen.main?.backingScaleFactor ?? 2
        let image = renderer.nsImage
        image?.isTemplate = template
        image?.accessibilityDescription = "PhoneBridge"
        return image
    }
}

/// Transparent drop target over the status item button. Clicks fall through to the button
/// (NSView passes mouseDown up the responder chain), so the menu still opens normally.
final class DropView: NSView {
    var onDrop: ([URL]) -> Void = { _ in }

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError() }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        layer?.backgroundColor = NSColor.controlAccentColor.withAlphaComponent(0.35).cgColor
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        layer?.backgroundColor = nil
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        layer?.backgroundColor = nil
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                         options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        guard !urls.isEmpty else { return false }
        onDrop(urls)
        return true
    }

    override func viewDidMoveToWindow() {
        wantsLayer = true
        layer?.cornerRadius = 4
    }
}
