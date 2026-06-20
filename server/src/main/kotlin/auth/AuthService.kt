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
    suspend fun register(req: RegisterRequest): TokenPair {
        if (users.findByUsername(req.username) != null) throw AuthError.UsernameTaken()
        val hash = hasher.hash(req.password.toCharArray())
        val user = users.create(req.username, req.email, hash, req.displayName)
        return TokenPair(tokens.issueAccess(user.id, user.isAdmin), tokens.issueRefresh(user.id))
    }

    suspend fun login(req: LoginRequest): TokenPair {
        val user = users.findByUsername(req.username) ?: throw AuthError.InvalidCredentials()
        if (!hasher.verify(user.passwordHash, req.password.toCharArray())) {
            throw AuthError.InvalidCredentials()
        }
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
}
