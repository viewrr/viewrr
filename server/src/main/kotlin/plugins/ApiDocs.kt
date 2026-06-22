package wtf.jobin.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Ktor-native API docs: interactive Swagger UI at /swagger, plus the raw spec at
 * /openapi.yaml (handy for Bruno/Scalar/Redoc imports). Both are unauthenticated —
 * the schema is public; only the endpoints behind it require a token.
 */
fun Application.configureApiDocs() {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")

        get("/openapi.yaml") {
            val spec = javaClass.classLoader.getResourceAsStream("openapi/documentation.yaml")
            if (spec == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondText(spec.bufferedReader().use { it.readText() }, ContentType.parse("application/yaml"))
            }
        }
    }
}
