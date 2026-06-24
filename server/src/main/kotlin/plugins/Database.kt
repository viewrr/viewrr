package wtf.jobin.plugins

import io.ktor.server.application.*
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig
import wtf.jobin.db.runMigrations

fun Application.configureDatabase() {
    val cfg by inject<AppConfig>()
    if (cfg.role == wtf.jobin.config.Role.AGENT) {
        log.info("Agent: skipping DB + migrations (#97 stateless agent)")
        return
    }
    runMigrations(cfg.db)
    log.info("Flyway migrations applied")
}
