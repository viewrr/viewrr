package wtf.jobin.worklet

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaCopies
import wtf.jobin.db.MediaItems

/**
 * #121 slice 3: which content this deployment can provide, as content_uuids to announce.
 *
 * A title is announceable when it has a #124 content_uuid AND at least one physical media_copy.
 * ponytail: deployment-level, not per-node — every title with a copy is announced. Filtering to a
 * specific node's own copies matters only once nodes actually SERVE bytes (slice 5); until then the
 * Hub-run worklet advertising everything the deployment holds is the correct availability signal.
 */
open class AnnounceRepository(private val db: R2dbcDatabase) {
    /** Distinct content_uuid as 32-char lowercase hex (dashes stripped, matching swarmTopic input). */
    open suspend fun localContentUuids(): List<String> = suspendTransaction(db) {
        MediaItems.join(MediaCopies, JoinType.INNER, MediaItems.id, MediaCopies.titleId)
            .select(MediaItems.contentUuid)
            .where { MediaItems.contentUuid.isNotNull() }
            .map { it[MediaItems.contentUuid]!!.toString().replace("-", "") }
            .toList()
            .distinct() // many copies per title -> collapse to one announce per content_uuid
    }
}
