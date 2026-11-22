import AppKit
// Draws the installer window background (660x470): the drag arrow from the app to Applications, then the steps for
// opening an app macOS can't verify (the "Not Opened" warning), next to the Privacy & Security shortcut.
// Icon slots (Finder positions, from the top-left): app (170,120), Applications (490,120), shortcut (545,355).
let W = 660.0, H = 510.0
let white = { (a: Double) in NSColor(white: 1, alpha: a) }
let accent = NSColor(calibratedRed: 0.30, green: 0.62, blue: 1.0, alpha: 1)

/// Draws text with its top-left corner at (x, top), measured from the top of the window; wraps at `width`.
func text(_ s: String, x: Double, top: Double, width: Double, size: Double, weight: NSFont.Weight = .regular,
          color: NSColor, center: Bool = false) -> Double {
    let para = NSMutableParagraphStyle()
    para.lineSpacing = 2.5
    para.alignment = center ? .center : .left
    let str = NSAttributedString(string: s, attributes: [
        .font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color, .paragraphStyle: para])
    let h = str.boundingRect(with: NSSize(width: width, height: 400), options: [.usesLineFragmentOrigin]).height
    str.draw(with: NSRect(x: x, y: H - top - h, width: width, height: h), options: [.usesLineFragmentOrigin])
    return h
}

/// A numbered step: a filled circle with the number, then the title and the detail under it. Returns the bottom.
func step(_ n: Int, top: Double, title: String, detail: String, width: Double = 380) -> Double {
    let r = 13.0
    let cy = H - top - r
    accent.setFill()
    NSBezierPath(ovalIn: NSRect(x: 48, y: cy - r, width: 2 * r, height: 2 * r)).fill()
    let num = NSAttributedString(string: "\(n)", attributes: [.font: NSFont.systemFont(ofSize: 13, weight: .bold), .foregroundColor: NSColor.white])
    num.draw(at: NSPoint(x: 48 + r - num.size().width / 2, y: cy - num.size().height / 2))
    let t = text(title, x: 86, top: top + 4, width: width, size: 14, weight: .semibold, color: white(0.92))
    let d = text(detail, x: 86, top: top + 8 + t, width: width, size: 12, color: white(0.58))
    return top + 8 + t + d
}

let img = NSImage(size: NSSize(width: W, height: H), flipped: false) { rect in
    NSColor(calibratedRed: 0.043, green: 0.043, blue: 0.051, alpha: 1).setFill(); rect.fill()
    let glow = NSGradient(colors: [white(0.07), white(0)])!
    glow.draw(fromCenter: NSPoint(x: W / 2, y: H - 60), radius: 0, toCenter: NSPoint(x: W / 2, y: H - 60), radius: 330, options: [])

    _ = text("Install PhoneBridge", x: 0, top: 18, width: W, size: 17, weight: .medium, color: white(0.9), center: true)

    // arrow between the two icons
    let y = H - 120
    let arrow = NSBezierPath()
    arrow.move(to: NSPoint(x: 258, y: y)); arrow.line(to: NSPoint(x: 392, y: y))
    arrow.move(to: NSPoint(x: 376, y: y + 14)); arrow.line(to: NSPoint(x: 394, y: y)); arrow.line(to: NSPoint(x: 376, y: y - 14))
    arrow.lineWidth = 3; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
    white(0.55).setStroke(); arrow.stroke()

    // divider
    white(0.1).setFill()
    NSRect(x: 40, y: H - 212, width: W - 80, height: 1).fill()

    var top = 230.0
    top = step(1, top: top, title: "Drag PhoneBridge onto Applications",
               detail: "Then open PhoneBridge from your Applications folder.") + 12
    top = step(2, top: top, title: "macOS says “PhoneBridge” Not Opened? Click Done",
               detail: "Don't move it to the Trash. PhoneBridge isn't registered with Apple, so macOS asks you to allow it once.") + 12
    top = step(3, top: top, title: "Allow it in Privacy & Security",
               detail: "Double-click Privacy & Security on the right. Scroll down, click Open Anyway, confirm, and enter your Mac password.") + 12
    _ = text("You only do this once. Later versions update themselves from the menu bar.", x: 86, top: top,
             width: 360, size: 11.5, color: white(0.4))

    // Finder draws icon names in dark grey here; a light tag under each name keeps it readable.
    for (cx, cy, w) in [(170.0, 184.0, 112.0), (490.0, 184.0, 104.0), (545.0, 420.0, 136.0)] {
        white(0.88).setFill()
        NSBezierPath(roundedRect: NSRect(x: cx - w / 2, y: H - cy - 10, width: w, height: 20), xRadius: 6, yRadius: 6).fill()
    }

    // a soft pointer from step 3 to the shortcut
    let p = NSBezierPath()
    p.move(to: NSPoint(x: 470, y: H - 355)); p.line(to: NSPoint(x: 488, y: H - 355))
    p.move(to: NSPoint(x: 480, y: H - 347)); p.line(to: NSPoint(x: 489, y: H - 355)); p.line(to: NSPoint(x: 480, y: H - 363))
    p.lineWidth = 2; p.lineCapStyle = .round; p.lineJoinStyle = .round
    accent.withAlphaComponent(0.8).setStroke(); p.stroke()
    return true
}
let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
