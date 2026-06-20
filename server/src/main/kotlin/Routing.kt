package wtf.jobin

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import wtf.jobin.auth.AuthService
import wtf.jobin.auth.UserRepository
import wtf.jobin.auth.adminUserRoutes
import wtf.jobin.auth.authRoutes
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.scannerRoutes
import wtf.jobin.media.MediaSearchService
import wtf.jobin.media.mediaSearchRoutes

fun Application.configureRouting() {
    val auth by inject<AuthService>()
    val users by inject<UserRepository>()
    val scanner by inject<MediaScanner>()
    val mediaSearch by inject<MediaSearchService>()
    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(auth)
        adminUserRoutes(users)
        scannerRoutes(scanner)
        mediaSearchRoutes(mediaSearch)
    }
}
