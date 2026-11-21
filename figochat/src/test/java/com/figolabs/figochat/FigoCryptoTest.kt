package com.figolabs.figochat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FigoCryptoTest {

    @Test
    fun roundTripsWithAFreshIvEachTime() {
        val crypto = FigoCrypto()
        for (value in listOf("a1b2c3d4e5f6", "eu", "ünïcødé ✓ key", "")) {
            val first = crypto.encrypt(value)
            val second = crypto.encrypt(value)
            assertNotEquals(first, second)
            // only long values: a short one like "eu" can turn up by chance in random base64
            if (value.length >= 8) assertFalse(first.contains(value))
            assertEquals(value, crypto.decrypt(first))
        }
    }

    @Test
    fun anotherInstanceCannotDecrypt() {
        val sealed = FigoCrypto().encrypt("secret")
        assertThrows(Exception::class.java) { FigoCrypto().decrypt(sealed) }
    }

    @Test
    fun tamperedValueIsRejected() {
        val crypto = FigoCrypto()
        val sealed = crypto.encrypt("secret")
        val tampered = sealed.dropLast(2) + if (sealed.endsWith("AA")) "BB" else "AA"
        assertThrows(Exception::class.java) { crypto.decrypt(tampered) }
        assertThrows(IllegalArgumentException::class.java) { crypto.decrypt("garbage") }
    }
}
