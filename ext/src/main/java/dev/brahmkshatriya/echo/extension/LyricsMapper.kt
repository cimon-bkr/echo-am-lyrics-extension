package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics

/** Converts provider data into Echo's [Lyrics.Lyric] variants. */
object LyricsMapper {

    private const val FALLBACK_LINE_MS = 4_000L

    /** One selectable result, e.g. "line-synced" or "word-by-word". */
    data class Variant(val kind: String, val lyric: Lyrics.Lyric)

    // ---- LyricsPlus -------------------------------------------------------

    /**
     * Echo's current player only highlights [Lyrics.Timed]; [Lyrics.WordByWord] is flattened
     * into one row per item without highlighting. So line-synced comes first and
     * word-by-word is offered as an extra for players that render it.
     */
    fun lyricsPlusVariants(result: LpResult): List<Variant> {
        val lines = result.lines
            .map { it.copy(text = lineText(it)) }
            .filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        if (lines.none { it.startMs > 0 }) {
            return listOf(Variant("plain", Lyrics.Simple(lines.joinToString("\n") { it.text })))
        }

        val variants = mutableListOf(Variant("line-synced", timed(lines)))
        if (lines.any { it.syllables.isNotEmpty() }) {
            variants += Variant("word-by-word", wordByWord(lines))
        }
        return variants
    }

    private fun lineText(line: LpLine): String =
        line.text.ifBlank { line.syllables.joinToString("") { it.text } }.trim()

    private fun timed(lines: List<LpLine>): Lyrics.Lyric =
        Lyrics.Timed(
            lines.mapIndexed { i, line ->
                Lyrics.Item(line.text, line.startMs, lineEnd(line, lines.getOrNull(i + 1)))
            }
        )

    private fun wordByWord(lines: List<LpLine>): Lyrics.Lyric =
        Lyrics.WordByWord(
            lines.mapIndexed { i, line -> wordItems(line, lines.getOrNull(i + 1)) }
                .filter { it.isNotEmpty() }
        )

    private fun wordItems(line: LpLine, next: LpLine?): List<Lyrics.Item> {
        if (line.syllables.isEmpty()) {
            return listOf(Lyrics.Item(line.text, line.startMs, lineEnd(line, next)))
        }

        // Some responses already carry spaces inside the syllable text, some don't.
        // If the joined syllables equal the line text, trust them as-is; otherwise
        // add a space after every syllable that isn't a word fragment.
        val joined = line.syllables.joinToString("") { it.text }
        val alreadySpaced = joined.trim() == line.text.trim()

        return line.syllables.mapIndexed { i, s ->
            val isLast = i == line.syllables.lastIndex
            val text =
                if (alreadySpaced || isLast || s.part || s.text.endsWith(" ")) s.text
                else s.text + " "
            val end = when {
                s.endMs > s.startMs -> s.endMs
                else -> line.syllables.getOrNull(i + 1)?.startMs ?: lineEnd(line, next)
            }
            Lyrics.Item(text, s.startMs, end)
        }
    }

    private fun lineEnd(line: LpLine, next: LpLine?): Long = when {
        line.endMs > line.startMs -> line.endMs
        next != null && next.startMs > line.startMs -> next.startMs
        else -> line.startMs + FALLBACK_LINE_MS
    }

    // ---- LRC (LRCLIB) -----------------------------------------------------

    private val LRC_LINE = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\](.*)$""")

    private data class RawLrc(val start: Long, val text: String)

    fun fromLrc(lrc: String, trackDurationMs: Long?): Lyrics.Lyric? {
        val raw = lrc.lineSequence().mapNotNull { line ->
            val m = LRC_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val minutes = m.groupValues[1].toLong()
            val seconds = m.groupValues[2].toLong()
            val millis = m.groupValues[3].padEnd(3, '0').take(3).toLong()
            RawLrc(minutes * 60_000 + seconds * 1_000 + millis, m.groupValues[4].trim())
        }.sortedBy { it.start }.toList()

        // Blank lines are kept while computing end times, then dropped.
        val items = raw.mapIndexed { i, r ->
            val end = raw.getOrNull(i + 1)?.start
                ?: trackDurationMs?.takeIf { it > r.start }
                ?: (r.start + FALLBACK_LINE_MS)
            Lyrics.Item(r.text, r.start, end)
        }.filter { it.text.isNotBlank() }

        return if (items.isEmpty()) null else Lyrics.Timed(items)
    }
}
