package wtf.jobin.media

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * #86: re-acquire trigger. When a stream/playback request finds NO online copy for a Title
 * (see [wtf.jobin.db.hasOnlineCopy] / #85), the request path fires this so the missing media
 * can later be re-fetched. Actual acquisition (arr / torrent) is Phase 17 and NOT built here —
 * this is a logging stub that is idempotent: repeated triggers for the same Title inside the
 * debounce window are dropped so a player retry-storm doesn't spam the log (or, later, the
 * acquisition queue).
 *
 * ponytail: a process-wide singleton with an in-memory debounce map — no DB, no DI threading.
 * Good enough for the stub; when real acquisition lands the debounce/idempotency can move to a
 * persisted queue. // TODO #87+ wire to arr (Radarr/Sonarr) / torrent acquisition.
 */
object ReacquireService {
    private val log = LoggerFactory.getLogger(ReacquireService::class.java)

    // #86: how long to suppress duplicate triggers for the same Title. A player that can't
    // play an offline title will retry the stream/playback call repeatedly; we only want one
    // re-acquire signal per title per window.
    private val DEBOUNCE: Duration = Duration.ofMinutes(5)

    // titleId -> last time we actually fired (not just received) a trigger.
    private val lastFired = ConcurrentHashMap<UUID, Instant>()

    /**
     * #86: optional acquisition enqueue hook. Phase 17's boot plugin
     * (plugins/Acquisition.kt) sets this to AcquisitionService.enqueueTitle ONLY when
     * acquisition.enabled=true. When null (the default, and always when acquisition is
     * disabled) trigger() keeps its pure debounced-logging behavior — so the three
     * callers (StremioService, HlsTranscoder, PlaybackRoutes) see no change and there
     * is zero regression. ponytail: a single nullable hook avoids threading DI into an
     * object singleton; it is set once at boot.
     */
    @Volatile
    var enqueueHook: ((UUID) -> Unit)? = null

    /**
     * #86: signal that [titleId] has no online copy and should be re-acquired. Idempotent within
     * [DEBOUNCE]: the first call (or first after the window elapses) logs/acts; repeats are dropped.
     * Returns true when this call actually fired (useful for tests / callers that want to know).
     */
    suspend fun trigger(titleId: UUID): Boolean {
        val now = Instant.now()
        // compute() is atomic per-key so concurrent retries can't both pass the debounce check.
        var fired = false
        lastFired.compute(titleId) { _, prev ->
            if (prev == null || Duration.between(prev, now) >= DEBOUNCE) {
                fired = true
                now
            } else {
                prev
            }
        }
        if (fired) {
            // #86: hand off to Phase 17 acquisition when wired (enabled builds only).
            val hook = enqueueHook
            if (hook != null) {
                log.info("#86 re-acquire requested for title {} (no online copy); enqueueing acquisition (#87+)", titleId)
                runCatching { hook(titleId) }
                    .onFailure { log.warn("#86 acquisition enqueue hook failed for title {}", titleId, it) }
            } else {
                // No acquisition wired (disabled / not Phase 17): preserve the logging stub.
                log.info("#86 re-acquire requested for title {} (no online copy); acquisition not enabled, logging only", titleId)
            }
        } else {
            log.debug("#86 re-acquire for title {} debounced (already fired within {}s)", titleId, DEBOUNCE.seconds)
        }
        return fired
    }
}
