// Rules check for CallLogic (no test target here). Run:
//   swiftc -parse-as-library -swift-version 5 -o /tmp/calllogic Sources/CallLogic.swift Tests/call-logic-check.swift && /tmp/calllogic

import Foundation
var failures = 0
func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL: \(what)") } else { print("ok:   \(what)") } }
let t0 = Date(timeIntervalSince1970: 1_000_000)
func later(_ s: Double) -> Date { t0.addingTimeInterval(s) }

@main struct T { static func main() {
    // 2: ringing never starts a timer, however many reports arrive
    var c = CallLogic()
    _ = c.update(["state": "ringing", "name": "Alice", "number": "+111"], now: t0)
    check(c.active == false && c.answered == false && c.startedAt == nil, "ringing: not active, no timer")
    _ = c.update(["state": "ringing", "name": "Alice"], now: later(3))
    check(c.answered == false && c.startedAt == nil, "ringing again: still no timer")
    _ = c.update(["state": "active", "name": "Alice", "connected": false], now: later(5))
    check(c.active == true && c.answered == false, "active without connected: no timer (never counts before pickup)")
    _ = c.update(["state": "active", "name": "Alice", "connected": true, "incoming": true, "since": 1_000_000_000.0 * 0 + 999_000_000.0], now: later(8))
    check(c.answered == true && c.startedAt == later(8), "picked up: timer starts at pickup time")

    // 1: a second call never replaces the first
    _ = c.update(["state": "active", "name": "Alice", "connected": true, "waiting": true, "waitingName": "Bob", "waitingNumber": "+222"], now: later(60))
    check(c.name == "Alice" && c.number == "+111", "waiting call: caller stays Alice")
    check(c.waiting == WaitingCaller(name: "Bob", number: "+222"), "waiting call: Bob is in the waiting slot")
    check(c.startedAt == later(8), "waiting call: Alice's timer untouched")
    _ = c.update(["state": "ringing", "name": "Bob", "number": "+222"], now: later(61))
    check(c.name == "Alice" && c.active, "stray ringing report while on a call is ignored")
    _ = c.update(["state": "active", "name": "Bob", "connected": true, "waiting": false], now: later(70))
    check(c.name == "Alice" && c.waiting == nil && c.startedAt == later(8), "waiting call declined: still Alice, same timer")

    // answering the waiting call from the Mac switches to it, with a fresh timer
    _ = c.update(["state": "active", "connected": true, "waiting": true, "waitingName": "Bob", "waitingNumber": "+222"], now: later(80))
    c.willAnswerWaiting()
    _ = c.update(["state": "active", "connected": true, "waiting": false], now: later(85))
    check(c.name == "Bob" && c.heldCaller == "Alice" && c.startedAt == later(85) && c.waiting == nil, "answered waiting call: Bob is current, Alice on hold")

    // ending reports the call that was talked
    let e = c.update(["state": "idle"], now: later(145))
    check(e == .ended(name: "Bob", number: "+222", seconds: 60), "ended: reports Bob, 60 s")
    check(c.name == nil && !c.active && c.waiting == nil, "idle: everything cleared")

    // missed call: no ended event
    var m = CallLogic()
    _ = m.update(["state": "ringing", "name": "Carol"], now: t0)
    check(m.update(["state": "idle"], now: later(10)) == .hide, "missed call: just hides")

    // 3: a call you dial
    var d = CallLogic()
    d.startOutgoing(name: "Dave", number: "+333")
    check(d.outgoing && !d.active && !d.answered, "dialling: calling…")
    _ = d.update(["state": "active", "connected": false, "detail": "Calling…"], now: later(2))
    check(d.active && !d.answered && d.startedAt == nil, "dialling: still ringing on the other end, no timer")
    let theirStart = later(7).timeIntervalSince1970 * 1000
    _ = d.update(["state": "active", "connected": true, "since": theirStart], now: later(8))
    check(d.answered && d.startedAt == later(7), "they pick up: timer starts from the dialler's own start time")
    _ = d.update(["state": "active", "connected": true, "since": theirStart, "detail": "Ongoing call"], now: later(30))
    check(d.answered && d.startedAt == later(7), "stays connected")

    // a call dialled on the phone itself
    var p = CallLogic()
    _ = p.update(["state": "active", "name": "Erin", "connected": NSNull()], now: t0)
    check(p.active && !p.answered, "dialled on the phone, unknown: no timer yet")
    _ = p.update(["state": "active", "connected": true], now: later(4))
    check(p.answered && p.startedAt == later(4), "dialled on the phone, picks up: timer")

    // the first call ends while the second still rings: phone reports ringing for Bob
    var q = CallLogic()
    _ = q.update(["state": "ringing", "name": "Alice"], now: t0)
    _ = q.update(["state": "active", "connected": true, "incoming": true], now: later(1))
    _ = q.update(["state": "active", "connected": true, "waiting": true, "waitingName": "Bob"], now: later(5))
    _ = q.update(["state": "idle"], now: later(20))
    _ = q.update(["state": "ringing", "name": "Bob", "number": "+222"], now: later(20))
    check(q.name == "Bob" && !q.active && !q.answered && q.waiting == nil, "first call ended, second now rings: Answer/Decline for Bob")

    print(failures == 0 ? "ALL PASSED" : "\(failures) FAILED")
    exit(failures == 0 ? 0 : 1)
} }
