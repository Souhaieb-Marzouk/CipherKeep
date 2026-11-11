package dev.cipherkeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockBackoffPolicyTest {
    @Test
    fun firstFourFailuresAreFree() {
        for (attempt in 1..4) {
            assertEquals(0L, UnlockBackoffPolicy.delaySeconds(attempt))
        }
    }

    @Test
    fun fifthFailureStartsAtThirtySeconds() {
        assertEquals(30L, UnlockBackoffPolicy.delaySeconds(5))
        assertEquals(60L, UnlockBackoffPolicy.delaySeconds(6))
        assertEquals(120L, UnlockBackoffPolicy.delaySeconds(7))
    }

    @Test
    fun delayIsCappedAtOneHour() {
        assertEquals(3_600L, UnlockBackoffPolicy.delaySeconds(50))
    }
}
