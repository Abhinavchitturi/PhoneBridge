import AppKit
import AVFoundation
import CoreAudio

/// New Mac screenshots → phone (Spotlight tags screenshots with kMDItemIsScreenCapture).
@MainActor
final class ScreenshotWatcher: ObservableObject {
    @Published var enabled = UserDefaults.standard.object(forKey: "sendMacScreenshots") as? Bool ?? true {
        didSet { UserDefaults.standard.set(enabled, forKey: "sendMacScreenshots") }
    }
    var onScreenshot: (URL) -> Void = { _ in }
    private let query = NSMetadataQuery()
    private var started = Date()

    func start() {
        started = Date()
        query.predicate = NSPredicate(format: "kMDItemIsScreenCapture == 1")
        query.searchScopes = [NSMetadataQueryUserHomeScope]
        NotificationCenter.default.addObserver(forName: .NSMetadataQueryDidUpdate, object: query, queue: .main) { [weak self] note in
            let added = note.userInfo?[NSMetadataQueryUpdateAddedItemsKey] as? [NSMetadataItem] ?? []
            let paths = added.compactMap { $0.value(forAttribute: NSMetadataItemPathKey) as? String }
            MainActor.assumeIsolated {
                guard let self, self.enabled else { return }
                for path in paths {
                    let url = URL(fileURLWithPath: path)
                    let created = (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
                    if created > self.started { self.onScreenshot(url) }
                }
            }
        }
        query.start()
    }
}

/// Pastes an image at the Mac's cursor (Scan to Mac): put it on the clipboard, then ⌘V.
@MainActor
enum Paster {
    static func pasteImage(_ url: URL, clipboard: ClipboardSync) -> Bool {
        guard let data = try? Data(contentsOf: url) else { return false }
        let png = NSBitmapImageRep(data: NSImage(data: data)?.tiffRepresentation ?? Data())?
            .representation(using: .png, properties: [:]) ?? data
        clipboard.setImageFromPhone(png)
        guard InputControl.ensureAccessibility(prompt: false) else { return false }
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: nil, virtualKey: 9, keyDown: down) // V
            e?.flags = .maskCommand
            e?.post(tap: .cghidEventTap)
        }
        return true
    }
}

/// Plays the phone's microphone into the BlackHole virtual audio device, which Zoom/Meet can pick as a mic.
@MainActor
final class PhoneMic: ObservableObject {
    @Published private(set) var active = false
    @Published private(set) var status = "Off"

    private var engine: AVAudioEngine?
    private let buffer = SampleBuffer()

    static func blackHoleDevice() -> AudioDeviceID? {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size)
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids)
        return ids.first { id in
            var nameAddr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName, mScope: kAudioObjectPropertyScopeGlobal,
                                                      mElement: kAudioObjectPropertyElementMain)
            var name: Unmanaged<CFString>?
            var n = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
            AudioObjectGetPropertyData(id, &nameAddr, 0, nil, &n, &name)
            return (name?.takeRetainedValue() as String?)?.contains("BlackHole") == true
        }
    }

    func handleState(_ on: Bool) {
        on ? start() : stop()
    }

    func handleAudio(_ b64: String) {
        guard active, let data = Data(base64Encoded: b64) else { return }
        let samples = data.withUnsafeBytes { raw in raw.bindMemory(to: Int16.self).map { Float($0) / 32768 } }
        buffer.append(samples)
    }

    private func start() {
        guard engine == nil else { return }
        guard var device = Self.blackHoleDevice() else {
            status = "Install BlackHole first (see below)"
            return
        }
        let engine = AVAudioEngine()
        let format = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
        let buffer = self.buffer
        let source = AVAudioSourceNode(format: format) { _, _, frameCount, audioBufferList in
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let out = abl[0].mData!.assumingMemoryBound(to: Float.self)
            buffer.read(into: out, count: Int(frameCount))
            return noErr
        }
        engine.attach(source)
        engine.outputNode.withAudioUnit { unit in
            guard let unit else { return }
            AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                 &device, UInt32(MemoryLayout<AudioDeviceID>.size))
        }
        do {
            try engine.connectNode(source, to: engine.mainMixerNode, format: format)
            try engine.start()
            self.engine = engine
            active = true
            status = "Live — choose “BlackHole 2ch” as the microphone in Zoom/Meet"
        } catch {
            status = "Couldn't start audio: \(error.localizedDescription)"
        }
    }

    private func stop() {
        engine?.stop()
        engine = nil
        active = false
        buffer.clear()
        status = "Off"
    }
}

/// Small thread-safe FIFO between the network and the audio thread; keeps latency under ~0.3 s.
final class SampleBuffer: @unchecked Sendable {
    private var samples: [Float] = []
    private let lock = NSLock()

    func append(_ s: [Float]) {
        lock.lock()
        samples += s
        if samples.count > 4_800 { samples.removeFirst(samples.count - 4_800) }
        lock.unlock()
    }

    func read(into out: UnsafeMutablePointer<Float>, count: Int) {
        lock.lock()
        let n = min(count, samples.count)
        for i in 0..<n { out[i] = samples[i] }
        for i in n..<count { out[i] = 0 }
        samples.removeFirst(n)
        lock.unlock()
    }

    func clear() {
        lock.lock()
        samples.removeAll()
        lock.unlock()
    }
}
