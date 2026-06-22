package wtf.jobin

import io.ktor.server.application.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import wtf.jobin.config.AppConfig
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
import wtf.jobin.scanner.LibraryRepository
import wtf.jobin.scanner.LibraryWatcher
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.libraryRoutes
import wtf.jobin.scanner.mediaRoutes
import wtf.jobin.scanner.mediaAdminRoutes
import wtf.jobin.scanner.scannerRoutes
import wtf.jobin.recs.RecEngineClient
import wtf.jobin.recs.RecsRepository
import wtf.jobin.recs.adminRecsRoutes
import wtf.jobin.recs.recsRoutes
import wtf.jobin.watch.ContinueWatchingService
import wtf.jobin.watch.WatchEventRepository
import wtf.jobin.watch.continueWatchingRoutes
import wtf.jobin.watch.watchEventRoutes
import wtf.jobin.party.PartyHub
import wtf.jobin.party.PartyRoomRepository
import wtf.jobin.party.partyRoomRoutes
import wtf.jobin.streaming.streamRoutes
import wtf.jobin.streaming.subtitleRoutes
import wtf.jobin.trickplay.trickplayRoutes
import wtf.jobin.party.partyWebSocketRoutes
import wtf.jobin.downloads.DownloadService
import wtf.jobin.downloads.downloadRoutes
import wtf.jobin.collection.CollectionRepository
import wtf.jobin.collection.collectionRoutes
import wtf.jobin.series.seriesRoutes

fun Application.configureRouting() {
    val auth by inject<AuthService>()
    val users by inject<UserRepository>()
    val scanner by inject<MediaScanner>()
    val transcoder by inject<HlsTranscoder>()
    val libraries by inject<LibraryRepository>()
    val libraryWatcher by inject<LibraryWatcher>()
    val mediaSearch by inject<MediaSearchService>()
    val recs by inject<RecsRepository>()
    val recEngine by inject<RecEngineClient>()
    val watchEvents by inject<WatchEventRepository>()
    val continueWatching by inject<ContinueWatchingService>()
    val partyRooms by inject<PartyRoomRepository>()
    val db by inject<R2dbcDatabase>()
    val appConfig by inject<AppConfig>()
    val partyHub by inject<PartyHub>()
    val downloads by inject<DownloadService>()
    val collections by inject<CollectionRepository>()
    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(auth)
        adminUserRoutes(users)
        scannerRoutes(scanner)
        libraryRoutes(libraries, libraryWatcher, scanner)
        mediaRoutes(transcoder)
        mediaSearchRoutes(mediaSearch)
        recsRoutes(recs)
        adminRecsRoutes(recEngine)
        watchEventRoutes(watchEvents)
        continueWatchingRoutes(continueWatching)
        partyRoomRoutes(partyRooms, partyHub)
        streamRoutes(db, appConfig.media)
        trickplayRoutes(db, appConfig.media)
        subtitleRoutes(db, appConfig.media)
        partyWebSocketRoutes(partyHub, db)
        downloadRoutes(downloads, appConfig.publicBaseUrl)
        collectionRoutes(collections)
        seriesRoutes(db)
        mediaAdminRoutes(db)
    }
    partyHub.startFlushLoop(this)
}
