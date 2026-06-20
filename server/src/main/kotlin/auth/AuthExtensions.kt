package wtf.jobin.auth

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import java.util.UUID

fun ApplicationCall.userId(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.subject!!)

fun ApplicationCall.requireAdmin() {
    val isAdmin = principal<JWTPrincipal>()
        ?.payload
        ?.getClaim("admin")
        ?.asBoolean() == true
    // 404 over 403 — don't leak admin surface.
    if (!isAdmin) throw NotFoundException()
}
