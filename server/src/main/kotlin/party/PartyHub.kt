package wtf.jobin.party

import io.lettuce.core.RedisClient
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
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
 */
class PartyHub(
    redisClient: RedisClient,
    private val redis: RedisAsyncCommands<String, String>,
) {
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

    private fun channelFor(roomId: UUID) = "party:$roomId"
    private fun stateKey(roomId: UUID) = "party:$roomId:state"

    private fun parseRoomId(channel: String): UUID? = try {
        UUID.fromString(channel.removePrefix("party:"))
    } catch (_: IllegalArgumentException) {
        null
    }

    private class RoomState {
        val subscribers: MutableSet<Channel<String>> = ConcurrentHashMap.newKeySet()
    }
}
