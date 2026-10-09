package com.pumsok.story

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubReaderTest {
    private fun epub(container: String = "OEBPS/content.opf", extra: String = ""): File {
        val file = File.createTempFile("storybook", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val files = mapOf(
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="$container"/></rootfiles></container>""",
                "OEBPS/content.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>테스트 동화</dc:title></metadata><manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>""",
                "OEBPS/a.xhtml" to """<html><body><p>두 번째 문단</p>$extra</body></html>""",
                "OEBPS/b.xhtml" to """<html><body><p>첫 번째 문단</p><script>악성 코드</script></body></html>"""
            )
            for ((path, body) in files) {
                zip.putNextEntry(ZipEntry(path)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
        return file
    }

    @Test fun followsSpineAndExcludesScripts() {
        val file = epub()
        try {
            val result = EpubReader.read(file)
            assertEquals("테스트 동화", result.title)
            assertEquals(listOf("첫 번째 문단", "두 번째 문단"), result.paragraphs)
        } finally { file.delete() }
    }

    @Test fun rejectsTraversal() {
        val file = epub("../content.opf")
        try {
            assertThrows(IllegalArgumentException::class.java) { EpubReader.read(file) }
        } finally { file.delete() }
    }

    @Test fun avoidsNestedBlockDuplicates() {
        val file = epub(extra = "<ul><li><p>중첩 문단</p></li></ul>")
        try {
            assertEquals(1, EpubReader.read(file).paragraphs.count { it == "중첩 문단" })
        } finally { file.delete() }
    }
}
