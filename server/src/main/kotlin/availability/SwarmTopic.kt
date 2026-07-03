package wtf.jobin.availability

import java.nio.ByteBuffer
import java.util.UUID

/**
 * #124 (P2P-ADR 0008): the Kotlin twin of `worklet/topic.mjs`. Derives the pseudonymous swarm
 * topic for a content-addressed Title:
 *
 *   topic = blake2b-256( 16 raw bytes of contentUuid )   → 32 bytes, lowercase hex
 *
 * A node holding a copy joins `hash(contentUuid)`; a peer wanting it joins the SAME topic. Purely
 * deterministic, no coordination — the P2P analogue of a BitTorrent infohash swarm. Crucially there
 * is NO `publicKey <-> title` table anywhere: the topic advertises WHAT exists, never WHO holds it.
 *
 * FROZEN cross-repo wire contract — reproduced byte-for-byte by `worklet/topic.mjs`
 * (`hypercore-crypto.hash`) and the #142 mobile / viewrr-web repos:
 *   input     = the 16 raw bytes of content_uuid (dashes stripped, hex-decoded)
 *   algorithm = BLAKE2b with 32-byte digest, unkeyed (== libsodium crypto_generichash default,
 *               == hypercore-crypto.hash)
 *   output    = 32 bytes, lowercase hex (64 chars) — exactly what [WorkletHypercore.swarmJoin]
 *               expects as its 32-byte topicHex
 *   GOLDEN: content_uuid "bc592db3-805a-58ff-9f95-b90687681997" (== ContentUuid.forTmdb(550, MOVIE))
 *        -> topic        "a4f704e6350a26c2910080d281a6097163a86aa7f19f4921fc10f7bac0df1643"
 *
 * ponytail: BLAKE2b is hand-rolled (RFC 7693) ONLY because the frozen contract is
 * `hypercore-crypto.hash` (BLAKE2b), which no JDK provider ships and the repo forbids new deps. It
 * is a pure function with a golden-vector test — not a crypto subsystem. If a vetted BLAKE2b lands
 * on the classpath later, swap the body of [Blake2b256.hash] for it; the wire output must not move.
 */
object SwarmTopic {

    /** Topic hex for a Title's content address. */
    fun topicHex(contentUuid: UUID): String = hex(Blake2b256.hash(uuidBytes(contentUuid)))

    /**
     * Topic hex from a content_uuid already in hex form, with or without dashes — matches the shape
     * [wtf.jobin.worklet.AnnounceRepository.localContentUuids] emits (32-char dash-stripped hex) and
     * `topic.mjs`'s `swarmTopic(contentUuidHex)`.
     */
    fun topicHexFromUuidHex(contentUuidHex: String): String {
        val hex = contentUuidHex.replace("-", "").lowercase()
        require(hex.length == 32) { "content_uuid must be 16 bytes (32 hex chars), got ${hex.length}" }
        return hex(Blake2b256.hash(hexToBytes(hex)))
    }

    /** The 16 canonical bytes of a UUID (most-significant then least-significant). */
    private fun uuidBytes(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}

/**
 * Minimal RFC 7693 BLAKE2b, unkeyed, configurable digest length (we use 32). Pure stdlib. Exists
 * solely so [SwarmTopic] can reproduce `hypercore-crypto.hash` without a crypto dependency; see the
 * note on [SwarmTopic]. Not a general-purpose facility — keep it internal to this feature.
 */
internal object Blake2b256 {

    private val IV = longArrayOf(
        0x6a09e667f3bcc908uL.toLong(), 0xbb67ae8584caa73buL.toLong(),
        0x3c6ef372fe94f82buL.toLong(), 0xa54ff53a5f1d36f1uL.toLong(),
        0x510e527fade682d1uL.toLong(), 0x9b05688c2b3e6c1fuL.toLong(),
        0x1f83d9abfb41bd6buL.toLong(), 0x5be0cd19137e2179uL.toLong(),
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    )

    /** BLAKE2b digest of [input] with a 32-byte output. */
    fun hash(input: ByteArray, outLen: Int = 32): ByteArray {
        val h = IV.copyOf()
        h[0] = h[0] xor (0x01010000L or outLen.toLong()) // params: digest length, unkeyed

        val block = ByteArray(128)
        var offset = 0
        var counter = 0L
        // Full non-final blocks.
        while (input.size - offset > 128) {
            System.arraycopy(input, offset, block, 0, 128)
            counter += 128
            compress(h, block, counter, false)
            offset += 128
        }
        // Final block (zero-padded); counter is the true total input length.
        block.fill(0)
        val remaining = input.size - offset
        System.arraycopy(input, offset, block, 0, remaining)
        counter += remaining
        compress(h, block, counter, true)

        val out = ByteArray(outLen)
        for (i in 0 until outLen) out[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    private fun compress(h: LongArray, block: ByteArray, t: Long, last: Boolean) {
        val m = LongArray(16)
        for (i in 0 until 16) {
            var w = 0L
            for (j in 0 until 8) w = w or ((block[i * 8 + j].toLong() and 0xff) shl (8 * j))
            m[i] = w
        }
        val v = LongArray(16)
        System.arraycopy(h, 0, v, 0, 8)
        System.arraycopy(IV, 0, v, 8, 8)
        v[12] = v[12] xor t          // low word of 128-bit counter
        // v[13] xor high word (always 0 here — inputs are tiny)
        if (last) v[14] = v[14] xor -1L

        for (r in 0 until 12) {
            val s = SIGMA[r]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = rotr(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = rotr(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = rotr(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = rotr(v[b] xor v[c], 63)
    }

    private fun rotr(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))
}
