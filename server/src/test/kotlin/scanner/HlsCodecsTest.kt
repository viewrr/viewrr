package wtf.jobin.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

// #116: master playlist must carry a valid CODECS per rendition or hls.js stalls before FRAG_LOADING.
class HlsCodecsTest {
    @Test
    fun videoAndAudioByHeight() {
        assertEquals("avc1.640028,mp4a.40.2", hlsCodecsAttr(1080, hasAudio = true))
        assertEquals("avc1.64001f,mp4a.40.2", hlsCodecsAttr(720, hasAudio = true))
        assertEquals("avc1.640032,mp4a.40.2", hlsCodecsAttr(1440, hasAudio = true))
        assertEquals("avc1.640033,mp4a.40.2", hlsCodecsAttr(2160, hasAudio = true))
    }

    @Test
    fun videoOnlyWhenNoAudio() {
        assertEquals("avc1.640028", hlsCodecsAttr(1080, hasAudio = false))
    }

    @Test
    fun nullHeightAssumes1080() {
        assertEquals("avc1.640028,mp4a.40.2", hlsCodecsAttr(null, hasAudio = true))
    }
}
