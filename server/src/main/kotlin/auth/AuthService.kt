package wtf.jobin.auth

sealed class AuthError(msg: String) : RuntimeException(msg) {
    class UsernameTaken : AuthError("username taken")
    class InvalidCredentials : AuthError("invalid credentials")
    class InvalidRefresh : AuthError("invalid refresh token")
}

class AuthService(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val tokens: TokenService,
) {
    // Dummy hash for constant-time login on user-not-found.
    // Built lazily so AuthService construction stays cheap; first miss pays ~100ms once.
    private val dummyHash: String by lazy { hasher.hash("not-a-real-password".toCharArray()) }

    suspend fun register(req: RegisterRequest): TokenPair {
        if (users.findByUsername(req.username) != null) throw AuthError.UsernameTaken()
        val hash = hasher.hash(req.password.toCharArray())
        val user = try {
            users.create(req.username, req.email, hash, req.displayName)
        } catch (e: Exception) {
            if (isUniqueViolation(e)) throw AuthError.UsernameTaken()
            throw e
        }
        return TokenPair(tokens.issueAccess(user.id, user.isAdmin), tokens.issueRefresh(user.id))
    }

    suspend fun login(req: LoginRequest): TokenPair {
        val user = users.findByUsername(req.username)
        // Always verify against a hash so user-not-found and wrong-password
        // take ~the same wall time (no username-enumeration timing oracle).
        val hashToCheck = user?.passwordHash ?: dummyHash
        val ok = hasher.verify(hashToCheck, req.password.toCharArray())
        // ponytail: disabled users reuse InvalidCredentials — no separate code, no enumeration.
        if (user == null || !ok || !user.isActive) throw AuthError.InvalidCredentials()
        return TokenPair(tokens.issueAccess(user.id, user.isAdmin), tokens.issueRefresh(user.id))
    }

    suspend fun refresh(refreshToken: String): TokenPair {
        val userId = tokens.consumeRefresh(refreshToken) ?: throw AuthError.InvalidRefresh()
        val user = users.findById(userId) ?: throw AuthError.InvalidRefresh()
        return TokenPair(tokens.issueAccess(user.id, user.isAdmin), tokens.issueRefresh(user.id))
    }

    suspend fun logout(refreshToken: String) {
        tokens.revokeRefresh(refreshToken)
    }

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            // Postgres SQLSTATE 23505 = unique_violation. R2DBC/postgres surfaces it in the message.
            val msg = (cur.message ?: "").lowercase()
            if ("23505" in msg || "duplicate key" in msg || "unique constraint" in msg) return true
            cur = cur.cause
        }
        return false
    }
}
