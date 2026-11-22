import SwiftUI

/// Shown whenever the phone isn't connected: a glossy orb with thin rings and drifting particles while the Mac searches
/// the Wi-Fi for a phone that has PhoneBridge open.
struct DiscoveryView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject private var themes = ThemeStore.shared

    var body: some View {
        let t = themes.current
        ZStack {
            searching(t)
        }
    }

    private func searching(_ t: AppTheme) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)
            Text(bridge.foundPhone == nil ? "Looking for\nnearby phones" : "Found\n\(bridge.foundPhone ?? "")")
                .font(.system(size: 34, weight: .regular, design: .serif))
                .multilineTextAlignment(.center).lineSpacing(2)
                .padding(.horizontal, 24)
            OrbView(theme: t).frame(width: 330, height: 330).padding(.vertical, 6)
            Text(bridge.foundPhone != nil
                 ? "Check that the 6-digit code on your phone matches the one shown here, then tap Pair on both."
                 : bridge.phoneNote ?? "Open PhoneBridge on your phone. Both need to be on the same Wi-Fi.")
                .font(.system(size: 14)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .frame(maxWidth: 340).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 24)
            VStack(spacing: 12) {
                Text("Having trouble finding your phone?").font(.system(size: 13)).foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    Button { bridge.reconnect() } label: {
                        Label("Search again", systemImage: "arrow.triangle.2.circlepath").font(.system(size: 13.5, weight: .semibold))
                            .frame(maxWidth: .infinity).frame(height: 42).card(14)
                    }
                    .buttonStyle(.plain)
                    if Keys.trustedPhone != nil {
                        Button { bridge.forget() } label: {
                            Label("Forget this phone", systemImage: "iphone.slash").font(.system(size: 13.5, weight: .semibold))
                                .frame(maxWidth: .infinity).frame(height: 42).card(14)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .frame(maxWidth: 380).padding(.bottom, 26)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A dark glossy blob that slowly changes shape, two thin wobbling rings around it, a ripple from the centre,
/// and small dots, squares and triangles drifting on the rings. Drawn from the clock, so it needs no state.
struct OrbView: View {
    let theme: AppTheme

    var body: some View {
        TimelineView(.animation) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in
                let c = CGPoint(x: size.width / 2, y: size.height / 2)
                let R = min(size.width, size.height) * 0.25

                func blob(_ scale: CGFloat, _ phase: Double, _ amp: CGFloat) -> Path {
                    var p = Path()
                    let n = 96
                    for i in 0...n {
                        let a = Double(i) / Double(n) * 2 * .pi
                        let wobble = sin(3 * a + phase) * 0.6 + sin(5 * a - phase * 0.7) * 0.4 + sin(2 * a + phase * 0.5) * 0.5
                        let r = R * scale * (1 + amp * CGFloat(wobble))
                        let pt = CGPoint(x: c.x + CGFloat(cos(a)) * r, y: c.y + CGFloat(sin(a)) * r)
                        if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                    }
                    p.closeSubpath()
                    return p
                }

                ctx.stroke(blob(1.62, t * 0.3, 0.05), with: .color(.white.opacity(0.13)), lineWidth: 1)
                ctx.stroke(blob(1.42, -t * 0.26 + 1.3, 0.06), with: .color(.white.opacity(0.2)), lineWidth: 1)

                let pulse = (t / 2.6).truncatingRemainder(dividingBy: 1)
                ctx.stroke(blob(1.0 + 0.55 * CGFloat(pulse), t * 0.7, 0.07), with: .color(theme.accent.opacity((1 - pulse) * 0.32)), lineWidth: 1.2)

                let core = blob(1.0, t * 0.7, 0.07)
                ctx.fill(core, with: .radialGradient(Gradient(colors: [Color(white: 0.34), Color(white: 0.12), Color(white: 0.07)]),
                                                     center: CGPoint(x: c.x + R * 0.38, y: c.y - R * 0.3), startRadius: 0, endRadius: R * 1.35))
                ctx.stroke(core, with: .color(.white.opacity(0.09)), lineWidth: 1)
                var rim = Path()
                rim.addArc(center: c, radius: R * 0.72, startAngle: .degrees(120), endAngle: .degrees(210), clockwise: false)
                ctx.stroke(rim, with: .color(.white.opacity(0.1)), style: StrokeStyle(lineWidth: 1.2, lineCap: .round))

                MotifArt.orbExtras(ctx, center: c, radius: R, theme: theme, time: t)
                let themed: Bool = {
                    switch theme.motif {
                    case .bat: return ThemeArtImages.get("bat") != nil
                    case .matrix: return true
                    default: return false
                    }
                }()
                for i in 0..<(themed ? 0 : 16) {
                    let f = Double(i)
                    let ringScale: CGFloat = i % 2 == 0 ? 1.62 : 1.42
                    let dir: Double = i % 3 == 0 ? -1 : 1
                    let a = f * 0.83 + t * (0.1 + 0.03 * Double(i % 4)) * dir
                    let r = R * ringScale * (1 + 0.05 * CGFloat(sin(3 * a)))
                    let p = CGPoint(x: c.x + CGFloat(cos(a)) * r, y: c.y + CGFloat(sin(a)) * r)
                    let alpha = 0.3 + 0.3 * sin(t * 0.9 + f)
                    let s: CGFloat = 1.6 + CGFloat(i % 3)
                    var shape = Path()
                    switch i % 3 {
                    case 0: shape = Path(ellipseIn: CGRect(x: p.x - s, y: p.y - s, width: s * 2, height: s * 2))
                    case 1: shape = Path(CGRect(x: -s, y: -s, width: s * 2, height: s * 2)).applying(CGAffineTransform(rotationAngle: a + t * 0.4).concatenating(CGAffineTransform(translationX: p.x, y: p.y)))
                    default:
                        shape.move(to: CGPoint(x: 0, y: -s * 1.3)); shape.addLine(to: CGPoint(x: s * 1.2, y: s)); shape.addLine(to: CGPoint(x: -s * 1.2, y: s)); shape.closeSubpath()
                        shape = shape.applying(CGAffineTransform(rotationAngle: a * 2).concatenating(CGAffineTransform(translationX: p.x, y: p.y)))
                    }
                    ctx.fill(shape, with: .color(.white.opacity(max(0.08, alpha))))
                }

                var g = ctx
                let breathe = 1 + 0.06 * CGFloat(sin(t * 2))
                g.translateBy(x: c.x, y: c.y)
                g.scaleBy(x: breathe, y: breathe)
                g.translateBy(x: -22, y: -22)
                MotifArt.glyph(g, size: CGSize(width: 44, height: 44), theme: theme, color: theme.accent)
            }
        }
    }
}
