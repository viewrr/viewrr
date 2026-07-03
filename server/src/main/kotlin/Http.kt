package wtf.jobin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig
import wtf.jobin.config.resolveCorsHosts

fun Application.configureHttp() {
    val cfg by inject<AppConfig>()

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // #118: entries may carry a scheme (e.g. "https://app.viewrr.stream" in prod); bare
        // "host:port" entries stay http-only, preserving the dev localhost behavior.
        resolveCorsHosts(cfg.cors.allowedHosts).forEach { allowHost(it.host, schemes = it.schemes) }
    }
    install(Compression)
    install(ForwardedHeaders) // for use behind a reverse proxy
    install(XForwardedHeaders)
}
