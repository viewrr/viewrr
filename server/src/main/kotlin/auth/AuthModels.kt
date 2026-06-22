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
data class SetActiveRequest(val active: Boolean)

@Serializable
data class SetMaxRatingRequest(val maxRating: String? = null)

@Serializable
data class UserView(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String? = null,
    val isAdmin: Boolean,
    val isActive: Boolean,
    val maxRating: String? = null,
)

fun UserRow.toView(): UserView = UserView(
    id = id.toString(),
    username = username,
    email = email,
    displayName = displayName,
    isAdmin = isAdmin,
    isActive = isActive,
    maxRating = maxRating,
)
