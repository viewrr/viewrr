package wtf.jobin.recs

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.db.UserRecommendations
import java.util.UUID

@Serializable
data class Recommendation(
    val mediaId: String,
    val title: String,
    val hlsPath: String?,
    val score: Float,
    val rank: Short,
)

/**
 * Reads pre-computed recs written by the Python rec engine. Ktor never writes
 * to user_recommendations — that table is owned by an out-of-process job.
 */
class RecsRepository(private val db: R2dbcDatabase) {

    suspend fun forUser(userId: UUID, limit: Int): List<Recommendation> = suspendTransaction(db) {
        (UserRecommendations innerJoin MediaItems)
            .select(
                MediaItems.id,
                MediaItems.title,
                MediaItems.hlsPath,
                UserRecommendations.score,
                UserRecommendations.rank,
            )
            .where { UserRecommendations.userId eq userId }
            .orderBy(UserRecommendations.rank to SortOrder.ASC)
            .limit(limit)
            .map {
                Recommendation(
                    mediaId = it[MediaItems.id].value.toString(),
                    title = it[MediaItems.title],
                    hlsPath = it[MediaItems.hlsPath],
                    score = it[UserRecommendations.score],
                    rank = it[UserRecommendations.rank],
                )
            }
            .toList()
    }
}
