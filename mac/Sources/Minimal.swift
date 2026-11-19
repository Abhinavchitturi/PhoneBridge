import SwiftUI

/// Rounded capsule button: quiet by default, filled with the theme's accent when `prominent`.
struct PillButtonStyle: ButtonStyle {
    var prominent = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 14).padding(.vertical, 8)
            .foregroundStyle(prominent ? Theme.onAccent : Color.primary)
            .background(Capsule().fill(prominent ? Theme.accent : Color.white.opacity(0.08)))
            .overlay(Capsule().strokeBorder(Color.white.opacity(prominent ? 0 : 0.1)))
            .opacity(configuration.isPressed ? 0.75 : 1)
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.55), value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { _, down in if down { FxCenter.shared.haptic() } }
    }
}

/// A quick action: round icon above a short label, on a soft card.
struct ActionTile: View {
    let icon: String
    let title: String
    var primary = false
    var active = false
    let action: () -> Void
    @StateObject private var flag = HoverFlag()

    var body: some View {
        let filled = primary || active
        return Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 19, weight: .medium))
                    .foregroundStyle(filled ? Theme.onAccent : Theme.accent)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(filled ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Theme.accent.opacity(0.14))))
                Text(title).font(.system(size: 12, weight: .medium)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 14)
            .card(18, fill: flag.on ? Color.white.opacity(0.09) : Theme.card)
            .scaleEffect(flag.on ? 1.02 : 1)
        }
        .buttonStyle(.plain)
        .pressFx()
        .onHover { flag.on = $0 }
        .animation(.easeOut(duration: 0.12), value: flag.on)
    }
}
