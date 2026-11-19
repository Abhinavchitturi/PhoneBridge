import Foundation

struct WaitingCaller: Equatable {
    var name: String?
    var number: String?
}

/// What the call card shows, driven by the phone's "call" reports. No UI in here so the rules can be tested:
///  - the timer starts only when the phone says the other side is on the line (`connected`), never on "ringing";
///  - a second call that rings while you're on one goes in `waiting` and never replaces the call you're on;
///  - the call you're on keeps its name and timer until it ends.
struct CallLogic {
    var name: String?
    var number: String?
    var detail: String?
    /// Dialled from the Mac: "Calling…" until the phone reports the call.
    var outgoing = false
    /// A call is on (answered, or dialled).
    var active = false
    /// The other side is on the line: the timer runs.
    var answered = false
    var startedAt: Date?
    /// A second call, ringing while you're on one.
    var waiting: WaitingCaller?
    /// The call that was put on hold when you took the waiting one from the Mac.
    var heldCaller: String?
    private var ringingShown = false
    private var switchTo: WaitingCaller?

    enum Effect: Equatable {
        case none
        case show
        case hide
        /// A call that was picked up has finished.
        case ended(name: String?, number: String?, seconds: Int)
    }

    mutating func startOutgoing(name: String?, number: String) {
        self = CallLogic()
        outgoing = true
        self.name = name
        self.number = number
    }

    /// You pressed Answer on the waiting call from the Mac: when the phone says it's gone, that call is the one you're on.
    mutating func willAnswerWaiting() { switchTo = waiting }

    mutating func update(_ m: [String: Any], now: Date = Date()) -> Effect {
        let state = m["state"] as? String ?? "idle"
        if state == "idle" {
            var effect = Effect.hide
            if answered, let start = startedAt, name != nil || number != nil {
                effect = .ended(name: name, number: number, seconds: Int(now.timeIntervalSince(start)))
            }
            self = CallLogic()
            return effect
        }
        if state == "ringing" {
            if outgoing || active { return .none } // a stray report must not touch the call you're on
            name = Self.text(m["name"]) ?? name
            number = Self.text(m["number"]) ?? number
            detail = Self.text(m["detail"]) ?? detail
            ringingShown = true
            return .show
        }
        // state == "active"
        let wasRinging = ringingShown && !outgoing
        if !active {
            active = true
            ringingShown = false
            answered = false
            startedAt = nil
        }
        // Who you're talking to is fixed when the call starts; later reports only fill in what's missing.
        if name == nil { name = Self.text(m["name"]) }
        if number == nil { number = Self.text(m["number"]) }
        if let d = Self.text(m["detail"]) { detail = d }
        if m["connected"] as? Bool == true {
            let since = (m["since"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
            if !answered {
                answered = true
                // Calls you picked up start now; for calls you dialled the dialer's own start time is the exact one.
                startedAt = (wasRinging || m["incoming"] as? Bool == true) ? now : (since ?? now)
            } else if let since, let s = startedAt, abs(since.timeIntervalSince(s)) > 1, since < s.addingTimeInterval(5) {
                startedAt = since
            }
        }
        if m["waiting"] as? Bool == true {
            waiting = WaitingCaller(name: Self.text(m["waitingName"]), number: Self.text(m["waitingNumber"]))
        } else if waiting != nil {
            if let taken = switchTo { // you answered it from the Mac: the old call is on hold, this one is the call now
                heldCaller = name ?? number
                name = taken.name
                number = taken.number
                answered = true
                startedAt = now
            }
            waiting = nil
            switchTo = nil
        }
        return .show
    }

    private static func text(_ v: Any?) -> String? {
        guard let s = v as? String, !s.isEmpty else { return nil }
        return s
    }
}
