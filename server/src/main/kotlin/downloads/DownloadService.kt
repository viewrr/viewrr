package wtf.jobin.downloads

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.config.AppConfig
import wtf.jobin.db.Downloads
import java.time.Instant
import java.util.Date
import java.util.UUID

/** Audience claim that distinguishes signed download URLs from regular access tokens. */
const val DOWNLOAD_AUDIENCE = "viewrr-download"

private const val DOWNLOAD_TTL_MINUTES = 10L

data class PreparedDownload(
    val downloadId: UUID,
    val token: String,
    val tokenExpiresAt: Instant,
)

data class DownloadRow(
    val id: UUID,
    val filePath: String?,
    val expiresAt: Instant,
)

/**
 * Wraps Mp4Downloader and owns the short-TTL download-JWT lifecycle.
 * Same HMAC secret as the access JWT, distinct audience ("viewrr-download")
 * so the access token cannot be reused on the public file endpoint and vice versa.
 */
class DownloadService(
    private val db: R2dbcDatabase,
    private val mp4: Mp4Downloader,
    auth: AppConfig.Auth,
) {
    private val algo = Algorithm.HMAC256(auth.jwtSecret)
    private val issuer = auth.jwtIssuer
    private val verifier = JWT.require(algo)
        .withIssuer(issuer)
        .withAudience(DOWNLOAD_AUDIENCE)
        .build()

    suspend fun prepare(mediaId: UUID, userId: UUID, deviceId: String): PreparedDownload {
        mp4.prepare(mediaId, userId, deviceId)
        // Mp4Downloader upserts on (user_id, media_id, device_id) — read the surrogate id back.
        val downloadId = suspendTransaction(db) {
            Downloads.select(Downloads.id)
                .where {
                    (Downloads.userId eq userId) and
                        (Downloads.mediaId eq mediaId) and
                        (Downloads.deviceId eq deviceId)
                }
                .map { it[Downloads.id].value }
                .firstOrNull()
        } ?: error("download row missing after prepare for $userId/$mediaId/$deviceId")

        val nowMillis = System.currentTimeMillis()
        val expires = Instant.ofEpochMilli(nowMillis + DOWNLOAD_TTL_MINUTES * 60_000L)
        val token = JWT.create()
            .withIssuer(issuer)
            .withAudience(DOWNLOAD_AUDIENCE)
            .withSubject(userId.toString())
            .withClaim("dl", downloadId.toString())
            .withIssuedAt(Date(nowMillis))
            .withExpiresAt(Date.from(expires))
            .sign(algo)
        return PreparedDownload(downloadId, token, expires)
    }

    /** Returns the download id from the `dl` claim, or null if the token is bad/expired/wrong-audience. */
    fun verify(token: String): UUID? = try {
        val claim = verifier.verify(token).getClaim("dl").asString() ?: return null
        UUID.fromString(claim)
    } catch (_: JWTVerificationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    suspend fun lookup(downloadId: UUID): DownloadRow? = suspendTransaction(db) {
        Downloads.selectAll()
            .where { Downloads.id eq downloadId }
            .map {
                DownloadRow(
                    id = it[Downloads.id].value,
                    filePath = it[Downloads.filePath],
                    expiresAt = it[Downloads.expiresAt],
                )
            }
            .firstOrNull()
    }
}
