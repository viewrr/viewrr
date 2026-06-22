package wtf.jobin.scanner

/** Filename-parsed metadata. [cleanTitle] is always populated; the rest are best-effort. */
data class ParsedMedia(
    val cleanTitle: String,
    val year: Int?,
    val showTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/**
 * Pure filename → metadata parser. No external dep, no API key. TV markers win over
 * the movie-year pattern. Self-checked in FilenameParserTest.
 */
object FilenameParser {

    private val EXT = Regex("""\.(mp4|m4v|mkv|webm|mov|avi|ts)$""", RegexOption.IGNORE_CASE)

    // Tried in order; group 1 = season, group 2 = episode.
    private val TV_PATTERNS = listOf(
        Regex("""[Ss](\d{1,2})[Ee](\d{1,2})"""),                       // S01E02
        Regex("""(?<!\d)(\d{1,2})[xX](\d{1,2})(?!\d)"""),              // 1x02 (not inside 1920x1080)
        Regex("""[Ss]eason\s+(\d{1,2})\s+[Ee]pisode\s+(\d{1,2})"""),   // Season 1 Episode 2
    )

    // Standalone 4-digit year 1900-2099, not embedded in a longer number (avoids 1080p, 2160p).
    private val YEAR = Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""")

    // Quality/junk tokens (lowercased). On a match, the token and everything after it is dropped.
    private val JUNK = setOf(
        "1080p", "720p", "2160p", "480p", "bluray", "webrip", "web-dl",
        "x264", "x265", "hevc", "aac", "dts", "hdr", "remux", "proper",
    )

    fun parse(filename: String): ParsedMedia {
        val base = EXT.replaceFirst(filename, "")

        for (re in TV_PATTERNS) {
            val m = re.find(base) ?: continue
            val season = m.groupValues[1].toInt()
            val episode = m.groupValues[2].toInt()
            val show = clean(base.substring(0, m.range.first)).ifBlank { null }
            val cleanTitle = clean(base.substring(m.range.last + 1)).ifBlank {
                val s = season.toString().padStart(2, '0')
                val e = episode.toString().padStart(2, '0')
                listOfNotNull(show, "S${s}E$e").joinToString(" ")
            }
            return ParsedMedia(cleanTitle, year = null, showTitle = show, seasonNumber = season, episodeNumber = episode)
        }

        YEAR.find(base)?.let { m ->
            return ParsedMedia(
                cleanTitle = clean(base.substring(0, m.range.first)),
                year = m.groupValues[1].toInt(),
                showTitle = null, seasonNumber = null, episodeNumber = null,
            )
        }

        return ParsedMedia(clean(base), year = null, showTitle = null, seasonNumber = null, episodeNumber = null)
    }

    /** Delimiters → spaces, drop the first junk token and everything after, collapse + trim. */
    private fun clean(raw: String): String {
        val spaced = raw.replace(Regex("""[._()\[\]]"""), " ").trim().replace(Regex("""\s+"""), " ")
        if (spaced.isEmpty()) return ""
        return spaced.split(' ')
            .takeWhile { it.lowercase() !in JUNK }
            .joinToString(" ")
            .trim()
            .trim('-', ' ')
    }
}
