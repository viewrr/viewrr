package wtf.jobin.editorial

import java.util.UUID
import kotlin.math.abs

/** A catalog Title the matcher can bind an editorial item to. */
data class MovieTitle(val id: UUID, val title: String, val cleanTitle: String?, val year: Int?)

/** A successful bind: which Title, and how confident (0.0..1.0). */
data class TitleMatch(val id: UUID, val score: Double)

/**
 * Best-effort binding of a review/award headline to a catalog Title. Normalized-string token overlap
 * (overlap coefficient) plus a year nudge. No new deps, no external service.
 *
 * Honest ceiling: this is a heuristic. It reliably catches "'Dune' Review" -> Dune and rejects
 * unrelated movies, but it will miss creative headlines and can't disambiguate same-title remakes
 * without a year. THRESHOLD is tuned for precision (few false links) over recall.
 */
object FuzzyMatcher {
    const val THRESHOLD = 0.6

    private val STOPWORDS = setOf("the", "a", "an", "of", "and", "part", "movie", "film", "review")

    fun match(headline: String, itemYear: Int?, candidates: List<MovieTitle>): TitleMatch? {
        val q = tokens(extractMovieTitle(headline))
        if (q.isEmpty()) return null
        var best: TitleMatch? = null
        for (c in candidates) {
            val candText = c.cleanTitle?.takeIf { it.isNotBlank() } ?: c.title
            var s = overlap(q, tokens(candText))
            if (itemYear != null && c.year != null) {
                s += if (abs(itemYear - c.year) <= 1) 0.15 else -0.30 // year agreement rewarded; clash punished
            }
            s = s.coerceIn(0.0, 1.0)
            if (s >= THRESHOLD && (best == null || s > best!!.score)) best = TitleMatch(c.id, s)
        }
        return best
    }

    /** Pull the likely movie title out of an editorial headline. */
    fun extractMovieTitle(headline: String): String {
        val t = headline.trim()
        // 1. Quoted title wins: 'Dune' Review, "Poor Things": A ...
        Regex("[\"'“”‘’](.+?)[\"'“”‘’]").find(t)
            ?.let { return it.groupValues[1].trim() }
        // 2. Strip review markers, then take the segment before the first separator.
        val stripped = t
            .replace(Regex("(?i)\\bmovie review\\b"), " ")
            .replace(Regex("(?i)\\breview of\\b"), " ")
            .replace(Regex("(?i)\\breview\\b"), " ")
        val seg = stripped.split(Regex("[:\\-–—|]"), limit = 2)
            .firstOrNull { it.isNotBlank() }?.trim()
        return (seg ?: stripped).trim()
    }

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\(\\d{4}\\)"), " ")   // drop a trailing (2024)
            .replace(Regex("[^a-z0-9]+"), " ")     // punctuation -> space
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(s: String): Set<String> =
        normalize(s).split(" ").filter { it.isNotBlank() && it !in STOPWORDS }.toSet()

    /** Overlap coefficient: |A ∩ B| / min(|A|,|B|). Robust to one title being longer than the other. */
    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / minOf(a.size, b.size)
    }
}
