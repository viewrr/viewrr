package wtf.jobin.auth

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenPair(val accessToken: String, val refreshToken: String)

@Serializable
data class PromoteRequest(val isAdmin: Boolean)

@Serializable
data class UserView(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String? = null,
    val isAdmin: Boolean,
)

fun UserRow.toView(): UserView = UserView(
    id = id.toString(),
    username = username,
    email = email,
    displayName = displayName,
    isAdmin = isAdmin,
)
