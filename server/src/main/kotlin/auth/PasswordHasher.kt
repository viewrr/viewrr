package wtf.jobin.auth

import de.mkammerer.argon2.Argon2Factory

class PasswordHasher {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val iterations = 3
    private val memoryKb = 65_536
    private val parallelism = 2

    fun hash(plain: CharArray): String =
        try { argon2.hash(iterations, memoryKb, parallelism, plain) }
        finally { argon2.wipeArray(plain) }

    fun verify(hash: String, plain: CharArray): Boolean =
        try { argon2.verify(hash, plain) }
        finally { argon2.wipeArray(plain) }
}
