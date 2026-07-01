package wtf.jobin.editorial

/**
 * What an editorial item is, once classified. `slug` is the stored `type` for highlights; REVIEW and
 * IGNORE have no highlight slug.
 */
enum class EditorialKind(val slug: String?) {
    REVIEW(null),
    OSCAR_NOM("oscar-nom"),
    GLOBE_NOM("globe-nom"),
    FESTIVAL_WIN("festival-win"),
    IGNORE(null),
}

/**
 * Keyword classifier over an item's title + content. Deliberately dumb and explainable — no ML, no
 * new deps. Award/festival signals win over "review" because an award headline is the more specific
 * event. Order of checks encodes precedence.
 *
 * Honest ceiling: keyword rules will mislabel the occasional headline (e.g. a *review* that merely
 * mentions "Oscar buzz"). Tuned to favor precision on the award slugs (require an explicit win/nom
 * verb) so we don't spray false badges.
 */
object Classifier {

    private val FESTIVALS = listOf(
        "cannes", "venice", "berlinale", "berlin film festival", "sundance",
        "toronto", "tiff", "locarno", "telluride", "sxsw",
    )
    private val FESTIVAL_WIN_VERBS = listOf(
        "win", "wins", "won", "palme d'or", "palme dor", "golden lion", "grand jury", "top prize", "best film",
    )

    fun classify(title: String, content: String? = null): EditorialKind {
        val text = (title + " " + (content ?: "")).lowercase()

        val mentionsNom = text.contains("nomin") // nominee / nomination / nominated
        if ((text.contains("oscar") || text.contains("academy award")) && mentionsNom) return EditorialKind.OSCAR_NOM
        if (text.contains("golden globe") && mentionsNom) return EditorialKind.GLOBE_NOM

        val atFestival = FESTIVALS.any { text.contains(it) }
        if (atFestival && FESTIVAL_WIN_VERBS.any { text.contains(it) }) return EditorialKind.FESTIVAL_WIN

        // Review signal: the word "review" in the *title* is the strong tell; outlets like Ebert put
        // it in the URL/headline. Body-only "review" is too noisy to trust.
        if (title.lowercase().contains("review")) return EditorialKind.REVIEW

        return EditorialKind.IGNORE
    }

    /** Human badge text for a highlight kind, e.g. shown on the thumbnail. */
    fun badgeLabel(kind: EditorialKind): String = when (kind) {
        EditorialKind.OSCAR_NOM -> "Oscar Nominee"
        EditorialKind.GLOBE_NOM -> "Golden Globe Nominee"
        EditorialKind.FESTIVAL_WIN -> "Festival Winner"
        EditorialKind.REVIEW, EditorialKind.IGNORE -> ""
    }
}
