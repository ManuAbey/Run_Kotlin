package com.example.runkotlin

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.texteditor.kotlinide.databinding.DialogFindReplaceBinding

class FindReplaceDialog : DialogFragment() {

    private var _binding: DialogFindReplaceBinding? = null
    private val binding get() = _binding!!

    private lateinit var findReplaceHelper: FindReplaceHelper

    companion object {
        fun show(fragmentManager: FragmentManager, helper: FindReplaceHelper) {
            val dialog = FindReplaceDialog()
            dialog.findReplaceHelper = helper
            dialog.show(fragmentManager, "FindReplaceDialog")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogFindReplaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnFindNext.setOnClickListener {
            val searchTerm = binding.etSearchTerm.text.toString()
            val caseSensitive = binding.cbCaseSensitive.isChecked
            val wholeWord = binding.cbWholeWord.isChecked

            if (searchTerm.isNotEmpty()) {
                val found = findReplaceHelper.findNext(searchTerm, caseSensitive, wholeWord)
                if (!found) {
                    Toast.makeText(context, "No more matches found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnFindPrevious.setOnClickListener {
            val searchTerm = binding.etSearchTerm.text.toString()
            val caseSensitive = binding.cbCaseSensitive.isChecked
            val wholeWord = binding.cbWholeWord.isChecked

            if (searchTerm.isNotEmpty()) {
                val found = findReplaceHelper.findPrevious(searchTerm, caseSensitive, wholeWord)
                if (!found) {
                    Toast.makeText(context, "No more matches found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnReplace.setOnClickListener {
            val searchTerm = binding.etSearchTerm.text.toString()
            val replaceTerm = binding.etReplaceTerm.text.toString()
            val caseSensitive = binding.cbCaseSensitive.isChecked
            val wholeWord = binding.cbWholeWord.isChecked

            if (searchTerm.isNotEmpty()) {
                val replaced = findReplaceHelper.replaceNext(searchTerm, replaceTerm, caseSensitive, wholeWord)
                if (!replaced) {
                    Toast.makeText(context, "No matches found to replace", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnReplaceAll.setOnClickListener {
            val searchTerm = binding.etSearchTerm.text.toString()
            val replaceTerm = binding.etReplaceTerm.text.toString()
            val caseSensitive = binding.cbCaseSensitive.isChecked
            val wholeWord = binding.cbWholeWord.isChecked

            if (searchTerm.isNotEmpty()) {
                val count = findReplaceHelper.replaceAll(searchTerm, replaceTerm, caseSensitive, wholeWord)
                Toast.makeText(context, "Replaced $count occurrences", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}