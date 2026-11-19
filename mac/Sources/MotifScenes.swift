import SwiftUI

private func hash(_ x: Double) -> Double {
    let v = sin(x * 12.9898) * 43758.5453
    return v - floor(v)
}

/// The animated banner at the top of the home screen: one small scene per theme.
extension MotifArt {
    /// Draws a picture to fill `rect` (cropped like "cover"), centred on the `focus` point of the picture.
    static func cover(_ ctx: GraphicsContext, _ name: String, in rect: CGRect, zoom: CGFloat = 1, focus: CGPoint = CGPoint(x: 0.5, y: 0.5), opacity: Double = 1) {
        guard let ns = ThemeArtImages.get(name) else { return }
        let iw = ns.size.width, ih = ns.size.height
        let s = max(rect.width / iw, rect.height / ih) * zoom
        let dw = iw * s, dh = ih * s
        var x = rect.midX - focus.x * dw
        var y = rect.midY - focus.y * dh
        x = min(rect.minX, max(rect.maxX - dw, x))
        y = min(rect.minY, max(rect.maxY - dh, y))
        var g = ctx
        g.clip(to: Path(rect))
        g.opacity = opacity
        g.draw(Image(nsImage: ns), in: CGRect(x: x, y: y, width: dw, height: dh))
    }

    static func scene(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, time: Double) {
        let w = size.width, h = size.height
        let rect = CGRect(origin: .zero, size: size)
        ctx.fill(Path(rect), with: .color(t.bg))

        switch t.motif {
        case .arch:
            // Night sky with slow light, and a bridge between a phone and a Mac with little packets travelling over it.
            ctx.fill(Path(rect), with: .radialGradient(Gradient(colors: [Color.white.opacity(0.08), .clear]),
                                                       center: CGPoint(x: w * (0.3 + 0.12 * CGFloat(sin(time * 0.2))), y: h * 0.35), startRadius: 0, endRadius: w * 0.45))
            ctx.fill(Path(rect), with: .radialGradient(Gradient(colors: [Color(white: 0.7).opacity(0.06), .clear]),
                                                       center: CGPoint(x: w * (0.72 + 0.1 * CGFloat(cos(time * 0.17))), y: h * 0.6), startRadius: 0, endRadius: w * 0.4))
            for i in 0..<46 {
                let f = Double(i)
                let tw = 0.3 + 0.7 * abs(sin(time * 0.8 + hash(f + 200) * 6))
                let r = 0.5 + hash(f + 300) * 0.9
                let p = CGPoint(x: CGFloat(hash(f)) * w, y: CGFloat(hash(f + 100)) * h * 0.75)
                ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(.white.opacity(0.18 * tw)))
            }
            let L = CGPoint(x: w * 0.16, y: h * 0.5), R = CGPoint(x: w * 0.84, y: h * 0.5), C = CGPoint(x: w * 0.5, y: -h * 0.4)
            var arc = Path(); arc.move(to: L); arc.addQuadCurve(to: R, control: C)
            ctx.stroke(arc, with: .color(.white.opacity(0.26)), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
            func q(_ s: Double) -> CGPoint {
                let a = CGFloat(1 - s), b = CGFloat(s)
                return CGPoint(x: a * a * L.x + 2 * a * b * C.x + b * b * R.x, y: a * a * L.y + 2 * a * b * C.y + b * b * R.y)
            }
            for k in 0..<6 {
                let dir: Double = k % 2 == 0 ? 1 : -1
                let ph = (time * 0.2 + Double(k) / 6).truncatingRemainder(dividingBy: 1)
                let edge = sin(.pi * ph)
                for tr in 0..<9 {
                    let s = min(1, max(0, (dir > 0 ? ph : 1 - ph) - dir * Double(tr) * 0.013))
                    let p = q(s)
                    let r = max(0.6, 2.8 - 0.28 * Double(tr))
                    ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(.white.opacity((1 - Double(tr) / 9) * 0.85 * edge)))
                }
            }
            for (i, p) in [L, R].enumerated() {
                let frac = (time * 0.5 + Double(i) * 0.5).truncatingRemainder(dividingBy: 1)
                let rr = 13 + 12 * CGFloat(frac)
                ctx.stroke(Path(ellipseIn: CGRect(x: p.x - rr, y: p.y - rr, width: rr * 2, height: rr * 2)), with: .color(.white.opacity((1 - frac) * 0.3)), lineWidth: 1)
                ctx.fill(Path(ellipseIn: CGRect(x: p.x - 13, y: p.y - 13, width: 26, height: 26)), with: .color(t.bg))
                ctx.stroke(Path(ellipseIn: CGRect(x: p.x - 13, y: p.y - 13, width: 26, height: 26)), with: .color(.white.opacity(0.55)), lineWidth: 1)
                var icon = Path()
                if i == 0 { icon.addRoundedRect(in: CGRect(x: p.x - 3.5, y: p.y - 6.5, width: 7, height: 13), cornerSize: CGSize(width: 1.8, height: 1.8)) }
                else {
                    icon.addRect(CGRect(x: p.x - 6, y: p.y - 5, width: 12, height: 7.5))
                    icon.move(to: CGPoint(x: p.x - 8, y: p.y + 5)); icon.addLine(to: CGPoint(x: p.x + 8, y: p.y + 5))
                }
                ctx.stroke(icon, with: .color(.white.opacity(0.9)), style: StrokeStyle(lineWidth: 1.2, lineCap: .round, lineJoin: .round))
            }

        case .bat:
            // The signal in the clouds, swaying a little, with bats crossing and the Batmobile rolling along the bottom.
            ctx.fill(Path(rect), with: .linearGradient(Gradient(colors: [t.bg, Color(hex: 0x16130A)]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
            for i in 0..<3 {
                let f = Double(i)
                let cx = (CGFloat(time) * (6 + 3 * CGFloat(f)) + CGFloat(f) * w * 0.4).truncatingRemainder(dividingBy: w * 1.4) - w * 0.2
                ctx.fill(Path(ellipseIn: CGRect(x: cx - 90, y: h * (0.12 + 0.2 * CGFloat(f)) - 20, width: 180, height: 44)),
                         with: .radialGradient(Gradient(colors: [Color.white.opacity(0.05), .clear]), center: CGPoint(x: cx, y: h * (0.12 + 0.2 * CGFloat(f))), startRadius: 0, endRadius: 90))
            }
            let sc = CGPoint(x: w * 0.72, y: h * 0.38), sr = h * 0.36
            let sway = CGFloat(sin(time * 0.6)) * 8
            let base = CGPoint(x: w * 0.22, y: h + 4)
            var beam = Path()
            beam.move(to: CGPoint(x: base.x - 12, y: base.y)); beam.addLine(to: CGPoint(x: base.x + 12, y: base.y))
            beam.addLine(to: CGPoint(x: sc.x + sr * 0.85 + sway, y: sc.y)); beam.addLine(to: CGPoint(x: sc.x - sr * 0.85 + sway, y: sc.y)); beam.closeSubpath()
            ctx.fill(beam, with: .linearGradient(Gradient(colors: [t.accent.opacity(0.0), t.accent.opacity(0.24)]), startPoint: base, endPoint: sc))
            let pulse = 0.5 + 0.5 * sin(time * 1.4)
            ctx.fill(Path(ellipseIn: CGRect(x: sc.x - sr + sway, y: sc.y - sr, width: sr * 2, height: sr * 2)),
                     with: .radialGradient(Gradient(colors: [t.accent.opacity(0.5 + 0.2 * pulse), t.accent.opacity(0.1)]), center: CGPoint(x: sc.x + sway, y: sc.y), startRadius: 0, endRadius: sr))
            pictureT(ctx, "batoval", center: CGPoint(x: sc.x + sway, y: sc.y), fit: sr * 1.6, opacity: 0.95)
            for i in 0..<5 {
                let f = Double(i)
                let travel = (time * (34 + 9 * f) + f * 150).truncatingRemainder(dividingBy: Double(w + 100))
                let p = CGPoint(x: w + 30 - CGFloat(travel), y: h * (0.16 + 0.13 * CGFloat(i)) + 8 * CGFloat(sin(time * 2 + f)))
                pictureT(ctx, "bat", center: p, fit: 26 + CGFloat(i % 2) * 6, opacity: 0.8, scaleY: CGFloat(0.62 + 0.38 * abs(sin(time * 9 + f * 1.7))))
            }
            batmobile(ctx, size: size, theme: t, time: time, roadY: h - 8, fit: min(150, w * 0.34))

        case .spider:
            // Spider-Man himself swinging across the banner between the buildings: a real pendulum, let go, flight, new web.
            ctx.fill(Path(rect), with: .linearGradient(Gradient(colors: [Color(hex: 0x2C0508), t.bg]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
            ctx.fill(Path(rect), with: .radialGradient(Gradient(colors: [Color(hex: 0x7A1018).opacity(0.35), .clear]), center: CGPoint(x: w * 0.8, y: h * 0.2), startRadius: 0, endRadius: w * 0.5))
            city(ctx, size: size, time: time, speed: 7, baseline: h, color: Color(hex: 0x1A0508), seed: 3, heights: 60...125)
            city(ctx, size: size, time: time, speed: 16, baseline: h, color: Color(hex: 0x0A0204), seed: 21, heights: 28...78, windows: t.accent2)
            pictureT(ctx, "web", center: CGPoint(x: w - 62, y: 52), fit: 112, opacity: 0.35)
            swing(ctx, size: size, theme: t, time: time, ref: h)

        case .reactor:
            // Iron Man in the dark: reactor glow behind him, HUD ticks, a beam from the arc reactor, and a missile now and then.
            ctx.fill(Path(rect), with: .linearGradient(Gradient(colors: [Color(hex: 0x1D0D0A), t.bg]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
            let pulse = 0.5 + 0.5 * sin(time * 2.2)
            let fc = CGPoint(x: w * 0.74, y: h * 0.5)
            glowDisc(ctx, at: fc, radius: h * 0.9, color: t.accent, alpha: 0.12 + 0.1 * pulse)
            var grid = Path()
            for i in 1..<8 { let y = h * CGFloat(i) / 8; grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: w, y: y)) }
            ctx.stroke(grid, with: .color(t.accent.opacity(0.05)), lineWidth: 0.6)
            let rc = CGPoint(x: w * 0.14, y: h * 0.66)
            let cyc = time.truncatingRemainder(dividingBy: 3.2)
            let fire = cyc < 0.55 ? 1 - cyc / 0.55 : 0
            glowDisc(ctx, at: rc, radius: 62, color: t.accent, alpha: 0.25 + 0.4 * fire)
            pictureT(ctx, "reactor", center: rc, fit: 58, opacity: 0.95, rotation: time * 0.8)
            sprite(ctx, "ironfig", at: CGPoint(x: w * 0.76, y: h * 1.0), height: h * 0.98, rotation: 0.015 * sin(time * 0.8))
            if fire > 0 {
                let target = CGPoint(x: w * 0.4, y: -h * 0.1)
                var beam = Path(); beam.move(to: rc); beam.addLine(to: target)
                ctx.stroke(beam, with: .color(t.accent.opacity(0.5 * fire)), style: StrokeStyle(lineWidth: 12 * fire, lineCap: .round))
                ctx.stroke(beam, with: .color(Color.white.opacity(0.95 * fire)), style: StrokeStyle(lineWidth: 3 * fire, lineCap: .round))
                let rr = 18 + 56 * (1 - fire)
                ctx.stroke(Path(ellipseIn: CGRect(x: rc.x - rr, y: rc.y - rr, width: rr * 2, height: rr * 2)), with: .color(t.accent.opacity(0.7 * fire)), lineWidth: 2)
            }
            // a missile off his shoulder every few seconds
            let mc = (time + 1.6).truncatingRemainder(dividingBy: 4.4)
            if mc < 1.1 {
                let q = mc / 1.1
                let o = CGPoint(x: w * 0.66, y: h * 0.42), tg = CGPoint(x: w * 0.12, y: h * 0.12), c1 = CGPoint(x: w * 0.5, y: -h * 0.1)
                missile(ctx, path: { u in
                    let a = 1 - CGFloat(u), b = CGFloat(u)
                    return CGPoint(x: a * a * o.x + 2 * a * b * c1.x + b * b * tg.x, y: a * a * o.y + 2 * a * b * c1.y + b * b * tg.y)
                }, q: q, color: t.accent, scale: 1)
            } else if mc < 1.35 {
                let k = (mc - 1.1) / 0.25
                glowDisc(ctx, at: CGPoint(x: w * 0.12, y: h * 0.12), radius: 10 + 40 * CGFloat(k), color: Color(hex: 0xFFB347), alpha: 0.9 * (1 - k))
            }

        case .thor:
            // Storm clouds and lightning; the hammer is thrown, spins across and slaps into an open hand of light.
            for i in 0..<3 {
                let f = Double(i)
                let c = CGPoint(x: w * (0.2 + 0.3 * CGFloat(i)) + 20 * CGFloat(sin(time * 0.3 + f)), y: h * 0.25)
                ctx.fill(Path(rect), with: .radialGradient(Gradient(colors: [t.accent2.opacity(0.10), .clear]), center: c, startRadius: 0, endRadius: w * 0.35))
            }
            let period = 4.4
            let p = time.truncatingRemainder(dividingBy: period) / period
            let catchP = CGPoint(x: w * 0.74, y: h * 0.44)
            let startP = CGPoint(x: -30, y: h * 0.92)
            var hand = Path()
            hand.addEllipse(in: CGRect(x: catchP.x - 24, y: catchP.y - 24, width: 48, height: 48))
            ctx.stroke(hand, with: .color(t.accent.opacity(0.25 + 0.15 * sin(time * 3))), lineWidth: 1.2)
            if p < 0.55 {
                let q = p / 0.55
                let e = 1 - pow(1 - q, 2)
                let pos = CGPoint(x: startP.x + (catchP.x - startP.x) * CGFloat(e), y: startP.y + (catchP.y - startP.y) * CGFloat(e) - CGFloat(sin(.pi * q)) * h * 0.3)
                for k in 1...5 {
                    let qq = max(0, q - Double(k) * 0.03)
                    let ee = 1 - pow(1 - qq, 2)
                    let tp = CGPoint(x: startP.x + (catchP.x - startP.x) * CGFloat(ee), y: startP.y + (catchP.y - startP.y) * CGFloat(ee) - CGFloat(sin(.pi * qq)) * h * 0.3)
                    ctx.fill(Path(ellipseIn: CGRect(x: tp.x - 4, y: tp.y - 4, width: 8, height: 8)), with: .color(t.accent.opacity(0.3 * (1 - Double(k) / 6))))
                }
                pictureT(ctx, "hammer", center: pos, fit: 56, opacity: 1, rotation: q * 18)
            } else {
                let q = (p - 0.55) / 0.45
                let flash = max(0, 1 - q * 3)
                ctx.fill(Path(ellipseIn: CGRect(x: catchP.x - 90, y: catchP.y - 90, width: 180, height: 180)),
                         with: .radialGradient(Gradient(colors: [t.accent.opacity(0.55 * flash), .clear]), center: catchP, startRadius: 0, endRadius: 90))
                if flash > 0 {
                    let rr = 20 + 90 * (1 - flash)
                    ctx.stroke(Path(ellipseIn: CGRect(x: catchP.x - rr, y: catchP.y - rr, width: rr * 2, height: rr * 2)), with: .color(Color.white.opacity(0.8 * flash)), lineWidth: 2)
                    for k in 0..<4 {
                        let a = Double(k) * (.pi / 2) + 0.5
                        let bolt = lightning(from: catchP, to: CGPoint(x: catchP.x + CGFloat(cos(a)) * 120, y: catchP.y + CGFloat(sin(a)) * 80), seed: Double(k) * 3 + floor(time / period), jitter: 10)
                        ctx.stroke(bolt, with: .color(Color.white.opacity(0.9 * flash)), lineWidth: 1.5)
                        ctx.stroke(bolt, with: .color(t.accent.opacity(0.6 * flash)), lineWidth: 5)
                    }
                }
                pictureT(ctx, "hammer", center: catchP, fit: 56, opacity: max(0, 1 - max(0, q - 0.55) * 2.2), rotation: 0.45)
            }

        case .matrix:
            let chars = Array("01アウ7Z3")
            let cols = max(8, Int(w / 18))
            for col in 0..<cols {
                let speed = 40 + 50 * hash(Double(col))
                let head = (time * speed + hash(Double(col) + 3) * 400).truncatingRemainder(dividingBy: Double(h) + 160) - 20
                for r in 0..<12 {
                    let y = head - Double(r) * 15
                    if y < -8 || y > Double(h) + 8 { continue }
                    let ch = String(chars[Int(hash(Double(col * 31 + r) + floor(time * 5)) * Double(chars.count)) % chars.count])
                    let alpha = r == 0 ? 0.9 : 0.55 * (1 - Double(r) / 12)
                    ctx.draw(Text(ch).font(.system(size: 12, design: .monospaced)).foregroundStyle(r == 0 ? Color.white.opacity(alpha) : t.accent.opacity(alpha)),
                             at: CGPoint(x: 10 + CGFloat(col) * 18, y: CGFloat(y)))
                }
            }
        }
    }
}

/// The banner itself: a rounded card showing the theme's scene.
struct ThemeHero: View {
    let theme: AppTheme
    var height: CGFloat = 190

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
            Canvas { ctx, size in MotifArt.scene(ctx, size: size, theme: theme, time: tl.date.timeIntervalSinceReferenceDate) }
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.white.opacity(0.1)))
    }
}
