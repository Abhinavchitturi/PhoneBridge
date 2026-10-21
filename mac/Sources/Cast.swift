import AppKit
import CoreMedia
import VideoToolbox

/// Decodes the phone's H.264 stream (Annex B, from Android's hardware encoder) with VideoToolbox.
/// Runs on the link's queue; frames are handed to `onFrame`.
final class H264Decoder: @unchecked Sendable {
    var onFrame: (CVPixelBuffer) -> Void = { _ in }
    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var sps: Data?
    private var pps: Data?
    // Diagnostics shown in the window while waiting for the first picture.
    private(set) var received = 0
    private(set) var decoded = 0
    private(set) var lastError: OSStatus = noErr
    var hasHeader: Bool { session != nil }

    func reset() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        format = nil
        currentParams = nil
        received = 0
        decoded = 0
        lastError = noErr
    }

    func decode(_ annexB: Data) {
        received += 1
        var frameNALs: [Data] = []
        for nal in Self.split(annexB) {
            guard let first = nal.first else { continue }
            switch first & 0x1F {
            case 7: sps = nal
            case 8: pps = nal
            default: frameNALs.append(nal)
            }
        }
        if let sps, let pps, needsNewFormat(sps, pps) { makeSession(sps: sps, pps: pps) }
        guard !frameNALs.isEmpty, let session, let format else { return }

        // AVCC: each NAL prefixed by its 4-byte big-endian length.
        var avcc = Data()
        for nal in frameNALs {
            withUnsafeBytes(of: UInt32(nal.count).bigEndian) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }
        var block: CMBlockBuffer?
        let length = avcc.count
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: length, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: length, flags: 0,
                                                 blockBufferOut: &block) == noErr, let block else { return }
        avcc.withUnsafeBytes { raw in
            _ = CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: length)
        }
        var sample: CMSampleBuffer?
        var size = length
        guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { return }
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], infoFlagsOut: nil) { [weak self] status, _, image, _, _ in
            guard status == noErr, let image else { self?.lastError = status; return }
            self?.decoded += 1
            self?.onFrame(image)
        }
        if status != noErr { lastError = status }
    }

    private var currentParams: (Data, Data)?

    private func needsNewFormat(_ sps: Data, _ pps: Data) -> Bool {
        guard let current = currentParams else { return true }
        return current.0 != sps || current.1 != pps
    }

    private func makeSession(sps: Data, pps: Data) {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        currentParams = (sps, pps)
        var fd: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { s in
            pps.withUnsafeBytes { p in
                let pointers = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: nil, parameterSetCount: 2,
                                                                           parameterSetPointers: pointers, parameterSetSizes: sizes,
                                                                           nalUnitHeaderLength: 4, formatDescriptionOut: &fd)
            }
        }
        guard status == noErr, let fd else { return }
        format = fd
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as [String: Any],
        ]
        var s: VTDecompressionSession?
        VTDecompressionSessionCreate(allocator: nil, formatDescription: fd, decoderSpecification: nil,
                                     imageBufferAttributes: attrs as CFDictionary, outputCallback: nil, decompressionSessionOut: &s)
        session = s
    }

    /// Splits an Annex B byte stream on 00 00 01 / 00 00 00 01 start codes.
    static func split(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var nals: [Data] = []
        var start: Int?
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                if let s = start {
                    var end = i
                    if end > s, bytes[end - 1] == 0 { end -= 1 } // 4-byte start code
                    nals.append(Data(bytes[s..<end]))
                }
                i += 3
                start = i
            } else {
                i += 1
            }
        }
        if let s = start, s < bytes.count { nals.append(Data(bytes[s...])) }
        return nals
    }
}

/// The window showing the phone. Click = tap, drag = swipe, hold = long-press, scroll = swipe,
/// typing goes into the focused text box, Esc = Back.
@MainActor
final class CastWindow: NSObject, NSWindowDelegate {
    var send: ([String: Any]) -> Void = { _ in }
    var notify: (String, String) -> Void = { _, _ in }
    let decoder = H264Decoder()
    private var window: NSWindow?
    private var view: CastView?
    private var waiting: NSTextField?
    private var videoSize = CGSize(width: 9, height: 19.5)
    private var statusTimer: Timer?

    override init() {
        super.init()
        decoder.onFrame = { [weak self] buffer in
            DispatchQueue.main.async { self?.view?.show(buffer) }
        }
    }

    /// Ask the phone to start casting, optionally opening an app.
    func open(app: String?, title: String) {
        if window == nil { makeWindow() }
        window?.title = title
        waiting?.isHidden = view?.hasFrame == true
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        var msg: [String: Any] = ["t": "cast_start"]
        if let app { msg["app"] = app }
        send(msg)
    }

    private var phoneDiag = ""

    /// What the phone's encoder is doing (shown while waiting for the first picture).
    func handleDiag(_ m: [String: Any]) {
        let frames = m["frames"] as? Int ?? 0
        var text = "Phone: encoded \(frames) frames · sent \(m["sent"] as? Int ?? 0) · acked \(m["acked"] as? Int ?? 0)"
        if let r = m["restarts"] as? Int, r > 1 { text += " · encoder restarts \(r)" }
        if let p = m["pokes"] as? Int, p > 0 { text += " · nudged \(p)×" }
        if let e = m["err"] as? String, !e.isEmpty { text += "\n\(e)" }
        phoneDiag = text
    }

    func handleState(_ m: [String: Any]) {
        if m["on"] as? Bool == true {
            if let w = m["w"] as? Int, let h = m["h"] as? Int, w > 0, h > 0 {
                videoSize = CGSize(width: w, height: h)
                view?.videoSize = videoSize
                fitWindow()
            }
            if window == nil { makeWindow() }
        } else {
            if let error = m["error"] as? String { notify("Phone screen not shown", error) }
            close(tellPhone: false)
        }
    }

    private func makeWindow() {
        let v = CastView(frame: NSRect(x: 0, y: 0, width: 380, height: 820))
        v.videoSize = videoSize
        v.send = { [weak self] in self?.send($0) }
        let label = NSTextField(labelWithString: "Waiting for your phone…\nTap “Start” on the phone (choose one app or the entire screen).")
        label.alignment = .center
        label.textColor = .secondaryLabelColor
        label.translatesAutoresizingMaskIntoConstraints = false
        v.addSubview(label)
        NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: v.centerXAnchor),
                                     label.centerYAnchor.constraint(equalTo: v.centerYAnchor),
                                     label.widthAnchor.constraint(lessThanOrEqualTo: v.widthAnchor, constant: -30)])

        let bar = NSStackView(views: [
            navButton("chevron.backward", "Back") { [weak self] in self?.nav("back") },
            navButton("circle", "Home") { [weak self] in self?.nav("home") },
            navButton("square.on.square", "Recent apps") { [weak self] in self?.nav("recents") },
        ])
        bar.distribution = .fillEqually
        bar.edgeInsets = NSEdgeInsets(top: 4, left: 30, bottom: 6, right: 30)
        let stack = NSStackView(views: [v, bar])
        stack.orientation = .vertical
        stack.spacing = 0
        v.setContentHuggingPriority(.defaultLow, for: .vertical)

        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 380, height: 860),
                         styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        w.contentView = stack
        w.isReleasedWhenClosed = false
        w.delegate = self
        w.center()
        w.makeFirstResponder(v)
        window = w
        view = v
        waiting = label
        v.onFirstFrame = { [weak label] in label?.isHidden = true }
        fitWindow()
        // While no picture has arrived, show what the video pipeline is doing.
        statusTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.updateStatus() }
        }
    }

    private func updateStatus() {
        guard let view, !view.hasFrame, let waiting else { return }
        let d = decoder
        var text = "Waiting for your phone…\nTap “Start now” on the phone."
        if d.received > 0 {
            text += "\n\nVideo packets: \(d.received) · decoded: \(d.decoded)"
            if !d.hasHeader { text += "\nNo stream header yet" }
            if d.lastError != noErr { text += "\nDecoder error \(d.lastError)" }
        }
        if !phoneDiag.isEmpty { text += "\n\n" + phoneDiag }
        waiting.stringValue = text
    }

    private func navButton(_ symbol: String, _ tip: String, action: @escaping () -> Void) -> NSButton {
        let b = ClosureButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: tip)!, action: action)
        b.bezelStyle = .texturedRounded
        b.toolTip = tip
        return b
    }

    private func nav(_ action: String) { send(["t": "cast_input", "kind": "nav", "action": action]) }

    /// Size the window to the phone's shape, ~85% of the screen's height.
    private func fitWindow() {
        guard let window, let screen = window.screen ?? NSScreen.main else { return }
        let maxW = screen.visibleFrame.width * 0.9
        let maxH = screen.visibleFrame.height * 0.85
        let scale = min(maxW / videoSize.width, maxH / videoSize.height)
        window.setContentSize(NSSize(width: videoSize.width * scale, height: videoSize.height * scale + 36))
    }

    func windowWillClose(_ notification: Notification) {
        close(tellPhone: true)
    }

    private func close(tellPhone: Bool) {
        if tellPhone { send(["t": "cast_stop"]) }
        decoder.reset()
        view?.clear()
        if window?.isVisible == true {
            window?.delegate = nil
            window?.close()
            window?.delegate = self
        }
    }
}

final class ClosureButton: NSButton {
    private var handler: () -> Void = {}
    convenience init(image: NSImage, action: @escaping () -> Void) {
        self.init(frame: .zero)
        self.image = image
        handler = action
        target = self
        self.action = #selector(fire)
    }
    @objc private func fire() { handler() }
}

/// Draws decoded frames (IOSurface as layer contents) and turns mouse/keyboard into phone input.
final class CastView: NSView {
    var send: ([String: Any]) -> Void = { _ in }
    var onFirstFrame: () -> Void = {}
    var videoSize = CGSize(width: 9, height: 19.5)
    private(set) var hasFrame = false
    private var current: CVPixelBuffer?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.contentsGravity = .resizeAspect
    }

    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }

    func show(_ buffer: CVPixelBuffer) {
        current = buffer // keep it alive while displayed
        guard let surface = CVPixelBufferGetIOSurface(buffer)?.takeUnretainedValue() else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer?.contents = surface
        CATransaction.commit()
        if !hasFrame {
            hasFrame = true
            onFirstFrame()
        }
    }

    func clear() {
        layer?.contents = nil
        current = nil
        hasFrame = false
    }

    /// Where the video actually sits inside the view (aspect fit), and a view point → 0…1 on the phone.
    private var videoRect: CGRect {
        let b = bounds
        let scale = min(b.width / videoSize.width, b.height / videoSize.height)
        let size = CGSize(width: videoSize.width * scale, height: videoSize.height * scale)
        return CGRect(x: b.midX - size.width / 2, y: b.midY - size.height / 2, width: size.width, height: size.height)
    }

    private func normalized(_ event: NSEvent) -> CGPoint {
        let p = convert(event.locationInWindow, from: nil)
        let r = videoRect
        return CGPoint(x: (p.x - r.minX) / r.width, y: 1 - (p.y - r.minY) / r.height)
    }

    // Live touch: mouse down = finger down, drag = finger moves (sent ~40×/s), up = finger lifts.
    // Taps, long-presses, drags and flings all come out of that naturally on the phone.
    private var lastMoveSent = Date.distantPast
    private var pendingMove: CGPoint?

    private func touch(_ phase: String, _ p: CGPoint) {
        send(["t": "cast_input", "kind": phase, "x": min(max(p.x, 0), 1), "y": min(max(p.y, 0), 1)])
    }

    private func moveThrottled(_ p: CGPoint) {
        if Date().timeIntervalSince(lastMoveSent) >= 0.025 {
            lastMoveSent = Date()
            pendingMove = nil
            touch("move", p)
        } else {
            pendingMove = p
        }
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        endScroll()
        touch("down", normalized(event))
    }

    override func mouseDragged(with event: NSEvent) {
        moveThrottled(normalized(event))
    }

    override func mouseUp(with event: NSEvent) {
        if let p = pendingMove { touch("move", p) }
        pendingMove = nil
        touch("up", normalized(event))
    }

    override func rightMouseDown(with event: NSEvent) {
        send(["t": "cast_input", "kind": "nav", "action": "back"])
    }

    // Trackpad/mouse scrolling becomes a live finger drag from the pointer, so the page follows your fingers.
    private var scrollPoint: CGPoint?
    private var scrollEnd: Timer?

    override func scrollWheel(with event: NSEvent) {
        let r = videoRect
        let dx = event.scrollingDeltaX / r.width * (event.hasPreciseScrollingDeltas ? 1.0 : 12)
        let dy = event.scrollingDeltaY / r.height * (event.hasPreciseScrollingDeltas ? 1.0 : 12)
        if scrollPoint == nil {
            let start = normalized(event)
            scrollPoint = start
            touch("down", start)
        }
        guard var p = scrollPoint else { return }
        p.x += dx
        p.y += dy // natural scrolling: the "finger" moves the way your fingers do, so content follows them
        // Ran out of screen: lift and start again from the pointer.
        if p.y < 0.03 || p.y > 0.97 || p.x < 0.03 || p.x > 0.97 {
            endScroll()
            return
        }
        scrollPoint = p
        moveThrottled(p)
        scrollEnd?.invalidate()
        scrollEnd = Timer.scheduledTimer(withTimeInterval: 0.12, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.endScroll() }
        }
    }

    private func endScroll() {
        scrollEnd?.invalidate()
        scrollEnd = nil
        guard let p = scrollPoint else { return }
        scrollPoint = nil
        if let pending = pendingMove { touch("move", pending) }
        pendingMove = nil
        touch("up", p)
    }

    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 51: send(["t": "cast_input", "kind": "backspace"])
        case 36, 76: send(["t": "cast_input", "kind": "enter"])
        case 53: send(["t": "cast_input", "kind": "nav", "action": "back"])
        default:
            if let chars = event.characters, !chars.isEmpty, !event.modifierFlags.contains(.command) {
                send(["t": "cast_input", "kind": "text", "text": chars])
            } else {
                super.keyDown(with: event)
            }
        }
    }
}
