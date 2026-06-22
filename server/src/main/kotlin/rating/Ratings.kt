package wtf.jobin.rating

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
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

suspend fun maxRatingFor(db: R2dbcDatabase, userId: UUID): String? = suspendTransaction(db) {
    Users.selectAll()
        .where { Users.id eq userId }
        .map { it[Users.maxRating] }
        .firstOrNull()
}
