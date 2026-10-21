import Foundation
import FoundationModels

/// Three short reply suggestions for an incoming chat notification, written by Apple's on-device model:
/// free, private (nothing leaves the Mac) and offline. Returns nothing if Apple Intelligence is off or it's too slow.
enum SmartReply {
    static func suggest(sender: String, latest: String, earlier: [String]) async -> [String] {
        guard case .available = SystemLanguageModel.default.availability else { return [] }
        return await withTaskGroup(of: [String]?.self) { group in
            group.addTask { await generate(sender: sender, latest: latest, earlier: earlier) }
            group.addTask {
                try? await Task.sleep(nanoseconds: 4_500_000_000) // don't hold a notification back for long
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? []
        }
    }

    private static func generate(sender: String, latest: String, earlier: [String]) async -> [String]? {
        var context = ""
        if !earlier.isEmpty { context = "Earlier messages:\n" + earlier.joined(separator: "\n") + "\n\n" }
        let prompt = "\(context)New message from \(sender):\n\(latest.prefix(600))\n\nSuggest the user's reply."
        do {
            let session = LanguageModelSession(instructions: """
                You suggest quick replies for the user to send to a chat message they just received. Write exactly 3 different, \
                short, natural replies, one per line, with no numbering, quotes or extra words. Each under 10 words. Match the \
                language and tone of the message. Don't promise anything specific like times or money, and don't invent facts.
                """)
            let reply = try await session.respond(to: prompt)
            let lines = reply.content.split(separator: "\n").map {
                $0.trimmingCharacters(in: CharacterSet(charactersIn: "-•*\"“”0123456789. )")).trimmingCharacters(in: .whitespaces)
            }.filter { !$0.isEmpty && $0.count <= 80 }
            return Array(lines.prefix(3))
        } catch {
            return nil
        }
    }
}
