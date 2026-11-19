import AppKit
// Draws the installer window background (660x400): dark, a soft arrow from the app to Applications and a short instruction.
let W = 660.0, H = 400.0
let img = NSImage(size: NSSize(width: W, height: H), flipped: false) { rect in
    NSColor(calibratedRed: 0.043, green: 0.043, blue: 0.051, alpha: 1).setFill(); rect.fill()
    let glow = NSGradient(colors: [NSColor(white: 1, alpha: 0.07), NSColor(white: 1, alpha: 0)])!
    glow.draw(fromCenter: NSPoint(x: W / 2, y: H * 0.9), radius: 0, toCenter: NSPoint(x: W / 2, y: H * 0.9), radius: 330, options: [])
    // arrow between the two icon slots (app at x=170, Applications at x=490, icons centred at y=210 from the top)
    let y = H - 210
    let arrow = NSBezierPath()
    arrow.move(to: NSPoint(x: 258, y: y)); arrow.line(to: NSPoint(x: 392, y: y))
    arrow.move(to: NSPoint(x: 376, y: y + 14)); arrow.line(to: NSPoint(x: 394, y: y)); arrow.line(to: NSPoint(x: 376, y: y - 14))
    arrow.lineWidth = 3; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
    NSColor(white: 1, alpha: 0.55).setStroke(); arrow.stroke()
    let title = NSAttributedString(string: "Drag PhoneBridge to Applications", attributes: [
        .font: NSFont.systemFont(ofSize: 17, weight: .medium), .foregroundColor: NSColor(white: 1, alpha: 0.9)])
    title.draw(at: NSPoint(x: (W - title.size().width) / 2, y: H - 70))
    let sub = NSAttributedString(string: "Then open it from Launchpad. It lives in the menu bar.", attributes: [
        .font: NSFont.systemFont(ofSize: 12.5), .foregroundColor: NSColor(white: 1, alpha: 0.5)])
    sub.draw(at: NSPoint(x: (W - sub.size().width) / 2, y: H - 94))
    return true
}
let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
