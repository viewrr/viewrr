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
import wtf.jobin.music.MusicScanner
import wtf.jobin.music.musicRoutes
import wtf.jobin.media.mediaListRoutes
import wtf.jobin.media.playbackRoutes
import wtf.jobin.media.homeRoutes
import wtf.jobin.media.mediaSearchRoutes
import wtf.jobin.scanner.HlsTranscoder
import wtf.jobin.scanner.TranscodeCoordinator
import wtf.jobin.scanner.LibraryRepository
import wtf.jobin.scanner.LibraryWatcher
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.libraryRoutes
import wtf.jobin.scanner.mediaRoutes
import wtf.jobin.scanner.mediaAdminRoutes
import wtf.jobin.scanner.TmdbClient
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
import wtf.jobin.stremio.StremioKeys
import wtf.jobin.stremio.stremioRoutes
import wtf.jobin.cluster.NodeRegistry
import wtf.jobin.cluster.agentRoutes
import wtf.jobin.cluster.agentRawRoutes
import wtf.jobin.cluster.agentHlsRoutes // #95

fun Application.configureRouting() {
    val appConfig by inject<AppConfig>()
    // Phase 14 (#68): AGENT serves a minimal surface (no Hub routes). Raw byte
    // serving (#75) mounts here later. ponytail: agent still boots DB/scanner
    // plugins for now (module list is role-blind) — trim for NAS deploy.
    if (appConfig.role == wtf.jobin.config.Role.AGENT) {
        routing {
            get("/health") { call.respondText("ok") }
            agentRawRoutes(appConfig.cluster.enrollmentSecret, appConfig.agent.libraryRoots) // #76
            // #95 (Phase 18): edge-cache receiver. Mounted ONLY when edge-cache is enabled, so the
            // default-off agent surface is unchanged (just /health + /raw). TODO needs node deployed.
            if (appConfig.media.edgeCacheEnabled) {
                agentHlsRoutes(appConfig.cluster.enrollmentSecret, appConfig.media.hlsCacheRoot)
            }
        }
        return
    }
    val auth by inject<AuthService>()
    val users by inject<UserRepository>()
    val scanner by inject<MediaScanner>()
    val tmdb by inject<TmdbClient>()
    val transcoder by inject<HlsTranscoder>()
    val transcodeCoordinator by inject<TranscodeCoordinator>()
    val libraries by inject<LibraryRepository>()
    val libraryWatcher by inject<LibraryWatcher>()
    val musicScanner by inject<MusicScanner>()
    val mediaSearch by inject<MediaSearchService>()
    val recs by inject<RecsRepository>()
    val recEngine by inject<RecEngineClient>()
    val watchEvents by inject<WatchEventRepository>()
    val continueWatching by inject<ContinueWatchingService>()
    val partyRooms by inject<PartyRoomRepository>()
    val db by inject<R2dbcDatabase>()
    val partyHub by inject<PartyHub>()
    val downloads by inject<DownloadService>()
    val collections by inject<CollectionRepository>()
    val stremioKeys by inject<StremioKeys>()
    val nodeRegistry by inject<NodeRegistry>()
    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(auth)
        adminUserRoutes(users)
        scannerRoutes(scanner, musicScanner)
        libraryRoutes(libraries, libraryWatcher, scanner, musicScanner)
        mediaRoutes(transcoder)
        mediaSearchRoutes(mediaSearch)
        mediaListRoutes(db)
        playbackRoutes(db, stremioKeys, appConfig.publicBaseUrl, appConfig.cluster.enrollmentSecret, appConfig.media.edgeCacheEnabled) // #79 / #95
        homeRoutes(db)
        recsRoutes(recs)
        adminRecsRoutes(recEngine)
        watchEventRoutes(watchEvents)
        continueWatchingRoutes(continueWatching)
        partyRoomRoutes(partyRooms, partyHub)
        streamRoutes(db, appConfig.media, stremioKeys, transcodeCoordinator)
        trickplayRoutes(db, appConfig.media)
        subtitleRoutes(db, appConfig.media)
        partyWebSocketRoutes(partyHub, db)
        downloadRoutes(downloads, appConfig.publicBaseUrl)
        collectionRoutes(collections)
        seriesRoutes(db)
        musicRoutes(db)
        mediaAdminRoutes(db, tmdb)
        agentRoutes(nodeRegistry, db)
        stremioRoutes(db, appConfig.media, appConfig.publicBaseUrl, stremioKeys)
    }
    partyHub.startFlushLoop(this)
}
