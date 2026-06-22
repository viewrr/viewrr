package wtf.jobin.stremio

import kotlinx.serialization.Serializable

// Stremio addon protocol response objects. Serialized with explicitNulls=false so
// optional fields are omitted (Stremio clients, incl. Nuvio, expect lean JSON).

@Serializable
data class StManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val resources: List<String>,
    val types: List<String>,
    val catalogs: List<StCatalog>,
    val idPrefixes: List<String>,
)

@Serializable
data class StCatalog(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<StExtra> = emptyList(),
)

@Serializable
data class StExtra(val name: String, val isRequired: Boolean = false)

@Serializable
data class StMetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String? = null,
    val releaseInfo: String? = null,
    val description: String? = null,
)

@Serializable
data class StCatalogResponse(val metas: List<StMetaPreview>)

@Serializable
data class StVideo(
    val id: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
)

@Serializable
data class StMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val videos: List<StVideo>? = null,
)

@Serializable
data class StMetaResponse(val meta: StMeta)

@Serializable
data class StStream(
    val url: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class StStreamResponse(val streams: List<StStream>)

@Serializable
data class StSubtitle(val id: String, val url: String, val lang: String)

@Serializable
data class StSubtitleResponse(val subtitles: List<StSubtitle>)
