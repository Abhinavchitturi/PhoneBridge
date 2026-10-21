package app.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpTest {
    @Test
    fun findsCodes() {
        assertEquals("482913", Otp.find("482913 is your OTP for login to MyBank. Do not share it with anyone."))
        assertEquals("1234", Otp.find("Your verification code is 1234"))
        assertEquals("739201", Otp.find("G-739201 is your Google verification code."))
        assertEquals("123456", Otp.find("Use 123-456 as your security code"))
        assertEquals("55821", Otp.find("Dear customer, OTP 55821 for txn of Rs. 1,250.00 at Amazon. Valid for 10 mins."))
    }

    @Test
    fun ignoresNonCodes() {
        assertNull(Otp.find("Hey, are we meeting at 1930 tomorrow?"))
        assertNull(Otp.find("Your order 4829 has shipped"))
    }
}
