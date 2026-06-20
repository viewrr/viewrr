package wtf.jobin.koin

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.dsl.module
import wtf.jobin.config.AppConfig
import wtf.jobin.db.DatabaseFactory

val dbModule = module {
    single<R2dbcDatabase> { DatabaseFactory.connect(get<AppConfig>().db) }
}
