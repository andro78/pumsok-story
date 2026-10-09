package com.pumsok.story

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.jsoup.Jsoup

data class Story(val title: String, val paragraphs: List<String>)

object EpubReader {
    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(bytes.inputStream())

    fun read(file: File): Story = ZipFile(file).use { zip ->
        val entries = zip.entries().toList()
        require(entries.size <= 2000) { "EPUB 항목 수가 너무 많습니다" }
        var total = 0L
        fun load(path: String): ByteArray {
            require(!path.startsWith("/") && path.split('/').none { it == ".." })
            val entry = zip.getEntry(path) ?: error("EPUB 파일 누락: $path")
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
        val opfPath = container.getElementsByTagNameNS("*", "rootfile").item(0)
            ?.attributes?.getNamedItem("full-path")?.nodeValue ?: error("OPF를 찾을 수 없습니다")
        val opf = xml(load(opfPath))
        val items = opf.getElementsByTagNameNS("*", "item")
        val manifest = (0 until items.length).associate { i ->
            val node = items.item(i)
            node.attributes.getNamedItem("id").nodeValue to node.attributes.getNamedItem("href").nodeValue
        }
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        val paragraphs = mutableListOf<String>()
        for (i in 0 until spine.length) {
            val item = spine.item(i)
            if (item.attributes.getNamedItem("linear")?.nodeValue == "no") continue
            val href = manifest[item.attributes.getNamedItem("idref").nodeValue] ?: error("잘못된 spine")
            val resolved = URI(opfPath).resolve(href).normalize()
            require(!resolved.isAbsolute && resolved.rawAuthority == null) { "외부 본문은 지원하지 않습니다" }
            val doc = Jsoup.parse(load(resolved.path).inputStream(), null, "")
            doc.select("script,style,nav").remove()
            // Select leaf blocks to avoid duplicating text inside containers.
            val blocks = doc.select("h1,h2,h3,p,li").filter {
                it.select("h1,h2,h3,p,li").size == 1
            }
            if (blocks.isEmpty()) {
                doc.body().text().takeIf { it.isNotBlank() }?.let { paragraphs.add(it) }
            } else blocks.map { it.text() }.filter { it.isNotBlank() }.forEach { paragraphs.add(it) }
        }
        require(paragraphs.isNotEmpty()) { "읽을 본문이 없습니다. DRM 없는 EPUB을 선택해 주세요" }
        val title = opf.getElementsByTagNameNS("*", "title").item(0)?.textContent ?: "가져온 동화"
        Story(title, paragraphs)
    }
}
