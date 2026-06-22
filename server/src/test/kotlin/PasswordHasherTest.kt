package wtf.jobin.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test
    fun hashThenVerifyRoundtrips() {
        val hash = hasher.hash("correct horse battery staple".toCharArray())
        assertTrue(hasher.verify(hash, "correct horse battery staple".toCharArray()))
    }

    @Test
    fun verifyRejectsWrongPassword() {
        val hash = hasher.hash("correct horse battery staple".toCharArray())
        assertFalse(hasher.verify(hash, "wrong password".toCharArray()))
    }

    @Test
    fun sameInputProducesDifferentHashes() {
        val a = hasher.hash("same input".toCharArray())
        val b = hasher.hash("same input".toCharArray())
        assertNotEquals(a, b) // per-hash random salt
        assertTrue(hasher.verify(a, "same input".toCharArray()))
        assertTrue(hasher.verify(b, "same input".toCharArray()))
    }
}
