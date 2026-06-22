package wtf.jobin.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilenameParserTest {

    @Test
    fun movieWithParens() {
        val r = FilenameParser.parse("The Matrix (1999).mkv")
        assertEquals("The Matrix", r.cleanTitle)
        assertEquals(1999, r.year)
        assertNull(r.showTitle)
        assertNull(r.seasonNumber)
        assertNull(r.episodeNumber)
    }

    @Test
    fun movieDotDelimitedWithJunk() {
        val r = FilenameParser.parse("The.Matrix.1999.1080p.BluRay.x264.mkv")
        assertEquals("The Matrix", r.cleanTitle)
        assertEquals(1999, r.year)
        assertNull(r.showTitle)
    }

    @Test
    fun tvSxxExx() {
        val r = FilenameParser.parse("Breaking.Bad.S01E02.Cat's.in.the.Bag.mkv")
        assertEquals("Breaking Bad", r.showTitle)
        assertEquals(1, r.seasonNumber)
        assertEquals(2, r.episodeNumber)
        assertEquals("Cat's in the Bag", r.cleanTitle)
    }

    @Test
    fun tvNxNN() {
        val r = FilenameParser.parse("Show Name - 1x02.mkv")
        assertEquals("Show Name", r.showTitle)
        assertEquals(1, r.seasonNumber)
        assertEquals(2, r.episodeNumber)
    }

    @Test
    fun tvSeasonEpisodeWords() {
        val r = FilenameParser.parse("Some Show Season 3 Episode 7.mp4")
        assertEquals("Some Show", r.showTitle)
        assertEquals(3, r.seasonNumber)
        assertEquals(7, r.episodeNumber)
    }

    @Test
    fun noMatch() {
        val r = FilenameParser.parse("random_clip.mp4")
        assertEquals("random clip", r.cleanTitle)
        assertNull(r.year)
        assertNull(r.showTitle)
        assertNull(r.seasonNumber)
        assertNull(r.episodeNumber)
    }
}
