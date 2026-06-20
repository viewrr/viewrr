package wtf.jobin.koin

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import org.koin.dsl.module
import wtf.jobin.config.AppConfig

val redisModule = module {
    single<RedisClient>(createdAtStart = true) { RedisClient.create(get<AppConfig>().redis.uri) }
    single<StatefulRedisConnection<String, String>> { get<RedisClient>().connect() }
    single<RedisAsyncCommands<String, String>> { get<StatefulRedisConnection<String, String>>().async() }
}
