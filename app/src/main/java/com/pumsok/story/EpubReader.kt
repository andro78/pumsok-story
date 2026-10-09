package com.pumsok.story

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

data class Story(val title: String, val paragraphs: List<String>)

object EpubReader {
    private fun xml(bytes: ByteArray) =
        Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())

    fun read(file: File): Story = ZipFile(file).use { zip ->
        val entries = zip.entries().toList()
        require(entries.size <= 2000) { "EPUB 항목 수가 너무 많습니다" }
        var total = 0L
        fun load(path: String): ByteArray {
            val clean = path.removePrefix("/")
            require(clean.isNotBlank() && clean.split('/').none { it == ".." }) { "유효하지 않은 경로: $path" }
            val entry = zip.getEntry(clean) ?: zip.getEntry(path) ?: error("EPUB 파일 누락: $clean")
            return zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= 2 * 1024 * 1024) { "EPUB 항목이 너무 큽니다" }
                    output.write(buffer, 0, n)
                }
                val bytes = output.toByteArray()
                total += bytes.size
                require(total <= 32 * 1024 * 1024) { "EPUB 본문이 너무 큽니다" }
                bytes
            }
        }
        val container = xml(load("META-INF/container.xml"))
        val opfPath = container.select("rootfile").firstOrNull()?.attr("full-path")
            ?.ifBlank { null } ?: error("OPF를 찾을 수 없습니다")
        val opf = xml(load(opfPath))
        val manifest = opf.select("item").associate {
            it.attr("id") to it.attr("href")
        }
        val spine = opf.select("itemref")
        val paragraphs = mutableListOf<String>()
        for (item in spine) {
            if (item.attr("linear") == "no") continue
            val idref = item.attr("idref")
            val href = manifest[idref] ?: error("잘못된 spine: $idref")
            val resolved = URI(opfPath).resolve(href).normalize()
            require(!resolved.isAbsolute && resolved.rawAuthority == null) { "외부 본문은 지원하지 않습니다" }
            val doc = Jsoup.parse(load(resolved.path).inputStream(), null, "")
            doc.select("script,style,nav").remove()
            // Select leaf blocks to avoid duplicating text inside containers.
            val blocks = doc.select("h1,h2,h3,p,li").filter {
                it.select("h1,h2,h3,p,li").size == 1
            }
            if (blocks.isEmpty()) {
                doc.body()?.text()?.takeIf { it.isNotBlank() }?.let { paragraphs.add(it) }
            } else blocks.map { it.text() }.filter { it.isNotBlank() }.forEach { paragraphs.add(it) }
        }
        require(paragraphs.isNotEmpty()) { "읽을 본문이 없습니다. DRM 없는 EPUB을 선택해 주세요" }
        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.ifBlank { null }
            ?: opf.getElementsByTag("title").firstOrNull()?.text()?.ifBlank { null }
            ?: "가져온 동화"
        Story(title, paragraphs)
    }
}
