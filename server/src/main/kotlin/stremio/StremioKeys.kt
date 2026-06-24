package wtf.jobin.stremio

import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

// #77: JSON codec for the per-key capability profile stored alongside the key in Redis.
// explicitNulls=false keeps absent ceilings out of the canonical string; ignoreUnknownKeys
// tolerates older/newer profile shapes without breaking resolveProfile.
private val profileJson = Json { explicitNulls = false; ignoreUnknownKeys = true }

/**
 * #77: A playback device's declared capabilities, carried on its per-device Stremio key.
 * [codecs] is the set of decodable video codecs (e.g. "h264", "hevc"); [maxHeight] and
 * [maxBitrateKbps] are optional resolution / bitrate ceilings (null = no ceiling).
 * The Hub transcodes a title to match this profile instead of emitting a full ABR ladder (#78).
 */
@Serializable
data class CapabilityProfile(
    val codecs: List<String> = emptyList(),
    val maxHeight: Int? = null,
    val maxBitrateKbps: Int? = null,
)

/**
 * #77 / ponytail: short stable cache-dir segment for a (media, profile) pairing.
 * "default" for the no-profile case (so the on-disk layout adds exactly one "/default" segment
 * over today's {lib}/{media}/hls); otherwise the first 8 hex of sha256 of the profile's canonical
 * JSON. Single source of truth — both the serve path and the transcoder derive the dir from here
 * so they always agree.
 */
fun profileKeyOf(profile: CapabilityProfile?): String {
    if (profile == null) return "default"
    // Canonicalize: sort codecs so {h264,hevc} and {hevc,h264} hash identically.
    val canonical = profileJson.encodeToString(profile.copy(codecs = profile.codecs.sorted()))
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(8)
}

/**
 * Long-lived, revocable per-user key embedded in the Stremio addon install URL.
 * The install URL is a bearer credential, so we use a dedicated random key (not the
 * 15-minute JWT). One key per user, idempotent; resolving it yields the user for
 * parental scoping. Revoke by DEL stremio:key:<key> + stremio:user:<userId>.
 *
 * #77: a key may OPTIONALLY carry a [CapabilityProfile] (stored at stremio:profile:<key>).
 * This is additive — resolve(key)->userId is unchanged, and a key minted without a profile
 * has no stremio:profile entry, so resolveProfile returns null (today's behavior).
 */
class StremioKeys(private val redis: RedisAsyncCommands<String, String>) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /**
     * Idempotent per-user key. With the default ([profile] == null) this is byte-for-byte the
     * original behavior. When a [profile] is supplied it is persisted at stremio:profile:<key>
     * for the resolved key (mints the key first if the user has none).
     */
    suspend fun keyFor(userId: UUID, profile: CapabilityProfile? = null): String {
        val key = redis.get("stremio:user:$userId").await() ?: run {
            val fresh = b64.encodeToString(ByteArray(24).also(rng::nextBytes))
            redis.set("stremio:key:$fresh", userId.toString()).await()
            redis.set("stremio:user:$userId", fresh).await()
            fresh
        }
        // #77: profile is additive — only written when explicitly supplied; never cleared here.
        if (profile != null) {
            redis.set("stremio:profile:$key", profileJson.encodeToString(profile)).await()
        }
        return key
    }

    suspend fun resolve(key: String): UUID? =
        redis.get("stremio:key:$key").await()?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * #77: the capability profile attached to [key], or null when the key has none
     * (the default / no-profile path). Malformed JSON resolves to null rather than throwing,
     * so a bad entry degrades to today's full-ladder behavior instead of failing playback.
     */
    suspend fun resolveProfile(key: String): CapabilityProfile? =
        redis.get("stremio:profile:$key").await()
            ?.let { runCatching { profileJson.decodeFromString<CapabilityProfile>(it) }.getOrNull() }
}
