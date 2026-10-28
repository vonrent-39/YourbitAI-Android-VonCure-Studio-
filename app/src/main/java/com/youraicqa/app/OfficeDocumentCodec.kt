package com.youraicqa.app

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Small dependency-light text bridge for DOCX plus PDFBox-backed PDF text import/export. */
object OfficeDocumentCodec {
    fun extractDocx(bytes: ByteArray): String {
        val entries = unzip(bytes)
        val xml = entries["word/document.xml"] ?: throw IllegalArgumentException("В DOCX отсутствует word/document.xml")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val out = ArrayList<String>(paragraphs.length)
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val nodes = p.getElementsByTagNameNS("*", "t")
            val paragraph = StringBuilder()
            for (j in 0 until nodes.length) paragraph.append(nodes.item(j).textContent.orEmpty())
            // Preserve explicit tabs and line breaks when the XML uses them.
            val all = p.getElementsByTagNameNS("*", "tab")
            if (all.length > 0 && paragraph.isEmpty()) paragraph.append("\t")
            out += paragraph.toString()
        }
        return out.joinToString("\n").trim()
    }

    fun rewriteDocx(original: ByteArray, text: String): ByteArray {
        val entries = unzip(original).toMutableMap()
        val oldXml = entries["word/document.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("В DOCX отсутствует word/document.xml")
        val sectPr = Regex("(?s)<w:sectPr\\b.*?</w:sectPr>").find(oldXml)?.value
            ?: "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>"
        val rootOpenTag = Regex("(?s)<w:document\\b([^>]*)>").find(oldXml)?.groupValues?.get(1).orEmpty()
        val namespaces = Regex("""xmlns(?::[\w.-]+)?\s*=\s*(?:\"[^\"]*\"|'[^']*')""")
            .findAll(rootOpenTag).joinToString(" ") { it.value }
            .ifBlank { "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" }
        entries["word/document.xml"] = documentXml(text, sectPr, namespaces).toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    fun createDocx(text: String): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        entries["[Content_Types].xml"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(Charsets.UTF_8)
        entries["word/document.xml"] = documentXml(text, "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>").toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    fun extractPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            return PDFTextStripper().getText(doc).trim()
        }
    }

    /** Writes an editable, searchable PDF from revised text. This intentionally reflows text
     * instead of pretending to preserve the source PDF's exact layout or embedded graphics. */
    fun createPdf(context: Context, text: String): ByteArray {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument().use { document ->
            val unicodeFont = loadUnicodeFont(document)
            val font: PDFont = unicodeFont ?: PDType1Font.HELVETICA
            fun renderable(value: String): String {
                val cleaned = safePdfText(value)
                if (unicodeFont == null) return cleaned.map { if (it.code in 32..255) it else '?' }.joinToString("")
                val out = StringBuilder()
                var offset = 0
                while (offset < cleaned.length) {
                    val cp = cleaned.codePointAt(offset)
                    val glyph = String(Character.toChars(cp))
                    if (runCatching { font.encode(glyph) }.isSuccess) out.append(glyph) else out.append('?')
                    offset += Character.charCount(cp)
                }
                return out.toString()
            }
            val fontSize = 10.5f
            val left = 48f
            val right = 48f
            val top = 48f
            val bottom = 48f
            val pageWidth = PDRectangle.LETTER.width
            val pageHeight = PDRectangle.LETTER.height
            val width = pageWidth - left - right
            val leading = 14f
            var page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            var stream = PDPageContentStream(document, page)
            var y = pageHeight - top
            stream.beginText()
            stream.setFont(font, fontSize)
            stream.newLineAtOffset(left, y)
            fun closePage() {
                stream.endText()
                stream.close()
            }
            fun newPage() {
                closePage()
                page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                stream = PDPageContentStream(document, page)
                y = pageHeight - top
                stream.beginText()
                stream.setFont(font, fontSize)
                stream.newLineAtOffset(left, y)
            }
            val sourceLines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            for (sourceLine in sourceLines) {
                val words = sourceLine.split(Regex("\\s+"))
                var line = ""
                for (word in words) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    val measuredText = renderable(candidate)
                    val measured = runCatching { font.getStringWidth(measuredText) / 1000f * fontSize }.getOrDefault(width + 1)
                    if (measured > width && line.isNotEmpty()) {
                        if (y - leading < bottom) newPage()
                        stream.showText(renderable(line))
                        stream.newLineAtOffset(0f, -leading)
                        y -= leading
                        line = word
                    } else line = candidate
                }
                if (y - leading < bottom) newPage()
                if (line.isNotEmpty()) stream.showText(renderable(line))
                stream.newLineAtOffset(0f, -leading)
                y -= leading
            }
            closePage()
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        }
    }

    private fun loadUnicodeFont(document: PDDocument): PDFont? {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.isFile) {
                try { FileInputStream(file).use { return PDType0Font.load(document, it) } }
                catch (_: Exception) { }
            }
        }
        return null
    }

    private fun safePdfText(value: String): String = value.replace('\u0000', ' ').replace('\t', ' ').take(4000)

    private fun documentXml(
        text: String,
        sectPr: String,
        namespaces: String = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    ): String {
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $namespaces><w:body>$paragraphs$sectPr</w:body></w:document>"""
    }

    private fun escapeXml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) map[entry.name] = input.readBytes()
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        return map
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
