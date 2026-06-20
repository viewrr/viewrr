package wtf.jobin

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import wtf.jobin.auth.AuthService
import wtf.jobin.auth.UserRepository
import wtf.jobin.auth.adminUserRoutes
import wtf.jobin.auth.authRoutes
import wtf.jobin.media.MediaSearchService
import wtf.jobin.media.mediaSearchRoutes
import wtf.jobin.scanner.HlsTranscoder
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.mediaRoutes
import wtf.jobin.scanner.scannerRoutes
import wtf.jobin.recs.RecsRepository
import wtf.jobin.recs.recsRoutes
import wtf.jobin.watch.WatchEventRepository
import wtf.jobin.watch.watchEventRoutes
import wtf.jobin.party.PartyRoomRepository
import wtf.jobin.party.partyRoomRoutes

fun Application.configureRouting() {
    val auth by inject<AuthService>()
    val users by inject<UserRepository>()
    val scanner by inject<MediaScanner>()
    val transcoder by inject<HlsTranscoder>()
    val mediaSearch by inject<MediaSearchService>()
    val recs by inject<RecsRepository>()
    val watchEvents by inject<WatchEventRepository>()
    val partyRooms by inject<PartyRoomRepository>()
    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(auth)
        adminUserRoutes(users)
        scannerRoutes(scanner)
        mediaRoutes(transcoder)
        mediaSearchRoutes(mediaSearch)
        recsRoutes(recs)
        watchEventRoutes(watchEvents)
        partyRoomRoutes(partyRooms)
    }
}
