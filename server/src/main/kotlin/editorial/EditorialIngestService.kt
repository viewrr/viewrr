package wtf.jobin.editorial

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wtf.jobin.editorial.EditorialIngestService")

@Serializable
data class RefreshSummary(
    val feeds: Int,
    val itemsSeen: Int,
    val reviewsAdded: Int,
    val highlightsAdded: Int,
    val unmatched: Int,
)

/**
 * Orchestrates one editorial pass: load feed config -> fetch each -> classify each item -> fuzzy-match
 * to a catalog Title -> store as a review link or an award/festival highlight. Idempotent: re-running
 * only adds genuinely new links (repo dedups on unique keys).
 *
 * ponytail: sequential over feeds/items — the network is the bottleneck, not CPU, and personal
 * catalogs are small. Parallelize with a bounded dispatcher only if refresh latency actually bites.
 */
class EditorialIngestService(
    private val repo: EditorialRepository,
    private val fetcher: FeedFetcher = FeedFetcher(),
    private val feedsResource: String = EditorialFeeds.DEFAULT_RESOURCE,
) {
    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")

    suspend fun refresh(): RefreshSummary {
        val feeds = EditorialFeeds.load(feedsResource)
        val titles = repo.allTitles()
        if (titles.isEmpty()) log.info("editorial refresh: catalog empty, nothing to match against")

        var itemsSeen = 0
        var reviewsAdded = 0
        var highlightsAdded = 0
        var unmatched = 0

        for (feed in feeds) {
            val items = fetcher.fetch(feed)
            for (item in items) {
                itemsSeen++
                val kind = Classifier.classify(item.title, item.content)
                if (kind == EditorialKind.IGNORE) continue

                val year = yearRegex.find(item.title)?.value?.toIntOrNull()
                val m = FuzzyMatcher.match(item.title, year, titles)
                if (m == null) {
                    unmatched++
                    continue
                }

                if (kind == EditorialKind.REVIEW) {
                    val url = item.url ?: continue // review rows need a link; no link = nothing to render
                    if (repo.insertReview(m.id, feed.name, url, item.publishedAt, item.summary, null, m.score.toFloat())) {
                        reviewsAdded++
                    }
                } else {
                    if (repo.insertHighlight(m.id, kind.slug!!, Classifier.badgeLabel(kind), item.url, item.publishedAt)) {
                        highlightsAdded++
                    }
                }
            }
        }

        val summary = RefreshSummary(feeds.size, itemsSeen, reviewsAdded, highlightsAdded, unmatched)
        log.info("editorial refresh done: {}", summary)
        return summary
    }
}
