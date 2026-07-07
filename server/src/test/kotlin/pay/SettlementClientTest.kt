package wtf.jobin.pay

import kotlinx.coroutines.runBlocking
import kotlinx.rpc.grpc.server.GrpcServer
import kotlinx.rpc.registerService
// Wildcard pulls in the generated `Xxx { ... }` builder DSL (Xxx.Companion.invoke) the fake
// service's responses use below — same reason wtf.jobin.pay.SettlementClient wildcard-imports it.
import wtf.jobin.pay.v1.*
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mesh-hub (docs/pay/1-grpc-contract-service-skeleton.md): proves [SettlementClient] calls exactly
 * the two READ-ONLY `WalletService` RPCs (`EnsureWallet`, `GetBalance`) against a real in-process
 * gRPC server — a fake [WalletService] impl, no viewrr-pay process, no network. This is the
 * "real proof" test analogous to [wtf.jobin.availability.AvailabilityServiceTest]: it exercises
 * the actual generated stub + wire codec (loopback TCP on an ephemeral port), just with a fake
 * service implementation standing in for viewrr-pay.
 *
 * Money-moving RPCs (BandwidthService, StorageService) are intentionally NOT exercised here —
 * SettlementClient doesn't call them (legal #9 gate); this test only proves the read-only slice.
 */
class SettlementClientTest {
    private var server: GrpcServer? = null

    @AfterTest
    fun tearDown() {
        server?.shutdownNow()
    }

    /** Fake WalletService: records every call's account id, returns deterministic canned data. */
    private class FakeWalletService : WalletService {
        val ensureWalletCalls = mutableListOf<String>()
        val getBalanceCalls = mutableListOf<String>()

        override suspend fun EnsureWallet(message: EnsureWalletRequest): EnsureWalletResponse {
            ensureWalletCalls.add(message.account.accountId)
            return EnsureWalletResponse { walletAddress = "0xFAKE${message.account.accountId}" }
        }

        override suspend fun GetBalance(message: GetBalanceRequest): GetBalanceResponse {
            getBalanceCalls.add(message.account.accountId)
            return GetBalanceResponse { balance = UsdcAmount { baseUnits = 42_000_000uL } }
        }
    }

    @Test
    fun ensureWalletAndGetWalletInfoCallExactlyTheReadOnlyRpcs() = runBlocking {
        val fake = FakeWalletService()
        // kotlinx.rpc's GrpcServer.port always echoes back the port YOU asked for (it never reads
        // the actual bound ephemeral port off the underlying grpc.Server) — so port 0 dead-ends the
        // client at "connect to port 0". Reserve a free port ourselves, then bind the gRPC server to
        // it explicitly; the tiny release-then-rebind window is the standard test tradeoff for this.
        val port = ServerSocket(0).use { it.localPort }
        val srv = GrpcServer(port) {
            services { registerService<WalletService> { fake } }
        }.start()
        server = srv

        val client = SettlementClient("localhost:$port")

        val address = client.ensureWallet("acct-1")
        val info = client.getWalletInfo("acct-1")

        assertEquals("0xFAKEacct-1", address)
        assertEquals(42_000_000uL, info.balanceBaseUnits)
        assertEquals(listOf("acct-1"), fake.ensureWalletCalls)
        assertEquals(listOf("acct-1"), fake.getBalanceCalls)
    }
}
