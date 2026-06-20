package wtf.jobin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*

fun Application.configureHttp() {
    val allowedHosts = environment.config
        .propertyOrNull("viewrr.cors.allowedHosts")
        ?.getList()
        ?: emptyList()

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowedHosts.forEach { allowHost(it) }
    }
    install(Compression)
    install(ForwardedHeaders) // for use behind a reverse proxy
    install(XForwardedHeaders)
}
