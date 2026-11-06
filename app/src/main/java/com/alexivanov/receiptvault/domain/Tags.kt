package com.alexivanov.receiptvault.domain

/**
 * Free-form labels such as "work", "Berlin trip" or "tax 2026". Stored on the
 * receipt as one comma-separated column; commas therefore can't be part of a tag.
 */
object Tags {
    const val MAX_TAGS = 12
    const val MAX_LENGTH = 32

    /** What the user typed ("work, #Berlin trip,  work") -> ["work", "Berlin trip"]. */
    fun parse(input: String): List<String> = normalize(input.split(',', '\n', ';'))

    /** Cleans each tag, drops blanks and repeats (case-insensitive, first spelling wins). */
    fun normalize(tags: Iterable<String>): List<String> {
        val seen = HashSet<String>()
        return tags.asSequence()
            .map { it.replace(Regex("""\s+"""), " ").trim().trimStart('#').trim().take(MAX_LENGTH).trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            .take(MAX_TAGS)
            .toList()
    }

    fun encode(tags: List<String>): String = normalize(tags).joinToString(",")

    fun decode(column: String): List<String> = if (column.isBlank()) emptyList() else normalize(column.split(','))

    /** Text for the edit field. */
    fun display(column: String): String = decode(column).joinToString(", ")

    /** Every tag in use, most used first, for suggestions and the filter sheet. */
    fun inUse(columns: Iterable<String>): List<String> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (column in columns) {
            for (tag in decode(column)) {
                val key = tag.lowercase()
                val (spelling, n) = counts[key] ?: (tag to 0)
                counts[key] = spelling to n + 1
            }
        }
        return counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() }).map { it.first }
    }

    fun has(column: String, tag: String): Boolean = decode(column).any { it.equals(tag, ignoreCase = true) }
}
