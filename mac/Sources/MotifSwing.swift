import SwiftUI

private func hash(_ x: Double) -> Double {
    let v = sin(x * 12.9898) * 43758.5453
    return v - floor(v)
}

/// The real Spider-Man cut-out swinging across the screen, and the Batmobile driving along the bottom.
extension MotifArt {
    /// A band of building silhouettes that slides past, with a few lit windows.
    static func city(_ ctx: GraphicsContext, size: CGSize, time: Double, speed: Double, baseline: CGFloat, color: Color,
                     seed: Double, heights: ClosedRange<CGFloat>, windows: Color? = nil) {
        let period: CGFloat = 520
        var blocks: [(CGFloat, CGFloat, CGFloat)] = []
        var x: CGFloat = 0
        var i = 0.0
        while x < period {
            let bw = 28 + CGFloat(hash(seed + i)) * 44
            let bh = heights.lowerBound + CGFloat(hash(seed + i + 50)) * (heights.upperBound - heights.lowerBound)
            blocks.append((x, bw, bh))
            x += bw + 2
            i += 1
        }
        let off = CGFloat((time * speed).truncatingRemainder(dividingBy: Double(period)))
        for rep in -1..<(Int(size.width / period) + 2) {
            for (n, b) in blocks.enumerated() {
                let rx = b.0 - off + CGFloat(rep) * period
                if rx > size.width || rx + b.1 < 0 { continue }
                ctx.fill(Path(CGRect(x: rx, y: baseline - b.2, width: b.1, height: b.2)), with: .color(color))
                if let lit = windows {
                    for k in 0..<3 {
                        let wx = rx + 5 + CGFloat(k) * 11
                        let wy = baseline - b.2 + 8 + CGFloat(hash(Double(n * 7 + k) + seed)) * (b.2 - 18)
                        let tw = 0.25 + 0.5 * abs(sin(time * 0.7 + Double(n + k)))
                        ctx.fill(Path(CGRect(x: wx, y: wy, width: 3, height: 4)), with: .color(lit.opacity(0.4 * tw)))
                    }
                }
            }
        }
    }

    /// Spider-Man on a web. Each swing is a real pendulum (the angle follows a cosine, so he hangs back, drops through the
    /// bottom fast and rises on the far side); he lets go on the way up, flies a short arc, shoots a new web at a point further on,
    /// and swings again. He faces along his actual direction of travel. `ref` is the height the swing is sized to.
    static func swing(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, time: Double, ref: CGFloat, top: CGFloat = 0) {
        guard ThemeArtImages.get("swing1") != nil else { return }
        let w = size.width
        let L = ref * 0.86
        let theta = 0.9, u0 = 0.2, u1 = 0.85
        let thS = -theta * cos(.pi * u0), thE = -theta * cos(.pi * u1)
        let gap = ref * 0.32
        let span = L * CGFloat(sin(thE) - sin(thS))
        let D = span + gap
        let margin = ref * 0.55
        let nWrap = max(2, Int(ceil((w + 2 * margin) / D)))
        let swingT = 1.15, flightT = 0.32, segT = swingT + flightT
        let tt = time.truncatingRemainder(dividingBy: segT * Double(nWrap))
        let n = Int(tt / segT)
        let local = tt - Double(n) * segT
        let anchorY = top - ref * 0.18

        func anchor(_ k: Int) -> CGPoint { CGPoint(x: -margin - L * CGFloat(sin(thS)) + CGFloat(k) * D, y: anchorY) }
        func onRope(_ k: Int, _ u: Double) -> CGPoint {
            let th = -theta * cos(.pi * u)
            let a = anchor(k)
            return CGPoint(x: a.x + L * CGFloat(sin(th)), y: a.y + L * CGFloat(cos(th)))
        }
        func position(_ time: Double) -> (CGPoint, Int, Double, Bool) {
            let tt2 = time.truncatingRemainder(dividingBy: segT * Double(nWrap))
            let k = Int(tt2 / segT)
            let loc = tt2 - Double(k) * segT
            if loc < swingT { return (onRope(k, u0 + (u1 - u0) * loc / swingT), k, loc, true) }
            let s = (loc - swingT) / flightT
            let e = onRope(k, u1), st = onRope(k + 1, u0)
            let lift = ref * 0.2 * CGFloat(4 * s * (1 - s))
            return (CGPoint(x: e.x + (st.x - e.x) * CGFloat(s), y: e.y + (st.y - e.y) * CGFloat(s) - lift), k, loc, false)
        }

        let (pos, segIndex, _, onWeb) = position(tt)
        let poseName = (segIndex % 2 == 0 || ThemeArtImages.get("swingA") == nil) ? "swing1" : "swingA"
        guard let figure = ThemeArtImages.get(poseName) else { return }
        let hands: ((CGFloat, CGFloat), (CGFloat, CGFloat)) = poseName == "swing1" ? ((0.03, 0.42), (0.92, 0.05)) : ((0.12, 0.08), (0.56, 0.1))
        let (ahead, _, _, _) = position(tt + 0.03)
        let vel = CGPoint(x: ahead.x - pos.x, y: ahead.y - pos.y)
        let heading = atan2(Double(vel.y), Double(max(0.001, vel.x)))
        let rot = heading * 0.75
        let fit = ref * 0.7
        let figH = fit * figure.size.height / figure.size.width

        func handPoint(_ back: Bool) -> CGPoint {
            // the stills face left and are mirrored to face the way he travels (right); the hand behind him (raised arm) holds the web while the anchor is behind
            let hd = back ? hands.1 : hands.0
            let local = CGPoint(x: (0.5 - hd.0) * fit, y: (hd.1 - 0.5) * figH)
            return CGPoint(x: pos.x + local.x * CGFloat(cos(rot)) - local.y * CGFloat(sin(rot)), y: pos.y + local.x * CGFloat(sin(rot)) + local.y * CGFloat(cos(rot)))
        }

        let a = anchor(n)
        if onWeb {
            let anchorBehind = a.x < pos.x
            let hand = handPoint(anchorBehind)
            // the web shoots out in the first moments of a swing
            let grow = CGFloat(min(1, local / (swingT * 0.14)))
            let end = CGPoint(x: hand.x + (a.x - hand.x) * grow, y: hand.y + (a.y - hand.y) * grow)
            var web = Path(); web.move(to: hand); web.addLine(to: end)
            ctx.stroke(web, with: .color(Color.white.opacity(0.18)), lineWidth: 5)
            ctx.stroke(web, with: .color(Color.white.opacity(0.9)), lineWidth: 1.3)
        } else {
            // a loose strand falls away from the hand just after letting go
            let s = CGFloat((local - swingT) / flightT)
            let hand = handPoint(true)
            var web = Path(); web.move(to: hand); web.addLine(to: CGPoint(x: hand.x + (a.x - hand.x) * (1 - s * 0.6), y: hand.y + (a.y - hand.y) * (1 - s * 0.6)))
            ctx.stroke(web, with: .color(Color.white.opacity(0.7 * Double(1 - s))), lineWidth: 1)
        }
        faced(ctx, poseName, at: pos, fit: fit, rotation: rot, opacity: 1)
    }

    /// The Batmobile rolling left to right with its lights on.
    static func batmobile(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, time: Double, roadY: CGFloat, fit: CGFloat) {
        guard ThemeArtImages.get("batmobile") != nil else { return }
        let w = size.width
        let span = Double(w + fit * 2.4)
        let x = -fit * 1.2 + CGFloat((time * 130).truncatingRemainder(dividingBy: span))
        let bounce = CGFloat(sin(time * 16)) * 0.8
        ctx.fill(Path(CGRect(x: 0, y: roadY, width: w, height: 1)), with: .color(Color.white.opacity(0.08)))
        for i in 0..<7 {
            let sx = x - fit * 0.5 - CGFloat(i) * 22 - CGFloat(hash(Double(i)) * 14)
            let sy = roadY - 6 - CGFloat(hash(Double(i) + 5)) * fit * 0.3
            var l = Path(); l.move(to: CGPoint(x: sx, y: sy)); l.addLine(to: CGPoint(x: sx - 26 - CGFloat(hash(Double(i) + 9)) * 30, y: sy))
            ctx.stroke(l, with: .color(Color.white.opacity(0.12)), lineWidth: 1)
        }
        var cone = Path()
        let nose = CGPoint(x: x + fit * 0.42, y: roadY - fit * 0.2)
        cone.move(to: nose); cone.addLine(to: CGPoint(x: nose.x + 190, y: nose.y - 40)); cone.addLine(to: CGPoint(x: nose.x + 190, y: nose.y + 30)); cone.closeSubpath()
        ctx.fill(cone, with: .linearGradient(Gradient(colors: [t.accent.opacity(0.38), .clear]), startPoint: nose, endPoint: CGPoint(x: nose.x + 190, y: nose.y)))
        ctx.fill(Path(ellipseIn: CGRect(x: x - fit * 0.55, y: roadY - 6, width: fit * 1.1, height: 12)),
                 with: .radialGradient(Gradient(colors: [t.accent.opacity(0.25), .clear]), center: CGPoint(x: x, y: roadY), startRadius: 0, endRadius: fit * 0.55))
        pictureT(ctx, "batmobile", center: CGPoint(x: x, y: roadY - fit * 0.2 + bounce), fit: fit, opacity: 1)
    }

    /// Full-window motion behind the searching screen for the themes that have a moving character.
    static func ambient(_ ctx: GraphicsContext, size: CGSize, theme t: AppTheme, time: Double) {
        switch t.motif {
        case .spider:
            city(ctx, size: size, time: time, speed: 10, baseline: size.height * 0.62, color: Color(hex: 0x14040A), seed: 11, heights: 60...170)
            swing(ctx, size: size, theme: t, time: time, ref: 230, top: 10)
        case .bat:
            batmobile(ctx, size: size, theme: t, time: time, roadY: size.height - 26, fit: 190)
        default: break
        }
    }
}
