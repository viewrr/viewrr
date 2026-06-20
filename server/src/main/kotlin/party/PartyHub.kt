package wtf.jobin.party

import io.lettuce.core.KeyScanCursor
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import wtf.jobin.db.PartyRooms
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared JSON configuration for party-sync wire messages. Polymorphic on `type`.
 */
val PartyJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Owns the single Lettuce pub/sub connection and a per-room set of local subscriber
 * channels. Each WebSocket calls [subscribe] to register and [unsubscribe] on exit;
 * Redis SUBSCRIBE/UNSUBSCRIBE is issued only on the first/last local subscriber for
 * a given room, so a busy node holds one Redis subscription per occupied room.
 *
 * Also owns the background flush loop that periodically persists each room's
 * ephemeral state hash (party:{id}:state) back to `party_rooms` so a server restart
 * doesn't lose position. `R2dbcDatabase` is injected to do the UPDATE.
 */
class PartyHub(
    redisClient: RedisClient,
    private val redis: RedisAsyncCommands<String, String>,
    private val db: R2dbcDatabase,
) {
    private val log = LoggerFactory.getLogger(PartyHub::class.java)
    private val pubSub: StatefulRedisPubSubConnection<String, String> = redisClient.connectPubSub()
    private val rooms = ConcurrentHashMap<UUID, RoomState>()

    init {
        pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                val roomId = parseRoomId(channel) ?: return
                val state = rooms[roomId] ?: return
                // ponytail: unbounded UNLIMITED channels; bound when one slow client matters.
                for (ch in state.subscribers) ch.trySend(message)
            }
        })
    }

    suspend fun subscribe(roomId: UUID): Channel<String> {
        val ch = Channel<String>(Channel.UNLIMITED)
        var firstSubscriber = false
        rooms.compute(roomId) { _, existing ->
            val state = existing ?: RoomState().also { firstSubscriber = true }
            state.subscribers.add(ch)
            state
        }
        if (firstSubscriber) {
            pubSub.async().subscribe(channelFor(roomId)).await()
        }
        return ch
    }

    suspend fun unsubscribe(roomId: UUID, ch: Channel<String>) {
        var lastSubscriber = false
        rooms.computeIfPresent(roomId) { _, state ->
            state.subscribers.remove(ch)
            if (state.subscribers.isEmpty()) {
                lastSubscriber = true
                null
            } else state
        }
        ch.close()
        if (lastSubscriber) {
            // The pub/sub channel itself is ephemeral in Redis; resubscribing on next
            // local joiner restores fanout. The state hash (party:{id}:state) persists.
            pubSub.async().unsubscribe(channelFor(roomId)).await()
        }
    }

    suspend fun publish(roomId: UUID, payload: String) {
        redis.publish(channelFor(roomId), payload).await()
    }

    suspend fun updateState(roomId: UUID, positionSecs: Int?, isPlaying: Boolean?) {
        val updates = buildMap {
            positionSecs?.let { put("position_secs", it.toString()) }
            isPlaying?.let { put("is_playing", it.toString()) }
        }
        if (updates.isNotEmpty()) {
            redis.hset(stateKey(roomId), updates).await()
        }
    }

    suspend fun loadState(roomId: UUID): Pair<Int, Boolean> {
        val map = redis.hgetall(stateKey(roomId)).await()
        val pos = map["position_secs"]?.toIntOrNull() ?: 0
        val playing = map["is_playing"]?.toBooleanStrictOrNull() ?: false
        return pos to playing
    }

    /**
     * Reads the room's Redis hash and UPDATEs party_rooms with position_secs,
     * is_playing, and last_synced_at. No-op when the hash is empty (room never
     * touched since boot). Returns true when an UPDATE actually ran.
     */
    suspend fun flushRoomToDb(roomId: UUID): Boolean {
        val map = redis.hgetall(stateKey(roomId)).await()
        if (map.isEmpty()) return false
        val pos = map["position_secs"]?.toIntOrNull() ?: 0
        val playing = map["is_playing"]?.toBooleanStrictOrNull() ?: false
        val rows = suspendTransaction(db) {
            PartyRooms.update({ PartyRooms.id eq roomId }) {
                it[PartyRooms.positionSecs] = pos
                it[PartyRooms.isPlaying] = playing
                it[PartyRooms.lastSyncedAt] = Instant.now()
            }
        }
        return rows > 0
    }

    /** DEL the room's Redis state hash. Pub/sub auto-cleans when subscribers drop. */
    suspend fun deleteRoomState(roomId: UUID) {
        redis.del(stateKey(roomId)).await()
    }

    /** Synchronous flush + delete used by the close endpoint. */
    suspend fun closeRoom(roomId: UUID) {
        flushRoomToDb(roomId)
        deleteRoomState(roomId)
    }

    /**
     * Launches the periodic flush loop on the supplied scope (Application). SCANs
     * `party:*:state` every [intervalMs] and flushes each room. SCAN, not KEYS —
     * KEYS blocks Redis. Returns the [Job] so callers can cancel in tests.
     */
    fun startFlushLoop(scope: CoroutineScope, intervalMs: Long = 60_000L): Job = scope.launch {
        while (true) {
            delay(intervalMs)
            try {
                flushAllOnce()
            } catch (t: Throwable) {
                log.warn("party flush loop iteration failed", t)
            }
        }
    }

    /** One SCAN+flush pass. Visible for direct invocation (tests, admin). */
    suspend fun flushAllOnce() {
        val args = ScanArgs.Builder.matches("party:*:state").limit(100)
        var cursor: KeyScanCursor<String> = redis.scan(args).await()
        while (true) {
            for (key in cursor.keys) {
                val roomId = parseStateKey(key) ?: continue
                try {
                    flushRoomToDb(roomId)
                } catch (t: Throwable) {
                    log.warn("party flush failed for {}", roomId, t)
                }
            }
            if (cursor.isFinished) break
            cursor = redis.scan(cursor, args).await()
        }
    }

    private fun channelFor(roomId: UUID) = "party:$roomId"
    private fun stateKey(roomId: UUID) = "party:$roomId:state"

    private fun parseRoomId(channel: String): UUID? = try {
        UUID.fromString(channel.removePrefix("party:"))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseStateKey(key: String): UUID? = try {
        UUID.fromString(key.removePrefix("party:").removeSuffix(":state"))
    } catch (_: IllegalArgumentException) {
        null
    }

    private class RoomState {
        val subscribers: MutableSet<Channel<String>> = ConcurrentHashMap.newKeySet()
    }
}
