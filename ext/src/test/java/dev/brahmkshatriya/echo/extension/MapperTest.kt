package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Offline tests - no network. Run with `./gradlew :ext:test`. */
class MapperTest {

    private val wordLine = LpLine(
        1000, 3000, "Hello world",
        listOf(
            LpSyllable(1000, 1500, "Hel", false, true),
            LpSyllable(1500, 2000, "lo", false, false),
            LpSyllable(2000, 3000, "world", false, false),
        )
    )

    @Test
    fun lineSyncedComesFirstThenWordByWord() {
        val variants = LyricsMapper.lyricsPlusVariants(LpResult("word", "apple", listOf(wordLine)))
        assertEquals(listOf("line-synced", "word-by-word"), variants.map { it.kind })

        val timed = variants[0].lyric as Lyrics.Timed
        assertEquals(Lyrics.Item("Hello world", 1000, 3000), timed.list.single())

        val words = (variants[1].lyric as Lyrics.WordByWord).list.single()
        assertEquals("Hello world", words.joinToString("") { it.text }.trim())
    }

    @Test
    fun unsyncedBecomesSimple() {
        val lines = listOf(LpLine(0, 0, "a", emptyList()), LpLine(0, 0, "b", emptyList()))
        val v = LyricsMapper.lyricsPlusVariants(LpResult("none", null, lines)).single()
        assertEquals(Lyrics.Simple("a\nb"), v.lyric)
    }

    @Test
    fun lrcParsing() {
        val lrc = "[ar:Someone]\n[00:01.50]First\n[00:03.00]\n[00:04.250]Third"
        val timed = LyricsMapper.fromLrc(lrc, 10_000) as Lyrics.Timed
        assertEquals(Lyrics.Item("First", 1500, 3000), timed.list[0])
        assertEquals(Lyrics.Item("Third", 4250, 10_000), timed.list[1])
        assertNull(LyricsMapper.fromLrc("[ti:x]\nnothing", null))
    }

    @Test
    fun lyricsPlusJsonParsing() {
        val json = """
            {"type":"Word","metadata":{"source":"apple"},
             "lyrics":[{"time":1000,"duration":2000,"text":"Hello world",
               "syllabus":[{"time":1000,"duration":500,"text":"Hel","isBackground":false,"part":true},
                           {"time":1500,"duration":500,"text":"lo","isBackground":false}]}]}
        """.trimIndent()
        val result = LyricsPlusApi().parse(json)
        assertNotNull(result)
        assertEquals("word", result!!.type)
        assertEquals("apple", result.source)
        assertEquals(2, result.lines.single().syllables.size)
        assertEquals(3000L, result.lines.single().endMs)
        assertNull(LyricsPlusApi().parse("{\"lyrics\":[]}"))
    }
}
