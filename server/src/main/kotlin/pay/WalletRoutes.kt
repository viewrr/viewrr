package wtf.jobin.pay

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

/** viewrr-pay settles exclusively in USDC base units (6 decimals) — see pay.proto UsdcAmount. */
private const val ASSET = "USDC"
private const val DECIMALS = 6

@Serializable
data class WalletOptInResponse(val address: String, val optedIn: Boolean = true)

@Serializable
data class WalletView(
    val address: String,
    val balanceBaseUnits: String,
    val asset: String,
    val decimals: Int,
    val optedIn: Boolean = true,
)

@Serializable
data class WalletNotOptedIn(val optedIn: Boolean = false)

/**
 * mesh-hub (docs/pay/1-grpc-contract-service-skeleton.md): the HTTP contract mobile/web actually
 * consume — they talk HTTP to the Hub, not gRPC to viewrr-pay. Wraps [SettlementClient].
 *
 * Opt-in gate: [PayWalletRepository] is the SOLE source of truth for whether an account has a
 * wallet. `GET /api/pay/wallet` never calls [SettlementClient] unless a local opt-in row already
 * exists — an account that never opts in never causes viewrr-pay to provision anything, so the
 * app works fully with payments absent.
 */
fun Route.walletRoutes(settlement: SettlementClient, wallets: PayWalletRepository) {
    authenticate("auth-jwt") {
        // Idempotent: ensureWallet returns the same address on every call, and markOptedIn
        // upserts on the account's primary key — calling this twice is a no-op past the first hit.
        post("/api/pay/wallet/opt-in") {
            val accountId = call.accountId()
            val address = settlement.ensureWallet(accountId.toString())
            wallets.markOptedIn(accountId, address)
            call.respond(WalletOptInResponse(address = address))
        }

        get("/api/pay/wallet") {
            val accountId = call.accountId()
            val address = wallets.findAddress(accountId)
            if (address == null) {
                call.respond(WalletNotOptedIn())
                return@get
            }
            val info = settlement.getWalletInfo(accountId.toString())
            call.respond(
                WalletView(
                    address = address,
                    balanceBaseUnits = info.balanceBaseUnits.toString(),
                    asset = ASSET,
                    decimals = DECIMALS,
                ),
            )
        }
    }
}

/** Same subject-as-account-id shape as WatchEventRoutes — identity is the sole auth path (#150). */
private fun io.ktor.server.application.ApplicationCall.accountId(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.subject!!)
