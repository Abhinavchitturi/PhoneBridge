import SwiftUI

private func hash(_ x: Double) -> Double {
    let v = sin(x * 12.9898) * 43758.5453
    return v - floor(v)
}

/// Each theme's character moves: bats flap across, the spider swings on its thread, the reactor pulses,
/// lightning flickers, code falls. `time` 0 gives a still frame.
extension MotifArt {
    @discardableResult
    static func pictureT(_ ctx: GraphicsContext, _ name: String, center: CGPoint, fit: CGFloat, opacity: Double = 1,
                         scaleY: CGFloat = 1, rotation: Double = 0) -> Bool {
        guard let ns = ThemeArtImages.get(name) else { return false }
        let k = fit / max(ns.size.width, ns.size.height)
        let w = ns.size.width * k, h = ns.size.height * k
        var g = ctx
        g.opacity = opacity
        g.translateBy(x: center.x, y: center.y)
        g.rotate(by: .radians(rotation))
        g.scaleBy(x: 1, y: scaleY)
        g.draw(Image(nsImage: ns), in: CGRect(x: -w / 2, y: -h / 2, width: w, height: h))
        return true
    }

    static func lightning(from a: CGPoint, to b: CGPoint, seed: Double, segments: Int = 7, jitter: CGFloat) -> Path {
        var p = Path()
        p.move(to: a)
        let dx = b.x - a.x, dy = b.y - a.y
        let len = max(1, hypot(dx, dy))
        let nx = -dy / len, ny = dx / len
        for k in 1..<segments {
            let f = CGFloat(k) / CGFloat(segments)
            let off = (CGFloat(hash(seed + Double(k))) - 0.5) * 2 * jitter
            p.addLine(to: CGPoint(x: a.x + dx * f + nx * off, y: a.y + dy * f + ny * off))
        }
        p.addLine(to: b)
        return p
    }

    static func drawAnimated(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, time: Double) {
        let u = size.width / 380
        let w = size.width
        switch t.motif {
        case .arch:
            let pulse = 0.5 + 0.5 * sin(time * 1.2)
            arch(ctx, at: CGPoint(x: w - 120 * u, y: 96 * u), width: 150 * u, color: t.accent.opacity(0.06 + 0.07 * pulse), line: 9 * u)

        case .bat where ThemeArtImages.get("batoval") != nil:
            let pulse = 0.5 + 0.5 * sin(time * 1.6)
            pictureT(ctx, "batoval", center: CGPoint(x: w - 135 * u, y: 96 * u), fit: 250 * u * (1 + 0.012 * pulse), opacity: 0.5 + 0.12 * pulse)
            for i in 0..<4 {
                let f = Double(i)
                let travel = (time * (24 + 7 * f) * Double(u) + f * 140 * Double(u)).truncatingRemainder(dividingBy: Double(w + 120 * u))
                let x = w + 40 * u - CGFloat(travel)
                let y = (28 + 52 * CGFloat(i)) * u + 10 * u * CGFloat(sin(time * 2 + f))
                let flap = 0.62 + 0.38 * abs(sin(time * 9 + f * 1.7))
                pictureT(ctx, "bat", center: CGPoint(x: x, y: y), fit: (34 + 6 * CGFloat(i % 2)) * u, opacity: 0.5, scaleY: CGFloat(flap))
            }

        case .spider where ThemeArtImages.get("mask") != nil:
            pictureT(ctx, "mask", center: CGPoint(x: w - 120 * u, y: 112 * u), fit: 200 * u, opacity: 0.55)
            let sway = CGFloat(sin(time * 0.8)) * 10 * u
            let drop = 150 * u + CGFloat(sin(time * 1.1)) * 14 * u
            let x = w - 270 * u + sway
            var thread = Path()
            thread.move(to: CGPoint(x: w - 270 * u + sway * 0.3, y: 0))
            thread.addLine(to: CGPoint(x: x, y: drop - 50 * u))
            ctx.stroke(thread, with: .color(t.accent2.opacity(0.4)), lineWidth: max(0.8, u))
            pictureT(ctx, "spider", center: CGPoint(x: x, y: drop), fit: 110 * u, opacity: 0.5, rotation: Double(sway / u) * 0.01)

        case .reactor where ThemeArtImages.get("helmet") != nil:
            let pulse = 0.5 + 0.5 * sin(time * 2.2)
            let c = CGPoint(x: w - 120 * u, y: 112 * u)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - 140 * u, y: c.y - 140 * u, width: 280 * u, height: 280 * u)),
                     with: .radialGradient(Gradient(colors: [t.accent.opacity(0.10 + 0.12 * pulse), .clear]), center: c, startRadius: 0, endRadius: 140 * u))
            pictureT(ctx, "helmet", center: c, fit: 210 * u, opacity: 0.8)
            pictureT(ctx, "reactor", center: CGPoint(x: w - 270 * u, y: 160 * u), fit: 100 * u, opacity: 0.3 + 0.22 * pulse, rotation: time * 0.5)

        case .thor where ThemeArtImages.get("thor") != nil:
            let cycle = time.truncatingRemainder(dividingBy: 3.4)
            let flash = cycle < 0.35 ? 1 - cycle / 0.35 : 0
            let seed = floor(time / 3.4)
            let c = CGPoint(x: w - 120 * u, y: 113 * u)
            if flash > 0 {
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - 170 * u, y: c.y - 170 * u, width: 340 * u, height: 340 * u)),
                         with: .radialGradient(Gradient(colors: [t.accent.opacity(0.28 * flash), .clear]), center: c, startRadius: 0, endRadius: 170 * u))
            }
            pictureT(ctx, "thor", center: c, fit: 215 * u, opacity: 0.5 + 0.35 * flash, rotation: 0.04 * sin(time * 0.9))
            if flash > 0 {
                for (k, x0) in [w - 300 * u, w - 215 * u, w - 40 * u].enumerated() {
                    let bolt = lightning(from: CGPoint(x: x0, y: -6 * u), to: CGPoint(x: x0 + (hash(seed + Double(k)) - 0.5) * 90 * u, y: (120 + 60 * hash(seed + Double(k) + 9)) * u),
                                         seed: seed * 7 + Double(k), jitter: 14 * u)
                    ctx.stroke(bolt, with: .color(Color.white.opacity(0.9 * flash)), lineWidth: 1.6 * u)
                    ctx.stroke(bolt, with: .color(t.accent.opacity(0.6 * flash)), lineWidth: 5 * u)
                }
            }

        case .matrix:
            let chars = Array("01アウ7Z3")
            for col in 0..<9 {
                let speed = 28 + 18 * hash(Double(col))
                let head = (time * speed + hash(Double(col) + 3) * 260).truncatingRemainder(dividingBy: 280) - 20
                for r in 0..<10 {
                    let y = (head - Double(r) * 16) * Double(u)
                    if y < -8 * Double(u) || y > 270 * Double(u) { continue }
                    let ch = String(chars[Int(hash(Double(col * 31 + r) + floor(time * 5))  * Double(chars.count)) % chars.count])
                    let alpha = r == 0 ? 0.5 : 0.3 * (1 - Double(r) / 10)
                    let color = r == 0 ? Color.white.opacity(0.55) : t.accent.opacity(alpha)
                    ctx.draw(Text(ch).font(.system(size: max(5, 12 * u), design: .monospaced)).foregroundStyle(color),
                             at: CGPoint(x: w - 24 * u - CGFloat(col) * 22 * u, y: CGFloat(y)))
                }
            }

        default:
            draw(ctx, size: size, theme: t)
        }
    }

    /// Extra motion around the searching orb, specific to the theme. Called after the blob and rings are drawn.
    static func orbExtras(_ ctx: GraphicsContext, center c: CGPoint, radius R: CGFloat, theme t: AppTheme, time: Double) {
        switch t.motif {
        case .bat where ThemeArtImages.get("bat") != nil:
            for i in 0..<7 {
                let f = Double(i)
                let ring: CGFloat = i % 2 == 0 ? 1.62 : 1.42
                let a = f * 0.9 + time * (i % 2 == 0 ? 0.32 : -0.26)
                let p = CGPoint(x: c.x + CGFloat(cos(a)) * R * ring, y: c.y + CGFloat(sin(a)) * R * ring)
                let flap = 0.6 + 0.4 * abs(sin(time * 8 + f * 1.3))
                pictureT(ctx, "bat", center: p, fit: 22, opacity: 0.8, scaleY: CGFloat(flap), rotation: a + (i % 2 == 0 ? .pi / 2 : -.pi / 2))
            }
        case .spider where ThemeArtImages.get("spider") != nil:
            var silk = Path()
            for k in 0..<8 {
                let a = Double(k) * .pi / 4 + time * 0.06
                silk.move(to: CGPoint(x: c.x + CGFloat(cos(a)) * R * 1.05, y: c.y + CGFloat(sin(a)) * R * 1.05))
                silk.addLine(to: CGPoint(x: c.x + CGFloat(cos(a)) * R * 1.62, y: c.y + CGFloat(sin(a)) * R * 1.62))
            }
            ctx.stroke(silk, with: .color(t.accent2.opacity(0.22)), lineWidth: 0.8)
            let a = time * 0.3
            let p = CGPoint(x: c.x + CGFloat(cos(a)) * R * 1.62, y: c.y + CGFloat(sin(a)) * R * 1.62)
            pictureT(ctx, "spider", center: p, fit: 30, opacity: 0.9, rotation: a + .pi / 2 + 0.15 * sin(time * 6))
        case .reactor:
            let specs: [(CGFloat, Double, Double, Double, Color)] = [(1.74, 80, 0.9, 2.4, t.accent), (1.86, 44, -1.3, 1.8, t.accent2), (1.96, 120, 0.5, 1.4, t.accent)]
            for (scale, span, speed, w, color) in specs {
                var arc = Path()
                let start = time * speed * 57.3
                arc.addArc(center: c, radius: R * scale, startAngle: .degrees(start), endAngle: .degrees(start + span), clockwise: false)
                ctx.stroke(arc, with: .color(color.opacity(0.7)), style: StrokeStyle(lineWidth: w, lineCap: .round))
            }
        case .thor:
            let cycle = time.truncatingRemainder(dividingBy: 2.2)
            let flash = cycle < 0.3 ? 1 - cycle / 0.3 : 0
            if flash > 0 {
                let seed = floor(time / 2.2)
                for k in 0..<3 {
                    let a = hash(seed + Double(k) * 3.1) * 2 * .pi
                    let from = CGPoint(x: c.x + CGFloat(cos(a)) * R * 0.95, y: c.y + CGFloat(sin(a)) * R * 0.95)
                    let to = CGPoint(x: c.x + CGFloat(cos(a + 0.2)) * R * 1.95, y: c.y + CGFloat(sin(a + 0.2)) * R * 1.95)
                    let bolt = lightning(from: from, to: to, seed: seed * 5 + Double(k), jitter: 9)
                    ctx.stroke(bolt, with: .color(Color.white.opacity(0.9 * flash)), lineWidth: 1.4)
                    ctx.stroke(bolt, with: .color(t.accent.opacity(0.55 * flash)), lineWidth: 4)
                }
            }
        case .matrix:
            let chars = Array("01アウ7Z3")
            for i in 0..<16 {
                let f = Double(i)
                let ring: CGFloat = i % 2 == 0 ? 1.62 : 1.42
                let a = f * 0.83 + time * (0.1 + 0.03 * Double(i % 4)) * (i % 3 == 0 ? -1 : 1)
                let p = CGPoint(x: c.x + CGFloat(cos(a)) * R * ring, y: c.y + CGFloat(sin(a)) * R * ring)
                let ch = String(chars[Int(hash(f + floor(time * 3))  * Double(chars.count)) % chars.count])
                ctx.draw(Text(ch).font(.system(size: 11, design: .monospaced)).foregroundStyle(t.accent.opacity(0.35 + 0.35 * sin(time + f))), at: p)
            }
        default: break
        }
    }

    /// One-off flourish when a theme is chosen. `p` runs 0 → 1 over about 1.4 seconds.
    static func burst(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, p: Double) {
        sequence(ctx, size: size, theme: t, p: p)
    }
}

/// Plays the theme's burst over the window right after a theme is chosen, then goes away.
struct ThemeBurstView: View {
    @ObservedObject private var themes = ThemeStore.shared

    var body: some View {
        if themes.bursting && Bridge.shared.connected {   // never while still searching
            TimelineView(.animation) { tl in
                let p = tl.date.timeIntervalSince(themes.changedAt) / 3.6
                Canvas { ctx, size in
                    if p >= 0 && p < 1 { MotifArt.burst(ctx, size: size, theme: themes.current, p: p) }
                }
            }
            .allowsHitTesting(false)
        }
    }
}
