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
    private lateinit var adbCompilerManager: ADBCompilerManager // Changed to ADB compiler

    private var currentFile: File? = null
    private var currentFilename: String = "Main.kt"
    private var isTextChanged = false
    private var autoSaveHandler = Handler(Looper.getMainLooper())
    private var autoSaveRunnable: Runnable? = null
    private var textWatcher: TextWatcher? = null

    // ADB connection status
    private var isAdbConnected = false
    private var connectedDevice = "No device"

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
                        redo()
                    } else {
                        undo()
                    }
                    return true
                }

                android.view.KeyEvent.KEYCODE_Y -> {
                    redo()
                    return true
                }

                android.view.KeyEvent.KEYCODE_S -> {
                    saveFile()
                    return true
                }

                android.view.KeyEvent.KEYCODE_N -> {
                    newFile()
                    return true
                }

                android.view.KeyEvent.KEYCODE_O -> {
                    openFile()
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

        // Test ADB connection on startup
        testAdbConnection()
    }

    private fun initializeComponents() {
        viewModel = ViewModelProvider(this)[EditorViewModel::class.java]
        syntaxHighlighter = SyntaxHighlighter(this)
        fileManager = FileManager(this)
        findReplaceHelper = FindReplaceHelper(binding.editorText)
        adbCompilerManager = ADBCompilerManager(this) // Initialize ADB compiler

        // Observe ViewModel
        viewModel.documentStats.observe(this) { stats ->
            binding.statusText.text =
                "Lines: ${stats.lines} | Words: ${stats.words} | Characters: ${stats.characters} | ADB: $connectedDevice"
        }

        viewModel.compilationResult.observe(this) { result ->
            updateCompilationStatus(result)
        }
    }

    private fun setupUI() {
        setupTextWatcher()

        // Setup buttons - Updated to match XML IDs
        binding.btnCompile.setOnClickListener {
            compileAndRunViaAdb() // Compile and run in one action
        }

        binding.btnFind.setOnClickListener {
            showFindReplaceDialog()
        }

        // ADB connection check button - matches XML ID
        binding.btnCheckAdb.setOnClickListener {
            checkAdbConnectionStatus()
        }
    }

    private fun testAdbConnection() {
        adbCompilerManager.testAdbCompilerConnection { isWorking, message ->
            runOnUiThread {
                isAdbConnected = isWorking
                val (adbStatus, device) = adbCompilerManager.getAdbStatus()
                connectedDevice = if (adbStatus) device else "Disconnected"

                // Update status display
                updateStatusDisplay()
                updateAdbConnectionStatus()

                if (!isWorking) {
                    AlertDialog.Builder(this)
                        .setTitle("ADB Connection Status")
                        .setMessage("ADB Compiler Setup:\n\n$message\n\nFor ADB compilation to work:\n1. Enable USB debugging on device\n2. Connect device to desktop\n3. Install Kotlin compiler on desktop\n4. Ensure ADB is in PATH")
                        .setPositiveButton("Retry") { _, _ ->
                            testAdbConnection()
                        }
                        .setNegativeButton("Continue", null)
                        .show()
                } else {
                    Toast.makeText(this, "ADB Kotlin environment ready!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateAdbConnectionStatus() {
        binding.adbConnectionStatus.text = if (isAdbConnected) {
            "Connected - $connectedDevice"
        } else {
            "Disconnected"
        }

        binding.adbConnectionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isAdbConnected) android.R.color.holo_green_dark
                else android.R.color.holo_red_dark
            )
        )
    }

    private fun checkAdbConnectionStatus() {
        binding.compilationStatus.text = "⏳ Checking ADB connection..."
        binding.compilationStatus.setTextColor(
            ContextCompat.getColor(
                this,
                android.R.color.holo_blue_dark
            )
        )

        adbCompilerManager.testAdbCompilerConnection { isWorking, message ->
            runOnUiThread {
                isAdbConnected = isWorking
                val (adbStatus, device) = adbCompilerManager.getAdbStatus()
                connectedDevice = if (adbStatus) device else "Disconnected"

                updateStatusDisplay()
                updateAdbConnectionStatus()

                if (isWorking) {
                    binding.compilationStatus.text = "✓ ADB connection established"
                    binding.compilationStatus.setTextColor(
                        ContextCompat.getColor(
                            this,
                            android.R.color.holo_green_dark
                        )
                    )
                    binding.errorOutput.text =
                        "ADB Status: Connected\nDevice: $connectedDevice\n$message"
                } else {
                    binding.compilationStatus.text = "✗ ADB connection failed"
                    binding.compilationStatus.setTextColor(
                        ContextCompat.getColor(
                            this,
                            android.R.color.holo_red_dark
                        )
                    )
                    binding.errorOutput.text = "ADB Status: Disconnected\n$message"
                }
            }
        }
    }

    private fun updateStatusDisplay() {
        val stats = viewModel.documentStats.value
        if (stats != null) {
            binding.statusText.text =
                "Lines: ${stats.lines} | Words: ${stats.words} | Characters: ${stats.characters} | ADB: $connectedDevice"
        }
    }

    private fun setupTextWatcher() {
        textWatcher?.let { binding.editorText.removeTextChangedListener(it) }

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

                    s?.let { text ->
                        viewModel.updateDocumentStats(text.toString())
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (!isInternalChange) {
                    if (!isUndoRedoOperation) {
                        val currentText = s?.toString() ?: ""
                        if (beforeText != currentText && beforeText.isNotEmpty()) {
                            pushToUndoStack(beforeText)
                            redoStack.clear()
                        }
                    }

                    isInternalChange = true
                    syntaxHighlighter.applySyntaxHighlighting(binding.editorText, currentFilename)
                    isInternalChange = false
                }
            }
        }

        binding.editorText.addTextChangedListener(textWatcher)
    }

    private fun applySyntaxHighlighting() {
        syntaxHighlighter.applySyntaxHighlighting(binding.editorText, currentFilename)
    }

    private fun updateCurrentFilename(filename: String) {
        currentFilename = ensureProperExtension(filename)
        title = "$currentFilename ${if (isAdbConnected) "[ADB]" else "[Offline]"}"

        setupTextWatcher()
        applySyntaxHighlighting()
    }

    private fun ensureProperExtension(filename: String): String {
        val cleanName = filename.substringBeforeLast('.')
        val extension = filename.substringAfterLast('.', "")

        return when {
            extension.isEmpty() -> "$filename.kt"
            extension.lowercase() in listOf(
                "kt",
                "kts",
                "java",
                "py",
                "js",
                "html",
                "css",
                "xml"
            ) -> filename

            else -> "$cleanName.kt"
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

            R.id.action_create_sample -> {
                createSampleFile()
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
                compileAndRunViaAdb() // Use compile and run together
                true
            }

            R.id.action_compile_run -> {
                compileAndRunViaAdb()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun createSampleFile() {
        adbCompilerManager.createSampleKotlinFile()?.let { sampleFile ->
            try {
                val content = sampleFile.readText()

                // Save current work if there are changes
                if (isTextChanged) {
                    showSaveConfirmationDialog {
                        loadSampleFile(content, sampleFile.name)
                    }
                } else {
                    loadSampleFile(content, sampleFile.name)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error creating sample file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            Toast.makeText(this, "Failed to create sample file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSampleFile(content: String, fileName: String) {
        val currentText = binding.editorText.text.toString()
        if (currentText.isNotEmpty()) {
            pushToUndoStack(currentText)
        }

        setEditorText(content, pushUndo = false)
        updateCurrentFilename(fileName)

        currentFile = File(cacheDir, fileName)
        currentFile?.writeText(content)

        isTextChanged = false
        undoStack.clear()
        redoStack.clear()

        Toast.makeText(this, "Sample file '$fileName' loaded", Toast.LENGTH_SHORT).show()
    }
    private fun compileAndRunViaAdb() {
        if (!isAdbConnected) {
            AlertDialog.Builder(this)
                .setTitle("ADB Connection Required")
                .setMessage("ADB connection is required for compilation and execution.")
                .setPositiveButton("Check Connection") { _, _ ->
                    checkAdbConnectionStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // Ensure file has .kt extension before compilation
        if (!currentFilename.endsWith(".kt", ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle("File Extension")
                .setMessage(
                    "Kotlin compilation requires a .kt file extension. Change filename to ${
                        currentFilename.substringBeforeLast(
                            '.'
                        )
                    }.kt?"
                )
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
            binding.compilationStatus.text = "⏳ Compiling via ADB..."
            binding.compilationStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    android.R.color.holo_blue_dark
                )
            )
            binding.errorOutput.text = "Step 1: Compiling via ADB..."

            adbCompilerManager.compileKotlinFile(actualFile) { compilationResult ->
                runOnUiThread {
                    // Update compilation time display
                    binding.compilationTime.text = if (compilationResult.compilationTime > 0) {
                        "${compilationResult.compilationTime}ms"
                    } else {
                        ""
                    }

                    if (compilationResult.status == CompilationStatus.SUCCESS) {
                        // Compilation successful, now run
                        binding.errorOutput.text = "Step 2: Running compiled program via ADB..."
                        binding.compilationStatus.text = "⏳ Running via ADB..."

                        adbCompilerManager.runKotlinFile(actualFile) { statusMessage, output ->
                            runOnUiThread {
                                binding.btnCompile.isEnabled = true
                                binding.btnCompile.text = "Compile"

                                binding.compilationStatus.text =
                                    "✓ Compilation and execution completed"
                                binding.compilationStatus.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        android.R.color.holo_green_dark
                                    )
                                )

                                binding.errorOutput.text = buildString {
                                    appendLine("ADB Compilation: ${compilationResult.output}")
                                    appendLine("Execution Status: $statusMessage")
                                    if (output.isNotEmpty()) {
                                        appendLine("Program Output:")
                                        appendLine(output)
                                    } else {
                                        appendLine("Program executed successfully (no output)")
                                    }
                                    if (compilationResult.compilationTime > 0) {
                                        appendLine("Total Time: ${compilationResult.compilationTime}ms")
                                    }
                                }
                            }
                        }
                    } else {
                        // Compilation failed
                        binding.btnCompile.isEnabled = true
                        binding.btnCompile.text = "Compile"
                        updateAdbCompilationStatus(compilationResult)
                    }
                }
            }
        } ?: run {
            val tempFile = File(cacheDir, currentFilename)
            tempFile.writeText(binding.editorText.text.toString())
            currentFile = tempFile
            performCompileAndRun()
        }
    }

    private fun updateAdbCompilationStatus(result: CompilationResult) {
        // Update compilation time display
        binding.compilationTime.text = if (result.compilationTime > 0) {
            "${result.compilationTime}ms"
        } else {
            ""
        }

        when (result.status) {
            CompilationStatus.SUCCESS -> {
                binding.compilationStatus.text = "✓ ADB compilation successful"
                binding.compilationStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_green_dark
                    )
                )

                binding.errorOutput.text = buildString {
                    appendLine("ADB Compilation: ${result.output}")
                    if (result.errors.isNotEmpty()) {
                        appendLine("Warnings:")
                        result.errors.forEach { error ->
                            appendLine("  • $error")
                        }
                    }
                    if (result.compilationTime > 0) {
                        appendLine("Compilation Time: ${result.compilationTime}ms")
                    }
                    appendLine("\nProgram ready to execute...")
                }
            }

            CompilationStatus.ERROR -> {
                binding.compilationStatus.text = "✗ ADB compilation failed"
                binding.compilationStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_red_dark
                    )
                )

                binding.errorOutput.text = buildString {
                    appendLine("ADB Compilation Errors:")
                    appendLine(result.output)
                    if (result.errors.isNotEmpty()) {
                        appendLine("\nDetailed Errors:")
                        result.errors.forEach { error ->
                            appendLine("  • $error")
                        }
                    }
                }
            }

            CompilationStatus.ADB_ERROR -> {
                binding.compilationStatus.text = "✗ ADB connection error"
                binding.compilationStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_red_dark
                    )
                )
                binding.errorOutput.text =
                    "ADB Error: ${result.output}\n\nPlease check your ADB connection and try again."

                // Update connection status
                isAdbConnected = false
                connectedDevice = "Disconnected"
                updateStatusDisplay()
                updateAdbConnectionStatus()
            }

            CompilationStatus.TIMEOUT -> {
                binding.compilationStatus.text = "⚠ ADB compilation timeout"
                binding.compilationStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_orange_dark
                    )
                )
                binding.errorOutput.text =
                    "ADB compilation took too long and was cancelled. This might indicate network or desktop performance issues."
            }

            CompilationStatus.RUNNING -> {
                binding.compilationStatus.text = "⏳ ADB compilation running..."
                binding.compilationStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_blue_dark
                    )
                )
                binding.errorOutput.text = "Compiling your code via ADB connection..."
            }
        }
    }

    // Rest of the methods remain the same...
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
        val currentText = binding.editorText.text.toString()
        if (currentText.isNotEmpty()) {
            pushToUndoStack(currentText)
        }

        binding.editorText.text.clear()
        currentFile = null
        isTextChanged = false
        updateCurrentFilename("Main.kt")
        binding.statusText.text = "Lines: 1 | Words: 0 | Characters: 0 | ADB: $connectedDevice"

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

            val currentText = binding.editorText.text.toString()
            if (currentText.isNotEmpty()) {
                pushToUndoStack(currentText)
            }

            setEditorText(content, pushUndo = false)

            val fileName = fileManager.getFileNameFromUri(uri) ?: "Unknown.kt"
            updateCurrentFilename(fileName)

            currentFile = File(cacheDir, currentFilename)
            currentFile?.writeText(content)

            isTextChanged = false
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
                val properFile = if (file.name != currentFilename) {
                    File(file.parent, currentFilename).also { newFile ->
                        if (file.exists() && file != newFile) {
                            file.delete()
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
            putExtra(Intent.EXTRA_TITLE, currentFilename)
        }
        saveFileLauncher.launch(intent)
    }

    private fun saveFileToUri(uri: Uri) {
        try {
            fileManager.writeFileToUri(uri, binding.editorText.text.toString())
            isTextChanged = false

            val fileName = fileManager.getFileNameFromUri(uri) ?: currentFilename
            updateCurrentFilename(fileName)

            currentFile = File(cacheDir, currentFilename)
            currentFile?.writeText(binding.editorText.text.toString())

            Toast.makeText(this, "File saved successfully as $currentFilename", Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleAutoSave() {
        autoSaveRunnable?.let { autoSaveHandler.removeCallbacks(it) }

        autoSaveRunnable = Runnable {
            currentFile?.let { file ->
                try {
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

        autoSaveRunnable?.let { autoSaveHandler.postDelayed(it, 30000) }
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
        binding.editorText.setSelection(text.length)
        isUndoRedoOperation = false

        applySyntaxHighlighting()
    }

    private fun pushToUndoStack(text: String) {
        if (text.isEmpty() || (undoStack.isNotEmpty() && undoStack.peek() == text)) {
            return
        }

        undoStack.push(text)

        if (undoStack.size > maxUndoStackSize) {
            val newStack = java.util.Stack<String>()
            val itemsToKeep = undoStack.takeLast(maxUndoStackSize / 2)
            itemsToKeep.forEach { newStack.push(it) }
            undoStack.clear()
            undoStack.addAll(newStack)
        }

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
        val newText =
            currentText.substring(0, cursorPosition) + text + currentText.substring(cursorPosition)

        setEditorText(newText, pushUndo = true)
        binding.editorText.setSelection(cursorPosition + text.length)
    }

    private fun deleteSelectedText() {
        val start = binding.editorText.selectionStart
        val end = binding.editorText.selectionEnd
        if (start != end) {
            val currentText = binding.editorText.text.toString()
            val newText = currentText.substring(0, start) + currentText.substring(end)

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

    // Legacy compatibility for CompilationResult observer
    private fun updateCompilationStatus(result: CompilationResult) {
        updateAdbCompilationStatus(result)
    }
    fun refreshSyntaxHighlighting() {
        // Re-apply syntax highlighting with current filename
        syntaxHighlighter.applySyntaxHighlighting(binding.editorText, currentFilename)
    }
    override fun onDestroy() {
        super.onDestroy()
        adbCompilerManager.cleanup()
        autoSaveRunnable?.let { autoSaveHandler.removeCallbacks(it) }
    }
}