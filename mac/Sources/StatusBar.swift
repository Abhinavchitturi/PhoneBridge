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
        watch = bridge.$connected.sink { [weak item] up in
            let image = NSImage(systemSymbolName: up ? "iphone.radiowaves.left.and.right" : "iphone.slash",
                                accessibilityDescription: "PhoneBridge")
            image?.isTemplate = true
            item?.button?.image = image
        }
        self.item = item
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
