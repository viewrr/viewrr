package wtf.jobin.recs

import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.withService
// Wildcard pulls in `operator fun RefreshRequest.Companion.invoke` so the
// `RefreshRequest { ... }` builder DSL resolves.
import wtf.jobin.recs.proto.*
import java.util.UUID

/**
 * Thin wrapper around the kotlinx.rpc-generated [RecEngine] stub.
 *
 * Target string is `host:port` (e.g. `localhost:50051`). The underlying
 * [GrpcClient] is built lazily on first call so boot doesn't depend on the
 * Python rec engine being up — matches the issue's `createdAtStart = false`.
 *
 * ponytail: leaks the gRPC client. Wire ApplicationStopped → shutdown() if it matters.
 */
class RecEngineClient(target: String) {
    private val host: String
    private val port: Int

    init {
        val parts = target.split(":", limit = 2)
        host = parts[0]
        port = parts.getOrNull(1)?.toIntOrNull() ?: 50051
    }

    private val client by lazy {
        GrpcClient(host, port) { credentials = plaintext() }
    }
    private val service by lazy { client.withService<RecEngine>() }

    suspend fun refreshUserRecs(userId: UUID, topK: Int): Int =
        service.RefreshUserRecs(
            RefreshRequest {
                this.userId = userId.toString()
                this.topK = topK
            },
        ).written
}
