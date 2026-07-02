package wtf.jobin.rating

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.IdentityAccounts
import wtf.jobin.db.Users
import java.util.UUID

// Content-rating ladder, low → high. A user's max_rating caps what they can see.
val RATING_RANK = mapOf("G" to 1, "PG" to 2, "PG-13" to 3, "R" to 4, "NC-17" to 5)

fun normalizeRating(r: String?): String? = r?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

fun rank(r: String?): Int? = RATING_RANK[normalizeRating(r)]

/**
 * A restricted user (non-null max_rating) sees content at or below their cap and
 * NEVER sees unrated/unknown content. No cap → everything visible.
 */
fun isVisible(maxRating: String?, contentRating: String?): Boolean {
    if (normalizeRating(maxRating) == null) return true
    val cr = rank(contentRating)
    return cr != null && cr <= rank(maxRating)!!
}

fun isValidRating(r: String?): Boolean =
    normalizeRating(r) == null || RATING_RANK.containsKey(normalizeRating(r))

/**
 * Resolve the parental cap for an authenticated subject. After #150 the JWT subject is an
 * `identity_accounts.id` (publicKey identity is the sole auth path), so we resolve the cap from that
 * table FIRST: a matching row is authoritative — null max_rating = the account owner is an adult /
 * unrestricted, a set value is ENFORCED. Only when NO identity row matches the id do we fall back to
 * the legacy `users` table, so any pre-#150 users subject still resolves.
 *
 * This closes the #120 fail-open: previously this queried only `users`, so an identity subject never
 * matched → null → unrestricted, silently ignoring any cap set on the account.
 *
 * ponytail: an identity row must be distinguished from "no row" WITHOUT collapsing a legitimately-null
 * cap into the users fallback — hence toList()/isNotEmpty() rather than firstOrNull() on the value.
 */
suspend fun maxRatingFor(db: R2dbcDatabase, userId: UUID): String? = suspendTransaction(db) {
    val identityCap = IdentityAccounts.selectAll()
        .where { IdentityAccounts.id eq userId }
        .map { it[IdentityAccounts.maxRating] }
        .toList()
    if (identityCap.isNotEmpty()) return@suspendTransaction identityCap.first()

    Users.selectAll()
        .where { Users.id eq userId }
        .map { it[Users.maxRating] }
        .firstOrNull()
}
