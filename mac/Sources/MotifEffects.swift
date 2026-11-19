import SwiftUI
import AppKit

private func hash(_ x: Double) -> Double {
    let v = sin(x * 12.9898) * 43758.5453
    return v - floor(v)
}

enum FxKind { case tap, transition, connect, open }

struct FxEvent: Identifiable {
    let id = UUID()
    let kind: FxKind
    let start: Date
    let origin: CGPoint      // .zero means "the middle of the window"
    let space: String
    let duration: Double
}

/// Small themed animations that play on top of a window: a puff where you press, a flourish when the tab changes,
/// and a celebration when the phone connects.
@MainActor
final class FxCenter: ObservableObject {
    static let shared = FxCenter()
    @Published private(set) var events: [FxEvent] = []

    func fire(_ kind: FxKind, at p: CGPoint = .zero, space: String) {
        let d: Double = kind == .tap ? 0.55 : kind == .transition ? 0.6 : kind == .connect ? 3.8 : 3.6
        if kind == .open && !Bridge.shared.connected { return }   // no landing animation while searching
        // a connection right after launch replaces the opening sequence instead of stacking on it
        if kind == .connect { events.removeAll { $0.kind == .open && $0.space == space } }
        let e = FxEvent(kind: kind, start: Date(), origin: p, space: space, duration: d)
        events.append(e)
        DispatchQueue.main.asyncAfter(deadline: .now() + d + 0.1) { [weak self] in self?.events.removeAll { $0.id == e.id } }
    }

    private var opened: Set<String> = []
    /// Plays the theme's opening sequence the first time a window of this kind appears after launch.
    func fireOpenOnce(space: String) {
        guard !opened.contains(space) else { return }
        opened.insert(space)
        fire(.open, space: space)
    }

    func haptic(_ pattern: NSHapticFeedbackManager.FeedbackPattern = .alignment) {
        NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .now)
    }
}

private struct FxSpaceKey: EnvironmentKey { static let defaultValue = "main" }
extension EnvironmentValues {
    var fxSpace: String {
        get { self[FxSpaceKey.self] }
        set { self[FxSpaceKey.self] = newValue }
    }
}

struct FxOverlay: View {
    let space: String
    @ObservedObject private var center = FxCenter.shared
    @ObservedObject private var themes = ThemeStore.shared

    var body: some View {
        let mine = center.events.filter { $0.space == space }
        if !mine.isEmpty {
            TimelineView(.animation) { tl in
                Canvas { ctx, size in
                    for e in mine {
                        let p = tl.date.timeIntervalSince(e.start) / e.duration
                        guard p >= 0 && p < 1 else { continue }
                        let o = e.origin == .zero ? CGPoint(x: size.width / 2, y: size.height / 2) : e.origin
                        MotifArt.fx(ctx, size: size, theme: themes.current, kind: e.kind, p: p, origin: o)
                    }
                }
            }
            .allowsHitTesting(false)
        }
    }
}

/// Press feel: the control dips and springs back, you feel a tick on the trackpad, and the theme puffs out a little effect.
struct PressFx: ViewModifier {
    @Environment(\.fxSpace) private var space
    @StateObject private var pressed = HoverFlag()

    func body(content: Content) -> some View {
        content
            .scaleEffect(pressed.on ? 0.94 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.5), value: pressed.on)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .named("fx-\(space)"))
                    .onChanged { _ in
                        if !pressed.on { pressed.on = true; FxCenter.shared.haptic() }
                    }
                    .onEnded { v in
                        pressed.on = false
                        FxCenter.shared.fire(.tap, at: v.location, space: space)
                    }
            )
    }
}

extension View {
    func pressFx() -> some View { modifier(PressFx()) }
}

extension MotifArt {
    static func fx(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, kind: FxKind, p: Double, origin o: CGPoint) {
        switch kind {
        case .tap: tapFx(ctx, theme: t, p: p, o: o)
        case .transition: transitionFx(ctx, size: size, theme: t, p: p)
        case .connect: connectFx(ctx, size: size, theme: t, p: p, o: o)
        case .open: sequence(ctx, size: size, theme: t, p: p)
        }
    }

    // MARK: A small puff where you pressed

    private static func tapFx(_ ctx: GraphicsContext, theme t: AppTheme, p: Double, o: CGPoint) {
        let e = CGFloat(1 - pow(1 - p, 3))
        let fade = 1 - p
        switch t.motif {
        case .arch:
            for k in 0..<2 {
                let pp = max(0, p - Double(k) * 0.15)
                let r = 8 + 36 * CGFloat(1 - pow(1 - pp, 3))
                ctx.stroke(Path(ellipseIn: CGRect(x: o.x - r, y: o.y - r, width: r * 2, height: r * 2)), with: .color(.white.opacity(0.5 * (1 - pp))), lineWidth: 1.4)
            }
        case .bat:
            for i in 0..<4 {
                let a = -Double.pi / 2 + (Double(i) - 1.5) * 0.65
                let d = 8 + 48 * e
                let pos = CGPoint(x: o.x + CGFloat(cos(a)) * d, y: o.y + CGFloat(sin(a)) * d)
                pictureT(ctx, "bat", center: pos, fit: 18, opacity: fade, scaleY: CGFloat(0.5 + 0.5 * abs(sin(p * 30 + Double(i)))))
            }
        case .spider:
            var web = Path()
            for k in 0..<8 {
                let a = Double(k) * .pi / 4
                web.move(to: CGPoint(x: o.x + CGFloat(cos(a)) * 6, y: o.y + CGFloat(sin(a)) * 6))
                web.addLine(to: CGPoint(x: o.x + CGFloat(cos(a)) * (10 + 38 * e), y: o.y + CGFloat(sin(a)) * (10 + 38 * e)))
            }
            for ring in 1...2 { web.addEllipse(in: CGRect(x: o.x - 14 * CGFloat(ring) * e, y: o.y - 14 * CGFloat(ring) * e, width: 28 * CGFloat(ring) * e, height: 28 * CGFloat(ring) * e)) }
            ctx.stroke(web, with: .color(t.accent2.opacity(0.9 * fade)), lineWidth: 1)
        case .reactor:
            let r = 8 + 40 * e
            ctx.stroke(Path(ellipseIn: CGRect(x: o.x - r, y: o.y - r, width: r * 2, height: r * 2)), with: .color(t.accent.opacity(0.9 * fade)), lineWidth: 2.2 * CGFloat(fade) + 0.5)
            var sparks = Path()
            for k in 0..<8 {
                let a = Double(k) * .pi / 4 + 0.2
                let r0 = 10 + 34 * e
                sparks.move(to: CGPoint(x: o.x + CGFloat(cos(a)) * r0, y: o.y + CGFloat(sin(a)) * r0))
                sparks.addLine(to: CGPoint(x: o.x + CGFloat(cos(a)) * (r0 + 9), y: o.y + CGFloat(sin(a)) * (r0 + 9)))
            }
            ctx.stroke(sparks, with: .color(Color.white.opacity(fade)), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
        case .thor:
            if p < 0.7 {
                for k in 0..<3 {
                    let a = Double(k) * 2.1 + hash(floor(p * 10)) * 1.5
                    let to = CGPoint(x: o.x + CGFloat(cos(a)) * 42, y: o.y + CGFloat(sin(a)) * 42)
                    let bolt = lightning(from: o, to: to, seed: Double(k) * 4 + floor(p * 10), segments: 5, jitter: 7)
                    ctx.stroke(bolt, with: .color(Color.white.opacity(0.95 * (1 - p / 0.7))), lineWidth: 1.4)
                    ctx.stroke(bolt, with: .color(t.accent.opacity(0.6 * (1 - p / 0.7))), lineWidth: 4)
                }
            }
        case .matrix:
            let chars = Array("01アウ7Z3")
            for k in 0..<6 {
                let ch = String(chars[Int(hash(Double(k) + floor(p * 8)) * Double(chars.count)) % chars.count])
                let x = o.x + (CGFloat(k) - 2.5) * 9
                ctx.draw(Text(ch).font(.system(size: 12, design: .monospaced)).foregroundStyle(t.accent.opacity(fade)), at: CGPoint(x: x, y: o.y - 6 - 40 * e * CGFloat(0.6 + 0.4 * hash(Double(k)))))
            }
        }
    }

    // MARK: A flourish across the window when you change tab

    private static func transitionFx(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, p: Double) {
        let w = size.width, h = size.height
        let fade = 1 - p
        switch t.motif {
        case .arch:
            // the logo draws itself: the bridge arch sweeps across, its two end dots pop in, a spark runs along it, then it fades
            let c0 = CGPoint(x: w / 2, y: h / 2)
            let r = min(w, h) * 0.17
            let vis = min(1, p / 0.1, (1 - p) / 0.35)
            var arch = Path()
            arch.move(to: CGPoint(x: c0.x - r, y: c0.y + r * 0.35))
            arch.addQuadCurve(to: CGPoint(x: c0.x + r, y: c0.y + r * 0.35), control: CGPoint(x: c0.x, y: c0.y - r * 1.15))
            let draw = CGFloat(min(1, p / 0.55))
            ctx.stroke(arch.trimmedPath(from: 0, to: draw), with: .color(Color.white.opacity(0.18 * vis)), lineWidth: 9)
            ctx.stroke(arch.trimmedPath(from: 0, to: draw), with: .color(Color.white.opacity(0.95 * vis)), lineWidth: 2.4)
            for (i, x) in [c0.x - r, c0.x + r].enumerated() {
                let pop = CGFloat(min(1, max(0, (p - (i == 0 ? 0.0 : 0.5)) / 0.18)))
                let dr = 5.5 * (0.4 + 0.6 * pop) + (i == 1 ? 0 : 0)
                let pt = CGPoint(x: x, y: c0.y + r * 0.35)
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - dr * 2, y: pt.y - dr * 2, width: dr * 4, height: dr * 4)), with: .color(Color.white.opacity(0.12 * vis * pop)))
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - dr, y: pt.y - dr, width: dr * 2, height: dr * 2)), with: .color(Color.white.opacity(vis * pop)))
            }
            // a spark runs along the arch
            let tt = CGFloat(min(1, p / 0.7)), u = 1 - tt
            let sp = CGPoint(x: u * u * (c0.x - r) + 2 * u * tt * c0.x + tt * tt * (c0.x + r),
                             y: u * u * (c0.y + r * 0.35) + 2 * u * tt * (c0.y - r * 1.15) + tt * tt * (c0.y + r * 0.35))
            glowDisc(ctx, at: sp, radius: 20, color: .white, alpha: 0.7 * vis)
        case .bat:
            // the bat emblem flares up like the signal in the sky: it swells in a golden oval of light that pulses outwards
            let c0 = CGPoint(x: w / 2, y: h / 2)
            let vis = min(1, p / 0.1, (1 - p) / 0.4)
            let swell = CGFloat(0.55 + 0.6 * min(1, p / 0.4) - 0.1 * max(0, (p - 0.4) / 0.6))
            glowDisc(ctx, at: c0, radius: 120 * swell, color: t.accent, alpha: 0.45 * vis)
            for k in 0..<2 {
                let pp = max(0, min(1, (p - Double(k) * 0.15) / (1 - Double(k) * 0.15)))
                let rw = 50 + CGFloat(1 - pow(1 - pp, 3)) * min(w, h) * 0.4
                ctx.stroke(Path(ellipseIn: CGRect(x: c0.x - rw, y: c0.y - rw * 0.62, width: rw * 2, height: rw * 1.24)), with: .color(t.accent.opacity(0.75 * (1 - pp))), lineWidth: 2)
            }
            pictureT(ctx, "batoval", center: c0, fit: 110 * swell, opacity: vis)
        case .spider:
            // a small thing: the spider mask pops at the centre while two thin web rings spread out from it
            let c0 = CGPoint(x: w / 2, y: h / 2)
            let pop = CGFloat(0.55 + 0.6 * min(1, p / 0.35) - 0.15 * max(0, (p - 0.35) / 0.65))
            let vis = min(1, p / 0.12, (1 - p) / 0.45)
            for k in 0..<2 {
                let pp = max(0, min(1, (p - Double(k) * 0.14) / (1 - Double(k) * 0.14)))
                let r = 30 + CGFloat(1 - pow(1 - pp, 3)) * min(w, h) * 0.34
                var ring = Path(), spokes = Path()
                for i in 0...8 {
                    let a = Double(i) / 8 * 2 * .pi + 0.3
                    let pt = CGPoint(x: c0.x + CGFloat(cos(a)) * r, y: c0.y + CGFloat(sin(a)) * r)
                    if i == 0 { ring.move(to: pt) } else { ring.addLine(to: pt) }
                    spokes.move(to: c0); spokes.addLine(to: pt)
                }
                ctx.stroke(ring, with: .color(t.accent.opacity(0.7 * (1 - pp))), lineWidth: 1.4)
                if k == 0 { ctx.stroke(spokes, with: .color(Color.white.opacity(0.22 * (1 - pp))), lineWidth: 0.8) }
            }
            pictureT(ctx, "mask", center: c0, fit: 74 * pop, opacity: vis)
        case .reactor:
            // a small thing: the arc reactor lights up at the centre, spins, and pulses rings of light outwards
            let c0 = CGPoint(x: w / 2, y: h / 2)
            let vis = min(1, p / 0.12, (1 - p) / 0.4)
            glowDisc(ctx, at: c0, radius: 90 + 60 * CGFloat(p), color: t.accent, alpha: 0.55 * vis)
            for k in 0..<3 {
                let pp = max(0, min(1, (p - Double(k) * 0.12) / (1 - Double(k) * 0.12)))
                let r = 28 + CGFloat(1 - pow(1 - pp, 3)) * min(w, h) * 0.38
                ctx.stroke(Path(ellipseIn: CGRect(x: c0.x - r, y: c0.y - r, width: r * 2, height: r * 2)), with: .color(t.accent.opacity((k == 0 ? 0.8 : 0.45) * (1 - pp))), lineWidth: k == 0 ? 2 : 1.2)
            }
            pictureT(ctx, "reactor", center: c0, fit: 72 * CGFloat(0.7 + 0.35 * min(1, p / 0.3)), opacity: vis, rotation: p * 2.2)
        case .thor:
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0xBFE3FF).opacity(max(0, 0.22 - p * 0.5))))
            if p < 0.5 {
                let x0 = w * (0.2 + 0.6 * hash(floor(p * 12)))
                let bolt = lightning(from: CGPoint(x: x0, y: 0), to: CGPoint(x: x0 + 40, y: h * 0.55), seed: floor(p * 12), segments: 8, jitter: 22)
                ctx.stroke(bolt, with: .color(Color.white.opacity(0.95)), lineWidth: 1.8)
                ctx.stroke(bolt, with: .color(t.accent.opacity(0.6)), lineWidth: 6)
            }
        case .matrix:
            let chars = Array("01アウ7Z3")
            for col in 0..<22 {
                let x = w * (CGFloat(col) + 0.5) / 22
                let head = CGFloat(p * (1.6 + hash(Double(col)))) * h
                for r in 0..<8 {
                    let y = head - CGFloat(r) * 16
                    if y < 0 || y > h { continue }
                    let ch = String(chars[Int(hash(Double(col * 13 + r) + floor(p * 12)) * Double(chars.count)) % chars.count])
                    let a = (r == 0 ? 0.9 : 0.5 * (1 - Double(r) / 8)) * fade
                    ctx.draw(Text(ch).font(.system(size: 13, design: .monospaced)).foregroundStyle(r == 0 ? Color.white.opacity(a) : t.accent.opacity(a)), at: CGPoint(x: x, y: y))
                }
            }
        }
    }

    // MARK: The celebration when the phone connects

    private static func connectFx(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, p: Double, o: CGPoint) {
        sequence(ctx, size: size, theme: t, p: p)
        // a small "connected" badge near the top once the sequence is under way
        let k = min(1, max(0, (p - 0.5) / 0.12))
        let out = p > 0.9 ? max(0, 1 - (p - 0.9) / 0.1) : 1
        guard k > 0 else { return }
        let c = CGPoint(x: size.width / 2, y: size.height * 0.12)
        let s = CGFloat(easeOutBack(k))
        let r = 28 * s
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)), with: .color(t.accent.opacity(0.95 * out)))
        let draw = CGFloat(min(1, max(0, (p - 0.55) / 0.12)))
        var check = Path()
        check.move(to: CGPoint(x: c.x - 11, y: c.y + 1)); check.addLine(to: CGPoint(x: c.x - 3, y: c.y + 9)); check.addLine(to: CGPoint(x: c.x + 12, y: c.y - 9))
        ctx.stroke(check.trimmedPath(from: 0, to: draw), with: .color(t.onAccent.opacity(out)), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
    }

    private static func easeOutBack(_ x: Double) -> Double {
        let c1 = 1.70158, c3 = c1 + 1
        return 1 + c3 * pow(x - 1, 3) + c1 * pow(x - 1, 2)
    }
}
