import AppKit
import LocalAuthentication
import SwiftUI

/// Full-screen "this Mac is locked" cover. Opens only with the authenticator code from the owner's phone (or the recovery code).
final class GuardWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

@MainActor
final class GuardLockModel: ObservableObject {
    @Published var input = ""
    @Published var message = ""
    @Published var testSecondsLeft: Int?
    /// A line under the instructions, e.g. "Looking for your phone…" while an automatic lock waits for it.
    @Published var hint = ""
    var verify: (String) -> Bool = { _ in false }
    var onUnlocked: () -> Void = {}
    @Published var touchIDAvailable = false
    /// Called around the system's Touch ID prompt, so the lock cover can step out of its way.
    var promptWillShow: () -> Void = {}
    var promptDidEnd: () -> Void = {}
    private var prompting = false
    private var failures = 0
    private var lockedOutUntil = Date.distantPast

    func reset() {
        input = ""
        message = ""
        failures = 0
        lockedOutUntil = .distantPast
    }

    /// Asks for the owner's fingerprint (biometrics only: no password fallback, since the code is the fallback here).
    func useTouchID() {
        guard !prompting else { return } // a second click would cancel the prompt that's already up
        let ctx = LAContext()
        ctx.localizedFallbackTitle = ""
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            touchIDAvailable = false
            message = "Touch ID isn't available — enter the code."
            return
        }
        prompting = true
        promptWillShow()
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "unlock this Mac") { ok, error in
            Task { @MainActor in
                self.prompting = false
                self.promptDidEnd()
                if ok {
                    self.reset()
                    self.onUnlocked()
                } else {
                    let e = error as NSError?
                    self.message = "Touch ID didn't work (\(e?.localizedDescription ?? "unknown"), code \(e?.code ?? 0)) — try again or enter the code."
                }
            }
        }
    }

    func submit() {
        let code = input.trimmingCharacters(in: .whitespaces)
        input = ""
        guard !code.isEmpty else {
            if touchIDAvailable { useTouchID() } // Return on an empty field asks for the fingerprint again
            return
        }
        let wait = Int(lockedOutUntil.timeIntervalSinceNow.rounded(.up))
        if wait > 0 { message = "Too many tries. Wait \(wait) s."; return }
        if verify(code) {
            reset()
            onUnlocked()
        } else {
            failures += 1
            if failures >= 5 {
                let seconds = min(300, 30 * (failures - 4))
                lockedOutUntil = Date().addingTimeInterval(Double(seconds))
                message = "Too many tries. Try again in \(seconds) s."
            } else {
                message = "That code didn't work."
            }
        }
    }
}

@MainActor
final class GuardLockController {
    let model = GuardLockModel()
    private(set) var showing = false
    private var windows: [GuardWindow] = []
    private var timer: Timer?
    private var observers: [NSObjectProtocol] = []

    func present(test: Bool) {
        guard !showing else { return }
        showing = true
        model.reset()
        model.testSecondsLeft = test ? 12 : nil
        model.promptWillShow = { [weak self] in self?.setPrompt(true) }
        model.promptDidEnd = { [weak self] in self?.setPrompt(false) }
        model.touchIDAvailable = LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        NSApp.setActivationPolicy(.regular)
        buildWindows()
        // Hides the Dock and menu bar, and disables Cmd-Tab, force quit and the logout/shutdown menu while this is up.
        NSApp.presentationOptions = [.hideDock, .hideMenuBar, .disableAppleMenu, .disableProcessSwitching,
                                     .disableForceQuit, .disableSessionTermination, .disableHideApplication]
        NSApp.activate(ignoringOtherApps: true)
        let nc = NotificationCenter.default
        observers = [
            nc.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.keepFront() }
            },
            nc.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.buildWindows() }
            },
            NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.keepFront() }
            },
        ]
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        // Offer the fingerprint straight away; the button asks again.
        if model.touchIDAvailable && !test {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                if self?.showing == true { self?.model.useTouchID() }
            }
        }
    }

    func dismiss() {
        guard showing else { return }
        showing = false
        timer?.invalidate()
        timer = nil
        observers.forEach { NotificationCenter.default.removeObserver($0); NSWorkspace.shared.notificationCenter.removeObserver($0) }
        observers = []
        NSApp.presentationOptions = []
        windows.forEach { $0.orderOut(nil) }
        windows = []
        model.reset()
        model.hint = ""
        model.testSecondsLeft = nil
    }

    private var testTicks = 0
    /// While the Touch ID prompt is up the cover drops below it and stops grabbing focus (grabbing it cancels the prompt).
    private var promptUp = false

    private func setPrompt(_ up: Bool) {
        promptUp = up
        for w in windows { w.level = up ? .floating : NSWindow.Level(rawValue: Int(CGShieldingWindowLevel())) }
        if !up { keepFront() }
    }

    private func tick() {
        guard showing else { return }
        keepFront()
        if let left = model.testSecondsLeft {
            testTicks += 1
            if testTicks % 2 == 0 { model.testSecondsLeft = left - 1 }
            if left <= 1 { testTicks = 0; dismiss() }
        }
    }

    private func keepFront() {
        guard showing, !promptUp else { return }
        if NSScreen.screens.count != windows.count { buildWindows() }
        if !NSApp.isActive { NSApp.activate(ignoringOtherApps: true) }
        for (i, w) in windows.enumerated() {
            if i == 0 { w.makeKeyAndOrderFront(nil) } else { w.orderFrontRegardless() }
        }
    }

    private func buildWindows() {
        windows.forEach { $0.orderOut(nil) }
        windows = NSScreen.screens.enumerated().map { i, screen in
            let w = GuardWindow(contentRect: screen.frame, styleMask: .borderless, backing: .buffered, defer: false, screen: screen)
            w.level = NSWindow.Level(rawValue: Int(CGShieldingWindowLevel()))
            w.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenAuxiliary]
            w.isOpaque = true
            w.backgroundColor = .black
            w.hasShadow = false
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: i == 0 ? AnyView(GuardLockView(model: model)) : AnyView(Color.black))
            w.setFrame(screen.frame, display: true)
            w.orderFrontRegardless()
            return w
        }
        windows.first?.makeKeyAndOrderFront(nil)
    }
}

/// A password-style field that takes focus at once and reports Return.
struct LockField: NSViewRepresentable {
    @Binding var text: String
    var onSubmit: () -> Void

    func makeNSView(context: Context) -> NSSecureTextField {
        let f = NSSecureTextField()
        f.isBordered = false
        f.drawsBackground = false
        f.focusRingType = .none
        f.alignment = .center
        f.font = .monospacedSystemFont(ofSize: 26, weight: .semibold)
        f.textColor = .white
        f.placeholderAttributedString = NSAttributedString(string: "Code", attributes: [.foregroundColor: NSColor.white.withAlphaComponent(0.35),
                                                                                         .font: NSFont.systemFont(ofSize: 22)])
        f.delegate = context.coordinator
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { f.window?.makeFirstResponder(f) }
        return f
    }

    func updateNSView(_ f: NSSecureTextField, context: Context) {
        if f.stringValue != text { f.stringValue = text }
        context.coordinator.parent = self
        if f.window?.firstResponder == nil { f.window?.makeFirstResponder(f) }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, NSTextFieldDelegate {
        var parent: LockField
        init(_ p: LockField) { parent = p }
        func controlTextDidChange(_ n: Notification) { if let f = n.object as? NSTextField { parent.text = f.stringValue } }
        func control(_ control: NSControl, textView: NSTextView, doCommandBy sel: Selector) -> Bool {
            if sel == #selector(NSResponder.insertNewline(_:)) { parent.onSubmit(); return true }
            return false
        }
    }
}

struct GuardLockView: View {
    @ObservedObject var model: GuardLockModel

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.05, green: 0.04, blue: 0.13), Color(red: 0.14, green: 0.06, blue: 0.22)], startPoint: .top, endPoint: .bottom)
            VStack(spacing: 18) {
                Image(systemName: "lock.shield.fill").font(.system(size: 70)).foregroundStyle(Theme.gradient)
                Text("This Mac is locked").font(.system(size: 36, weight: .bold, design: .rounded)).foregroundStyle(.white)
                Text("Use Touch ID, or enter the unlock code from the PhoneBridge app on the owner's phone.")
                    .font(.system(size: 15)).foregroundStyle(.white.opacity(0.65)).multilineTextAlignment(.center).frame(maxWidth: 420)
                LockField(text: $model.input) { model.submit() }
                    .frame(width: 300, height: 48)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.1)))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.white.opacity(0.15)))
                Text(model.message.isEmpty ? " " : model.message).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.red)
                if !model.hint.isEmpty {
                    Text(model.hint).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.accent2)
                }
                Text(model.touchIDAvailable ? "Press Return to use Touch ID again. The saved recovery code works too." : "The saved recovery code works too.").font(.system(size: 12)).foregroundStyle(.white.opacity(0.4))
                if let left = model.testSecondsLeft {
                    Text("Test mode — this screen closes by itself in \(max(left, 0)) s")
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(.orange).padding(.top, 8)
                }
            }
        }
    }
}
