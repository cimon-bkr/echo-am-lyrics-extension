package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

// OkHttp, coroutines and kotlinx-serialization-json all come from Echo's common library,
// so this module declares no extra dependencies.

internal object Http {
    private const val UA = "AmLyricsEchoExtension/1.0"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Body on success, null on 404, IOException on anything else. */
    fun get(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { res ->
            if (res.code == 404) return null
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} from ${url.host}")
            return res.body.string()
        }
    }
}

private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

// ---------------------------------------------------------------------------
// LyricsPlus (KPoe) - the primary provider in am-lyrics.
// GET {mirror}/v2/lyrics/get?title=&artist=&album=&duration=<seconds>&isrc=
// The response is read defensively: field names come from secondary sources.
// ---------------------------------------------------------------------------

class LyricsPlusApi {

    suspend fun get(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        isrc: String?,
    ): LpResult? = withContext(Dispatchers.IO) {
        for (base in MIRRORS) {
            val url = "$base/v2/lyrics/get".toHttpUrl().newBuilder().apply {
                addQueryParameter("title", title)
                addQueryParameter("artist", artist)
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
                if (durationMs != null && durationMs > 0) {
                    addQueryParameter("duration", (durationMs / 1000).toString())
                }
                if (!isrc.isNullOrBlank()) addQueryParameter("isrc", isrc)
            }.build()

            try {
                val body = Http.get(url) ?: return@withContext null // 404 = no lyrics, stop
                return@withContext parse(body)
            } catch (_: IOException) {
                // mirror down / 5xx -> try the next one
            }
        }
        null
    }

    internal fun parse(body: String): LpResult? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        val lines = (root["lyrics"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.toLine() }
        if (lines.isEmpty()) return null

        val meta = root["metadata"] as? JsonObject
        return LpResult(
            type = root.str("type")?.lowercase(),
            source = meta?.str("source") ?: root.str("source"),
            lines = lines,
        )
    }

    private fun JsonObject.toLine(): LpLine {
        val start = num("time").toLong()
        val syllables = (this["syllabus"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.toSyllable() }
        return LpLine(
            startMs = start,
            endMs = start + num("duration").toLong(),
            text = str("text").orEmpty(),
            syllables = syllables,
        )
    }

    private fun JsonObject.toSyllable(): LpSyllable {
        val start = num("time").toLong()
        return LpSyllable(
            startMs = start,
            endMs = start + num("duration").toLong(),
            text = str("text").orEmpty(),
            background = bool("isBackground"),
            part = bool("part"),
        )
    }

    companion object {
        // Mirrors listed in the LyricsPlus discussion threads; first one that answers wins.
        val MIRRORS = listOf(
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
        )
    }
}

// ---------------------------------------------------------------------------
// LRCLIB - line-synced / plain fallback (am-lyrics also falls back to it).
// ---------------------------------------------------------------------------

class LrcLibApi {

    suspend fun get(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder().apply {
            addQueryParameter("track_name", title)
            addQueryParameter("artist_name", artist)
            if (!album.isNullOrBlank()) addQueryParameter("album_name", album)
            if (durationMs != null && durationMs > 0) {
                addQueryParameter("duration", (durationMs / 1000).toString())
            }
        }.build()

        try {
            val body = Http.get(url) ?: return@withContext null
            val json = Json.parseToJsonElement(body) as? JsonObject ?: return@withContext null
            if (json.bool("instrumental")) return@withContext null
            val synced = json.str("syncedLyrics")?.takeIf { it.isNotBlank() }
            val plain = json.str("plainLyrics")?.takeIf { it.isNotBlank() }
            if (synced == null && plain == null) null else LrcLibResult(synced, plain)
        } catch (_: IOException) {
            null
        }
    }
}
