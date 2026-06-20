package wtf.jobin.party

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/**
 * Wire format for party-sync WebSocket frames. Polymorphic on a `type` discriminator
 * supplied by [PartyJson]. Clients send Play/Pause/Seek/StateRequest/ChatEvent; the
 * server publishes those to Redis pub/sub and replies to StateRequest with a
 * StateSnapshot. ChatEvent's senderId/atServerEpochMs are server-stamped — any client
 * values are overwritten.
 */
@Serializable
sealed class PartyMessage {
    @Serializable
    @SerialName("play")
    data class PlayEvent(val positionSecs: Int, val atServerEpochMs: Long) : PartyMessage()

    @Serializable
    @SerialName("pause")
    data class PauseEvent(val positionSecs: Int) : PartyMessage()

    @Serializable
    @SerialName("seek")
    data class SeekEvent(val positionSecs: Int) : PartyMessage()

    @Serializable
    @SerialName("state-request")
    object StateRequest : PartyMessage()

    @Serializable
    @SerialName("state-snapshot")
    data class StateSnapshot(
        val positionSecs: Int,
        val isPlaying: Boolean,
        val members: List<String>,
    ) : PartyMessage()

    @Serializable
    @SerialName("chat")
    data class ChatEvent(
        val body: String,
        @Serializable(with = UUIDAsStringSerializer::class)
        val senderId: UUID = ZERO_UUID,
        val atServerEpochMs: Long = 0L,
    ) : PartyMessage()
}

private val ZERO_UUID: UUID = UUID(0L, 0L)

internal object UUIDAsStringSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
