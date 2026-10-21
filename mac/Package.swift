// swift-tools-version:6.0
// For editors only (VS Code's Swift extension uses it for ⌘-click / autocomplete across files).
// The app itself is built with build.sh.
import PackageDescription

let package = Package(
    name: "PhoneBridge",
    platforms: [.macOS("27.0")], // the macOS this Mac runs; matches the SDK the app is built with
    targets: [
        .executableTarget(name: "PhoneBridge", path: "Sources"),
    ],
    swiftLanguageModes: [.v5] // same as build.sh
)
