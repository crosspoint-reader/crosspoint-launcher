package com.crosspoint.launcher.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Opens the CrossPoint firmware's EPUB fixtures with the Android parser.
 *
 * The fixtures are copied verbatim from crosspoint-reader/test/epubs at the commit recorded in
 * docs/porting/upstream.md. They are the shared acceptance corpus for the Kotlin EPUB engine port.
 */
class EpubFixturesTest {
    private fun fixtures(): List<File> {
        val root = requireNotNull(javaClass.classLoader?.getResource("epubs")) { "Missing epubs test resources" }
        return File(root.toURI()).listFiles { file -> file.isFile && file.extension == "epub" }
            .orEmpty()
            .sortedBy { it.name }
    }

    @Test
    fun everyFirmwareFixtureOpens() {
        val files = fixtures()
        assertEquals("Fixture corpus changed without updating the test", 18, files.size)
        for (file in files) {
            val book = EpubBook(file)
            assertTrue("${file.name}: no chapters", book.chapters.isNotEmpty())
            assertFalse("${file.name}: empty title", book.title.isBlank())
            assertTrue("${file.name}: first chapter is empty", book.chapterHtml(0).isNotBlank())
        }
    }

    @Test
    fun tableOfContentsFixturesResolveToSpineChapters() {
        for (file in fixtures()) {
            val book = EpubBook(file)
            for (entry in book.toc) {
                assertTrue("${file.name}: TOC chapter out of range", entry.chapter in book.chapters.indices)
                assertTrue("${file.name}: empty TOC title", entry.title.isNotBlank())
            }
        }
    }
}
