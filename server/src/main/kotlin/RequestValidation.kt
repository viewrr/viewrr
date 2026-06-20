package wtf.jobin

import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.RequestValidation

fun Application.configureRequestValidation() {
    install(RequestValidation) {
        // Per-route validators are added as routes appear.
    }
}
