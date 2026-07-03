package wtf.jobin.scanner

import kotlinx.coroutines.runBlocking
import wtf.jobin.db.ContentUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #124: the pure status -> tri-state mapping behind [TmdbClient.titleExists], and the disabled-key
 * short-circuit. The IO itself is intentionally not mocked (JDK HttpClient is awkward to fake and
 * the repo forbids new deps); the decision that MATTERS — how a status code becomes an existence
 * verdict — is pulled out as a pure function and proven here, mirroring how `parse(body)` is tested.
 */
class TmdbExistenceTest {

    private val client = TmdbClient(apiKey = "test-key")

    @Test
    fun status200MeansExists() = assertEquals(true, client.existenceFromStatus(200))

    @Test
    fun status404MeansDefinitivelyAbsent() = assertEquals(false, client.existenceFromStatus(404))

    @Test
    fun rateLimitAndServerErrorsAreUnknownNotAbsent() {
        assertNull(client.existenceFromStatus(429)) // never treat throttling as poison
        assertNull(client.existenceFromStatus(401))
        assertNull(client.existenceFromStatus(500))
    }

    @Test
    fun disabledClientReportsUnknownWithoutNetwork() = runBlocking {
        // Blank key => disabled; must return null (unknown), never false, and never touch the network.
        val disabled = TmdbClient(apiKey = "")
        assertNull(disabled.titleExists(550, ContentUuid.Kind.MOVIE))
    }

    @Test
    fun nonPositiveIdIsUnknownWithoutNetwork() = runBlocking {
        assertNull(client.titleExists(0, ContentUuid.Kind.MOVIE))
    }
}
