package wtf.jobin.worklet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import wtf.jobin.auth.dormantDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #121 slice 3: the announce loop sends exactly the repo's content_uuids, and no-ops when empty. */
class WorkletAnnouncerTest {
    // The fake overrides the query, so dormantDb's connection is never touched.
    private class FakeRepo(private val uuids: List<String>) : AnnounceRepository(dormantDb) {
        override suspend fun localContentUuids(): List<String> = uuids
    }

    @Test
    fun sendsRepoContentUuidsAsAnnounceParams() = runBlocking {
        var captured: Pair<String, JsonElement?>? = null
        val announcer = WorkletAnnouncer(
            repo = FakeRepo(listOf("aaaa", "bbbb")),
            callWorklet = { method, params -> captured = method to params; null },
            intervalMs = 60_000,
        )

        announcer.announceOnce()

        assertEquals("announce", captured!!.first)
        val arr = (captured!!.second as JsonObject)["contentUuids"]!!.jsonArray
        assertEquals(JsonArray(listOf(JsonPrimitive("aaaa"), JsonPrimitive("bbbb"))), arr)
    }

    @Test
    fun emptyLocalContentDoesNotCallWorklet() = runBlocking {
        var called = false
        val announcer = WorkletAnnouncer(
            repo = FakeRepo(emptyList()),
            callWorklet = { _, _ -> called = true; null },
            intervalMs = 60_000,
        )

        announcer.announceOnce()

        assertTrue(!called, "no local content -> no announce RPC")
    }
}
