package app.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTrackerTest {
    private var clock = 1_000L
    private val contacts = mapOf("+111" to "Alice", "+222" to "Bob")
    private fun tracker() = CallTracker(nameFor = { contacts[it] }, now = { clock })
    private fun note(key: String, incoming: Boolean = false, name: String? = null, detail: String? = null, chrono: Boolean = false, whenMs: Long = 0) =
        CallNote(key, incoming, name, detail, chrono, whenMs)

    // --- 2: no timer before you pick up

    @Test fun ringingStaysRingingUntilOffHook() {
        val t = tracker()
        val a = t.onTelephony(1, "+111")!!
        assertEquals("ringing", a.state)
        assertEquals("Alice", a.name)
        assertNull(a.connected)
        // The dialer's notification — even one that doesn't look "incoming" — must not make it active.
        val b = t.onNotification(note("n1", incoming = false, name = "Alice", detail = "Incoming call"), tele = 1)
        assertTrue(b == null || b.state == "ringing")
        val c = t.onTelephony(2, null)!!
        assertEquals("active", c.state)
        assertEquals(true, c.connected)
        assertEquals(1_000L, c.since)
    }

    @Test fun notificationBeforeThePhoneReportsRingingIsIgnored() {
        val t = tracker()
        assertNull(t.onNotification(note("n1", incoming = true, name = "Alice"), tele = 0))
    }

    @Test fun notificationAloneCanStartRingingButNeverAnswers() {
        val t = tracker() // the phone-state broadcast never came (no permission)
        val s = t.onNotification(note("n1", incoming = false, name = "Alice", detail = "Incoming"), tele = 1)!!
        assertEquals("ringing", s.state)
        assertEquals("Alice", s.name)
    }

    // --- 1: a second call never replaces the one you're on

    @Test fun waitingCallKeepsTheCallYouAreOn() {
        val t = tracker()
        t.onTelephony(1, "+111")
        t.onTelephony(2, null) // on a call with Alice
        clock += 60_000
        val w = t.onTelephony(1, "+222")!! // Bob rings
        assertEquals("active", w.state)
        assertEquals("Alice", w.name)
        assertEquals("+111", w.number)
        assertTrue(w.waiting)
        assertEquals("Bob", w.waitingName)
        assertEquals(true, w.connected)
        assertEquals(1_000L, w.since) // Alice's timer is untouched
    }

    @Test fun waitingNotificationsDoNotRenameTheActiveCall() {
        val t = tracker()
        t.onTelephony(1, "+111"); t.onTelephony(2, null)
        t.onTelephony(1, "+222")
        // Bob's incoming notification, and a re-post of Alice's ongoing one that carries Bob's name in its title.
        t.onNotification(note("n2", incoming = true, name = "Bob"), tele = 1)
        val s = t.onNotification(note("n1", incoming = false, name = "Bob", detail = "Incoming call waiting"), tele = 1)
        // Either nothing changed (null), or the call you're on is still Alice's.
        assertTrue(s == null || (s.name == "Alice" && s.state == "active"))
    }

    @Test fun decliningTheWaitingCallLeavesTheFirstCallAlone() {
        val t = tracker()
        t.onTelephony(1, "+111"); t.onTelephony(2, null)
        t.onTelephony(1, "+222")
        val s = t.onTelephony(2, null)!! // back to just the one call
        assertEquals("active", s.state)
        assertFalse(s.waiting)
        assertEquals("Alice", s.name)
        assertEquals(1_000L, s.since)
    }

    @Test fun firstCallEndsWhileTheSecondStillRings() {
        val t = tracker()
        t.onTelephony(1, "+111"); t.onTelephony(2, null)
        t.onNotification(note("n1", name = "Alice"), tele = 2)
        t.onTelephony(1, "+222")
        t.onNotification(note("n2", incoming = true, name = "Bob"), tele = 1)
        val s = t.onNotificationRemoved("n1", tele = 1)!!
        assertEquals("ringing", s.state)
        assertEquals("Bob", s.name)
        assertEquals("+222", s.number)
        assertFalse(s.waiting)
        assertNull(s.connected)
    }

    @Test fun removingTheWaitingNotificationDoesNothing() {
        val t = tracker()
        t.onTelephony(1, "+111"); t.onTelephony(2, null)
        t.onNotification(note("n1", name = "Alice"), tele = 2)
        t.onTelephony(1, "+222")
        t.onNotification(note("n2", incoming = true, name = "Bob"), tele = 1)
        assertNull(t.onNotificationRemoved("n2", tele = 1))
    }

    // --- 3: calls you dial know when the other side picks up

    @Test fun dialledCallBecomesConnectedWhenTheTimerAppears() {
        val t = tracker()
        val d = t.onTelephony(2, null)!!
        assertEquals("active", d.state)
        assertFalse(d.incoming)
        assertNull(d.connected)
        val calling = t.onNotification(note("n1", name = "Alice", detail = "Calling…"), tele = 2)!!
        assertEquals(false, calling.connected)
        clock = 9_000
        val up = t.onNotification(note("n1", name = "Alice", detail = "00:03", chrono = true, whenMs = 8_500), tele = 2)!!
        assertEquals(true, up.connected)
        assertEquals(8_500L, up.since)
    }

    @Test fun dialledCallConnectsWhenTheWordsChangeEvenWithoutATimer() {
        val t = tracker()
        t.onTelephony(2, null)
        t.onNotification(note("n1", name = "Alice", detail = "Dialling"), tele = 2)
        clock = 5_000
        val up = t.onNotification(note("n1", name = "Alice", detail = "Ongoing call"), tele = 2)!!
        assertEquals(true, up.connected)
        assertEquals(5_000L, up.since)
    }

    @Test fun dialledCallWithUnknownWordingStaysUnknownNotConnected() {
        val t = tracker()
        t.onTelephony(2, null)
        val s = t.onNotification(note("n1", name = "Alice", detail = null), tele = 2)
        assertTrue(s == null || s.connected == null)
    }

    @Test fun connectedNeverGoesBack() {
        val t = tracker()
        t.onTelephony(2, null)
        t.onNotification(note("n1", detail = "Calling…"), tele = 2)
        t.onNotification(note("n1", detail = "00:01", chrono = true, whenMs = 2_000), tele = 2)
        val s = t.onNotification(note("n1", detail = "Calling…"), tele = 2)
        assertTrue(s == null || s.connected == true)
    }

    // --- end of call

    @Test fun idleClearsEverything() {
        val t = tracker()
        t.onTelephony(1, "+111"); t.onTelephony(2, null); t.onTelephony(1, "+222")
        val s = t.onTelephony(0, null)!!
        assertEquals("idle", s.state)
        assertFalse(s.waiting)
        assertNull(s.name)
    }
}
