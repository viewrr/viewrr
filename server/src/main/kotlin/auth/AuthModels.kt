package wtf.jobin.auth

import kotlinx.serialization.Serializable

// #120 (P2P-ADR 0001): the argon2 password-login DTOs (RegisterRequest / LoginRequest /
// RefreshRequest) were retired with /auth and AuthService. KEPT here: TokenPair (the token-issuance
// shape TokenService produces and IdentityService returns from challenge→verify) and the admin /
// parental-controls DTOs (#49/#50) still used by AdminUserRoutes + UserRepository — those are
// authorization features, NOT Keycloak, and survive the cutover.

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
