package com.youraicqa.app

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object SyntaxHighlighter {

    private val COLOR_KEYWORD: Int get() = Th.fg(0xFFC792EA.toInt())
    private val COLOR_STRING: Int get() = Th.fg(0xFFA5D6A7.toInt())
    private val COLOR_COMMENT: Int get() = Th.fg(0xFF6A737D.toInt())
    private val COLOR_NUMBER: Int get() = Th.fg(0xFFF2B366.toInt())
    private val COLOR_TYPE: Int get() = Th.fg(0xFF82B1FF.toInt())

    private val CLIKE_KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "class", "object", "interface",
        "package", "import", "private", "public", "protected", "internal", "override", "companion", "init", "this",
        "super", "try", "catch", "finally", "throw", "is", "as", "in", "out", "null", "true", "false", "lateinit",
        "const", "suspend", "sealed", "data", "enum", "abstract", "open", "inline", "static", "final", "void", "int",
        "long", "short", "byte", "float", "double", "boolean", "bool", "char", "string", "new", "extends",
        "implements", "instanceof", "namespace", "using", "struct", "typedef", "template", "typename", "virtual",
        "delete", "auto", "nullptr", "func", "chan", "defer", "go", "range", "map", "switch", "case", "break",
        "continue", "default", "export", "from", "async", "await", "yield", "let", "function", "typeof",
        "undefined", "of", "readonly", "type", "protocol", "extension", "guard", "self", "nil", "base", "foreach"
    )
    private val PY_KEYWORDS = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "try", "except",
        "finally", "raise", "with", "lambda", "yield", "pass", "break", "continue", "and", "or", "not", "in", "is",
        "None", "True", "False", "self", "global", "nonlocal", "async", "await", "del", "assert"
    )
    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "return",
        "local", "export", "echo", "exit", "in", "select", "until", "break", "continue"
    )
    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
        "alter", "drop", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "as",
        "and", "or", "not", "null", "primary", "key", "foreign", "references", "index", "view", "union", "limit",
        "distinct", "default"
    )

    private class LangProfile(
        val keywords: Set<String>,
        val lineComment: String? = null,
        val blockComment: Pair<String, String>? = null,
        val hashComment: Boolean = false,
        val markup: Boolean = false
    )

    private fun profileFor(lang: String): LangProfile = when (lang.trim().lowercase()) {
        "kotlin", "kt", "kts" -> LangProfile(CLIKE_KEYWORDS, lineComment = "//", blockComment = "/*" to "*/")
        "java" -> LangProfile(CLIKE_KEYWORDS, lineComment = "//", blockComment = "/*" to "*/")
        "javascript", "js", "jsx" -> LangProfile(CLIKE_KEYWORDS, lineComment = "//", blockComment = "/*" to "*/")
        "typescript", "ts", "tsx" -> LangProfile(CLIKE_KEYWORDS, lineComment = "//", blockComment = "/*" to "*/")
        "c", "cpp", "c++", "h", "hpp", "cs", "csharp", "go", "swift", "rust", "rs", "dart", "kotlin-script" ->
            LangProfile(CLIKE_KEYWORDS, lineComment = "//", blockComment = "/*" to "*/")
        "python", "py" -> LangProfile(PY_KEYWORDS, hashComment = true)
        "bash", "sh", "shell", "zsh", "console", "terminal" -> LangProfile(SHELL_KEYWORDS, hashComment = true)
        "yaml", "yml" -> LangProfile(emptySet(), hashComment = true)
        "sql" -> LangProfile(SQL_KEYWORDS, lineComment = "--")
        "html", "htm", "xml" -> LangProfile(emptySet(), blockComment = "<!--" to "-->", markup = true)
        "css", "scss", "less" -> LangProfile(emptySet(), blockComment = "/*" to "*/")
        "json" -> LangProfile(setOf("true", "false", "null"))
        else -> LangProfile(CLIKE_KEYWORDS, lineComment = "//", blockComment = "/*" to "*/")
    }

    private val NUMBER_PATTERN = Pattern.compile("0[xX][0-9a-fA-F]+|\\d+\\.?\\d*([eE][+-]?\\d+)?[fFLl]?")
    private val WORD_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*")
    private val TAG_PATTERN = Pattern.compile("</?[A-Za-z][A-Za-z0-9:_-]*|/?>")

    fun highlight(code: String, lang: String): SpannableString {
        val ss = SpannableString(code)
        if (code.isEmpty()) return ss
        val profile = profileFor(lang)
        val numberMatcher = NUMBER_PATTERN.matcher(code)
        val wordMatcher = WORD_PATTERN.matcher(code)
        val tagMatcher = TAG_PATTERN.matcher(code)
        val n = code.length
        var i = 0
        fun span(color: Int, from: Int, to: Int) {
            if (to > from) ss.setSpan(ForegroundColorSpan(color), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        while (i < n) {
            val c = code[i]
            if (profile.lineComment != null && code.startsWith(profile.lineComment, i)) {
                val end = code.indexOf('\n', i).let { if (it == -1) n else it }
                span(COLOR_COMMENT, i, end)
                i = end
                continue
            }
            if (profile.hashComment && c == '#') {
                val end = code.indexOf('\n', i).let { if (it == -1) n else it }
                span(COLOR_COMMENT, i, end)
                i = end
                continue
            }
            if (profile.blockComment != null && code.startsWith(profile.blockComment.first, i)) {
                val closeAt = code.indexOf(profile.blockComment.second, i + profile.blockComment.first.length)
                val end = if (closeAt == -1) n else closeAt + profile.blockComment.second.length
                span(COLOR_COMMENT, i, end)
                i = end
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < n) {
                    if (code[j] == '\\' && j + 1 < n) { j += 2; continue }
                    if (code[j] == c) { j++; break }
                    if (code[j] == '\n') break
                    j++
                }
                span(COLOR_STRING, i, j.coerceAtMost(n))
                i = j
                continue
            }
            if (profile.markup && c == '<') {
                tagMatcher.region(i, n)
                if (tagMatcher.lookingAt()) {
                    span(COLOR_KEYWORD, tagMatcher.start(), tagMatcher.end())
                    i = tagMatcher.end()
                    continue
                }
            }
            if (c.isDigit()) {
                numberMatcher.region(i, n)
                if (numberMatcher.lookingAt()) {
                    span(COLOR_NUMBER, numberMatcher.start(), numberMatcher.end())
                    i = numberMatcher.end()
                    continue
                }
            }
            if (c.isLetter() || c == '_') {
                wordMatcher.region(i, n)
                if (wordMatcher.lookingAt()) {
                    val word = wordMatcher.group()
                    when {
                        word in profile.keywords -> span(COLOR_KEYWORD, wordMatcher.start(), wordMatcher.end())
                        !profile.markup && word.isNotEmpty() && word[0].isUpperCase() ->
                            span(COLOR_TYPE, wordMatcher.start(), wordMatcher.end())
                    }
                    i = wordMatcher.end()
                    continue
                }
            }
            i++
        }
        return ss
    }

    fun lineNumbers(code: String): String {
        val count = code.count { it == '\n' } + 1
        return (1..count).joinToString("\n")
    }
}
