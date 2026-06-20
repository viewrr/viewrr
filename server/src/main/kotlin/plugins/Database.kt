package wtf.jobin.plugins

import io.ktor.server.application.*
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig
import wtf.jobin.db.DatabaseFactory

fun Application.configureDatabase() {
    val cfg by inject<AppConfig>()
    DatabaseFactory.migrate(cfg.db)
    log.info("Flyway migrations applied")
}
