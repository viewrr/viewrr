package wtf.jobin.scanner

import wtf.jobin.stremio.CapabilityProfile

/**
 * #130 (P2P-ADR 0002): the viewrr codec ladder — the ordered set of video codecs the Hub can
 * target when transcoding a title for a device. AV1 is the PRIMARY rung (best compression, which
 * already delivers ADR-0002's "less data" goal); H.264 is the universal FALLBACK (broad hardware
 * decode, and today's default output). AV2 is defined as a FUTURE rung but stays DISABLED — no
 * encoder is wired and there is no hardware decode yet (ADR-0002) — so enabling it later is a
 * one-line config flip (`enabled = true` + an encoder), not new code.
 *
 * This type is pure config + selection. It does NOT change the live encode path: [HlsTranscoder]
 * still emits H.264 today. It exists so the transcoder and the future Compose Desktop player agree
 * on one codec-preference source of truth; wiring [CodecLadder.select] into the encode path is a
 * later, default-OFF-gated slice.
 */
enum class VideoCodec(
    /** ffprobe `codec_name` values (and manifest aliases) that identify a source already in this codec. */
    val probeNames: Set<String>,
    /** ffmpeg `-c:v` encoder for this rung, or null when no encoder is wired yet (AV2). */
    val ffmpegEncoder: String?,
    /** Preference rank; lower is preferred. AV1 (0) beats H.264 (1). */
    val rank: Int,
    /** Whether the ladder may select this rung. AV2 stays false until HW decode + an encoder land. */
    val enabled: Boolean,
) {
    /** Primary rung: best compression, already delivers the "less data" goal (ADR-0002). */
    AV1(probeNames = setOf("av1", "av01"), ffmpegEncoder = "libsvtav1", rank = 0, enabled = true),

    /** Universal fallback: broad hardware decode, byte-identical to today's H.264 output. */
    H264(probeNames = setOf("h264", "avc1"), ffmpegEncoder = "libx264", rank = 1, enabled = true),

    /** Future config-add rung — no encoder, no hardware decode yet (ADR-0002). Never selected while disabled. */
    AV2(probeNames = setOf("av2"), ffmpegEncoder = null, rank = 2, enabled = false),
    ;

    /** The token(s) a [CapabilityProfile] can use to declare support for this rung (enum name + probe aliases). */
    val declaredTokens: Set<String> = probeNames + name.lowercase()

    /**
     * The RFC 6381 CODECS token for this rung at rendition height [h] (null → assume 1080) with
     * optional audio, for the HLS master playlist's #EXT-X-STREAM-INF. H.264 reuses [hlsCodecsAttr]
     * (avc1.*); AV1 emits an av01.* string. AV2 has no registered manifest codec string and is
     * disabled, so it throws — it cannot reach a live manifest.
     */
    fun hlsCodecs(h: Int?, hasAudio: Boolean): String = when (this) {
        H264 -> hlsCodecsAttr(h, hasAudio)
        AV1 -> av1CodecsAttr(h, hasAudio)
        AV2 -> error("AV2 has no manifest codec string yet (#130: future rung, no HW decode)")
    }
}

/**
 * #130: the codec-preference decision. Single source of truth for "which video codec should the
 * Hub target for this device?", consumed by the transcoder and player in later slices.
 */
object CodecLadder {
    /** Enabled rungs in preference order (AV1 primary first). Excludes disabled future rungs (AV2). */
    val enabledRungs: List<VideoCodec> = VideoCodec.entries.filter { it.enabled }.sortedBy { it.rank }

    /** The universal fallback rung — always H.264. */
    val fallback: VideoCodec = VideoCodec.H264

    /**
     * Pick the transcode target codec for a device [profile]. Returns the highest-preference ENABLED
     * rung whose codec the profile declares support for. Falls back to H.264 ([fallback]) when the
     * profile is null, lists no codecs, or lists only unsupported / still-disabled codecs (e.g. a
     * profile that declares "av2" gets H.264 until that rung is enabled). This is why H.264 stays the
     * safe default that reproduces today's behavior for every legacy / capability-less client.
     */
    fun select(profile: CapabilityProfile?): VideoCodec {
        val declared = profile?.codecs.orEmpty().map { it.lowercase() }.toSet()
        if (declared.isEmpty()) return fallback
        return enabledRungs.firstOrNull { rung -> rung.declaredTokens.any { it in declared } } ?: fallback
    }
}

/**
 * #130: the RFC 6381 CODECS token for an AV1 rung: `av01.0.<levelIdx>M.08` — profile 0 (Main), the
 * seq_level_idx for the rendition height, Main tier (M), 8-bit (08) — plus mp4a.40.2 (AAC-LC) when
 * the variant carries audio. Mirrors [hlsCodecsAttr] (H.264) so each ladder rung can signal itself
 * in the HLS master playlist. Level is a conservative ceiling (decoders accept a codec string whose
 * level >= the actual stream); height is null only on the probe-failure rung, where we assume 1080.
 * Top-level + internal so it's unit-testable without a transcoder.
 */
internal fun av1CodecsAttr(h: Int?, hasAudio: Boolean): String {
    val height = h ?: 1080
    val levelIdx = when {
        height >= 2160 -> "13" // 5.1
        height >= 1440 -> "12" // 5.0
        height >= 1080 -> "08" // 4.0
        height >= 720 -> "05" // 3.1
        height >= 480 -> "04" // 3.0
        else -> "01" // 2.1
    }
    val video = "av01.0.${levelIdx}M.08"
    return if (hasAudio) "$video,mp4a.40.2" else video
}
