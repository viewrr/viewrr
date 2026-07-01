package wtf.jobin.db

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * #124 (P2P-ADR 0008): the deterministic content address of a catalog Title.
 *
 * `contentUuid = UUIDv5(VIEWRR_CONTENT_NS, name)` where `name` is a frozen string built from
 * the title's TMDB identity. Every peer — Hub, mobile, web — MUST derive the identical value
 * from the same TMDB id, because the P2P availability swarm is keyed by `hash(contentUuid)`
 * (BitTorrent-style). No coordination, and — critically — no `publicKey <-> title` table: the
 * Hub advertises WHAT exists, never WHO holds it.
 *
 * FROZEN WIRE CONTRACT — reimplemented byte-for-byte in the mobile/web repos:
 *   - namespace = [VIEWRR_CONTENT_NS] (never change; would re-address the whole catalog)
 *   - name      = "tmdb:{kind}:{id}"                    for movies
 *                 "tmdb:tv:{id}[:s{season}][:e{episode}]" for TV (each episode its own address)
 *   - algorithm = RFC 4122 UUIDv5 (SHA-1), version nibble 5, IETF variant
 *
 * ponytail: hand-rolled v5 on purpose. JDK's `UUID.nameUUIDFromBytes` is v3 (MD5) — a different
 * value another platform's standard `uuidv5()` would NOT reproduce. Do not swap it in.
 */
object ContentUuid {
    /** Arbitrary but permanently frozen namespace for viewrr content addresses. */
    val VIEWRR_CONTENT_NS: UUID = UUID.fromString("9a7b1e40-1c3d-4f56-8a90-b2c4d6e8f012")

    enum class Kind(val wire: String) { MOVIE("movie"), TV("tv") }

    /** Content address for a TMDB-identified title. season/episode only affect TV names. */
    fun forTmdb(tmdbId: Int, kind: Kind, season: Int? = null, episode: Int? = null): UUID {
        val name = buildString {
            append("tmdb:").append(kind.wire).append(':').append(tmdbId)
            if (kind == Kind.TV) {
                if (season != null) append(":s").append(season)
                if (episode != null) append(":e").append(episode)
            }
        }
        return v5(VIEWRR_CONTENT_NS, name)
    }

    /** RFC 4122 §4.3 name-based UUIDv5 (SHA-1). */
    fun v5(namespace: UUID, name: String): UUID {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(toBytes(namespace))
        sha1.update(name.toByteArray(Charsets.UTF_8))
        val h = sha1.digest() // 20 bytes; take the first 16
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte() // version 5
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        val bb = ByteBuffer.wrap(h, 0, 16)
        return UUID(bb.long, bb.long)
    }

    private fun toBytes(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
}
