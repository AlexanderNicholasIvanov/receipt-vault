package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {

    @Test
    fun `typed tags are trimmed, de-hashed and de-duplicated`() {
        assertEquals(listOf("work", "Berlin trip", "tax 2026"), Tags.parse(" work, #Berlin   trip ;tax 2026,, WORK ,  "))
    }

    @Test
    fun `long tags are cut and the count is capped`() {
        assertEquals(Tags.MAX_LENGTH, Tags.parse("x".repeat(50)).single().length)
        assertEquals(Tags.MAX_TAGS, Tags.parse((1..30).joinToString(",") { "t$it" }).size)
    }

    @Test
    fun `column round trip`() {
        val column = Tags.encode(listOf("work", "Berlin trip"))
        assertEquals("work,Berlin trip", column)
        assertEquals(listOf("work", "Berlin trip"), Tags.decode(column))
        assertEquals("work, Berlin trip", Tags.display(column))
        assertEquals(emptyList<String>(), Tags.decode(""))
    }

    @Test
    fun `tags in use are counted case-insensitively, most used first`() {
        assertEquals(listOf("work", "berlin", "home"), Tags.inUse(listOf("work,berlin", "Work", "home,berlin,WORK", "")))
    }

    @Test
    fun `has ignores case`() {
        assertTrue(Tags.has("work,Berlin trip", "berlin TRIP"))
        assertFalse(Tags.has("work", "berlin"))
        assertFalse(Tags.has("", "work"))
    }
}
