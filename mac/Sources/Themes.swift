import SwiftUI

// MARK: - Themes (all dark). One accent per theme, plus a few small drawn motifs.

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, opacity: alpha)
    }
}

enum Motif { case arch, bat, spider, reactor, thor, matrix }

struct AppTheme: Identifiable, Equatable {
    let id: String
    let name: String
    let bg: Color
    let accent: Color
    let accent2: Color
    let onAccent: Color
    let motif: Motif

    static let all: [AppTheme] = [
        AppTheme(id: "midnight", name: "Midnight", bg: Color(hex: 0x0B0B0D), accent: Color(hex: 0xF5F5F7), accent2: Color(hex: 0xA1A1A6), onAccent: Color(hex: 0x0B0B0D), motif: .arch),
        AppTheme(id: "batman", name: "Batman", bg: Color(hex: 0x09090A), accent: Color(hex: 0xF5C518), accent2: Color(hex: 0xB8921A), onAccent: Color(hex: 0x111111), motif: .bat),
        AppTheme(id: "spiderman", name: "Spider-Man", bg: Color(hex: 0x0A0A0B), accent: Color(hex: 0xE23636), accent2: Color(hex: 0x8FA3FF), onAccent: .white, motif: .spider),
        AppTheme(id: "ironman", name: "Iron Man", bg: Color(hex: 0x0F0A0A), accent: Color(hex: 0xFFC21A), accent2: Color(hex: 0xD0112B), onAccent: Color(hex: 0x111111), motif: .reactor),
        AppTheme(id: "thor", name: "Thor", bg: Color(hex: 0x080A10), accent: Color(hex: 0x5AB8FF), accent2: Color(hex: 0xA9B7C6), onAccent: Color(hex: 0x06121F), motif: .thor),
        AppTheme(id: "matrix", name: "Matrix", bg: Color(hex: 0x020A05), accent: Color(hex: 0x19F06B), accent2: Color(hex: 0x0E9B45), onAccent: Color(hex: 0x021108), motif: .matrix),
    ]
}

final class ThemeStore: ObservableObject {
    static let shared = ThemeStore()
    /// Called when you pick a theme on this Mac, so the phone can follow.
    var onLocalChange: ((AppTheme) -> Void)?
    private var applyingRemote = false

    /// The phone picked a theme: switch to it here too (without telling the phone back).
    func applyRemote(_ t: AppTheme) {
        guard t != current else { return }
        applyingRemote = true
        current = t
        applyingRemote = false
    }

    @Published var current: AppTheme {
        didSet {
            if !applyingRemote { onLocalChange?(current) }
            UserDefaults.standard.set(current.id, forKey: "theme")
            changedAt = Date()
            bursting = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.8) { [weak self] in self?.bursting = false }
        }
    }
    /// When the theme last changed, and whether its one-off burst animation is still playing.
    @Published var bursting = false
    private(set) var changedAt = Date.distantPast
    private init() {
        let id = UserDefaults.standard.string(forKey: "theme")
        current = AppTheme.all.first { $0.id == id } ?? AppTheme.all[0]
    }
}

// MARK: - Drawing

/// Character artwork (cut-out PNGs shipped in the app's Resources folder), cached after the first load.
enum ThemeArtImages {
    /// Where to look when not running from an app bundle (used for previews).
    nonisolated(unsafe) static var fallbackDir: String?
    nonisolated(unsafe) private static var cache: [String: NSImage] = [:]

    static func get(_ name: String) -> NSImage? {
        if let hit = cache[name] { return hit }
        var image: NSImage?
        for ext in ["png", "jpg"] where image == nil {
            if let url = Bundle.main.url(forResource: "themeart_\(name)", withExtension: ext) { image = NSImage(contentsOf: url) }
            else if let dir = fallbackDir { image = NSImage(contentsOfFile: "\(dir)/themeart_\(name).\(ext)") }
        }
        if let image { cache[name] = image }
        return image
    }
}

enum MotifArt {
    /// Draws a cut-out picture centred on `center`, scaled so its longest side is `fit`. Returns false if the picture isn't available.
    @discardableResult
    static func picture(_ ctx: GraphicsContext, _ name: String, center: CGPoint, fit: CGFloat, opacity: Double = 1) -> Bool {
        guard let ns = ThemeArtImages.get(name) else { return false }
        let k = fit / max(ns.size.width, ns.size.height)
        let rect = CGRect(x: center.x - ns.size.width * k / 2, y: center.y - ns.size.height * k / 2, width: ns.size.width * k, height: ns.size.height * k)
        var g = ctx
        g.opacity = opacity
        g.draw(Image(nsImage: ns), in: rect)
        return true
    }

    private static func poly(_ pts: [(CGFloat, CGFloat)], _ pt: (CGFloat, CGFloat) -> CGPoint) -> Path {
        var p = Path()
        p.move(to: pt(pts[0].0, pts[0].1))
        for q in pts.dropFirst() { p.addLine(to: pt(q.0, q.1)) }
        p.closeSubpath()
        return p
    }

    /// Bat emblem: two ears, wide wings with scalloped edges. About 96 units wide at scale 1, centred on (0, 3).
    static func bat(_ ctx: GraphicsContext, at p: CGPoint, scale s: CGFloat, color: Color) {
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: p.x + x * s, y: p.y + (y - 3) * s) }
        var path = Path()
        path.move(to: pt(0, -9)); path.addLine(to: pt(3, -13)); path.addLine(to: pt(4, -8))
        path.addQuadCurve(to: pt(14, -8), control: pt(9, -4))
        path.addQuadCurve(to: pt(48, 2), control: pt(30, -16))
        path.addQuadCurve(to: pt(30, 7), control: pt(38, 0))
        path.addQuadCurve(to: pt(16, 12), control: pt(24, 2))
        path.addQuadCurve(to: pt(0, 20), control: pt(10, 8))
        path.addQuadCurve(to: pt(-16, 12), control: pt(-10, 8))
        path.addQuadCurve(to: pt(-30, 7), control: pt(-24, 2))
        path.addQuadCurve(to: pt(-48, 2), control: pt(-38, 0))
        path.addQuadCurve(to: pt(-14, -8), control: pt(-30, -16))
        path.addQuadCurve(to: pt(-4, -8), control: pt(-9, -4))
        path.addLine(to: pt(-3, -13))
        path.closeSubpath()
        ctx.fill(path, with: .color(color))
    }

    /// Spider emblem: pointed body and four long legs on each side. About 70 units tall, centred on (0, 0).
    static func spider(_ ctx: GraphicsContext, at p: CGPoint, scale s: CGFloat, color: Color) {
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: p.x + x * s, y: p.y + (y - 4) * s) }
        let legs: [((CGFloat, CGFloat), (CGFloat, CGFloat), (CGFloat, CGFloat))] = [
            ((3, -6), (20, -10), (16, -36)), ((3, -3), (28, -4), (27, -30)),
            ((3, 1), (26, 8), (32, 34)), ((3, 5), (16, 14), (17, 40)),
        ]
        var path = Path()
        for side in [CGFloat(-1), 1] {
            for l in legs {
                path.move(to: pt(side * l.0.0, l.0.1))
                path.addLine(to: pt(side * l.1.0, l.1.1))
                path.addLine(to: pt(side * l.2.0, l.2.1))
            }
        }
        ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: max(1, 2.1 * s), lineCap: .round, lineJoin: .round))
        var body = Path()
        body.move(to: pt(0, -2))
        body.addQuadCurve(to: pt(3, 24), control: pt(9, 8))
        body.addLine(to: pt(0, 32)); body.addLine(to: pt(-3, 24))
        body.addQuadCurve(to: pt(0, -2), control: pt(-9, 8))
        ctx.fill(body, with: .color(color))
        ctx.fill(Path(ellipseIn: CGRect(x: p.x - 3.4 * s, y: p.y - 11 * s, width: 6.8 * s, height: 7.6 * s)), with: .color(color))
    }

    /// Masked face on a web: round, with black-rimmed white eyes.
    static func mask(_ ctx: GraphicsContext, at p: CGPoint, radius r: CGFloat, fill: Color, eye: Color, line: Color) {
        ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(fill))
        var web = Path()
        for i in 0..<8 {
            let a = CGFloat(i) * .pi / 4
            web.move(to: p); web.addLine(to: CGPoint(x: p.x + cos(a) * r * 0.98, y: p.y + sin(a) * r * 0.98))
        }
        web.addEllipse(in: CGRect(x: p.x - r * 0.62, y: p.y - r * 0.62, width: r * 1.24, height: r * 1.24))
        ctx.stroke(web, with: .color(line), lineWidth: max(0.6, r * 0.07))
        for side in [CGFloat(-1), 1] {
            let c = CGPoint(x: p.x + side * r * 0.44, y: p.y - r * 0.04)
            let t = CGAffineTransform(translationX: c.x, y: c.y).rotated(by: -side * 0.5).translatedBy(x: -c.x, y: -c.y)
            let rim = Path(ellipseIn: CGRect(x: c.x - r * 0.46, y: c.y - r * 0.29, width: r * 0.92, height: r * 0.58)).applying(t)
            let lens = Path(ellipseIn: CGRect(x: c.x - r * 0.3, y: c.y - r * 0.17, width: r * 0.6, height: r * 0.34)).applying(t)
            ctx.fill(rim, with: .color(line))
            ctx.fill(lens, with: .color(eye))
        }
    }

    static func web(_ ctx: GraphicsContext, corner: CGPoint, k: CGFloat, color: Color) {
        let ends: [CGPoint] = [(-95, 28), (-78, 72), (-48, 108), (-4, 124)].map { CGPoint(x: corner.x + $0.0 * k, y: corner.y + $0.1 * k) }
        var p = Path()
        for e in ends { p.move(to: corner); p.addLine(to: e) }
        for t in [CGFloat(0.35), 0.65] {
            for (i, e) in ends.enumerated() {
                let q = CGPoint(x: corner.x + (e.x - corner.x) * t, y: corner.y + (e.y - corner.y) * t)
                if i == 0 { p.move(to: q) } else { p.addLine(to: q) }
            }
        }
        ctx.stroke(p, with: .color(color), lineWidth: max(0.8, k * 0.55))
    }

    static func reactor(_ ctx: GraphicsContext, at c: CGPoint, radius r: CGFloat, color: Color) {
        for (f, w) in [(1.0, 1.0), (0.72, 2.0), (0.38, 2.0)] as [(CGFloat, CGFloat)] {
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r * f, y: c.y - r * f, width: r * f * 2, height: r * f * 2)), with: .color(color), lineWidth: w)
        }
    }

    /// Flat helmet: red shell, gold faceplate with the red crest notch on top, white eye slits, jaw lines. About 107 units tall.
    static func ironFace(_ ctx: GraphicsContext, at c: CGPoint, scale s: CGFloat, helmet: Color, plate: Color, eye: Color) {
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: c.x + x * s, y: c.y + y * s) }
        ctx.fill(poly([(0, -53), (18, -50), (29, -38), (32, -20), (34, -6), (38, 0), (34, 10), (26, 36), (22, 48), (12, 54),
                       (-12, 54), (-22, 48), (-26, 36), (-34, 10), (-38, 0), (-34, -6), (-32, -20), (-29, -38), (-18, -50)], pt), with: .color(helmet))
        ctx.fill(poly([(-27, -40), (-20, -47), (-9, -47), (-6, -20), (6, -20), (9, -47), (20, -47), (27, -40), (29, -8), (25, 14),
                       (15, 38), (9, 46), (-9, 46), (-15, 38), (-25, 14), (-29, -8)], pt), with: .color(plate))
        for side in [CGFloat(-1), 1] {
            ctx.fill(poly([(side * 27, -7), (side * 5, 1), (side * 5, 10), (side * 24, 7)], pt), with: .color(helmet))
            ctx.fill(poly([(side * 24, -4), (side * 8, 3), (side * 8, 6.5), (side * 21, 5)], pt), with: .color(eye))
        }
        var lines = Path()
        lines.move(to: pt(-10, 36)); lines.addLine(to: pt(10, 36))
        lines.move(to: pt(-9, 41)); lines.addLine(to: pt(9, 41))
        for side in [CGFloat(-1), 1] { lines.move(to: pt(side * 21, 16)); lines.addLine(to: pt(side * 13, 34)) }
        ctx.stroke(lines, with: .color(helmet), style: StrokeStyle(lineWidth: max(0.8, 1.7 * s), lineCap: .round))
    }

    /// A war hammer tilted to the right: head with bevelled ends, wrapped handle. About 75 units tall.
    static func hammer(_ ctx: GraphicsContext, at c: CGPoint, scale s: CGFloat, color: Color, cut: Color) {
        var g = ctx
        g.translateBy(x: c.x, y: c.y)
        g.rotate(by: .degrees(25))
        func box(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> Path {
            Path(roundedRect: CGRect(x: x * s, y: (y + 6) * s, width: w * s, height: h * s), cornerRadius: 2 * s)
        }
        g.fill(box(-24, -26, 48, 34), with: .color(color))
        g.fill(box(-6, -31, 12, 6), with: .color(color))
        g.fill(box(-5, 8, 10, 34), with: .color(color))
        g.fill(box(-7, 40, 14, 4), with: .color(color))
        var cuts = Path()
        for sx in [CGFloat(-19), 19] { cuts.move(to: CGPoint(x: sx * s, y: (-20 + 6) * s)); cuts.addLine(to: CGPoint(x: sx * s, y: (2 + 6) * s)) }
        for y in [CGFloat(17), 24, 31] { cuts.move(to: CGPoint(x: -5 * s, y: (y + 3 + 6) * s)); cuts.addLine(to: CGPoint(x: 5 * s, y: (y - 3 + 6) * s)) }
        g.stroke(cuts, with: .color(cut), lineWidth: max(0.7, 2.1 * s))
    }

    static func bolt(_ ctx: GraphicsContext, from o: CGPoint, scale s: CGFloat, color: Color) {
        var p = Path()
        let pts: [(CGFloat, CGFloat)] = [(0, 0), (-14, 34), (-2, 34), (-18, 78), (8, 28), (-4, 28), (10, 0)]
        p.move(to: CGPoint(x: o.x + pts[0].0 * s, y: o.y + pts[0].1 * s))
        for q in pts.dropFirst() { p.addLine(to: CGPoint(x: o.x + q.0 * s, y: o.y + q.1 * s)) }
        p.closeSubpath()
        ctx.fill(p, with: .color(color))
    }

    static func arch(_ ctx: GraphicsContext, at c: CGPoint, width w: CGFloat, color: Color, line: CGFloat) {
        var p = Path()
        p.move(to: CGPoint(x: c.x - w / 2, y: c.y + w * 0.3))
        p.addQuadCurve(to: CGPoint(x: c.x + w / 2, y: c.y + w * 0.3), control: CGPoint(x: c.x, y: c.y - w * 0.7))
        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: line, lineCap: .round))
        for sx in [-w / 2, w / 2] {
            let r = line * 1.25
            ctx.fill(Path(ellipseIn: CGRect(x: c.x + sx - r, y: c.y + w * 0.3 - r, width: r * 2, height: r * 2)), with: .color(color))
        }
    }

    /// Big faint artwork for the top-right corner of a window or tile. Everything scales with the width (designed at 380).
    static func draw(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme) {
        let u = size.width / 380
        let w = size.width
        switch t.motif {
        case .arch:
            arch(ctx, at: CGPoint(x: w - 120 * u, y: 96 * u), width: 150 * u, color: t.accent.opacity(0.09), line: 9 * u)
        case .bat where ThemeArtImages.get("batoval") != nil:
            picture(ctx, "batoval", center: CGPoint(x: w - 135 * u, y: 96 * u), fit: 250 * u, opacity: 0.6)
            picture(ctx, "bat", center: CGPoint(x: w - 285 * u, y: 45 * u), fit: 62 * u, opacity: 0.5)
            picture(ctx, "bat", center: CGPoint(x: w - 60 * u, y: 215 * u), fit: 50 * u, opacity: 0.4)
        case .bat:
            let oval = Path(ellipseIn: CGRect(x: w - 250 * u, y: 16 * u, width: 240 * u, height: 150 * u))
            ctx.fill(oval, with: .color(t.accent.opacity(0.15)))
            ctx.stroke(oval, with: .color(t.accent.opacity(0.35)), lineWidth: 3 * u)
            bat(ctx, at: CGPoint(x: w - 130 * u, y: 92 * u), scale: 1.9 * u, color: t.bg)
            bat(ctx, at: CGPoint(x: w - 285 * u, y: 40 * u), scale: 0.35 * u, color: t.accent.opacity(0.25))
            bat(ctx, at: CGPoint(x: w - 60 * u, y: 210 * u), scale: 0.3 * u, color: t.accent.opacity(0.22))
        case .spider where ThemeArtImages.get("mask") != nil:
            picture(ctx, "mask", center: CGPoint(x: w - 120 * u, y: 112 * u), fit: 200 * u, opacity: 0.55)
            picture(ctx, "spider", center: CGPoint(x: w - 270 * u, y: 150 * u), fit: 110 * u, opacity: 0.5)
        case .spider:
            web(ctx, corner: CGPoint(x: w, y: 0), k: 2.1 * u, color: t.accent2.opacity(0.26))
            spider(ctx, at: CGPoint(x: w - 110 * u, y: 150 * u), scale: 2.4 * u, color: t.accent.opacity(0.6))
        case .reactor where ThemeArtImages.get("helmet") != nil:
            picture(ctx, "helmet", center: CGPoint(x: w - 120 * u, y: 112 * u), fit: 210 * u, opacity: 0.8)
            picture(ctx, "reactor", center: CGPoint(x: w - 270 * u, y: 160 * u), fit: 100 * u, opacity: 0.4)
        case .reactor:
            reactor(ctx, at: CGPoint(x: w - 120 * u, y: 112 * u), radius: 112 * u, color: t.accent.opacity(0.22))
            var faint = ctx
            faint.opacity = 0.75
            ironFace(faint, at: CGPoint(x: w - 120 * u, y: 112 * u), scale: 1.75 * u, helmet: t.accent2, plate: t.accent, eye: Color(hex: 0xF4F4F4))
        case .thor where ThemeArtImages.get("thor") != nil:
            picture(ctx, "thor", center: CGPoint(x: w - 120 * u, y: 113 * u), fit: 215 * u, opacity: 0.5)
        case .thor:
            let ring = Path(ellipseIn: CGRect(x: w - 225 * u, y: 8 * u, width: 210 * u, height: 210 * u))
            ctx.stroke(ring, with: .color(t.accent.opacity(0.3)), lineWidth: 6 * u)
            hammer(ctx, at: CGPoint(x: w - 120 * u, y: 108 * u), scale: 2.0 * u, color: t.accent.opacity(0.5), cut: t.bg)
            bolt(ctx, from: CGPoint(x: w - 285 * u, y: 6 * u), scale: 0.8 * u, color: t.accent2.opacity(0.28))
        case .matrix:
            let chars = Array("01アウ7Z3")
            for c in 0..<9 {
                for r in 0..<9 {
                    let ch = String(chars[(c * 3 + r) % chars.count])
                    let text = Text(ch).font(.system(size: max(5, 12 * u), design: .monospaced)).foregroundStyle(t.accent.opacity(0.05 + Double(r) * 0.03))
                    ctx.draw(text, at: CGPoint(x: w - 24 * u - CGFloat(c) * 22 * u, y: 20 * u + CGFloat(r) * 22 * u))
                }
            }
        }
    }

    /// Small mark in the theme's accent colour (dock, theme tiles).
    static func glyph(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, color: Color) {
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let k = min(size.width, size.height) / 28
        switch t.motif {
        case .arch: arch(ctx, at: CGPoint(x: c.x, y: c.y - 1.8 * k), width: 18 * k, color: color, line: 2.4 * k)
        case .bat where ThemeArtImages.get("bat") != nil: picture(ctx, "bat", center: c, fit: 26 * k)
        case .bat: bat(ctx, at: c, scale: 0.27 * k, color: color)
        case .spider where ThemeArtImages.get("mask") != nil: picture(ctx, "mask", center: c, fit: 24 * k)
        case .spider: mask(ctx, at: c, radius: 10 * k, fill: color, eye: .white, line: t.bg)
        case .reactor where ThemeArtImages.get("helmet") != nil: picture(ctx, "helmet", center: c, fit: 27 * k)
        case .reactor: ironFace(ctx, at: c, scale: 0.25 * k, helmet: t.accent2, plate: color, eye: .white)
        case .thor where ThemeArtImages.get("thor") != nil: picture(ctx, "thor", center: c, fit: 25 * k)
        case .thor: hammer(ctx, at: CGPoint(x: c.x, y: c.y - 3 * k), scale: 0.3 * k, color: color, cut: t.bg)
        case .matrix: ctx.draw(Text("01").font(.system(size: 12 * k, weight: .bold, design: .monospaced)).foregroundStyle(color), at: c)
        }
    }
}

struct MotifLayer: View {
    let theme: AppTheme
    /// When set, the artwork moves (this is the clock, in seconds); otherwise it is a still picture.
    var time: Double? = nil
    var body: some View {
        Canvas { ctx, size in
            if let time { MotifArt.drawAnimated(ctx, size: size, theme: theme, time: time) }
            else { MotifArt.draw(ctx, size: size, theme: theme) }
        }
        .allowsHitTesting(false)
    }
}

struct ThemeGlyph: View {
    let theme: AppTheme
    var color: Color? = nil
    var body: some View {
        Canvas { ctx, size in MotifArt.glyph(ctx, size: size, theme: theme, color: color ?? theme.accent) }
    }
}

/// Settings → Appearance: one tile per theme.
struct ThemePicker: View {
    @ObservedObject private var store = ThemeStore.shared

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 200), spacing: 12)], spacing: 12) {
            ForEach(AppTheme.all) { t in tile(t) }
        }
    }

    private func tile(_ t: AppTheme) -> some View {
        let selected = store.current == t
        return Button { withAnimation(.easeOut(duration: 0.2)) { store.current = t } } label: {
            VStack(spacing: 8) {
                ZStack(alignment: .topTrailing) {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(t.bg)
                    MotifLayer(theme: t).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    VStack(alignment: .leading, spacing: 6) {
                        Spacer()
                        HStack(spacing: 6) {
                            Circle().fill(t.accent).frame(width: 10, height: 10)
                            Capsule().fill(Color.white.opacity(0.5)).frame(width: 44, height: 5)
                        }
                        Capsule().fill(Color.white.opacity(0.2)).frame(width: 70, height: 5)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                }
                .frame(height: 96)
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(selected ? t.accent : Color.white.opacity(0.1), lineWidth: selected ? 2 : 1))
                Text(t.name).font(.system(size: 12.5, weight: selected ? .semibold : .regular)).foregroundStyle(selected ? Color.primary : Color.secondary)
            }
        }
        .buttonStyle(.plain)
        .pressFx()
    }
}
