package wtf.jobin.scanner

import wtf.jobin.stremio.CapabilityProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #130 (P2P-ADR 0002): AV1-primary + H.264-fallback codec ladder. Pure selection/config —
// asserts AV1 wins when the device supports it, H.264 is the universal fallback, and AV2 stays
// a disabled future rung that never gets selected.
class CodecLadderTest {

    @Test
    fun av1PickedWhenProfileSupportsIt() {
        // Device that can decode both AV1 and H.264 → the Hub targets AV1 (primary rung).
        val profile = CapabilityProfile(codecs = listOf("av1", "h264"))
        assertEquals(VideoCodec.AV1, CodecLadder.select(profile))
    }

    @Test
    fun av1PickedRegardlessOfDeclaredOrder() {
        // Preference is by ladder rank, not the order the device lists codecs.
        val profile = CapabilityProfile(codecs = listOf("h264", "av1"))
        assertEquals(VideoCodec.AV1, CodecLadder.select(profile))
    }

    @Test
    fun av1PickedWhenOnlyAv1Supported() {
        assertEquals(VideoCodec.AV1, CodecLadder.select(CapabilityProfile(codecs = listOf("av1"))))
    }

    @Test
    fun h264FallbackWhenOnlyH264Supported() {
        assertEquals(VideoCodec.H264, CodecLadder.select(CapabilityProfile(codecs = listOf("h264"))))
    }

    @Test
    fun h264FallbackWhenProfileNull() {
        // No capability profile (today's default / legacy client) → H.264, preserving current output.
        assertEquals(VideoCodec.H264, CodecLadder.select(null))
    }

    @Test
    fun h264FallbackWhenNoCodecsDeclared() {
        assertEquals(VideoCodec.H264, CodecLadder.select(CapabilityProfile(codecs = emptyList())))
    }

    @Test
    fun h264FallbackWhenOnlyUnsupportedCodec() {
        // A codec the ladder doesn't know (e.g. HEVC) degrades to the universal fallback.
        assertEquals(VideoCodec.H264, CodecLadder.select(CapabilityProfile(codecs = listOf("hevc"))))
    }

    @Test
    fun av2IsAFutureDisabledRungAndNeverSelected() {
        // AV2 exists as config but is disabled: a device declaring ONLY av2 falls back to H.264.
        assertFalse(VideoCodec.AV2.enabled)
        assertEquals(VideoCodec.H264, CodecLadder.select(CapabilityProfile(codecs = listOf("av2"))))
        // AV1 still wins when both the future rung and AV1 are declared.
        assertEquals(
            VideoCodec.AV1,
            CodecLadder.select(CapabilityProfile(codecs = listOf("av2", "av1", "h264"))),
        )
    }

    @Test
    fun codecTokenMatchIsCaseInsensitive() {
        assertEquals(VideoCodec.AV1, CodecLadder.select(CapabilityProfile(codecs = listOf("AV1"))))
        assertEquals(VideoCodec.AV1, CodecLadder.select(CapabilityProfile(codecs = listOf("Av01"))))
    }

    @Test
    fun enabledRungsAreAv1ThenH264InPreferenceOrder() {
        assertEquals(listOf(VideoCodec.AV1, VideoCodec.H264), CodecLadder.enabledRungs)
        assertTrue(VideoCodec.AV1.rank < VideoCodec.H264.rank)
    }

    @Test
    fun enabledRungsHaveAnEncoderWired() {
        // Every selectable rung must have an ffmpeg encoder; the disabled AV2 rung is exempt.
        assertEquals("libsvtav1", VideoCodec.AV1.ffmpegEncoder)
        assertEquals("libx264", VideoCodec.H264.ffmpegEncoder)
        assertEquals(null, VideoCodec.AV2.ffmpegEncoder)
    }

    @Test
    fun h264RungReusesExistingAvc1ManifestCodecs() {
        // The H.264 rung's manifest CODECS is byte-identical to the existing hlsCodecsAttr output.
        assertEquals(hlsCodecsAttr(1080, hasAudio = true), VideoCodec.H264.hlsCodecs(1080, hasAudio = true))
        assertEquals("avc1.640028,mp4a.40.2", VideoCodec.H264.hlsCodecs(1080, hasAudio = true))
    }

    @Test
    fun av1RungManifestCodecsByHeight() {
        assertEquals("av01.0.08M.08,mp4a.40.2", VideoCodec.AV1.hlsCodecs(1080, hasAudio = true))
        assertEquals("av01.0.05M.08,mp4a.40.2", VideoCodec.AV1.hlsCodecs(720, hasAudio = true))
        assertEquals("av01.0.12M.08,mp4a.40.2", VideoCodec.AV1.hlsCodecs(1440, hasAudio = true))
        assertEquals("av01.0.13M.08,mp4a.40.2", VideoCodec.AV1.hlsCodecs(2160, hasAudio = true))
    }

    @Test
    fun av1RungManifestCodecsVideoOnlyAndNullHeight() {
        assertEquals("av01.0.08M.08", VideoCodec.AV1.hlsCodecs(1080, hasAudio = false))
        // null height (probe-failure rung) assumes 1080, matching hlsCodecsAttr.
        assertEquals("av01.0.08M.08,mp4a.40.2", VideoCodec.AV1.hlsCodecs(null, hasAudio = true))
    }
}
