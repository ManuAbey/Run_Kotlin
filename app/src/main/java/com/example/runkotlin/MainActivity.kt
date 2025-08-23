package com.example.runkotlin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.runkotlin.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: EditorViewModel
    private lateinit var syntaxHighlighter: SyntaxHighlighter
    private lateinit var fileManager: FileManager
    private lateinit var findReplaceHelper: FindReplaceHelper
    private lateinit var compilerManager: CompilerManager

    private var currentFile: File? = null
    private var isTextChanged = false
    private var autoSaveHandler = Handler(Looper.getMainLooper())
    private var autoSaveRunnable: Runnable? = null

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                openFileFromUri(uri)
            }
        }
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                saveFileToUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        // Initialize components
        initializeComponents()
        setupUI()
        requestPermissions()

        // Load default syntax highlighting configurations
        syntaxHighlighter.loadDefaultConfigurations()
    }

    private fun initializeComponents() {
        viewModel = ViewModelProvider(this)[EditorViewModel::class.java]
        syntaxHighlighter = SyntaxHighlighter(this)
        fileManager = FileManager(this)
        findReplaceHelper = FindReplaceHelper(binding.editorText)
        compilerManager = CompilerManager(this)

        // Observe ViewModel
        viewModel.documentStats.observe(this) { stats ->
            binding.statusText.text = "Lines: ${stats.lines} | Words: ${stats.words} | Characters: ${stats.characters}"
        }

        viewModel.compilationResult.observe(this) { result ->
            updateCompilationStatus(result)
        }
    }

    private fun setupUI() {
        // Setup text editor
        binding.editorText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isTextChanged = true
                scheduleAutoSave()

                // Update syntax highlighting
                currentFile?.let { file ->
                    val extension = file.extension
                    syntaxHighlighter.applySyntaxHighlighting(binding.editorText, extension)
                }

                // Update document stats
                s?.let { text ->
                    viewModel.updateDocumentStats(text.toString())
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Setup buttons
        binding.btnCompile.setOnClickListener {
            compileCurrentFile()
        }

        binding.btnFind.setOnClickListener {
            showFindReplaceDialog()
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val permissionsNeeded = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 100)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new -> {
                newFile()
                true
            }
            R.id.action_open -> {
                openFile()
                true
            }
            R.id.action_save -> {
                saveFile()
                true
            }
            R.id.action_save_as -> {
                saveAsFile()
                true
            }
            R.id.action_undo -> {
                undo()
                true
            }
            R.id.action_redo -> {
                redo()
                true
            }
            R.id.action_copy -> {
                copy()
                true
            }
            R.id.action_paste -> {
                paste()
                true
            }
            R.id.action_cut -> {
                cut()
                true
            }
            R.id.action_syntax_config -> {
                showSyntaxConfigDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun newFile() {
        if (isTextChanged) {
            showSaveConfirmationDialog {
                createNewFile()
            }
        } else {
            createNewFile()
        }
    }

    private fun createNewFile() {
        binding.editorText.text.clear()
        currentFile = null
        isTextChanged = false
        title = "New Document"
        binding.statusText.text = "Lines: 1 | Words: 0 | Characters: 0"
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        openFileLauncher.launch(intent)
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            val content = fileManager.readFileFromUri(uri)
            binding.editorText.setText(content)

            // Try to get file name from URI
            val fileName = fileManager.getFileNameFromUri(uri) ?: "Unknown"
            title = fileName

            // Create temporary file for processing
            currentFile = File(cacheDir, fileName)
            currentFile?.writeText(content)

            isTextChanged = false

            // Apply syntax highlighting based on file extension
            val extension = fileName.substringAfterLast('.', "")
            syntaxHighlighter.applySyntaxHighlighting(binding.editorText, extension)

            Toast.makeText(this, "File opened successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveFile() {
        currentFile?.let { file ->
            try {
                file.writeText(binding.editorText.text.toString())
                isTextChanged = false
                Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            saveAsFile()
        }
    }

    private fun saveAsFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "untitled.kt")
        }
        saveFileLauncher.launch(intent)
    }

    private fun saveFileToUri(uri: Uri) {
        try {
            fileManager.writeFileToUri(uri, binding.editorText.text.toString())
            isTextChanged = false

            val fileName = fileManager.getFileNameFromUri(uri) ?: "Unknown"
            title = fileName

            // Create/update current file reference
            currentFile = File(cacheDir, fileName)
            currentFile?.writeText(binding.editorText.text.toString())

            Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleAutoSave() {
        autoSaveRunnable?.let { autoSaveHandler.removeCallbacks(it) }

        autoSaveRunnable = Runnable {
            currentFile?.let { file ->
                try {
                    file.writeText(binding.editorText.text.toString())
                } catch (e: Exception) {
                    // Ignore auto-save errors
                }
            }
        }

        autoSaveRunnable?.let { autoSaveHandler.postDelayed(it, 30000) } // Auto-save every 30 seconds
    }

    private fun showSaveConfirmationDialog(onProceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Unsaved Changes")
            .setMessage("Do you want to save the current document?")
            .setPositiveButton("Save") { _, _ ->
                saveFile()
                onProceed()
            }
            .setNegativeButton("Don't Save") { _, _ ->
                onProceed()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun undo() {
        // Basic undo functionality - you might want to implement a more sophisticated undo/redo system
        Toast.makeText(this, "Undo functionality - implement with TextWatcher history", Toast.LENGTH_SHORT).show()
    }

    private fun redo() {
        // Basic redo functionality
        Toast.makeText(this, "Redo functionality - implement with TextWatcher history", Toast.LENGTH_SHORT).show()
    }

    private fun copy() {
        val selectedText = getSelectedText()
        if (selectedText.isNotEmpty()) {
            copyToClipboard(selectedText)
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun paste() {
        val clipboardText = getFromClipboard()
        if (clipboardText.isNotEmpty()) {
            insertTextAtCursor(clipboardText)
        }
    }

    private fun cut() {
        val selectedText = getSelectedText()
        if (selectedText.isNotEmpty()) {
            copyToClipboard(selectedText)
            deleteSelectedText()
            Toast.makeText(this, "Text cut", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getSelectedText(): String {
        val start = binding.editorText.selectionStart
        val end = binding.editorText.selectionEnd
        return if (start != end) {
            binding.editorText.text.substring(start, end)
        } else ""
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("text", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun getFromClipboard(): String {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    private fun insertTextAtCursor(text: String) {
        val cursorPosition = binding.editorText.selectionStart
        val currentText = binding.editorText.text.toString()
        val newText = currentText.substring(0, cursorPosition) + text + currentText.substring(cursorPosition)
        binding.editorText.setText(newText)
        binding.editorText.setSelection(cursorPosition + text.length)
    }

    private fun deleteSelectedText() {
        val start = binding.editorText.selectionStart
        val end = binding.editorText.selectionEnd
        if (start != end) {
            val currentText = binding.editorText.text.toString()
            val newText = currentText.substring(0, start) + currentText.substring(end)
            binding.editorText.setText(newText)
            binding.editorText.setSelection(start)
        }
    }

    private fun showFindReplaceDialog() {
        FindReplaceDialog.show(supportFragmentManager, findReplaceHelper)
    }

    private fun showSyntaxConfigDialog() {
        SyntaxConfigDialog.show(supportFragmentManager, syntaxHighlighter)
    }

    private fun compileCurrentFile() {
        currentFile?.let { file ->
            if (file.extension == "kt") {
                binding.btnCompile.isEnabled = false
                binding.btnCompile.text = "Compiling..."

                compilerManager.compileKotlinFile(file) { result ->
                    runOnUiThread {
                        binding.btnCompile.isEnabled = true
                        binding.btnCompile.text = "Compile"
                        viewModel.setCompilationResult(result)
                    }
                }
            } else {
                Toast.makeText(this, "Only Kotlin files (.kt) can be compiled", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "Please save the file first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCompilationStatus(result: CompilationResult) {
        when (result.status) {
            CompilationStatus.SUCCESS -> {
                binding.compilationStatus.text = "✓ Compilation successful"
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                binding.errorOutput.text = ""
            }
            CompilationStatus.ERROR -> {
                binding.compilationStatus.text = "✗ Compilation failed"
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.errorOutput.text = result.output
            }
            CompilationStatus.TIMEOUT -> {
                binding.compilationStatus.text = "⚠ Compilation timeout"
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.errorOutput.text = "Compilation took too long"
            }
        }
    }
}