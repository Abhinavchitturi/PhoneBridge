import SwiftUI

private func hash(_ x: Double) -> Double {
    let v = sin(x * 12.9898) * 43758.5453
    return v - floor(v)
}
private func clamp01(_ x: Double) -> Double { min(1, max(0, x)) }
/// Progress of `p` through the stretch [a, b], clamped to 0...1.
private func seg(_ p: Double, _ a: Double, _ b: Double) -> Double { clamp01((p - a) / (b - a)) }
private func easeOut(_ x: Double) -> Double { 1 - pow(1 - x, 3) }
private func easeIn(_ x: Double) -> Double { x * x * x }
private func easeInOut(_ x: Double) -> Double { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }

/// The character sequences played when a theme is picked, when the app opens, and when the phone connects.
/// Thor drops in with lightning, looks up and leaps away; Spider-Man swings in, lands, looks up and swings off;
/// Iron Man lands, looks up, fires missiles across the sky and flies off. Batman brings the signal, bats and the Batmobile;
/// Matrix rains code; Midnight draws a constellation bridge.
extension MotifArt {
    /// Draws a cut-out standing on / pivoting around a point: (px, py) are fractions of the picture (0.5, 1 = bottom middle).
    static func sprite(_ ctx: GraphicsContext, _ name: String, at pivot: CGPoint, height: CGFloat, pivotFrac: CGPoint = CGPoint(x: 0.5, y: 1),
                       rotation: Double = 0, opacity: Double = 1, flip: Bool = false, scaleY: CGFloat = 1) {
        guard let ns = ThemeArtImages.get(name) else { return }
        let w = height * ns.size.width / ns.size.height
        var g = ctx
        g.opacity = opacity
        g.translateBy(x: pivot.x, y: pivot.y)
        g.rotate(by: .radians(rotation))
        g.scaleBy(x: flip ? -1 : 1, y: scaleY)
        g.draw(Image(nsImage: ns), in: CGRect(x: -w * pivotFrac.x, y: -height * pivotFrac.y, width: w, height: height))
    }

    /// A still that faces left, mirrored so it faces the way it travels (right), sized by its longest side.
    static func faced(_ ctx: GraphicsContext, _ name: String, at c: CGPoint, fit: CGFloat, rotation: Double, opacity: Double) {
        guard opacity > 0, let ns = ThemeArtImages.get(name) else { return }
        let aspect = ns.size.height / ns.size.width
        sprite(ctx, name, at: c, height: fit * aspect, pivotFrac: CGPoint(x: 0.5, y: 0.5), rotation: rotation, opacity: opacity, flip: true)
    }

    private static func boltLine(_ ctx: GraphicsContext, from a: CGPoint, to b: CGPoint, seed: Double, color: Color, alpha: Double, width: CGFloat = 1.6, jitter: CGFloat = 16, segments: Int = 8) {
        guard alpha > 0 else { return }
        let path = lightning(from: a, to: b, seed: seed, segments: segments, jitter: jitter)
        ctx.stroke(path, with: .color(Color.white.opacity(0.95 * alpha)), lineWidth: width)
        ctx.stroke(path, with: .color(color.opacity(0.6 * alpha)), lineWidth: width * 3.4)
    }

    static func glowDisc(_ ctx: GraphicsContext, at c: CGPoint, radius r: CGFloat, color: Color, alpha: Double) {
        guard alpha > 0 else { return }
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                 with: .radialGradient(Gradient(colors: [color.opacity(alpha), .clear]), center: c, startRadius: 0, endRadius: r))
    }

    /// A missile in flight: a small body, a bright tail and a trail of smoke along where it has been.
    static func missile(_ ctx: GraphicsContext, path: (Double) -> CGPoint, q: Double, color: Color, scale: CGFloat) {
        guard q > 0, q < 1 else { return }
        for k in 1...12 {
            let qq = max(0, q - Double(k) * 0.018)
            let p = path(qq)
            let r = (2 + CGFloat(k) * 0.55) * scale
            ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(Color.white.opacity(0.32 * (1 - Double(k) / 13))))
        }
        let head = path(q), before = path(max(0, q - 0.02))
        let ang = atan2(Double(head.y - before.y), Double(head.x - before.x))
        var g = ctx
        g.translateBy(x: head.x, y: head.y); g.rotate(by: .radians(ang))
        g.fill(Path(roundedRect: CGRect(x: -7 * scale, y: -2 * scale, width: 14 * scale, height: 4 * scale), cornerRadius: 2 * scale), with: .color(Color(white: 0.92)))
        g.fill(Path(ellipseIn: CGRect(x: -14 * scale, y: -3 * scale, width: 10 * scale, height: 6 * scale)),
               with: .radialGradient(Gradient(colors: [color, color.opacity(0)]), center: CGPoint(x: -9 * scale, y: 0), startRadius: 0, endRadius: 6 * scale))
    }

    static func sequence(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, p: Double) {
        let w = size.width, h = size.height
        let rect = CGRect(origin: .zero, size: size)
        let scrimAmount = (t.motif == .arch ? 0.7 : 0.62) * min(seg(p, 0, 0.08), 1 - seg(p, 0.92, 1))
        ctx.fill(Path(rect), with: .color(Color.black.opacity(scrimAmount)))
        let ground = h * 0.9

        switch t.motif {
        case .thor:
            let H = min(h * 0.4, w * 0.95 / 1.08)
            if p < 0.3 && hash(floor(p * 28)) > 0.45 {
                for k in 0..<3 { boltLine(ctx, from: CGPoint(x: w * (0.15 + 0.35 * CGFloat(k)) + CGFloat(hash(floor(p * 28) + Double(k))) * 40, y: 0), to: CGPoint(x: w * (0.1 + 0.4 * CGFloat(k)), y: h * 0.7), seed: Double(k) * 5 + floor(p * 28), color: t.accent, alpha: 0.9, width: 1.5, jitter: 26) }
            }
            let drop = seg(p, 0.06, 0.28), crouch = seg(p, 0.30, 0.58), jump = seg(p, 0.58, 0.88)
            var y = -H * 1.3
            var rot = 0.0
            if p >= 0.06 { y = -H * 1.3 + (ground + H * 1.3) * CGFloat(easeIn(drop)) }
            if p >= 0.28 { y = ground; rot = 0.16 * sin(.pi * crouch * 0.5) * (p < 0.58 ? 1 : 0) + (p >= 0.58 ? 0.16 : 0) }
            if p >= 0.58 { y = ground - (ground + H * 1.4) * CGFloat(easeIn(jump)); rot = 0.16 + 0.35 * jump }
            let cx = w * 0.5
            // trail while leaping
            if p >= 0.6 { for k in 1...4 { sprite(ctx, "thorup", at: CGPoint(x: cx, y: y + CGFloat(k) * H * 0.2 * CGFloat(jump)), height: H * 1.6, pivotFrac: CGPoint(x: 0.5, y: 0.85), opacity: 0.2 / Double(k)) } }
            glowDisc(ctx, at: CGPoint(x: cx, y: y - H * 0.5), radius: H * 0.9, color: t.accent, alpha: p >= 0.28 && p < 0.9 ? 0.28 : 0)
            if p < 0.6 {
                sprite(ctx, "thorfig", at: CGPoint(x: cx, y: y), height: H, rotation: p >= 0.58 ? 0 : rot)
            } else {
                // he launches arm-and-hammer first, straight up into the storm
                sprite(ctx, "thorup", at: CGPoint(x: cx, y: y), height: H * 1.6, pivotFrac: CGPoint(x: 0.5, y: 0.85))
            }
            // the strike that lands with him: a white-hot column, a flash, a ring, and cracks along the ground
            if p >= 0.27 && p < 0.5 {
                let k = seg(p, 0.27, 0.5)
                boltLine(ctx, from: CGPoint(x: cx + 10, y: 0), to: CGPoint(x: cx, y: ground - H * 0.4), seed: floor(p * 40), color: t.accent, alpha: 1 - k, width: 3, jitter: 22, segments: 12)
                ctx.fill(Path(rect), with: .color(Color.white.opacity(0.5 * (1 - seg(p, 0.27, 0.36)))))
                let r = CGFloat(easeOut(k)) * w * 0.6
                ctx.stroke(Path(ellipseIn: CGRect(x: cx - r, y: ground - r * 0.18, width: r * 2, height: r * 0.36)), with: .color(t.accent.opacity(0.8 * (1 - k))), lineWidth: 3)
                var cracks = Path()
                for i in 0..<9 {
                    let a = Double.pi * (0.05 + 0.9 * Double(i) / 8)
                    let len = CGFloat(easeOut(k)) * w * (0.25 + 0.25 * CGFloat(hash(Double(i))))
                    cracks.move(to: CGPoint(x: cx, y: ground))
                    cracks.addLine(to: CGPoint(x: cx + cos(a) * len * (i % 2 == 0 ? 1 : -1) * 0.9, y: ground + abs(sin(a)) * len * 0.12))
                }
                ctx.stroke(cracks, with: .color(Color.white.opacity(0.6 * (1 - k))), lineWidth: 1.4)
            }
            // thunder around him while he gathers himself, then as he leaps
            if p >= 0.3 && p < 0.9 {
                let around = CGPoint(x: cx, y: y - H * 0.5)
                let n = p >= 0.58 ? 6 : 3
                for k in 0..<n {
                    let tick = floor(p * 24)
                    let a = hash(tick * 7 + Double(k)) * 2 * .pi
                    boltLine(ctx, from: around, to: CGPoint(x: around.x + CGFloat(cos(a)) * H * 1.0, y: around.y + CGFloat(sin(a)) * H * 0.9), seed: tick * 3 + Double(k), color: t.accent, alpha: 0.9, width: 1.3, jitter: 14, segments: 6)
                }
            }
            if p >= 0.86 { ctx.fill(Path(rect), with: .color(t.accent.opacity(0.28 * (1 - seg(p, 0.86, 1))))) }

        case .spider:
            let Hc = min(h * 0.32, w * 0.8 / 0.67)
            let anchorY = -h * 0.06
            let figW = min(h * 0.3, w * 0.8)
            let sH = ground - anchorY - figW * 0.43 * 0.5   // pendulum length so the lowest point is on the ground
            let anchorX = w * 0.5
            if p < 0.36 {
                let a = seg(p, 0, 0.36)
                let th = -0.95 * cos(.pi / 2 * a)
                let pos = CGPoint(x: anchorX + sH * CGFloat(sin(th)), y: anchorY + sH * CGFloat(cos(th)))
                let nx = CGPoint(x: anchorX + sH * CGFloat(sin(-0.95 * cos(.pi / 2 * min(1, a + 0.01)))), y: anchorY + sH * CGFloat(cos(-0.95 * cos(.pi / 2 * min(1, a + 0.01)))))
                let heading = atan2(Double(nx.y - pos.y), Double(max(0.001, nx.x - pos.x)))
                let hand = CGPoint(x: pos.x - figW * 0.47 * CGFloat(cos(heading * 0.75)), y: pos.y - figW * 0.08)
                var web = Path(); web.move(to: hand); web.addLine(to: CGPoint(x: anchorX, y: anchorY))
                ctx.stroke(web, with: .color(Color.white.opacity(0.2)), lineWidth: 5)
                ctx.stroke(web, with: .color(Color.white.opacity(0.9)), lineWidth: 1.3)
                let appear = min(1, p / 0.03)
                let blend = seg(a, 0.38, 0.42)   // arms-up grab of the web, then stretched out through the swing
                // both stills face LEFT, he travels RIGHT, so mirror them: head and reaching arm lead the way
                faced(ctx, "swingA", at: pos, fit: figW, rotation: heading * 0.75, opacity: appear * (1 - blend))
                faced(ctx, "swing1", at: pos, fit: figW, rotation: heading * 0.75, opacity: appear * blend)
            } else if p < 0.74 {
                let land = seg(p, 0.36, 0.52), look = seg(p, 0.54, 0.72)
                let squash: CGFloat = 1 - 0.1 * CGFloat(sin(.pi * min(1, land * 1.6)))
                let rot = -0.14 * easeInOut(look)
                sprite(ctx, "spidercrouch", at: CGPoint(x: w * 0.5, y: ground), height: Hc, rotation: rot, scaleY: squash)
                // dust at the landing
                for i in 0..<10 {
                    let a = Double.pi * (0.1 + 0.8 * Double(i) / 9)
                    let d = CGFloat(easeOut(land)) * w * 0.28 * CGFloat(0.5 + 0.5 * hash(Double(i)))
                    let r = 4 + 12 * CGFloat(easeOut(land))
                    ctx.fill(Path(ellipseIn: CGRect(x: w * 0.5 + cos(a) * d * (i % 2 == 0 ? 1 : -1) - r, y: ground - sin(a) * d * 0.35 - r, width: r * 2, height: r * 2)), with: .color(Color.white.opacity(0.22 * (1 - land))))
                }
                // after looking up, a web shoots to the sky
                if look > 0.4 {
                    let g = CGFloat(seg(look, 0.4, 1))
                    let hand = CGPoint(x: w * 0.5 + Hc * 0.28, y: ground - Hc * 0.9)
                    let end = CGPoint(x: w * 0.78, y: -h * 0.1)
                    var web = Path(); web.move(to: hand); web.addLine(to: CGPoint(x: hand.x + (end.x - hand.x) * g, y: hand.y + (end.y - hand.y) * g))
                    ctx.stroke(web, with: .color(Color.white.opacity(0.9)), lineWidth: 1.4)
                }
            } else {
                let q = seg(p, 0.74, 1)
                let u = easeIn(q)
                let s0 = CGPoint(x: w * 0.5, y: ground - Hc * 0.35), c1 = CGPoint(x: w * 0.8, y: ground * 0.85), e = CGPoint(x: w * 1.4, y: -h * 0.3)
                let a = 1 - CGFloat(u), b = CGFloat(u)
                let pos = CGPoint(x: a * a * s0.x + 2 * a * b * c1.x + b * b * e.x, y: a * a * s0.y + 2 * a * b * c1.y + b * b * e.y)
                let u2 = min(1, u + 0.02), a2 = 1 - CGFloat(u2), b2 = CGFloat(u2)
                let nx = CGPoint(x: a2 * a2 * s0.x + 2 * a2 * b2 * c1.x + b2 * b2 * e.x, y: a2 * a2 * s0.y + 2 * a2 * b2 * c1.y + b2 * b2 * e.y)
                let heading = atan2(Double(nx.y - pos.y), Double(max(0.001, nx.x - pos.x)))
                var web = Path(); web.move(to: CGPoint(x: pos.x + 10, y: pos.y - figW * 0.1)); web.addLine(to: CGPoint(x: w * 0.8, y: -h * 0.12))
                ctx.stroke(web, with: .color(Color.white.opacity(0.9)), lineWidth: 1.3)
                let blend = seg(q, 0.28, 0.32)   // tucked as he springs off, then stretched out
                sprite(ctx, "swing2", at: pos, height: figW * 1.05 * 0.63, pivotFrac: CGPoint(x: 0.5, y: 0.5), rotation: heading * 0.5, opacity: 1 - blend, flip: true)
                faced(ctx, "swing1", at: pos, fit: figW * 1.05, rotation: heading * 0.75, opacity: blend)
            }

        case .reactor:
            let H = min(h * 0.44, w * 0.95 / 0.6)
            let arrive = seg(p, 0.02, 0.24), look = seg(p, 0.24, 0.42), away = seg(p, 0.74, 0.97)
            let cx = w * 0.5
            var y = -H * 0.2 + (ground + H * 0.2) * CGFloat(easeOut(arrive))
            if p < 0.02 { y = -H * 1.2 }
            var rot = -0.12 * easeInOut(look)
            if p >= 0.74 { y = ground - (ground + H * 1.4) * CGFloat(easeIn(away)); rot = -0.12 * (1 - easeIn(away)) }
            // repulsor glow under his feet and the landing ring
            glowDisc(ctx, at: CGPoint(x: cx, y: y), radius: H * 0.5, color: t.accent, alpha: p < 0.3 ? 0.5 * (1 - seg(p, 0.2, 0.3)) : (p >= 0.74 ? 0.7 : 0))
            if p >= 0.2 && p < 0.38 {
                let k = seg(p, 0.2, 0.38)
                let r = CGFloat(easeOut(k)) * w * 0.55
                ctx.stroke(Path(ellipseIn: CGRect(x: cx - r, y: ground - r * 0.16, width: r * 2, height: r * 0.32)), with: .color(t.accent.opacity(0.8 * (1 - k))), lineWidth: 3)
            }
            if p >= 0.74 && away < -1 {
                // thrusters and a sonic ring
                let fl = H * 0.55 * CGFloat(0.8 + 0.2 * sin(p * 80))
                var flame = Path()
                flame.move(to: CGPoint(x: cx - H * 0.12, y: y)); flame.addQuadCurve(to: CGPoint(x: cx, y: y + fl), control: CGPoint(x: cx - H * 0.1, y: y + fl * 0.5))
                flame.addQuadCurve(to: CGPoint(x: cx + H * 0.12, y: y), control: CGPoint(x: cx + H * 0.1, y: y + fl * 0.5)); flame.closeSubpath()
                ctx.fill(flame, with: .linearGradient(Gradient(colors: [Color.white, t.accent, t.accent2.opacity(0)]), startPoint: CGPoint(x: cx, y: y), endPoint: CGPoint(x: cx, y: y + fl)))
                for k in 0..<7 {
                    let x = cx + (CGFloat(k) - 3) * H * 0.12
                    var l = Path(); l.move(to: CGPoint(x: x, y: y + H * 0.2)); l.addLine(to: CGPoint(x: x, y: y + H * (0.7 + 0.5 * CGFloat(hash(Double(k))))))
                    ctx.stroke(l, with: .color(t.accent.opacity(0.35)), lineWidth: 1.2)
                }
            }
            let flyBlend = p >= 0.77 ? seg(p, 0.77, 0.795) : 0
            if flyBlend < 1 { sprite(ctx, "ironfig", at: CGPoint(x: cx, y: y), height: H, rotation: rot, opacity: 1 - flyBlend) }
            if flyBlend > 0 {
                // he straightens into the flying pose, jets blazing, and climbs straight up out of the screen
                sprite(ctx, "ironup", at: CGPoint(x: cx, y: y), height: H * 2.08, pivotFrac: CGPoint(x: 0.5, y: 0.48), opacity: flyBlend)
            }
            // lock-on reticles while he looks up, then missiles streaking to them
            let targets: [CGPoint] = (0..<8).map { i in CGPoint(x: w * (0.1 + 0.8 * CGFloat(i) / 7), y: h * (0.1 + 0.26 * CGFloat(hash(Double(i) * 3.3)))) }
            if p >= 0.3 && p < 0.74 {
                let appear = seg(p, 0.3, 0.42)
                for (i, tg) in targets.enumerated() {
                    let hit = 0.42 + Double(i) * 0.03 + 0.2
                    if p > hit { continue }
                    let r = 16 * CGFloat(easeOut(appear)) * (1 + 0.1 * CGFloat(sin(p * 30 + Double(i))))
                    ctx.stroke(Path(ellipseIn: CGRect(x: tg.x - r, y: tg.y - r, width: r * 2, height: r * 2)), with: .color(t.accent.opacity(0.9)), lineWidth: 1.4)
                    var cross = Path()
                    cross.move(to: CGPoint(x: tg.x - r - 5, y: tg.y)); cross.addLine(to: CGPoint(x: tg.x - r + 4, y: tg.y))
                    cross.move(to: CGPoint(x: tg.x + r - 4, y: tg.y)); cross.addLine(to: CGPoint(x: tg.x + r + 5, y: tg.y))
                    cross.move(to: CGPoint(x: tg.x, y: tg.y - r - 5)); cross.addLine(to: CGPoint(x: tg.x, y: tg.y - r + 4))
                    cross.move(to: CGPoint(x: tg.x, y: tg.y + r - 4)); cross.addLine(to: CGPoint(x: tg.x, y: tg.y + r + 5))
                    ctx.stroke(cross, with: .color(t.accent.opacity(0.9)), lineWidth: 1.2)
                }
            }
            for (i, tg) in targets.enumerated() {
                let launch = 0.42 + Double(i) * 0.03
                let q = (p - launch) / 0.2
                let origin = CGPoint(x: cx + (i % 2 == 0 ? -1 : 1) * H * 0.2, y: ground - H * 0.64)
                let ctrl = CGPoint(x: origin.x + (tg.x - origin.x) * 0.25, y: origin.y - h * 0.32)
                missile(ctx, path: { u in
                    let a = 1 - CGFloat(u), b = CGFloat(u)
                    return CGPoint(x: a * a * origin.x + 2 * a * b * ctrl.x + b * b * tg.x, y: a * a * origin.y + 2 * a * b * ctrl.y + b * b * tg.y)
                }, q: q, color: t.accent, scale: max(1, h / 700))
                if q >= 1 && q < 1.4 {
                    let k = (q - 1) / 0.4
                    glowDisc(ctx, at: tg, radius: 14 + 60 * CGFloat(easeOut(k)), color: Color(hex: 0xFFB347), alpha: 0.9 * (1 - k))
                    ctx.stroke(Path(ellipseIn: CGRect(x: tg.x - 70 * CGFloat(k), y: tg.y - 70 * CGFloat(k), width: 140 * CGFloat(k), height: 140 * CGFloat(k))), with: .color(Color.white.opacity(0.7 * (1 - k))), lineWidth: 2)
                }
            }

        case .bat:
            // the signal, a swarm of bats, and the Batmobile racing underneath
            let sc = CGPoint(x: w * 0.5, y: h * 0.24), sr = min(w, h) * 0.3
            let on = seg(p, 0.04, 0.2) * (1 - seg(p, 0.86, 1))
            if on > 0 {
                var beam = Path()
                beam.move(to: CGPoint(x: w * 0.42, y: h)); beam.addLine(to: CGPoint(x: w * 0.58, y: h))
                beam.addLine(to: CGPoint(x: sc.x + sr * 0.8, y: sc.y)); beam.addLine(to: CGPoint(x: sc.x - sr * 0.8, y: sc.y)); beam.closeSubpath()
                ctx.fill(beam, with: .linearGradient(Gradient(colors: [t.accent.opacity(0), t.accent.opacity(0.3 * on)]), startPoint: CGPoint(x: w * 0.5, y: h), endPoint: sc))
                glowDisc(ctx, at: sc, radius: sr, color: t.accent, alpha: 0.6 * on)
                pictureT(ctx, "batoval", center: sc, fit: sr * 1.5, opacity: on)
            }
            for i in 0..<16 {
                let f = Double(i)
                let q = seg(p, 0.12 + 0.012 * f, 0.62 + 0.012 * f)
                guard q > 0, q < 1 else { continue }
                let x = -w * 0.1 + CGFloat(q) * w * 1.2
                let y = h * (0.9 - 0.75 * CGFloat(q)) + CGFloat(sin(q * 10 + f)) * h * 0.06 + CGFloat(hash(f)) * h * 0.1
                pictureT(ctx, "bat", center: CGPoint(x: x, y: y), fit: 30 + 22 * CGFloat(hash(f + 3)), opacity: 0.95, scaleY: CGFloat(0.5 + 0.5 * abs(sin(p * 60 + f))))
            }
            if ThemeArtImages.get("batmobile") != nil {
                let q = seg(p, 0.34, 0.9)
                let fit = min(w * 0.75, 300)
                let x = -fit + CGFloat(q) * (w + fit * 2)
                batmobileAt(ctx, theme: t, x: x, roadY: h * 0.9, fit: fit, time: p * 8)
            }

        case .matrix:
            let chars = Array("01アウ7Z3")
            let cols = max(10, Int(w / 18))
            for col in 0..<cols {
                let speed = 0.9 + 1.4 * hash(Double(col))
                let start = 0.02 + 0.3 * hash(Double(col) + 4)
                let loop = Double(h + 300)
                if p < start { continue }
                let head = CGFloat(((p - start) * speed * 1.3 * loop).truncatingRemainder(dividingBy: loop)) - 40
                for r in 0..<16 {
                    let y = head - CGFloat(r) * 17
                    if y < -10 || y > h + 10 { continue }
                    let ch = String(chars[Int(hash(Double(col * 31 + r) + floor(p * 18)) * Double(chars.count)) % chars.count])
                    let a = (r == 0 ? 1.0 : 0.7 * (1 - Double(r) / 16)) * (1 - seg(p, 0.88, 1))
                    ctx.draw(Text(ch).font(.system(size: 14, weight: r == 0 ? .bold : .regular, design: .monospaced)).foregroundStyle(r == 0 ? Color.white.opacity(a) : t.accent.opacity(a)),
                             at: CGPoint(x: 10 + CGFloat(col) * 18, y: y))
                }
            }

        case .arch:
            // a constellation draws a bridge between a phone and a Mac, a pulse crosses it, then it blooms
            let L = CGPoint(x: w * 0.14, y: h * 0.56), R = CGPoint(x: w * 0.86, y: h * 0.56), C = CGPoint(x: w * 0.5, y: h * 0.06)
            func q(_ s: Double) -> CGPoint {
                let a = CGFloat(1 - s), b = CGFloat(s)
                return CGPoint(x: a * a * L.x + 2 * a * b * C.x + b * b * R.x, y: a * a * L.y + 2 * a * b * C.y + b * b * R.y)
            }
            for i in 0..<70 {
                let f = Double(i)
                let tw = 0.3 + 0.7 * abs(sin(p * 14 + hash(f + 20) * 6))
                let r = 0.6 + hash(f + 30) * 1.2
                let pt = CGPoint(x: CGFloat(hash(f)) * w, y: CGFloat(hash(f + 10)) * h)
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)), with: .color(Color.white.opacity(0.5 * tw * seg(p, 0, 0.2) * (1 - seg(p, 0.9, 1)))))
            }
            let n = 15
            var prev: CGPoint?
            for i in 0..<n {
                let s = Double(i) / Double(n - 1)
                let appear = seg(p, 0.08 + 0.4 * s, 0.14 + 0.4 * s)
                guard appear > 0 else { break }
                let pt = q(s)
                if let pv = prev {
                    var line = Path(); line.move(to: pv); line.addLine(to: CGPoint(x: pv.x + (pt.x - pv.x) * CGFloat(appear), y: pv.y + (pt.y - pv.y) * CGFloat(appear)))
                    ctx.stroke(line, with: .color(Color.white.opacity(0.4 * (1 - seg(p, 0.92, 1)))), lineWidth: 1.2)
                }
                let r = (2.2 + 4 * (1 - appear)) * (1 + CGFloat(sin(p * 20 + Double(i))) * 0.1)
                glowDisc(ctx, at: pt, radius: r * 4, color: .white, alpha: 0.25 * appear)
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)), with: .color(Color.white.opacity(0.95 * appear * (1 - seg(p, 0.92, 1)))))
                prev = pt
            }
            for (i, pt) in [L, R].enumerated() {
                let a = seg(p, 0.3 + Double(i) * 0.1, 0.45 + Double(i) * 0.1) * (1 - seg(p, 0.92, 1))
                guard a > 0 else { continue }
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - 16, y: pt.y - 16, width: 32, height: 32)), with: .color(Color.black.opacity(0.8 * a)))
                ctx.stroke(Path(ellipseIn: CGRect(x: pt.x - 16, y: pt.y - 16, width: 32, height: 32)), with: .color(Color.white.opacity(0.8 * a)), lineWidth: 1.2)
                var icon = Path()
                if i == 0 { icon.addRoundedRect(in: CGRect(x: pt.x - 4.5, y: pt.y - 8, width: 9, height: 16), cornerSize: CGSize(width: 2, height: 2)) }
                else { icon.addRect(CGRect(x: pt.x - 7.5, y: pt.y - 6, width: 15, height: 9.5)); icon.move(to: CGPoint(x: pt.x - 10, y: pt.y + 6.5)); icon.addLine(to: CGPoint(x: pt.x + 10, y: pt.y + 6.5)) }
                ctx.stroke(icon, with: .color(Color.white.opacity(a)), style: StrokeStyle(lineWidth: 1.3, lineCap: .round, lineJoin: .round))
            }
            // the pulse
            let pulse = seg(p, 0.56, 0.84)
            if pulse > 0 && pulse < 1 {
                for tr in 0..<12 {
                    let pt = q(max(0, pulse - Double(tr) * 0.012))
                    glowDisc(ctx, at: pt, radius: (16 - CGFloat(tr)) * 1.4, color: .white, alpha: 0.5 * (1 - Double(tr) / 12))
                }
            }
            if p > 0.82 {
                let k = seg(p, 0.82, 1)
                let r = CGFloat(easeOut(k)) * w * 0.6
                ctx.stroke(Path(ellipseIn: CGRect(x: w / 2 - r, y: h * 0.4 - r, width: r * 2, height: r * 2)), with: .color(Color.white.opacity(0.4 * (1 - k))), lineWidth: 2)
                glowDisc(ctx, at: CGPoint(x: w / 2, y: h * 0.4), radius: w * 0.5, color: .white, alpha: 0.14 * (1 - k))
            }
        }
    }

    /// The Batmobile at a given x with headlights, for the sequences (the banner has its own, time-looped, version).
    static func batmobileAt(_ ctx: GraphicsContext, theme t: AppTheme, x: CGFloat, roadY: CGFloat, fit: CGFloat, time: Double) {
        guard ThemeArtImages.get("batmobile") != nil else { return }
        var cone = Path()
        let nose = CGPoint(x: x + fit * 0.42, y: roadY - fit * 0.2)
        cone.move(to: nose); cone.addLine(to: CGPoint(x: nose.x + 220, y: nose.y - 46)); cone.addLine(to: CGPoint(x: nose.x + 220, y: nose.y + 34)); cone.closeSubpath()
        ctx.fill(cone, with: .linearGradient(Gradient(colors: [t.accent.opacity(0.4), .clear]), startPoint: nose, endPoint: CGPoint(x: nose.x + 220, y: nose.y)))
        for i in 0..<8 {
            let sx = x - fit * 0.5 - CGFloat(i) * 26
            let sy = roadY - 6 - CGFloat(hash(Double(i) + 5)) * fit * 0.3
            var l = Path(); l.move(to: CGPoint(x: sx, y: sy)); l.addLine(to: CGPoint(x: sx - 30 - CGFloat(hash(Double(i))) * 36, y: sy))
            ctx.stroke(l, with: .color(Color.white.opacity(0.18)), lineWidth: 1.2)
        }
        pictureT(ctx, "batmobile", center: CGPoint(x: x, y: roadY - fit * 0.2 + CGFloat(sin(time * 20)) * 0.8), fit: fit)
    }
}
