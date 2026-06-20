package wtf.jobin

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import wtf.jobin.auth.AuthService
import wtf.jobin.auth.authRoutes
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.scannerRoutes

fun Application.configureRouting() {
    val auth by inject<AuthService>()
    val scanner by inject<MediaScanner>()
    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(auth)
        scannerRoutes(scanner)
    }
}
