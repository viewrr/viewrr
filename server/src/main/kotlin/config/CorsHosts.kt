package wtf.jobin.config

/**
 * A CORS allow-list entry resolved into the (host, schemes) shape Ktor's `allowHost` expects.
 *
 * #118: entries in `viewrr.cors.allowedHosts` may be written two ways:
 *  - bare authority ("localhost:5173", "app.viewrr.stream") — treated as http-only, which matches
 *    Ktor's `allowHost` default and preserves the pre-#118 dev behavior exactly.
 *  - full origin with scheme ("https://app.viewrr.stream") — allows exactly that scheme. A browser
 *    sends "https://app.viewrr.stream" as its Origin for the deployed client, so the prod origin
 *    MUST be configured with the scheme or the preflight is rejected.
 */
data class CorsHost(val host: String, val schemes: List<String>)

/** Parse one allow-list entry into a [CorsHost], or null if the entry is blank/malformed. */
fun parseCorsHost(entry: String): CorsHost? {
    val trimmed = entry.trim()
    if (trimmed.isEmpty()) return null
    val schemeSep = trimmed.indexOf("://")
    if (schemeSep <= 0) {
        // Bare authority — keep Ktor's default http scheme (unchanged dev behavior).
        return CorsHost(trimmed.trimEnd('/'), listOf("http"))
    }
    val scheme = trimmed.substring(0, schemeSep).lowercase()
    val host = trimmed.substring(schemeSep + 3).trimEnd('/')
    if (host.isEmpty()) return null
    return CorsHost(host, listOf(scheme))
}

/**
 * Resolve the configured allow-list into deduped [CorsHost] entries ready for `allowHost`.
 * Blank/malformed entries are dropped so a trailing comma or empty env value is harmless.
 */
fun resolveCorsHosts(allowedHosts: List<String>): List<CorsHost> =
    allowedHosts.mapNotNull(::parseCorsHost).distinct()
