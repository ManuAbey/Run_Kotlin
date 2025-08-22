package com.example.runkotlin

import android.widget.EditText
import java.util.regex.Pattern

class FindReplaceHelper(private val editText: EditText) {

    private var lastSearchPosition = 0
    private var currentSearchTerm = ""

    fun findNext(searchTerm: String, caseSensitive: Boolean = false, wholeWord: Boolean = false): Boolean {
        if (searchTerm.isEmpty()) return false

        val text = editText.text.toString()
        if (text.isEmpty()) return false

        // Reset position if search term changed
        if (currentSearchTerm != searchTerm) {
            lastSearchPosition = 0
            currentSearchTerm = searchTerm
        }

        val pattern = createSearchPattern(searchTerm, caseSensitive, wholeWord)
        val matcher = pattern.matcher(text)

        // Find next occurrence starting from last position
        if (matcher.find(lastSearchPosition)) {
            highlightMatch(matcher.start(), matcher.end())
            lastSearchPosition = matcher.end()
            return true
        } else {
            // Wrap around to beginning
            if (matcher.find(0) && matcher.start() < lastSearchPosition) {
                highlightMatch(matcher.start(), matcher.end())
                lastSearchPosition = matcher.end()
                return true
            }
        }

        return false
    }

    fun findPrevious(searchTerm: String, caseSensitive: Boolean = false, wholeWord: Boolean = false): Boolean {
        if (searchTerm.isEmpty()) return false

        val text = editText.text.toString()
        if (text.isEmpty()) return false

        val pattern = createSearchPattern(searchTerm, caseSensitive, wholeWord)
        val matcher = pattern.matcher(text)

        var lastMatch: Pair<Int, Int>? = null
        var foundMatch: Pair<Int, Int>? = null

        // Find all matches before current position
        while (matcher.find()) {
            if (matcher.start() < lastSearchPosition) {
                lastMatch = Pair(matcher.start(), matcher.end())
            } else {
                break
            }
        }

        if (lastMatch != null) {
            foundMatch = lastMatch
        } else {
            // Wrap around to end - find last match in entire text
            matcher.reset()
            while (matcher.find()) {
                lastMatch = Pair(matcher.start(), matcher.end())
            }
            foundMatch = lastMatch
        }

        foundMatch?.let { match ->
            highlightMatch(match.first, match.second)
            lastSearchPosition = match.first
            return true
        }

        return false
    }

    fun replaceNext(searchTerm: String, replaceTerm: String, caseSensitive: Boolean = false, wholeWord: Boolean = false): Boolean {
        if (findNext(searchTerm, caseSensitive, wholeWord)) {
            val start = editText.selectionStart
            val end = editText.selectionEnd

            val text = editText.text.toString()
            val newText = text.substring(0, start) + replaceTerm + text.substring(end)

            editText.setText(newText)
            editText.setSelection(start + replaceTerm.length)

            return true
        }
        return false
    }

    fun replaceAll(searchTerm: String, replaceTerm: String, caseSensitive: Boolean = false, wholeWord: Boolean = false): Int {
        val text = editText.text.toString()
        val pattern = createSearchPattern(searchTerm, caseSensitive, wholeWord)

        val newText = pattern.matcher(text).replaceAll(replaceTerm)
        val replacementCount = countOccurrences(text, pattern)

        editText.setText(newText)

        return replacementCount
    }

    private fun createSearchPattern(searchTerm: String, caseSensitive: Boolean, wholeWord: Boolean): Pattern {
        var patternString = Pattern.quote(searchTerm)

        if (wholeWord) {
            patternString = "\\b$patternString\\b"
        }

        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        return Pattern.compile(patternString, flags)
    }

    private fun highlightMatch(start: Int, end: Int) {
        editText.setSelection(start, end)
        editText.requestFocus()
    }

    private fun countOccurrences(text: String, pattern: Pattern): Int {
        val matcher = pattern.matcher(text)
        var count = 0
        while (matcher.find()) {
            count++
        }
        return count
    }

    fun resetSearch() {
        lastSearchPosition = 0
        currentSearchTerm = ""
    }
}