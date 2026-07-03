package wtf.jobin.worklet

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put

/**
 * #126 (P2P-ADR 0006): typed Kotlin surface for the private-topic + Vault-Link pairing worklet
 * methods (`pairlet.mjs`), spoken over a live [WorkletRpc]. Like [WorkletHypercore] this is
 * codec-only — it owns no process and no swarm; a caller wires it to a spawned `bare pairlet.mjs`.
 *
 * It covers the two #126 capabilities:
 *  - [deriveTopic]: the STEADY-STATE private discovery topic, keyed BLAKE2b of a device-shared
 *    secret (a vault-sync key), NOT the public identity key — so outsiders can't compute/join it.
 *    Deterministic and side-effect-free.
 *  - [pairBegin]/[pairFinish]/[pairJoin]: the EPHEMERAL Vault Link. A fresh one-time secret (shown as
 *    a QR) is the sole rendezvous for a new device; the encrypted vault crosses the Noise-encrypted
 *    Hyperswarm channel; the pairing topic is torn down after the single transfer. The identity key
 *    is NEVER the pairing rendezvous.
 *
 * Deliberately a NEW, self-contained file (parity with [WorkletHypercore]): it touches no db/Tables,
 * no media/, no p2p/PeerRanking, and does NOT edit WorkletHypercore.kt (a parallel agent reads it).
 *
 * ponytail: per-method [timeoutMs] because the calls differ in nature. [deriveTopic] resolves locally
 * and wants a short leash; the pairing waits ([pairJoin], [pairFinish]) block until a PEER appears
 * over the DHT, so their timeout is the "no device ever paired" bound — a timeout there means
 * teardown-without-transfer, which the caller handles by giving up (and [pairAbort] force-cleans).
 */
class WorkletPairing(private val rpc: WorkletRpc) {

    /**
     * derive the steady-state private topic from a device-shared [secretHex] (16..64 bytes hex) and a
     * domain-separating [label]. Deterministic: same secret+label always yields the same topicHex.
     */
    suspend fun deriveTopic(secretHex: String, label: String = "", timeoutMs: Long = 8000): String {
        val params = buildJsonObject { put("secretHex", secretHex); put("label", label) }
        return rpc.call("deriveTopic", params, timeoutMs).jsonObject.getValue("topicHex").jsonPrimitive.content
    }

    /**
     * wrap a one-time [pairingSecretHex] in the frozen, integrity-checked vault-link URI the trusted
     * device renders as a QR (P2P-ADR 0006 "the QR carries a fresh pairing secret"). Pure; no swarm.
     */
    suspend fun vaultLinkEncode(pairingSecretHex: String, timeoutMs: Long = 8000): String {
        val params = buildJsonObject { put("pairingSecretHex", pairingSecretHex) }
        return rpc.call("vaultLinkEncode", params, timeoutMs).jsonObject.getValue("qr").jsonPrimitive.content
    }

    /**
     * parse + verify a scanned vault-link [qr] URI, returning the pairing secret and the topic it joins.
     * Throws (via [WorkletRpcException]) on wrong scheme/version, bad length, or checksum mismatch — so a
     * corrupt scan is rejected here, not as a downstream silent no-peer timeout. Pure; no swarm.
     */
    suspend fun vaultLinkDecode(qr: String, timeoutMs: Long = 8000): VaultLink {
        val params = buildJsonObject { put("qr", qr) }
        val obj = rpc.call("vaultLinkDecode", params, timeoutMs).jsonObject
        return VaultLink(
            pairingSecretHex = obj.getValue("pairingSecretHex").jsonPrimitive.content,
            topicHex = obj.getValue("topicHex").jsonPrimitive.content,
        )
    }

    /**
     * host side: begin a Vault Link. Mints a fresh one-time pairing secret, joins hash(secret) as
     * server, and arms delivery of [vaultHex] (the already-encrypted vault blob) to the first peer.
     * Returns immediately with the QR material; delivery completes under [pairFinish].
     */
    suspend fun pairBegin(vaultHex: String, timeoutMs: Long = 30000): PairSession {
        val params = buildJsonObject { put("vaultHex", vaultHex) }
        val obj = rpc.call("pairBegin", params, timeoutMs).jsonObject
        return PairSession(
            pairingSecretHex = obj.getValue("pairingSecretHex").jsonPrimitive.content,
            topicHex = obj.getValue("topicHex").jsonPrimitive.content,
            qr = obj.getValue("qr").jsonPrimitive.content,
        )
    }

    /**
     * host side: suspend until the vault has been handed to a peer, then tear the pairing swarm down.
     * [timeoutMs] is the "no device ever paired" bound — a [WorkletRpcException.Timeout] here means
     * pairing was abandoned; call [pairAbort] to force-clean the host's swarm afterwards.
     */
    suspend fun pairFinish(timeoutMs: Long = 60000): Boolean =
        rpc.call("pairFinish", null, timeoutMs).jsonObject.getValue("delivered").jsonPrimitive.boolean

    /**
     * new-device side: join the pairing topic derived from the QR [pairingSecretHex], read the vault
     * off the Noise channel, then tear the pairing swarm down. Returns the received vault hex. AWAITS
     * a peer, so [timeoutMs] bounds "host never appeared".
     */
    suspend fun pairJoin(pairingSecretHex: String, timeoutMs: Long = 60000): String {
        val params = buildJsonObject { put("pairingSecretHex", pairingSecretHex) }
        return rpc.call("pairJoin", params, timeoutMs).jsonObject.getValue("vaultHex").jsonPrimitive.content
    }

    /**
     * new-device side, from a scanned QR: join using the vault-link [qr] URI directly (the worklet
     * decodes+verifies it before joining, so a corrupt scan fails fast). Otherwise identical to
     * [pairJoin] — reads the vault off the Noise channel, then tears the pairing swarm down.
     */
    suspend fun pairJoinFromQr(qr: String, timeoutMs: Long = 60000): String {
        val params = buildJsonObject { put("qr", qr) }
        return rpc.call("pairJoin", params, timeoutMs).jsonObject.getValue("vaultHex").jsonPrimitive.content
    }

    /** force-teardown of whatever pairing this worklet holds (timeout/cancel cleanup). */
    suspend fun pairAbort(timeoutMs: Long = 8000): Boolean =
        rpc.call("pairAbort", null, timeoutMs).jsonObject.getValue("aborted").jsonPrimitive.boolean

    /**
     * Host's QR material: the one-time [pairingSecretHex] (raw secret), the [topicHex] it hashes to,
     * and [qr] — the frozen vault-link URI to render as the QR and hand to [pairJoinFromQr].
     */
    data class PairSession(
        val pairingSecretHex: String,
        val topicHex: String,
        val qr: String,
    )

    /** Decoded vault-link: the recovered [pairingSecretHex] and the [topicHex] a new device joins. */
    data class VaultLink(
        val pairingSecretHex: String,
        val topicHex: String,
    )
}
