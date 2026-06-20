package wtf.jobin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig

fun Application.configureHttp() {
    val cfg by inject<AppConfig>()

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        cfg.cors.allowedHosts.forEach { allowHost(it) }
    }
    install(Compression)
    install(ForwardedHeaders) // for use behind a reverse proxy
    install(XForwardedHeaders)
}
