package com.youraicqa.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Turns attached files and archives into text that is sent to the model together with the message.
 * Source code (.kt, .java, .py ...), configs and other text formats are passed as text; archives (ZIP, RAR, TAR, TAR.GZ, GZ)
 * are unpacked in memory and the text files inside are included, not only their names.
 */
object FileTextExtractor {
    /** Overall limit of characters that one attachment adds to the prompt */
    const val MAX_TOTAL_CHARS = 60_000
    private const val MAX_FILE_CHARS = 20_000
    private const val MAX_ENTRY_BYTES = 512 * 1024
    private const val MAX_ENTRIES = 300
    private const val MAX_UNPACKED_BYTES = 24L * 1024 * 1024

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "rst", "adoc", "json", "json5", "jsonc", "csv", "tsv", "log", "xml", "html", "htm", "xhtml", "svg",
        "css", "scss", "sass", "less", "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte", "astro",
        "kt", "kts", "java", "groovy", "gradle", "scala", "clj", "py", "pyw", "pyi", "ipynb", "rb", "php", "go", "rs", "swift",
        "c", "h", "cpp", "hpp", "cc", "cxx", "hh", "cs", "fs", "vb", "m", "mm", "dart", "lua", "pl", "pm", "r", "jl", "hs", "ex", "exs",
        "erl", "elm", "zig", "nim", "v", "sv", "vhd", "asm", "s", "sql", "graphql", "gql", "proto", "thrift",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1", "awk", "sed",
        "yml", "yaml", "toml", "ini", "cfg", "conf", "config", "properties", "env", "plist", "gitignore", "gitattributes", "gitmodules",
        "editorconfig", "dockerfile", "dockerignore", "makefile", "mk", "cmake", "pro", "pbxproj", "podspec", "lock", "sum", "mod",
        "tex", "bib", "srt", "vtt", "ass", "diff", "patch", "pem", "csr", "tpl", "hbs", "ejs", "jinja", "j2", "twig", "liquid",
        "aidl", "smali", "tsv", "plugin", "nanoplugin", "ino", "sol", "wgsl", "glsl", "frag", "vert", "hlsl", "cu", "cl", "tcl", "vim", "el"
    )
    private val TEXT_NAMES = setOf(
        "dockerfile", "makefile", "gemfile", "rakefile", "procfile", "license", "licence", "readme", "changelog", "authors",
        "gradlew", "jenkinsfile", "vagrantfile", "cmakelists.txt", "build", "workspace", "codeowners", "notice"
    )
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tif", "tiff", "heic", "avif", "psd",
        "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "zst", "jar", "aar", "war", "apk", "aab", "dex", "so", "dll", "exe", "bin",
        "class", "o", "a", "lib", "dylib", "ttf", "otf", "woff", "woff2", "eot", "mp3", "mp4", "wav", "ogg", "opus", "flac", "m4a",
        "mov", "avi", "mkv", "webm", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "db", "sqlite", "sqlite3", "keystore", "jks",
        "gguf", "onnx", "pt", "pth", "safetensors", "ckpt", "dat", "pyc", "iso", "img"
    )

    private fun ext(name: String): String {
        val n = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return if (n.contains('.')) n.substringAfterLast('.') else ""
    }

    fun isTextName(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (ext(name) in TEXT_EXT || base in TEXT_NAMES) return true
        // dot-files without extension (".env", ".gitignore")
        return base.startsWith(".") && base.indexOf('.', 1) < 0 && base.length < 30
    }

    fun isBinaryName(name: String): Boolean = ext(name) in BINARY_EXT

    /** "zip" | "rar" | "tar" | "tgz" | "gz" | "7z" | null */
    fun archiveKind(name: String, mime: String): String? {
        val n = name.lowercase()
        val m = mime.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".aar") || (m.contains("zip") && !m.contains("gzip")) -> "zip"
            n.endsWith(".rar") || m.contains("rar") -> "rar"
            n.endsWith(".tar") || m == "application/x-tar" -> "tar"
            n.endsWith(".gz") || m.contains("gzip") -> "gz"
            n.endsWith(".7z") || m.contains("7z") -> "7z"
            else -> null
        }
    }

    /** True when the bytes look like text (no NUL, few control characters) */
    fun looksLikeText(bytes: ByteArray, len: Int = bytes.size): Boolean {
        val n = minOf(len, 8192)
        if (n == 0) return true
        var bad = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 9 || (b in 14..31 && b != 27)) bad++
        }
        return bad * 100 / n < 3
    }

    private val CP1251: Charset? = try { Charset.forName("windows-1251") } catch (_: Exception) { null }

    /** UTF-8 (with or without BOM); old Russian files in windows-1251 are decoded correctly too */
    fun decode(bytes: ByteArray, len: Int = bytes.size, cut: Boolean = false): String {
        var off = 0
        if (len >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) off = 3
        if (len >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, len - 2, Charsets.UTF_16LE)
        if (len >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, len - 2, Charsets.UTF_16BE)
        var end = len
        // A cut-off buffer may end in the middle of a multi-byte character: drop the incomplete tail
        if (cut) {
            var back = 0
            while (back < 3 && end - back - 1 > off && (bytes[end - back - 1].toInt() and 0xC0) == 0x80) back++
            if (end - back - 1 >= off) {
                val lead = bytes[end - back - 1].toInt() and 0xFF
                val need = when { lead >= 0xF0 -> 4; lead >= 0xE0 -> 3; lead >= 0xC0 -> 2; else -> 1 }
                if (need > back + 1) end = end - back - 1
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, off, end - off)).toString()
        } catch (_: CharacterCodingException) {
            val cs = CP1251
            if (cs != null) String(bytes, off, end - off, cs) else String(bytes, off, end - off, Charsets.UTF_8)
        }
    }

    /** Text of a regular (non-archive) file for the prompt */
    fun plainFileText(name: String, mime: String, bytes: ByteArray, sizeLabel: String): String {
        val binaryByName = isBinaryName(name)
        if (binaryByName || !looksLikeText(bytes)) {
            return "Файл: $name ($mime, $sizeLabel) — двоичный файл, содержимое не показано."
        }
        val truncated = bytes.size > MAX_TOTAL_CHARS * 2
        val text = decode(bytes, minOf(bytes.size, MAX_TOTAL_CHARS * 2), truncated)
        val shown = if (text.length > MAX_TOTAL_CHARS) text.substring(0, MAX_TOTAL_CHARS) else text
        val cut = truncated || text.length > MAX_TOTAL_CHARS
        val lang = ext(name)
        val head = "Файл: $name ($mime, $sizeLabel)" + if (lang.isNotEmpty()) ", тип: .$lang" else ""
        return head + "\n\n" + shown + if (cut) "\n\n[… файл обрезан: показано первые ${shown.length} символов …]" else ""
    }

    private class Collector(val title: String) {
        val index = StringBuilder()
        val bodies = StringBuilder()
        var entries = 0
        var shownFiles = 0
        var skippedByBudget = 0
        var budget = MAX_TOTAL_CHARS
        var unpacked = 0L

        fun add(path: String, size: Long, data: ByteArray?, dataLen: Int, cutOff: Boolean) {
            entries++
            if (entries > MAX_ENTRIES) return
            val sz = if (size >= 0) " (" + ChatAttach.formatSize(size) + ")" else ""
            val textual = data != null && !isBinaryName(path) && looksLikeText(data, dataLen)
            if (!textual) {
                index.append("• ").append(path).append(sz).append('\n')
                return
            }
            if (budget < 200) {
                skippedByBudget++
                index.append("• ").append(path).append(sz).append(" — не показан (лимит объёма)\n")
                return
            }
            var text = decode(data!!, dataLen, cutOff)
            var cut = cutOff
            if (text.length > MAX_FILE_CHARS) { text = text.substring(0, MAX_FILE_CHARS); cut = true }
            if (text.length > budget) { text = text.substring(0, budget); cut = true }
            budget -= text.length
            shownFiles++
            index.append("• ").append(path).append(sz).append(" — текст показан\n")
            bodies.append("\n=== ").append(path).append(" ===\n").append(text)
            if (!text.endsWith("\n")) bodies.append('\n')
            if (cut) bodies.append("[… файл обрезан …]\n")
        }

        fun result(extra: String = ""): String {
            val sb = StringBuilder(title).append('\n')
            sb.append("Файлов в архиве: ").append(entries)
            if (entries > MAX_ENTRIES) sb.append(" (в списке первые ").append(MAX_ENTRIES).append(')')
            sb.append("\nСодержимое:\n").append(index)
            if (extra.isNotEmpty()) sb.append(extra).append('\n')
            if (bodies.isNotEmpty()) sb.append("\nТекстовые файлы из архива:\n").append(bodies)
            return sb.toString().trimEnd()
        }
    }

    /** Reads up to [max] bytes of the current stream position; the rest is skipped. Returns the data and whether it was cut. */
    private fun readLimited(ins: InputStream, max: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(minOf(max, 16 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        var cut = false
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            if (total < max) {
                val take = minOf(n, max - total)
                out.write(buf, 0, take)
                if (take < n) cut = true
            } else cut = true
            total += n
            if (total > max * 4) { cut = true; break }
        }
        return out.toByteArray() to cut
    }

    fun zipText(ins: InputStream, name: String, sizeLabel: String): String {
        val c = Collector("Архив ZIP: $name ($sizeLabel)")
        ZipInputStream(java.io.BufferedInputStream(ins)).use { zis ->
            var e = zis.nextEntry
            while (e != null && c.entries < MAX_ENTRIES + 50) {
                if (!e.isDirectory) {
                    if (c.unpacked > MAX_UNPACKED_BYTES) { c.entries++; e = zis.nextEntry; continue }
                    val wantBody = !isBinaryName(e.name)
                    if (wantBody) {
                        val (data, cut) = readLimited(zis, MAX_ENTRY_BYTES)
                        c.unpacked += data.size
                        c.add(e.name, e.size, data, data.size, cut)
                    } else {
                        c.add(e.name, e.size, null, 0, false)
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        if (c.entries == 0) throw IllegalArgumentException("Архив повреждён или не содержит читаемых записей")
        return c.result()
    }

    fun tarText(ins: InputStream, title: String): String {
        val c = Collector(title)
        val header = ByteArray(512)
        var longName: String? = null
        fun readFully(buf: ByteArray, len: Int): Int {
            var got = 0
            while (got < len) { val n = ins.read(buf, got, len - got); if (n < 0) break; got += n }
            return got
        }
        fun skipBytes(count: Long) {
            var left = count
            val tmp = ByteArray(8192)
            while (left > 0) { val n = ins.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt()); if (n < 0) break; left -= n }
        }
        while (c.entries < MAX_ENTRIES + 50) {
            if (readFully(header, 512) < 512) break
            if (header.all { it.toInt() == 0 }) break
            fun field(off: Int, len: Int): String {
                var end = off
                while (end < off + len && header[end].toInt() != 0) end++
                return String(header, off, end - off, Charsets.UTF_8)
            }
            var path = field(0, 100)
            val prefix = field(345, 155)
            if (prefix.isNotEmpty() && field(257, 5) == "ustar") path = "$prefix/$path"
            val size = field(124, 12).trim().toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if (type == 'L') { // GNU long file name
                val (data, _) = readLimited(ins, 4096)
                longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
                skipBytes(padded - data.size.coerceAtMost(size.toInt()))
                continue
            }
            if (longName != null) { path = longName; longName = null }
            if (type == '0' || type == '\u0000') {
                if (c.unpacked > MAX_UNPACKED_BYTES || isBinaryName(path)) {
                    c.add(path, size, null, 0, false); skipBytes(padded)
                } else {
                    val want = minOf(size, MAX_ENTRY_BYTES.toLong()).toInt()
                    val data = ByteArray(want)
                    val got = readFully(data, want)
                    c.unpacked += got
                    c.add(path, size, data, got, size > want)
                    skipBytes(padded - got)
                }
            } else {
                skipBytes(padded)
            }
        }
        if (c.entries == 0) throw IllegalArgumentException("Архив повреждён или не содержит читаемых записей")
        return c.result()
    }

    fun tgzText(ins: InputStream, name: String, sizeLabel: String): String =
        GZIPInputStream(java.io.BufferedInputStream(ins)).use { tarText(it, "Архив TAR.GZ: $name ($sizeLabel)") }

    /** .gz holds either a tar archive or one packed file */
    fun gzText(ins: InputStream, name: String, sizeLabel: String): String {
        val raw = GZIPInputStream(java.io.BufferedInputStream(ins)).use { readLimited(it, MAX_ENTRY_BYTES) }
        val (data, cut) = raw
        val isTar = data.size >= 262 && String(data, 257, 5, Charsets.ISO_8859_1) == "ustar"
        if (isTar) return tarText(java.io.ByteArrayInputStream(data), "Архив TAR.GZ: $name ($sizeLabel)")
        val inner = name.removeSuffix(".gz").removeSuffix(".GZ")
        val c = Collector("Сжатый файл GZ: $name ($sizeLabel)")
        c.add(inner, -1, data, data.size, cut)
        return c.result()
    }

    private class LimitedOut(private val limit: Int) : OutputStream() {
        val buf = ByteArrayOutputStream()
        var overflow = false
        override fun write(b: Int) {
            if (buf.size() >= limit) { overflow = true; throw IOException("limit") }
            buf.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = limit - buf.size()
            if (room <= 0) { overflow = true; throw IOException("limit") }
            if (len > room) { buf.write(b, off, room); overflow = true; throw IOException("limit") }
            buf.write(b, off, len)
        }
    }

    fun rarText(cacheDir: File, ins: InputStream, name: String, sizeLabel: String): String {
        val tmp = File(cacheDir, "attach_${System.currentTimeMillis()}.rar")
        try {
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val c = Collector("Архив RAR: $name ($sizeLabel)")
            val arch = com.github.junrar.Archive(tmp)
            try {
                for (hdr in arch.fileHeaders) {
                    if (c.entries >= MAX_ENTRIES + 50) break
                    if (hdr.isDirectory) continue
                    val fn = try { hdr.fileNameString } catch (_: Exception) { "?" }
                    val size = hdr.fullUnpackSize
                    if (isBinaryName(fn) || c.unpacked > MAX_UNPACKED_BYTES || hdr.isEncrypted) {
                        c.add(fn, size, null, 0, false)
                        continue
                    }
                    val out = LimitedOut(MAX_ENTRY_BYTES)
                    try { arch.extractFile(hdr, out) } catch (_: Exception) { /* limit reached or damaged entry: use what was read */ }
                    val data = out.buf.toByteArray()
                    c.unpacked += data.size
                    if (data.isEmpty() && size > 0) c.add(fn, size, null, 0, false) else c.add(fn, size, data, data.size, out.overflow)
                }
            } finally {
                try { arch.close() } catch (_: Exception) {}
            }
            if (c.entries == 0) throw IllegalArgumentException("Архив пуст или повреждён")
            return c.result()
        } finally {
            tmp.delete()
        }
    }

    /**
     * One entry point: returns the prompt text for any non-media, non-document attachment,
     * or null when [name]/[mime] is not an archive (the caller then uses [plainFileText]).
     */
    fun archiveText(cacheDir: File, open: () -> InputStream?, name: String, mime: String, sizeLabel: String): String? {
        val kind = archiveKind(name, mime) ?: return null
        val ins = open() ?: throw IOException("Не удалось открыть файл")
        return when (kind) {
            "zip" -> zipText(ins, name, sizeLabel)
            "rar" -> rarText(cacheDir, ins, name, sizeLabel)
            "tar" -> ins.use { tarText(it, "Архив TAR: $name ($sizeLabel)") }
            "tgz" -> tgzText(ins, name, sizeLabel)
            "gz" -> gzText(ins, name, sizeLabel)
            else -> { ins.close(); "Архив 7Z: $name ($sizeLabel) — формат 7z приложение распаковать не может, содержимое не показано. Попросите пользователя прислать ZIP или RAR." }
        }
    }
}
