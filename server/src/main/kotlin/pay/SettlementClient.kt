package wtf.jobin.pay

import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.withService
// Wildcard pulls in the generated `Xxx { ... }` builder DSL (Xxx.Companion.invoke), same pattern
// as wtf.jobin.recs.RecEngineClient.
import wtf.jobin.pay.v1.*

/**
 * mesh-hub (docs/pay/1-grpc-contract-service-skeleton.md; DECISION 0001 —
 * viewrr-pay/docs/decisions/0001-key-custody-signing-model.md): the Hub's gRPC client into
 * viewrr-pay, the Go settlement service. `pay.proto` is vendored/mirrored into
 * `core/src/commonMain/proto/pay.proto` — same package (`viewrr.pay.v1`) and message/service
 * shapes as the canonical contract, only Kotlin codegen options added.
 *
 * DECISION 0001: viewrr-pay is a pure remote SIGNER — the Hub/device holds and uses the signing
 * key; viewrr-pay builds unsigned digests and never receives a seed. That doesn't change this
 * class's two calls (they're plain reads), but it's why every future money-moving RPC
 * (BandwidthService, StorageService) will need a Prepare/Submit seam instead of a single unary
 * call like these — do not extend this client with a money-moving RPC without that seam AND
 * legal #9 sign-off.
 *
 * READ-ONLY SLICE: only [ensureWallet] (idempotent opt-in, returns an address) and
 * [getWalletInfo] (on-chain balance read-through) are wired. `BandwidthService`/`StorageService`
 * are vendored in the proto for contract completeness but have no client method here.
 *
 * Target string is `host:port` (e.g. `localhost:50051` — viewrr-pay's own `cmd/viewrr-pay`
 * default listen address, `VIEWRR_PAY_ADDR=:50051`). Per docs/pay/2 this is a **local/loopback**
 * channel, not a call into a public network service. The underlying [GrpcClient] is built lazily
 * on first call so Hub boot never depends on viewrr-pay being up — same `createdAtStart = false`
 * shape as [wtf.jobin.recs.RecEngineClient].
 *
 * ponytail: leaks the gRPC client, same as RecEngineClient — wire ApplicationStopped -> shutdown()
 * if it matters.
 */
class SettlementClient(target: String) {
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
    private val wallet by lazy { client.withService<WalletService>() }

    /**
     * Idempotent opt-in: derives (or returns) the account's EVM wallet address on viewrr-pay's L2.
     * Moves no money — the wallet doesn't hold funds merely by existing.
     */
    suspend fun ensureWallet(accountId: String): String =
        wallet.EnsureWallet(
            EnsureWalletRequest { account = Account { this.accountId = accountId } },
        ).walletAddress

    /**
     * Read-through on-chain USDC balance for the account's wallet (base units, 6 decimals; maps
     * the real `WalletService.GetBalance` RPC — the draft's original "GetWalletInfo" name split
     * into `EnsureWallet` + `GetBalance` on the ratified contract). No custody, no write.
     */
    suspend fun getWalletInfo(accountId: String): WalletInfo =
        wallet.GetBalance(
            GetBalanceRequest { account = Account { this.accountId = accountId } },
        ).let { WalletInfo(balanceBaseUnits = it.balance.baseUnits) }
}

/** Domain-level wallet read — decouples callers from the generated proto response type. */
data class WalletInfo(val balanceBaseUnits: ULong)
