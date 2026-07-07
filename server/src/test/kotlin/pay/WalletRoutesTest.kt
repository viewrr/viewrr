package wtf.jobin.pay

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.r2dbc.spi.ConnectionFactories
import kotlinx.rpc.grpc.server.GrpcServer
import kotlinx.rpc.registerService
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.auth.TokenService
import wtf.jobin.auth.noOpRedis
import wtf.jobin.auth.testAuthConfig
import wtf.jobin.db.IdentityAccounts
import wtf.jobin.db.PayWallets
// Wildcard pulls in the generated `Xxx { ... }` builder DSL (Xxx.Companion.invoke) — same
// reason SettlementClient.kt and SettlementClientTest.kt wildcard-import this package.
import wtf.jobin.pay.v1.*
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mesh-hub: proves the HTTP wallet contract (`WalletRoutes.kt`) end to end against a real
 * in-process gRPC server standing in for viewrr-pay (same fake-service shape as
 * [SettlementClientTest]) and a real H2-backed [PayWalletRepository] — no mocking framework, no
 * network beyond loopback.
 */
class WalletRoutesTest {
    private var server: GrpcServer? = null

    @AfterTest
    fun tearDown() {
        server?.shutdownNow()
    }

    /** Fake WalletService: deterministic address, fixed balance — same shape as SettlementClientTest. */
    private class FakeWalletService : WalletService {
        override suspend fun EnsureWallet(message: EnsureWalletRequest): EnsureWalletResponse =
            EnsureWalletResponse { walletAddress = "0xFAKE${message.account.accountId}" }

        override suspend fun GetBalance(message: GetBalanceRequest): GetBalanceResponse =
            GetBalanceResponse { balance = UsdcAmount { baseUnits = 1_500_000uL } }
    }

    private fun freshDb(): R2dbcDatabase {
        val name = "wallet_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
        )
    }

    private suspend fun newAccount(db: R2dbcDatabase): UUID = suspendTransaction(db) {
        SchemaUtils.create(IdentityAccounts, PayWallets)
        IdentityAccounts.insertAndGetId {
            it[publicKey] = UUID.randomUUID().toString()
            it[createdAt] = Instant.now()
        }.value
    }

    private fun bearerFor(accountId: UUID): String =
        TokenService(testAuthConfig, noOpRedis()).issueAccess(accountId, isAdmin = false)

    private fun verifier() = JWT.require(Algorithm.HMAC256(testAuthConfig.jwtSecret))
        .withIssuer(testAuthConfig.jwtIssuer)
        .withAudience(testAuthConfig.jwtAudience)
        .build()

    private fun settlementClientAgainstFake(): SettlementClient {
        val port = ServerSocket(0).use { it.localPort }
        server = GrpcServer(port) {
            services { registerService<WalletService> { FakeWalletService() } }
        }.start()
        return SettlementClient("localhost:$port")
    }

    @Test
    fun optInIsIdempotentAndReturnsAddress() = testApplication {
        val db = freshDb()
        val account = newAccount(db)
        val wallets = PayWalletRepository(db)
        val settlement = settlementClientAgainstFake()
        val token = bearerFor(account)

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(verifier())
                    validate { credential ->
                        if (credential.payload.subject == null) null else JWTPrincipal(credential.payload)
                    }
                }
            }
            routing { walletRoutes(settlement, wallets) }
        }

        val first = client.post("/api/pay/wallet/opt-in") { header("Authorization", "Bearer $token") }
        val second = client.post("/api/pay/wallet/opt-in") { header("Authorization", "Bearer $token") }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        val body = Json.decodeFromString<WalletOptInResponse>(first.bodyAsText())
        assertEquals("0xFAKE$account", body.address)
        assertEquals(true, body.optedIn)
        // Idempotent: the second call returns the SAME address, and only one opt-in row persists.
        assertEquals(body, Json.decodeFromString<WalletOptInResponse>(second.bodyAsText()))
        assertEquals("0xFAKE$account", wallets.findAddress(account))
    }

    @Test
    fun walletBeforeOptInReportsNotOptedInWithoutTouchingSettlement() = testApplication {
        val db = freshDb()
        val account = newAccount(db)
        val wallets = PayWalletRepository(db)
        val settlement = settlementClientAgainstFake() // never dialed — asserts nothing calls it
        val token = bearerFor(account)

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(verifier())
                    validate { credential ->
                        if (credential.payload.subject == null) null else JWTPrincipal(credential.payload)
                    }
                }
            }
            routing { walletRoutes(settlement, wallets) }
        }

        val response = client.get("/api/pay/wallet") { header("Authorization", "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(WalletNotOptedIn(), Json.decodeFromString<WalletNotOptedIn>(response.bodyAsText()))
    }

    @Test
    fun walletAfterOptInReportsAddressAndBalance() = testApplication {
        val db = freshDb()
        val account = newAccount(db)
        val wallets = PayWalletRepository(db)
        val settlement = settlementClientAgainstFake()
        val token = bearerFor(account)

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(verifier())
                    validate { credential ->
                        if (credential.payload.subject == null) null else JWTPrincipal(credential.payload)
                    }
                }
            }
            routing { walletRoutes(settlement, wallets) }
        }

        client.post("/api/pay/wallet/opt-in") { header("Authorization", "Bearer $token") }
        val response = client.get("/api/pay/wallet") { header("Authorization", "Bearer $token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<WalletView>(response.bodyAsText())
        assertEquals("0xFAKE$account", body.address)
        assertEquals("1500000", body.balanceBaseUnits)
        assertEquals("USDC", body.asset)
        assertEquals(6, body.decimals)
        assertEquals(true, body.optedIn)
    }

    @Test
    fun unauthenticatedRequestsAreRejected() = testApplication {
        val db = freshDb()
        newAccount(db) // schema only; no principal to attach to a request
        val wallets = PayWalletRepository(db)
        val settlement = settlementClientAgainstFake()

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(verifier())
                    validate { credential ->
                        if (credential.payload.subject == null) null else JWTPrincipal(credential.payload)
                    }
                }
            }
            routing { walletRoutes(settlement, wallets) }
        }

        val response = client.get("/api/pay/wallet")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
