package wtf.jobin

import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import wtf.jobin.config.AppConfig
import wtf.jobin.koin.authModule
import wtf.jobin.koin.dbModule
import wtf.jobin.koin.redisModule
import wtf.jobin.koin.scannerModule

fun Application.configureKoin() {
    val appConfig = AppConfig.from(environment)
    install(Koin) {
        slf4jLogger()
        modules(
            module { single { appConfig } },
            dbModule,
            redisModule,
            authModule,
            scannerModule,
        )
    }
}
