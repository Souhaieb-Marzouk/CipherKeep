package dev.cipherkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorBindingTest {
    @Test
    fun passwordGeneratorCrossesTheBinding() {
        val generated = generatePassword(
            FfiPasswordPolicy(
                length = 20u,
                lowercase = true,
                uppercase = true,
                digits = true,
                symbols = true,
                excludeAmbiguous = true,
            ),
        )
        assertEquals(20, generated.value.length)
        assertTrue(generated.value.any(Char::isLowerCase))
        assertTrue(generated.value.any(Char::isUpperCase))
        assertTrue(generated.value.any(Char::isDigit))
        assertTrue(generated.entropyBits > 100.0)
    }

    @Test
    fun totpCodeCrossesTheBinding() {
        val code = totpCode(
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&digits=6&period=30",
            59uL,
        )
        assertEquals(6, code.code.length)
        assertEquals(1uL, code.secondsRemaining)
    }

    @Test
    fun passphraseUsesTheRequestedSeparator() {
        val generated = generatePassphrase(
            FfiPassphrasePolicy(words = 6u, separator = "-"),
        )
        assertEquals(6, generated.value.split('-').size)
    }
}
