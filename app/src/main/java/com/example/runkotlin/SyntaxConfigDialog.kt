package com.example.runkotlin

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.runkotlin.databinding.DialogSyntaxConfigBinding

class SyntaxConfigDialog : DialogFragment() {

    private var _binding: DialogSyntaxConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var syntaxHighlighter: SyntaxHighlighter
    private var currentConfig: SyntaxConfig? = null

    companion object {
        fun show(fragmentManager: FragmentManager, highlighter: SyntaxHighlighter) {
            val dialog = SyntaxConfigDialog()
            dialog.syntaxHighlighter = highlighter
            dialog.show(fragmentManager, "SyntaxConfigDialog")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogSyntaxConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLanguageSpinner()
        setupClickListeners()
    }

    private fun setupLanguageSpinner() {
        val languages = syntaxHighlighter.getAvailableConfigurations()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        binding.spinnerLanguage.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLanguage = languages[position]
                loadConfiguration(selectedLanguage)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun loadConfiguration(extension: String) {
        currentConfig = syntaxHighlighter.getConfiguration(extension)
        currentConfig?.let { config ->
            binding.etLanguageName.setText(config.language)
            binding.etKeywords.setText(config.keywords.joinToString(", "))
            binding.etCommentStart.setText(config.commentStart)
            binding.etCommentEnd.setText(config.commentEnd)
            binding.etStringDelimiters.setText(config.stringDelimiters.joinToString(", "))
            binding.etKeywordColor.setText(config.colors.keyword)
            binding.etCommentColor.setText(config.colors.comment)
            binding.etStringColor.setText(config.colors.string)
            binding.etNumberColor.setText(config.colors.number)
        }
    }

    private fun setupClickListeners() {
        binding.btnSave.setOnClickListener {
            saveConfiguration()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnAddNew.setOnClickListener {
            clearFields()
        }
    }

    private fun clearFields() {
        binding.etLanguageName.text?.clear()
        binding.etKeywords.text?.clear()
        binding.etCommentStart.text?.clear()
        binding.etCommentEnd.text?.clear()
        binding.etStringDelimiters.text?.clear()
        binding.etKeywordColor.setText("#FF9800")
        binding.etCommentColor.setText("#4CAF50")
        binding.etStringColor.setText("#2196F3")
        binding.etNumberColor.setText("#E91E63")
        currentConfig = null
    }

    private fun saveConfiguration() {
        try {
            val languageName = binding.etLanguageName.text.toString().trim()
            val keywords = binding.etKeywords.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val commentStart = binding.etCommentStart.text.toString().trim()
            val commentEnd = binding.etCommentEnd.text.toString().trim()
            val stringDelimiters = binding.etStringDelimiters.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }

            if (languageName.isEmpty()) {
                Toast.makeText(context, "Language name cannot be empty", Toast.LENGTH_SHORT).show()
                return
            }

            val colors = SyntaxColors(
                keyword = binding.etKeywordColor.text.toString().trim(),
                comment = binding.etCommentColor.text.toString().trim(),
                string = binding.etStringColor.text.toString().trim(),
                number = binding.etNumberColor.text.toString().trim()
            )

            val config = SyntaxConfig(
                language = languageName,
                keywords = keywords,
                commentStart = commentStart,
                commentEnd = commentEnd,
                stringDelimiters = stringDelimiters,
                colors = colors
            )

            // Use language name as file extension for saving
            val extension = languageName.lowercase().replace(" ", "")
            syntaxHighlighter.saveConfigurationToFile(extension, config)

            Toast.makeText(context, "Configuration saved successfully", Toast.LENGTH_SHORT).show()
            dismiss()

        } catch (e: Exception) {
            Toast.makeText(context, "Error saving configuration: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}