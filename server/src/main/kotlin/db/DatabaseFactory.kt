package wtf.jobin.db

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import wtf.jobin.config.AppConfig
import java.time.Duration

fun runMigrations(cfg: AppConfig.Db) {
    Flyway.configure()
        .dataSource(cfg.jdbcUrl, cfg.user, cfg.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

fun connectDatabase(cfg: AppConfig.Db): R2dbcDatabase {
    val baseFactory = ConnectionFactories.get(
        ConnectionFactoryOptions.parse(cfg.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, cfg.user)
            .option(ConnectionFactoryOptions.PASSWORD, cfg.password)
            .build()
    )
    val poolConfig = ConnectionPoolConfiguration.builder(baseFactory)
        .maxSize(cfg.poolMaxSize)
        .maxIdleTime(Duration.ofMinutes(5))
        .build()
    val pool = ConnectionPool(poolConfig)
    val builder = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = PostgreSQLDialect() }
    return R2dbcDatabase.connect(connectionFactory = pool, databaseConfig = builder)
}
