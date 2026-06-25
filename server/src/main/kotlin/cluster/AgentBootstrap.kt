package wtf.jobin.cluster

import io.ktor.server.application.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig
import wtf.jobin.config.Role
import wtf.jobin.scanner.Ffprobe
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 14 (#68): Agent-mode boot. When role=AGENT, self-register with the Hub
 * (stdlib HttpClient — no new dep) and persist {nodeId, token} so a re-boot skips
 * re-registering. No-op in HUB mode.
 *
 * ponytail: one-shot blocking call at startup; fine for boot. Re-register/refresh
 * of addresses + heartbeat are later issues (#83). Real agents must also skip the
 * DB/scanner plugins (still role-blind in the module list) — trim when agent runs
 * on a NAS without Postgres.
 */
fun Application.configureAgent() {
    val cfg by inject<AppConfig>()
    if (cfg.role != Role.AGENT) return

    val json = Json { ignoreUnknownKeys = true }
    val tokenPath = Path.of(cfg.agent.tokenFile)

    if (!Files.exists(tokenPath)) {
        val name = cfg.agent.name.ifBlank {
            runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("agent")
        }
        val body = json.encodeToString(
            RegisterRequest(
                enrollmentSecret = cfg.cluster.enrollmentSecret,
                name = name,
                meshAddress = cfg.agent.meshAddress,
                clientAddress = cfg.agent.clientAddress,
            ),
        )
        val req = HttpRequest.newBuilder(URI.create("${cfg.agent.hubBaseUrl.trimEnd('/')}/agent/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = try {
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("Agent: register call to ${cfg.agent.hubBaseUrl} failed: ${e.message}")
            return
        }
        if (resp.statusCode() != 200) {
            log.error("Agent: register rejected (HTTP ${resp.statusCode()}) — check enrollment secret")
            return
        }
        Files.writeString(tokenPath, resp.body())
        val r = json.decodeFromString<RegisterResponse>(resp.body())
        log.info("Agent: registered as node ${r.nodeId}; token persisted to ${cfg.agent.tokenFile}")
    } else {
        log.info("Agent: token present (${cfg.agent.tokenFile}); skipping register")
    }

    // Phase 15 (#81): scan local roots + push to the Hub (background; uses the token above).
    val scanner = AgentScanner(
        Ffprobe(cfg.media.ffprobePath),
        cfg.agent.hubBaseUrl,
        cfg.agent.tokenFile,
        cfg.agent.libraryRoots,
    )
    launch {
        runCatching { scanner.scanAndPush() }
            .onFailure { log.error("Agent: scan/push failed", it) }
    }

    // #83: heartbeat loop — stamp last_seen on the Hub every 30s so it knows we're online.
    launch {
        val hbUrl = URI.create("${cfg.agent.hubBaseUrl.trimEnd('/')}/agent/heartbeat")
        while (isActive) {
            kotlinx.coroutines.delay(30_000)
            runCatching {
                val token = json.decodeFromString<RegisterResponse>(Files.readString(tokenPath)).token
                val hb = HttpRequest.newBuilder(hbUrl)
                    .header("X-Viewrr-Token", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                HttpClient.newHttpClient().send(hb, HttpResponse.BodyHandlers.discarding())
            }.onFailure { log.warn("Agent: heartbeat failed: ${it.message}") }
        }
    }
}
