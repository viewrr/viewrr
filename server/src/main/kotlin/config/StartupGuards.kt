package wtf.jobin.config

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val UNSAFE_JWT_SECRET = "change-me-dev-only"
private const val UNSAFE_DB_PASSWORD = "postgres"
private const val LOCALHOST_PREFIX = "localhost:"

/**
 * Boot-time safety check. In production mode, exits the process if any of the
 * three known dev-only fallbacks (jwt secret, db password, localhost CORS) are
 * still in use. In dev mode, logs a single WARN listing them so they're visible.
 */
fun assertProdSafe(cfg: AppConfig) {
    val log = LoggerFactory.getLogger("wtf.jobin.config.StartupGuards")
    val issues = mutableListOf<String>()

    if (cfg.auth.jwtSecret == UNSAFE_JWT_SECRET) {
        issues += "viewrr.auth.jwtSecret is the dev default ($UNSAFE_JWT_SECRET) — set JWT_SECRET"
    }
    if (cfg.db.password == UNSAFE_DB_PASSWORD) {
        issues += "viewrr.db.password is the dev default ($UNSAFE_DB_PASSWORD) — set DB_PASSWORD"
    }
    val localhostHosts = cfg.cors.allowedHosts.filter { it.contains(LOCALHOST_PREFIX) }
    if (localhostHosts.isNotEmpty()) {
        issues += "viewrr.cors.allowedHosts contains localhost entries $localhostHosts — set non-localhost origins"
    }

    if (cfg.env == "production") {
        if (issues.isEmpty()) return
        log.error("FATAL: refusing to boot in production with unsafe defaults:")
        issues.forEach { log.error("  - $it") }
        exitProcess(1)
    }

    if (issues.isNotEmpty()) {
        log.warn("Dev-only fallbacks in use (env=${cfg.env}): ${issues.joinToString("; ")}")
    }
}
