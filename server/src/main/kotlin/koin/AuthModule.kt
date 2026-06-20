package wtf.jobin.koin

import org.koin.dsl.module
import wtf.jobin.auth.AuthService
import wtf.jobin.auth.PasswordHasher
import wtf.jobin.auth.TokenService
import wtf.jobin.auth.UserRepository
import wtf.jobin.config.AppConfig

val authModule = module {
    single { PasswordHasher() }
    single { TokenService(get<AppConfig>().auth, get()) }
    single { UserRepository(get()) }
    single { AuthService(get(), get(), get()) }
}
