package com.example.runkotlin

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.regex.Pattern

data class SyntaxConfig(
    val language: String,
    val keywords: List<String>,
    val commentStart: String,
    val commentEnd: String = "",
    val stringDelimiters: List<String>,
    val colors: SyntaxColors
)

data class SyntaxColors(
    val keyword: String = "#FF9800", // Orange
    val comment: String = "#4CAF50", // Green
    val string: String = "#2196F3",  // Blue
    val number: String = "#E91E63"   // Pink
)

class SyntaxHighlighter(private val context: Context) {

    private val syntaxConfigs = mutableMapOf<String, SyntaxConfig>()
    private val gson = Gson()
    private var isHighlighting = false // Prevent infinite loops

    // Default Kotlin configuration
    private val kotlinConfig = SyntaxConfig(
        language = "kotlin",
        keywords = listOf(
            "abstract", "annotation", "as", "break", "by", "catch", "class", "companion",
            "const", "constructor", "continue", "crossinline", "data", "do", "dynamic",
            "else", "enum", "external", "false", "final", "finally", "for", "fun",
            "get", "if", "import", "in", "init", "inline", "inner", "interface",
            "internal", "is", "lateinit", "null", "object", "open", "operator",
            "out", "override", "package", "private", "protected", "public", "reified",
            "return", "sealed", "set", "super", "suspend", "this", "throw", "true",
            "try", "typealias", "val", "var", "vararg", "when", "where", "while"
        ),
        commentStart = "//",
        commentEnd = "",
        stringDelimiters = listOf("\"", "'"),
        colors = SyntaxColors()
    )

    fun loadDefaultConfigurations() {
        // Load Kotlin configuration
        syntaxConfigs["kt"] = kotlinConfig

        // Load Java configuration
        syntaxConfigs["java"] = SyntaxConfig(
            language = "java",
            keywords = listOf(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                "import", "instanceof", "int", "interface", "long", "native", "new",
                "package", "private", "protected", "public", "return", "short", "static",
                "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "true", "false", "null"
            ),
            commentStart = "//",
            commentEnd = "",
            stringDelimiters = listOf("\"", "'"),
            colors = SyntaxColors()
        )

        // Load Python configuration
        syntaxConfigs["py"] = SyntaxConfig(
            language = "python",
            keywords = listOf(
                "and", "as", "assert", "break", "class", "continue", "def", "del", "elif",
                "else", "except", "exec", "finally", "for", "from", "global", "if",
                "import", "in", "is", "lambda", "not", "or", "pass", "print", "raise",
                "return", "try", "while", "with", "yield", "True", "False", "None"
            ),
            commentStart = "#",
            commentEnd = "",
            stringDelimiters = listOf("\"", "'", "\"\"\"", "'''"),
            colors = SyntaxColors()
        )

        // Save configurations to files
        saveConfigurationsToFiles()
    }

    private fun saveConfigurationsToFiles() {
        val configDir = File(context.filesDir, "syntax_configs")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        syntaxConfigs.forEach { (extension, config) ->
            val configFile = File(configDir, "$extension.json")
            try {
                configFile.writeText(gson.toJson(config))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadConfigurationFromFile(extension: String): SyntaxConfig? {
        val configFile = File(context.filesDir, "syntax_configs/$extension.json")
        return if (configFile.exists()) {
            try {
                val json = configFile.readText()
                gson.fromJson(json, SyntaxConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            syntaxConfigs[extension]
        }
    }

    fun saveConfigurationToFile(extension: String, config: SyntaxConfig) {
        val configDir = File(context.filesDir, "syntax_configs")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        val configFile = File(configDir, "$extension.json")
        try {
            configFile.writeText(gson.toJson(config))
            syntaxConfigs[extension] = config
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Extract file extension from filename
    private fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "").lowercase()
    }

    // Apply syntax highlighting with filename (extracts extension automatically)
    fun applySyntaxHighlighting(editText: EditText, filename: String) {
        val extension = getFileExtension(filename)
        applySyntaxHighlightingByExtension(editText, extension)
    }

    // Apply syntax highlighting with direct extension
    fun applySyntaxHighlightingByExtension(editText: EditText, fileExtension: String) {
        if (isHighlighting) return // Prevent infinite loops

        val config = loadConfigurationFromFile(fileExtension) ?: return
        val text = editText.text

        if (text.isEmpty()) return

        isHighlighting = true

        try {
            // Get current cursor position
            val cursorPosition = editText.selectionStart

            // Work with the existing Editable instead of creating new SpannableString
            val spannable = text as? SpannableStringBuilder ?: SpannableStringBuilder(text)

            // Clear existing spans
            val spans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
            spans.forEach { spannable.removeSpan(it) }

            // Apply syntax highlighting
            highlightKeywords(spannable, config)
            highlightComments(spannable, config)
            highlightStrings(spannable, config)
            highlightNumbers(spannable, config)

            // Only set text if it's different (avoid triggering TextWatcher)
            if (editText.text.toString() != spannable.toString()) {
                editText.text = spannable
            }

            // Restore cursor position
            val safePosition = cursorPosition.coerceAtMost(editText.text.length)
            editText.setSelection(safePosition)

        } finally {
            isHighlighting = false
        }
    }

    private fun highlightKeywords(spannable: SpannableStringBuilder, config: SyntaxConfig) {
        val keywordColor = Color.parseColor(config.colors.keyword)

        config.keywords.forEach { keyword ->
            val pattern = Pattern.compile("\\b$keyword\\b")
            val matcher = pattern.matcher(spannable)

            while (matcher.find()) {
                spannable.setSpan(
                    ForegroundColorSpan(keywordColor),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun highlightComments(spannable: SpannableStringBuilder, config: SyntaxConfig) {
        val commentColor = Color.parseColor(config.colors.comment)
        val text = spannable.toString()

        if (config.commentEnd.isEmpty()) {
            // Single-line comments
            val pattern = Pattern.compile("${Pattern.quote(config.commentStart)}.*$", Pattern.MULTILINE)
            val matcher = pattern.matcher(text)

            while (matcher.find()) {
                spannable.setSpan(
                    ForegroundColorSpan(commentColor),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            // Multi-line comments
            val pattern = Pattern.compile("${Pattern.quote(config.commentStart)}.*?${Pattern.quote(config.commentEnd)}", Pattern.DOTALL)
            val matcher = pattern.matcher(text)

            while (matcher.find()) {
                spannable.setSpan(
                    ForegroundColorSpan(commentColor),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun highlightStrings(spannable: SpannableStringBuilder, config: SyntaxConfig) {
        val stringColor = Color.parseColor(config.colors.string)
        val text = spannable.toString()

        config.stringDelimiters.forEach { delimiter ->
            when (delimiter.length) {
                1 -> {
                    // Single character delimiters like " or '
                    val pattern = Pattern.compile("${Pattern.quote(delimiter)}(.*?)${Pattern.quote(delimiter)}")
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        spannable.setSpan(
                            ForegroundColorSpan(stringColor),
                            matcher.start(),
                            matcher.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                3 -> {
                    // Triple quotes like """ or '''
                    val pattern = Pattern.compile("${Pattern.quote(delimiter)}[\\s\\S]*?${Pattern.quote(delimiter)}")
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        spannable.setSpan(
                            ForegroundColorSpan(stringColor),
                            matcher.start(),
                            matcher.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }
    }

    private fun highlightNumbers(spannable: SpannableStringBuilder, config: SyntaxConfig) {
        val numberColor = Color.parseColor(config.colors.number)
        val pattern = Pattern.compile("\\b\\d+(\\.\\d+)?[fFdDlL]?\\b")
        val matcher = pattern.matcher(spannable)

        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(numberColor),
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun getAvailableConfigurations(): List<String> {
        return syntaxConfigs.keys.toList()
    }

    fun getConfiguration(extension: String): SyntaxConfig? {
        return syntaxConfigs[extension]
    }

    // Create a TextWatcher for real-time syntax highlighting
    fun createTextWatcher(editText: EditText, filename: String): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (!isHighlighting) {
                    applySyntaxHighlighting(editText, filename)
                }
            }
        }
    }
}