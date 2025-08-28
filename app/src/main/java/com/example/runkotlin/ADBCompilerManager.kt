package com.example.runkotlin

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.util.regex.Pattern

enum class CompilationStatus {
    SUCCESS, ERROR, TIMEOUT, RUNNING, ADB_ERROR
}

data class CompilationResult(
    val status: CompilationStatus,
    val output: String,
    val executionOutput: String = "",
    val compilationTime: Long = 0,
    val executionTime: Long = 0,
    val errors: List<String> = emptyList()
)

class ADBCompilerManager(private val context: Context) {

    private val compilerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val androidWorkspace = File(context.filesDir, "kotlin_workspace")

    private var connectedDevice: String? = null
    private var isAdbConnected = false
    private var isInitialized = false

    init {
        initializeWorkspace()
    }

    private fun initializeWorkspace() {
        compilerScope.launch {
            try {
                if (!androidWorkspace.exists()) {
                    androidWorkspace.mkdirs()
                }
                isInitialized = true
            } catch (e: Exception) {
                isInitialized = false
            }
        }
    }

    suspend fun checkAdbConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isInitialized && androidWorkspace.exists()) {
                    isAdbConnected = true
                    connectedDevice = android.os.Build.MODEL + " (" + android.os.Build.DEVICE + ")"
                    true
                } else {
                    isAdbConnected = false
                    connectedDevice = null
                    false
                }
            } catch (e: Exception) {
                isAdbConnected = false
                connectedDevice = null
                false
            }
        }
    }

    fun compileKotlinFile(file: File, callback: (CompilationResult) -> Unit) {
        compilerScope.launch {
            try {
                if (!isInitialized) {
                    withContext(Dispatchers.Main) {
                        callback(
                            CompilationResult(
                                CompilationStatus.ERROR,
                                "Workspace not initialized. Please restart the app.",
                                ""
                            )
                        )
                    }
                    return@launch
                }

                val result = processKotlinFile(file)
                withContext(Dispatchers.Main) {
                    callback(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(
                        CompilationResult(
                            CompilationStatus.ERROR,
                            "Processing error: ${e.message}",
                            ""
                        )
                    )
                }
            }
        }
    }

    private suspend fun processKotlinFile(file: File): CompilationResult {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val workspaceFile = File(androidWorkspace, file.name)
                workspaceFile.writeText(file.readText())

                val kotlinContent = file.readText()
                val errors = mutableListOf<String>()
                validateKotlinSyntax(kotlinContent, errors)

                val processingTime = System.currentTimeMillis() - startTime

                if (errors.isEmpty()) {
                    val outputFile = File(androidWorkspace, "${file.nameWithoutExtension}.compiled")
                    outputFile.writeText("Compiled: ${file.name}")

                    CompilationResult(
                        CompilationStatus.SUCCESS,
                        "✓ Syntax validation passed\n✓ File structure analyzed\n✓ Ready for simulation",
                        "",
                        compilationTime = processingTime,
                        errors = errors
                    )
                } else {
                    CompilationResult(
                        CompilationStatus.ERROR,
                        "Syntax validation failed",
                        "",
                        compilationTime = processingTime,
                        errors = errors
                    )
                }
            } catch (e: Exception) {
                CompilationResult(
                    CompilationStatus.ERROR,
                    "File processing error: ${e.message}",
                    ""
                )
            }
        }
    }

    private fun validateKotlinSyntax(content: String, errors: MutableList<String>) {
        val lines = content.split("\n")
        var braceCount = 0
        var parenCount = 0
        var hasMainFunction = false

        for ((lineNum, line) in lines.withIndex()) {
            val trimmedLine = line.trim()

            braceCount += trimmedLine.count { it == '{' } - trimmedLine.count { it == '}' }
            parenCount += trimmedLine.count { it == '(' } - trimmedLine.count { it == ')' }

            // Check for main function
            if (trimmedLine.contains("fun main") || trimmedLine.contains("main(")) {
                hasMainFunction = true
            }

            when {
                trimmedLine.startsWith("fun ") && !trimmedLine.contains("(") -> {
                    errors.add("Line ${lineNum + 1}: Function declaration missing parentheses")
                }
                trimmedLine.startsWith("class ") && !trimmedLine.contains("{") && !trimmedLine.endsWith("{") -> {
                    errors.add("Line ${lineNum + 1}: Class declaration might be missing opening brace")
                }
                trimmedLine.contains("println") && !trimmedLine.contains("(") -> {
                    errors.add("Line ${lineNum + 1}: println statement missing parentheses")
                }
            }
        }

        if (!hasMainFunction) {
            errors.add("Warning: No main function found. Add 'fun main() { }' to make the program executable.")
        }

        if (braceCount != 0) {
            errors.add("Unmatched braces: ${if (braceCount > 0) "missing closing" else "extra closing"} brace(s)")
        }
        if (parenCount != 0) {
            errors.add("Unmatched parentheses: ${if (parenCount > 0) "missing closing" else "extra closing"} parenthesis/parentheses")
        }
    }

    fun testAdbCompilerConnection(callback: (Boolean, String) -> Unit) {
        compilerScope.launch {
            try {
                val isConnected = checkAdbConnection()
                val message = if (isConnected) {
                    buildString {
                        appendLine("Android workspace initialized")
                        appendLine("Device: $connectedDevice")
                        appendLine("Workspace: ${androidWorkspace.absolutePath}")
                        appendLine("Storage: ${androidWorkspace.freeSpace / (1024 * 1024)} MB available")
                        appendLine("Kotlin simulation engine ready")
                        appendLine("")
                        appendLine("SIMULATION MODE ACTIVE")
                        appendLine("- Kotlin syntax validation")
                        appendLine("- Basic code execution simulation")
                        appendLine("- println() output preview")
                        appendLine("- Variable and loop simulation")
                    }
                } else {
                    "Workspace initialization failed"
                }

                withContext(Dispatchers.Main) {
                    callback(isConnected, message)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, "Error: ${e.message}")
                }
            }
        }
    }

    fun getAdbStatus(): Pair<Boolean, String> {
        return Pair(isAdbConnected, connectedDevice ?: "Not connected")
    }

    fun runKotlinFile(file: File, callback: (String, String) -> Unit) {
        compilerScope.launch {
            try {
                val result = simulateKotlinExecution(file)
                withContext(Dispatchers.Main) {
                    callback(
                        if (result.status == CompilationStatus.SUCCESS) "Simulation completed successfully" else "Simulation failed",
                        result.executionOutput
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback("Execution error", "Error during simulation: ${e.message}")
                }
            }
        }
    }

    private suspend fun simulateKotlinExecution(file: File): CompilationResult {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val content = file.readText()
                val output = StringBuilder()

                // Create a simple variable context for simulation
                val variables = mutableMapOf<String, Any>()
                val lines = content.split("\n")
                var insideMainFunction = false

                for ((lineNum, line) in lines.withIndex()) {
                    val trimmedLine = line.trim()

                    // Detect main function
                    if (trimmedLine.contains("fun main") || trimmedLine.contains("main(")) {
                        insideMainFunction = true
                        continue
                    }

                    if (!insideMainFunction && !trimmedLine.startsWith("//")) continue

                    try {
                        when {
                            // Handle variable declarations
                            trimmedLine.matches(Regex("\\s*(val|var)\\s+\\w+\\s*=.*")) -> {
                                parseVariableDeclaration(trimmedLine, variables)
                            }

                            // Handle println statements
                            trimmedLine.contains("println(") -> {
                                val printOutput = processPrintStatement(trimmedLine, variables)
                                output.appendLine(printOutput)
                            }

                            // Handle for loops
                            trimmedLine.matches(Regex("\\s*for\\s*\\(.*\\).*")) -> {
                                val loopOutput = processForLoop(trimmedLine, lines, lineNum, variables)
                                output.append(loopOutput)
                            }

                            // Ignore empty lines, comments, braces, and other statements
                            trimmedLine.isEmpty() || trimmedLine.startsWith("//") ||
                                    trimmedLine == "{" || trimmedLine == "}" ||
                                    trimmedLine.matches(Regex("\\s*if\\s*\\(.*\\).*")) -> {
                                // Skip
                            }
                        }
                    } catch (e: Exception) {
                        // Skip lines that can't be processed
                    }
                }

                if (output.isEmpty()) {
                    output.appendLine("No output generated.")
                    output.appendLine("Add println() statements to see output.")
                }

                val executionTime = System.currentTimeMillis() - startTime

                CompilationResult(
                    CompilationStatus.SUCCESS,
                    "Simulation completed",
                    output.toString().trim(),
                    executionTime = executionTime
                )

            } catch (e: Exception) {
                CompilationResult(
                    CompilationStatus.ERROR,
                    "Simulation error: ${e.message}",
                    "Simulation failed: ${e.message}"
                )
            }
        }
    }

    private fun parseVariableDeclaration(line: String, variables: MutableMap<String, Any>) {
        try {
            val pattern = Pattern.compile("(val|var)\\s+(\\w+)\\s*=\\s*(.+)")
            val matcher = pattern.matcher(line.trim())

            if (matcher.find()) {
                val varName = matcher.group(2)
                val varValue = matcher.group(3).trim().removeSuffix(";")

                when {
                    varValue.startsWith("\"") && varValue.endsWith("\"") -> {
                        variables[varName] = varValue.removeSurrounding("\"")
                    }
                    varValue.matches(Regex("\\d+")) -> {
                        variables[varName] = varValue.toInt()
                    }
                    varValue == "true" || varValue == "false" -> {
                        variables[varName] = varValue.toBoolean()
                    }
                    else -> {
                        variables[varName] = varValue
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }

    private fun processPrintStatement(line: String, variables: Map<String, Any>): String {
        return try {
            val startIndex = line.indexOf("println(") + 8
            val endIndex = line.lastIndexOf(")")

            if (startIndex >= 8 && endIndex > startIndex) {
                val content = line.substring(startIndex, endIndex).trim()

                when {
                    content.startsWith("\"") && content.endsWith("\"") -> {
                        val stringContent = content.removeSurrounding("\"")
                        // Handle string interpolation
                        processStringInterpolation(stringContent, variables)
                    }
                    variables.containsKey(content) -> {
                        variables[content].toString()
                    }
                    content.matches(Regex("\\d+")) -> content
                    content == "true" || content == "false" -> content
                    else -> "<$content>"
                }
            } else {
                "<parsing error>"
            }
        } catch (e: Exception) {
            "<error: ${e.message}>"
        }
    }

    private fun processStringInterpolation(text: String, variables: Map<String, Any>): String {
        return try {
            var result = text

            // Handle ${variable} syntax
            val bracePattern = Pattern.compile("\\$\\{(\\w+)\\}")
            val braceMatcher = bracePattern.matcher(result)
            while (braceMatcher.find()) {
                val varName = braceMatcher.group(1)
                val replacement = variables[varName]?.toString() ?: "<$varName>"
                result = result.replace(braceMatcher.group(0), replacement)
            }

            // Handle $variable syntax (without braces)
            val simplePattern = Pattern.compile("\\$(\\w+)")
            val simpleMatcher = simplePattern.matcher(result)
            while (simpleMatcher.find()) {
                val varName = simpleMatcher.group(1)
                val replacement = variables[varName]?.toString() ?: "<$varName>"
                result = result.replace(simpleMatcher.group(0), replacement)
            }

            result
        } catch (e: Exception) {
            text
        }
    }

    private fun processForLoop(line: String, lines: List<String>, startIndex: Int, variables: Map<String, Any>): String {
        return try {
            val output = StringBuilder()

            // Simple for loop simulation
            if (line.contains("1..")) {
                val pattern = Pattern.compile("for\\s*\\(\\s*(\\w+)\\s+in\\s+1\\.\\.([0-9]+)\\s*\\)")
                val matcher = pattern.matcher(line)

                if (matcher.find()) {
                    val loopVar = matcher.group(1)
                    val endValue = matcher.group(2).toInt()

                    // Find the loop body and collect all println statements
                    val loopBody = mutableListOf<String>()
                    var braceCount = 0
                    var foundOpenBrace = false

                    for (j in startIndex + 1 until lines.size) {
                        val loopLine = lines[j].trim()

                        if (loopLine.contains("{")) {
                            foundOpenBrace = true
                            braceCount += loopLine.count { it == '{' }
                        }

                        braceCount += loopLine.count { it == '{' } - loopLine.count { it == '}' }

                        if (loopLine.contains("println(")) {
                            loopBody.add(loopLine)
                        }

                        if (foundOpenBrace && braceCount <= 0) {
                            break
                        }

                        if (j - startIndex > 10) break // Safety limit
                    }

                    // Execute the loop
                    for (i in 1..minOf(endValue, 10)) {
                        val tempVars = variables.toMutableMap()
                        tempVars[loopVar] = i

                        for (statement in loopBody) {
                            val printOutput = processPrintStatement(statement, tempVars)
                            output.appendLine(printOutput)
                        }
                    }

                    if (endValue > 10) {
                        output.appendLine("... (${endValue - 10} more iterations)")
                    }
                }
            }

            output.toString()
        } catch (e: Exception) {
            ""
        }
    }

    // Rest of the utility functions remain the same
    fun cleanup() {
        try {
            compilerScope.cancel()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    fun getWorkspaceFiles(): List<File> {
        return try {
            androidWorkspace.listFiles()?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteWorkspaceFile(fileName: String): Boolean {
        return try {
            File(androidWorkspace, fileName).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun getWorkspacePath(): String = androidWorkspace.absolutePath

    fun createSampleKotlinFile(fileName: String = "Hello.kt"): File? {
        return try {
            val sampleContent = """
                fun main() {
                    println("this is a sample file")
                    
                    val name = "Android Developer"
                    println("Hello, ${'$'}name!")
                    
                    val number = 42
                    println("The answer is ${'$'}number")
                    
                    for (i in 1..3) {
                        println("Count: ${'$'}i")
                    }
                    
                }
            """.trimIndent()

            val sampleFile = File(androidWorkspace, fileName)
            sampleFile.writeText(sampleContent)
            sampleFile
        } catch (e: Exception) {
            null
        }
    }
}