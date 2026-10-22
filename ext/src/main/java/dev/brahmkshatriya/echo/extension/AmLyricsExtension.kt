package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Echo lyrics extension built on the data layer of binimum/am-lyrics.
 *
 * Echo draws the lyrics itself, so the <am-lyrics> web component (DOM + CSS) isn't
 * used - what carries over is its lookup chain: LyricsPlus first, LRCLIB as fallback.
 *
 * Result order (Echo selects the first by default):
 *   1. LyricsPlus line-synced
 *   2. LRCLIB (line-synced, or plain)
 *   3. LyricsPlus word-by-word (for players that render it)
 */
class AmLyricsExtension : ExtensionClient, LyricsClient {

    private val lyricsPlus = LyricsPlusApi()
    private val lrcLib = LrcLibApi()

    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) {}

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> =
        search(Query.of(track)).toFeed()

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        if (lyrics.lyrics != null) return lyrics
        val query = Query.from(lyrics.extras) ?: return lyrics
        val found = search(query)
        return found.firstOrNull { it.id == lyrics.id } ?: found.firstOrNull() ?: lyrics
    }

    // -----------------------------------------------------------------------

    private suspend fun search(q: Query): List<Lyrics> = coroutineScope {
        if (q.title.isBlank() || q.artist.isBlank()) return@coroutineScope emptyList()

        val plus = async {
            runCatching { lyricsPlus.get(q.title, q.artist, q.album, q.durationMs, q.isrc) }
                .getOrNull()
        }
        val lrc = async {
            runCatching { lrcLib.get(q.title, q.artist, q.album, q.durationMs) }.getOrNull()
        }

        val plusVariants = plus.await()?.let { r ->
            val label = r.source?.let { "LyricsPlus ($it)" } ?: "LyricsPlus"
            LyricsMapper.lyricsPlusVariants(r).map { v ->
                build(q, "lyricsplus-${v.kind}", "$label · ${v.kind}", v.lyric)
            }
        }.orEmpty()

        val lrcResult = lrc.await()?.let { r ->
            val synced = r.synced?.let { LyricsMapper.fromLrc(it, q.durationMs) }
            when {
                synced != null -> build(q, "lrclib", "LRCLIB · line-synced", synced)
                r.plain != null -> build(q, "lrclib", "LRCLIB · plain", Lyrics.Simple(r.plain))
                else -> null
            }
        }

        val (word, other) = plusVariants.partition { it.id.contains("word-by-word") }
        other + listOfNotNull(lrcResult) + word
    }

    private fun build(q: Query, provider: String, label: String, lyric: Lyrics.Lyric) =
        Lyrics(
            id = "am-lyrics:$provider:${q.artist}:${q.title}",
            title = q.title,
            subtitle = label,
            lyrics = lyric,
            extras = q.toExtras(),
        )

    /** What we need to re-run a lookup later (kept in Lyrics.extras). */
    private data class Query(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val isrc: String?,
    ) {
        fun toExtras(): Map<String, String> = buildMap {
            put("title", title)
            put("artist", artist)
            album?.let { put("album", it) }
            durationMs?.let { put("durationMs", it.toString()) }
            isrc?.let { put("isrc", it) }
        }

        companion object {
            fun of(track: Track) = Query(
                title = track.title,
                // First artist only - multi-artist strings ("A, B") match worse upstream.
                artist = track.artists.firstOrNull()?.name.orEmpty(),
                album = track.album?.title,
                durationMs = track.duration,
                isrc = track.isrc,
            )

            fun from(extras: Map<String, String>): Query? {
                val title = extras["title"] ?: return null
                val artist = extras["artist"] ?: return null
                return Query(
                    title, artist,
                    extras["album"],
                    extras["durationMs"]?.toLongOrNull(),
                    extras["isrc"],
                )
            }
        }
    }
}
