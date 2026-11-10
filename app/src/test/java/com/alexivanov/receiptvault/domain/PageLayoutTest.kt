package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {

    @Test
    fun `pages are scaled to the widest page and stacked with a gap`() {
        val layout = PageLayout.of(listOf(1000 to 2000, 500 to 500), maxWidth = 1600, maxHeight = 12_000)
        assertEquals(1000, layout.width)
        assertEquals(listOf(2000, 1000), layout.pageHeights)
        assertEquals(listOf(0, 2000 + PageLayout.GAP), layout.tops)
        assertEquals(3000 + PageLayout.GAP, layout.height)
    }

    @Test
    fun `wide pages are narrowed to the width limit`() {
        val layout = PageLayout.of(listOf(3200 to 1600), maxWidth = 1600, maxHeight = 12_000)
        assertEquals(1600, layout.width)
        assertEquals(listOf(800), layout.pageHeights)
        assertEquals(800, layout.height)
    }

    @Test
    fun `a very long scan is shrunk to fit the height limit`() {
        val sizes = List(10) { 1600 to 3000 }
        val layout = PageLayout.of(sizes, maxWidth = 1600, maxHeight = 12_000)
        assertTrue(layout.height <= 12_000)
        assertTrue(layout.height > 11_900)
        assertTrue(layout.width < 1600)
        assertEquals(10, layout.tops.size)
        assertEquals(layout.height, layout.tops.last() + layout.pageHeights.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty page is rejected`() {
        PageLayout.of(listOf(100 to 0), 1600, 12_000)
    }

    @Test
    fun `page uris round trip through one navigation argument`() {
        val uris = listOf("content://media/1", "file:///data/scan%202.jpg")
        assertEquals(uris, PageUris.decode(PageUris.encode(uris)))
        assertEquals(emptyList<String>(), PageUris.decode(""))
        assertEquals(emptyList<String>(), PageUris.decode(null))
        assertEquals(listOf("content://one"), PageUris.decode("content://one"))
    }

    @Test
    fun `PDF pages are enlarged to read well but never beyond the memory cap`() {
        assertEquals(2f, PdfRaster.scale(800, 1100, 1600), 0.001f)
        assertEquals("already wide enough", 1f, PdfRaster.scale(2000, 3000, 1600), 0.001f)
        val huge = PdfRaster.scale(14_400, 14_400, 1600)
        assertTrue(huge < 1f)
        assertTrue(14_400 * huge * 14_400 * huge <= PdfRaster.MAX_PIXELS * 1.01f)
    }
}
