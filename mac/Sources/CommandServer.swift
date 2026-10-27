import Foundation

/// A private local socket (~/.phonebridge/pb.sock, owner-only) for the `pb` command-line tool and Shortcuts' "Run Shell Script".
/// One JSON line in, one JSON line out. Also keeps a copy of `pb` at ~/.phonebridge/pb so the path never changes.
final class CommandServer: @unchecked Sendable {
    /// Gets the request and a reply callback (call it once, from any thread).
    var handle: ([String: Any], @escaping ([String: Any]) -> Void) -> Void = { _, reply in reply(["ok": false, "error": "not ready"]) }

    private let queue = DispatchQueue(label: "phonebridge.commandserver")
    private var source: DispatchSourceRead?
    private var listener: Int32 = -1

    static var dir: URL {
        URL(fileURLWithPath: ProcessInfo.processInfo.environment["HOME"] ?? NSHomeDirectory()).appendingPathComponent(".phonebridge", isDirectory: true)
    }
    static var socketPath: String { dir.appendingPathComponent("pb.sock").path }

    func start() {
        let fm = FileManager.default
        try? fm.createDirectory(at: Self.dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        installTool()
        let path = Self.socketPath
        unlink(path)
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let capacity = MemoryLayout.size(ofValue: addr.sun_path)
        guard path.utf8.count < capacity else { NSLog("PhoneBridge: pb socket path too long"); return }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            path.withCString { _ = strcpy(raw.baseAddress!.assumingMemoryBound(to: CChar.self), $0) }
        }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return }
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        guard bound == 0, listen(fd, 8) == 0 else { NSLog("PhoneBridge: pb socket failed: \(String(cString: strerror(errno)))"); close(fd); return }
        chmod(path, 0o600)
        listener = fd
        let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        src.setEventHandler { [weak self] in self?.accept() }
        src.resume()
        source = src
    }

    private func accept() {
        let client = Darwin.accept(listener, nil, nil)
        guard client >= 0 else { return }
        var tv = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        DispatchQueue.global().async { [weak self] in
            var data = Data()
            var byte = [UInt8](repeating: 0, count: 4096)
            while data.count < 1_000_000 {
                let n = read(client, &byte, byte.count)
                if n <= 0 { break }
                data.append(contentsOf: byte[0..<n])
                if data.contains(0x0A) { break }
            }
            guard let self, let req = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                Self.write(client, ["ok": false, "error": "bad request"])
                return
            }
            self.handle(req) { Self.write(client, $0) }
        }
    }

    private static func write(_ fd: Int32, _ obj: [String: Any]) {
        var out = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{\"ok\":false}".utf8)
        out.append(0x0A)
        out.withUnsafeBytes { _ = Darwin.write(fd, $0.baseAddress, out.count) }
        close(fd)
    }

    /// Keeps ~/.phonebridge/pb in step with the copy inside the app.
    private func installTool() {
        guard let src = Bundle.main.url(forResource: "pb", withExtension: nil) else { return }
        let dst = Self.dir.appendingPathComponent("pb")
        try? FileManager.default.removeItem(at: dst)
        try? FileManager.default.copyItem(at: src, to: dst)
        chmod(dst.path, 0o755)
    }
}
