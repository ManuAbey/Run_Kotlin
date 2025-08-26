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
    private var currentFilename: String = "Main.kt" // Default filename with .kt extension
    private var isTextChanged = false
    private var autoSaveHandler = Handler(Looper.getMainLooper())
    private var autoSaveRunnable: Runnable? = null
    private var textWatcher: TextWatcher? = null

    // Undo/Redo functionality
    private val undoStack = java.util.Stack<String>()
    private val redoStack = java.util.Stack<String>()
    private var isUndoRedoOperation = false
    private val maxUndoStackSize = 100

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                openFileFromUri(uri)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Handle keyboard shortcuts
        if (event?.isCtrlPressed == true) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_Z -> {
                    if (event.isShiftPressed) {
                        redo() // Ctrl+Shift+Z for redo
                    } else {
                        undo() // Ctrl+Z for undo
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_Y -> {
                    redo() // Ctrl+Y for redo
                    return true
                }
                android.view.KeyEvent.KEYCODE_S -> {
                    saveFile() // Ctrl+S for save
                    return true
                }
                android.view.KeyEvent.KEYCODE_N -> {
                    newFile() // Ctrl+N for new file
                    return true
                }
                android.view.KeyEvent.KEYCODE_O -> {
                    openFile() // Ctrl+O for open file
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
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

        // Apply initial syntax highlighting
        applySyntaxHighlighting()

        // Initialize undo stack with empty content
        pushToUndoStack("")
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
        // Setup text editor with improved TextWatcher
        setupTextWatcher()

        // Setup buttons
        binding.btnCompile.setOnClickListener {
            compileCurrentFile()
        }

        binding.btnFind.setOnClickListener {
            showFindReplaceDialog()
        }

        // Add run button if it exists in your layout
        binding.btnRun?.setOnClickListener {
            runCurrentFile()
        }

        // Test Kotlin environment on startup
        testKotlinEnvironment()
    }

    private fun setupTextWatcher() {
        // Remove existing TextWatcher if any
        textWatcher?.let { binding.editorText.removeTextChangedListener(it) }

        // Create new TextWatcher
        textWatcher = object : TextWatcher {
            private var isInternalChange = false
            private var beforeText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isInternalChange && !isUndoRedoOperation) {
                    beforeText = s?.toString() ?: ""
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInternalChange && !isUndoRedoOperation) {
                    isTextChanged = true
                    scheduleAutoSave()

                    // Update document stats
                    s?.let { text ->
                        viewModel.updateDocumentStats(text.toString())
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (!isInternalChange) {
                    if (!isUndoRedoOperation) {
                        // Push to undo stack if significant change
                        val currentText = s?.toString() ?: ""
                        if (beforeText != currentText && beforeText.isNotEmpty()) {
                            pushToUndoStack(beforeText)
                            redoStack.clear() // Clear redo stack when new edit is made
                        }
                    }

                    isInternalChange = true
                    // Apply syntax highlighting with current filename
                    syntaxHighlighter.applySyntaxHighlighting(binding.editorText, currentFilename)
                    isInternalChange = false
                }
            }
        }

        // Add the TextWatcher
        binding.editorText.addTextChangedListener(textWatcher)
    }

    private fun applySyntaxHighlighting() {
        syntaxHighlighter.applySyntaxHighlighting(binding.editorText, currentFilename)
    }

    private fun updateCurrentFilename(filename: String) {
        // Ensure proper file extension
        currentFilename = ensureProperExtension(filename)
        title = currentFilename

        // Re-setup TextWatcher with new filename for syntax highlighting
        setupTextWatcher()

        // Apply syntax highlighting immediately
        applySyntaxHighlighting()
    }

    private fun ensureProperExtension(filename: String): String {
        val cleanName = filename.substringBeforeLast('.')
        val extension = filename.substringAfterLast('.', "")

        return when {
            extension.isEmpty() -> "$filename.kt" // Default to .kt if no extension
            extension.lowercase() in listOf("kt", "kts", "java", "py", "js", "html", "css", "xml") -> filename
            else -> "$cleanName.kt" // Replace unknown extensions with .kt
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

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // Update menu items based on undo/redo stack states
        menu?.findItem(R.id.action_undo)?.isEnabled = undoStack.isNotEmpty()
        menu?.findItem(R.id.action_redo)?.isEnabled = redoStack.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
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
            R.id.action_run -> {
                runCurrentFile()
                true
            }
            R.id.action_compile_run -> {
                compileAndRunCurrentFile()
                true
            }
            R.id.action_online_compile -> {
                compileOnline()
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
        // Save current state to undo stack before clearing
        val currentText = binding.editorText.text.toString()
        if (currentText.isNotEmpty()) {
            pushToUndoStack(currentText)
        }

        binding.editorText.text.clear()
        currentFile = null
        isTextChanged = false
        updateCurrentFilename("Main.kt") // Default to .kt extension
        binding.statusText.text = "Lines: 1 | Words: 0 | Characters: 0"

        // Clear undo/redo stacks for new file
        undoStack.clear()
        redoStack.clear()
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

            // Save current state before opening new file
            val currentText = binding.editorText.text.toString()
            if (currentText.isNotEmpty()) {
                pushToUndoStack(currentText)
            }

            setEditorText(content, pushUndo = false)

            // Get file name from URI and ensure proper extension
            val fileName = fileManager.getFileNameFromUri(uri) ?: "Unknown.kt"
            updateCurrentFilename(fileName)

            // Create temporary file for processing with proper extension
            currentFile = File(cacheDir, currentFilename)
            currentFile?.writeText(content)

            isTextChanged = false

            // Clear undo/redo stacks for new file
            undoStack.clear()
            redoStack.clear()

            Toast.makeText(this, "File opened successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveFile() {
        currentFile?.let { file ->
            try {
                // Ensure the file has the correct extension
                val properFile = if (file.name != currentFilename) {
                    File(file.parent, currentFilename).also { newFile ->
                        if (file.exists() && file != newFile) {
                            file.delete() // Remove old file with wrong extension
                        }
                        currentFile = newFile
                    }
                } else {
                    file
                }

                properFile.writeText(binding.editorText.text.toString())
                isTextChanged = false
                Toast.makeText(this, "File saved as ${properFile.name}", Toast.LENGTH_SHORT).show()
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
            putExtra(Intent.EXTRA_TITLE, currentFilename) // Use current filename with proper extension
        }
        saveFileLauncher.launch(intent)
    }

    private fun saveFileToUri(uri: Uri) {
        try {
            fileManager.writeFileToUri(uri, binding.editorText.text.toString())
            isTextChanged = false

            val fileName = fileManager.getFileNameFromUri(uri) ?: currentFilename
            updateCurrentFilename(fileName)

            // Create/update current file reference with proper extension
            currentFile = File(cacheDir, currentFilename)
            currentFile?.writeText(binding.editorText.text.toString())

            Toast.makeText(this, "File saved successfully as $currentFilename", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleAutoSave() {
        autoSaveRunnable?.let { autoSaveHandler.removeCallbacks(it) }

        autoSaveRunnable = Runnable {
            currentFile?.let { file ->
                try {
                    // Ensure auto-save uses correct filename
                    val properFile = if (file.name != currentFilename) {
                        File(file.parent, currentFilename).also { currentFile = it }
                    } else {
                        file
                    }
                    properFile.writeText(binding.editorText.text.toString())
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
        if (undoStack.isNotEmpty()) {
            val current = binding.editorText.text.toString()
            redoStack.push(current)
            val last = undoStack.pop()
            setEditorText(last, pushUndo = false)
            Toast.makeText(this, "Undo applied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = binding.editorText.text.toString()
            undoStack.push(current)
            val next = redoStack.pop()
            setEditorText(next, pushUndo = false)
            Toast.makeText(this, "Redo applied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setEditorText(text: String, pushUndo: Boolean = true) {
        if (pushUndo) {
            pushToUndoStack(binding.editorText.text.toString())
        }

        isUndoRedoOperation = true
        binding.editorText.setText(text)
        binding.editorText.setSelection(text.length) // Move cursor to end
        isUndoRedoOperation = false

        // Apply syntax highlighting
        applySyntaxHighlighting()
    }

    private fun pushToUndoStack(text: String) {
        // Don't push empty strings or duplicates
        if (text.isEmpty() || (undoStack.isNotEmpty() && undoStack.peek() == text)) {
            return
        }

        undoStack.push(text)

        // Limit stack size to prevent memory issues
        if (undoStack.size > maxUndoStackSize) {
            // Remove oldest entries
            val newStack = java.util.Stack<String>()
            val itemsToKeep = undoStack.takeLast(maxUndoStackSize / 2)
            itemsToKeep.forEach { newStack.push(it) }
            undoStack.clear()
            undoStack.addAll(newStack)
        }

        // Update menu items
        invalidateOptionsMenu()
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

        // Use setEditorText to properly handle undo/redo
        setEditorText(newText, pushUndo = true)
        binding.editorText.setSelection(cursorPosition + text.length)
    }

    private fun deleteSelectedText() {
        val start = binding.editorText.selectionStart
        val end = binding.editorText.selectionEnd
        if (start != end) {
            val currentText = binding.editorText.text.toString()
            val newText = currentText.substring(0, start) + currentText.substring(end)

            // Use setEditorText to properly handle undo/redo
            setEditorText(newText, pushUndo = true)
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
        // Ensure file has .kt extension before compilation
        if (!currentFilename.endsWith(".kt", ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle("File Extension")
                .setMessage("Kotlin compilation requires a .kt file extension. Change filename to ${currentFilename.substringBeforeLast('.')}.kt?")
                .setPositiveButton("Change") { _, _ ->
                    updateCurrentFilename("${currentFilename.substringBeforeLast('.')}.kt")
                    performCompilation()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        performCompilation()
    }

    private fun performCompilation() {
        currentFile?.let { file ->
            // Ensure current file matches current filename
            val actualFile = if (file.name != currentFilename) {
                File(file.parent, currentFilename).also { newFile ->
                    newFile.writeText(binding.editorText.text.toString())
                    currentFile = newFile
                }
            } else {
                file.apply { writeText(binding.editorText.text.toString()) }
            }

            binding.btnCompile.isEnabled = false
            binding.btnCompile.text = "Compiling..."

            compilerManager.compileKotlinFile(actualFile) { result ->
                runOnUiThread {
                    binding.btnCompile.isEnabled = true
                    binding.btnCompile.text = "Compile"
                    viewModel.setCompilationResult(result)
                }
            }
        } ?: run {
            // Create a temporary file for compilation
            val tempFile = File(cacheDir, currentFilename)
            tempFile.writeText(binding.editorText.text.toString())
            currentFile = tempFile
            performCompilation()
        }
    }

    private fun runCurrentFile() {
        currentFile?.let { file ->
            val actualFile = if (file.name != currentFilename) {
                File(file.parent, currentFilename)
            } else {
                file
            }

            val jarFile = File(File(cacheDir, "compiled"), "${actualFile.nameWithoutExtension}.jar")

            if (!jarFile.exists()) {
                Toast.makeText(this, "Please compile the file first", Toast.LENGTH_SHORT).show()
                return
            }

            binding.btnRun?.let { btn ->
                btn.isEnabled = false
                btn.text = "Running..."
            }

            binding.errorOutput.text = "Running program..."

            compilerManager.runKotlinFile(actualFile) { statusMessage, output ->
                runOnUiThread {
                    binding.btnRun?.let { btn ->
                        btn.isEnabled = true
                        btn.text = "Run"
                    }
                    binding.errorOutput.text = buildString {
                        appendLine("Status: $statusMessage")
                        if (output.isNotEmpty()) {
                            appendLine("Program Output:\n$output")
                        }
                    }
                }
            }

        } ?: run {
            Toast.makeText(this, "No file to run", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testKotlinEnvironment() {
        compilerManager.testKotlinEnvironment { isWorking, message ->
            runOnUiThread {
                if (!isWorking) {
                    AlertDialog.Builder(this)
                        .setTitle("Kotlin Environment")
                        .setMessage("Kotlin compiler might not be installed or configured properly.\n\nError: $message\n\nYou can still write code, but compilation might not work.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun compileAndRunCurrentFile() {
        // Ensure file has .kt extension before compilation
        if (!currentFilename.endsWith(".kt", ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle("File Extension")
                .setMessage("Kotlin compilation requires a .kt file extension. Change filename to ${currentFilename.substringBeforeLast('.')}.kt?")
                .setPositiveButton("Change") { _, _ ->
                    updateCurrentFilename("${currentFilename.substringBeforeLast('.')}.kt")
                    performCompileAndRun()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        performCompileAndRun()
    }

    private fun performCompileAndRun() {
        currentFile?.let { file ->
            val actualFile = if (file.name != currentFilename) {
                File(file.parent, currentFilename).also { newFile ->
                    newFile.writeText(binding.editorText.text.toString())
                    currentFile = newFile
                }
            } else {
                file.apply { writeText(binding.editorText.text.toString()) }
            }

            binding.btnCompile.isEnabled = false
            binding.btnCompile.text = "Compiling & Running..."
            binding.errorOutput.text = "Compiling..."

            // Use the enhanced compileAndRunKotlinFile method
            compilerManager.compileAndRunKotlinFile(actualFile) { result ->
                runOnUiThread {
                    binding.btnCompile.isEnabled = true
                    binding.btnCompile.text = "Compile"

                    when (result.status) {
                        CompilationStatus.SUCCESS -> {
                            binding.compilationStatus.text = "✓ Compilation and execution successful"
                            binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

                            // Show both compilation and execution output
                            binding.errorOutput.text = buildString {
                                if (result.output.isNotEmpty()) {
                                    appendLine("Compilation: ${result.output}")
                                }
                                if (result.executionOutput.isNotEmpty()) {
                                    appendLine("Program Output:")
                                    appendLine(result.executionOutput)
                                } else {
                                    appendLine("Program executed successfully (no output)")
                                }
                                if (result.compilationTime > 0) {
                                    appendLine("Time: ${result.compilationTime}ms")
                                }
                            }
                        }
                        CompilationStatus.ERROR -> {
                            binding.compilationStatus.text = "✗ Compilation/execution failed"
                            binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

                            binding.errorOutput.text = buildString {
                                if (result.output.isNotEmpty()) {
                                    appendLine("Error: ${result.output}")
                                }
                                if (result.executionOutput.isNotEmpty()) {
                                    appendLine("Execution Output:")
                                    appendLine(result.executionOutput)
                                }
                            }
                        }
                        CompilationStatus.TIMEOUT -> {
                            binding.compilationStatus.text = "⚠ Compilation/execution timeout"
                            binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                            binding.errorOutput.text = "Operation took too long and was cancelled"
                        }
                        CompilationStatus.RUNNING -> {
                            binding.compilationStatus.text = "⏳ Processing..."
                            binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                            binding.errorOutput.text = "Processing your code..."
                        }
                    }
                }
            }
        } ?: run {
            // Create a temporary file for compilation
            val tempFile = File(cacheDir, currentFilename)
            tempFile.writeText(binding.editorText.text.toString())
            currentFile = tempFile
            performCompileAndRun()
        }
    }

    private fun compileOnline() {
        val code = binding.editorText.text.toString()
        if (code.isEmpty()) {
            Toast.makeText(this, "Please write some code first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.errorOutput.text = "Compiling and running online..."
        binding.compilationStatus.text = "⏳ Online compilation running..."
        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))

        compilerManager.executeCodeDirectly(code) { result ->
            runOnUiThread {
                when (result.status) {
                    CompilationStatus.SUCCESS -> {
                        binding.compilationStatus.text = "✓ Online execution successful"
                        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

                        binding.errorOutput.text = buildString {
                            appendLine("Online Execution Result:")
                            if (result.executionOutput.isNotEmpty()) {
                                appendLine("Output:")
                                appendLine(result.executionOutput)
                            } else {
                                appendLine("Program executed successfully (no output)")
                            }
                            if (result.executionTime > 0) {
                                appendLine("Execution Time: ${result.executionTime}ms")
                            }
                        }
                    }
                    CompilationStatus.ERROR -> {
                        binding.compilationStatus.text = "✗ Online execution failed"
                        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

                        binding.errorOutput.text = buildString {
                            appendLine("Online Execution Error:")
                            if (result.output.isNotEmpty()) {
                                appendLine(result.output)
                            }
                            if (result.executionOutput.isNotEmpty()) {
                                appendLine("Additional Details:")
                                appendLine(result.executionOutput)
                            }
                        }
                    }
                    CompilationStatus.TIMEOUT -> {
                        binding.compilationStatus.text = "⚠ Online execution timeout"
                        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                        binding.errorOutput.text = "Online execution took too long and was cancelled"
                    }
                    else -> {
                        binding.compilationStatus.text = "⚠ Online execution issue"
                        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                        binding.errorOutput.text = "Online execution encountered an issue: ${result.output}"
                    }
                }
            }
        }
    }


    private fun updateCompilationStatus(result: CompilationResult) {
        when (result.status) {
            CompilationStatus.SUCCESS -> {
                binding.compilationStatus.text = "✓ Compilation successful"
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

                // Show both compilation and execution output properly
                binding.errorOutput.text = buildString {
                    if (result.output.isNotEmpty()) {
                        appendLine("Compilation: ${result.output}")
                    }
                    if (result.executionOutput.isNotEmpty()) {
                        appendLine("Program Output:")
                        appendLine(result.executionOutput)
                    } else {
                        appendLine("Compiled successfully. Click 'Run' to execute the program.")
                    }
                    if (result.compilationTime > 0) {
                        appendLine("Compilation Time: ${result.compilationTime}ms")
                    }
                }
            }
            CompilationStatus.ERROR -> {
                binding.compilationStatus.text = "✗ Compilation failed"
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

                binding.errorOutput.text = buildString {
                    appendLine("Compilation Errors:")
                    appendLine(result.output)
                    if (result.executionOutput.isNotEmpty()) {
                        appendLine("Additional Details:")
                        appendLine(result.executionOutput)
                    }
                }
            }
            CompilationStatus.TIMEOUT -> {
                binding.compilationStatus.text = "⚠ Compilation timeout"
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.errorOutput.text = "Compilation took too long and was cancelled"
            }
            CompilationStatus.RUNNING -> {
                binding.compilationStatus.text = " Compilation running..."
                binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                binding.errorOutput.text = "Compiling your code..."
            }
        }
    }

    private fun runCompiledProgram() {
        currentFile?.let { file ->
            val kotlinFile = if (file.name != currentFilename) {
                File(file.parent, currentFilename)
            } else {
                file
            }

            binding.errorOutput.text = "Running compiled program..."

            // Run the compiled Kotlin program
            compilerManager.runKotlinFile(kotlinFile) { statusMessage, output ->
                runOnUiThread {
                    // Display status and output clearly
                    binding.errorOutput.text = buildString {
                        appendLine("Execution Status: $statusMessage")
                        if (output.isNotEmpty()) {
                            appendLine("Program Output:")
                            appendLine(output)
                        } else {
                            appendLine("Program executed successfully (no output)")
                        }
                    }

                    // Update compilation status to show execution complete
                    if (output.contains("error", ignoreCase = true)) {
                        binding.compilationStatus.text = "✗ Execution failed"
                        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    } else {
                        binding.compilationStatus.text = "✓ Execution completed"
                        binding.compilationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    }
                }
            }
        } ?: run {
            binding.errorOutput.text = "No compiled file found. Please compile first."
        }
    }

}