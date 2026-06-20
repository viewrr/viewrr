package wtf.jobin.party

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for party-sync WebSocket frames. Polymorphic on a `type` discriminator
 * supplied by [PartyJson]. Clients send Play/Pause/Seek/StateRequest; the server
 * publishes those to Redis pub/sub and replies to StateRequest with a StateSnapshot.
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
}
