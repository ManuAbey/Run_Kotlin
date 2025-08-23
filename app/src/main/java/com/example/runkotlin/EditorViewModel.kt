package com.example.runkotlin

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class DocumentStats(
    val lines: Int,
    val words: Int,
    val characters: Int
)

class EditorViewModel : ViewModel() {

    private val _documentStats = MutableLiveData<DocumentStats>()
    val documentStats: LiveData<DocumentStats> = _documentStats

    private val _compilationResult = MutableLiveData<CompilationResult>()
    val compilationResult: LiveData<CompilationResult> = _compilationResult

    fun updateDocumentStats(text: String) {
        val lines = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
        val words = if (text.trim().isEmpty()) 0 else text.trim().split(Regex("\\s+")).size
        val characters = text.length

        _documentStats.value = DocumentStats(lines, words, characters)
    }

    fun setCompilationResult(result: CompilationResult) {
        _compilationResult.value = result
    }
}