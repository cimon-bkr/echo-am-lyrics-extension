package dev.brahmkshatriya.echo.extension

// Plain provider models (all times in milliseconds).

data class LpSyllable(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val background: Boolean,
    val part: Boolean, // true = continues into the next syllable (no space)
)

data class LpLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<LpSyllable>,
)

data class LpResult(
    val type: String?,   // "word" / "line" (lower-cased)
    val source: String?, // e.g. "apple", "musixmatch"
    val lines: List<LpLine>,
)

data class LrcLibResult(val synced: String?, val plain: String?)
