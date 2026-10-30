package app.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateTest {
    @Test fun ringingOnlyWhileThePhoneRings() {
        assertEquals("ringing", callStateFor(tele = 1, looksIncoming = true))
    }

    @Test fun anIncomingLookingNotificationIsNotAnswered() {
        // The bug: it used to count as "active" (and start the Mac's timer) before the phone reported ringing.
        assertNull(callStateFor(tele = 0, looksIncoming = true))
    }

    @Test fun offHookIsActive() {
        assertEquals("active", callStateFor(tele = 2, looksIncoming = true))
        assertEquals("active", callStateFor(tele = 2, looksIncoming = false))
    }

    @Test fun ongoingCallDuringCallWaitingStaysActive() {
        assertEquals("active", callStateFor(tele = 1, looksIncoming = false))
    }

    @Test fun unknownStateFallsBackToTheNotification() {
        assertEquals("ringing", callStateFor(tele = null, looksIncoming = true))
        assertEquals("active", callStateFor(tele = null, looksIncoming = false))
    }
}
