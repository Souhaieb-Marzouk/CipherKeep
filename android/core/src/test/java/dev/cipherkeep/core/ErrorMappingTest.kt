package dev.cipherkeep.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMappingTest {
    @Test
    fun codesMatchTheRustContract() {
        val cases = listOf(
            FfiException.InvalidArgument() to 1,
            FfiException.UnsupportedVersion() to 2,
            FfiException.AuthenticationFailed() to 3,
            FfiException.CorruptData() to 4,
            FfiException.Locked() to 5,
            FfiException.NotFound() to 6,
            FfiException.Conflict() to 7,
            FfiException.LimitExceeded() to 8,
            FfiException.Storage() to 9,
            FfiException.Internal() to 10,
            FfiException.PolicyRejected() to 11,
        )
        cases.forEach { (error, expected) ->
            assertEquals(expected, error.numericCode())
            assertEquals(ErrorCodes.message(expected), error.redactedMessage())
        }
    }

    @Test
    fun authenticationFailureIsGeneric() {
        val error = FfiException.AuthenticationFailed()
        assertEquals(3, error.numericCode())
        assertEquals("vault could not be opened", error.redactedMessage())
        assertEquals(false, error.redactedMessage().contains("password"))
        assertEquals(false, error.redactedMessage().contains("corrupt"))
    }
}
