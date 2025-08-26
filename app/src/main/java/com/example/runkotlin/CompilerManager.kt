package com.example.runkotlin

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class CompilationStatus {
    SUCCESS, ERROR, TIMEOUT, RUNNING
}

data class CompilationResult(
    val status: CompilationStatus,
    val output: String,
    val executionOutput: String = "", // Separate field for program output
    val compilationTime: Long = 0,
    val executionTime: Long = 0,
    val compiledFile: File? = null
)

class CompilerManager(val context: Context) {

    private val compilerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Online compilation settings
    private val judge0BaseUrl = "https://judge0-ce.p.rapidapi.com"
    private val rapidApiKey = "YOUR_RAPIDAPI_KEY" // Replace with actual key

    private val languageMap = mapOf(
        "kotlin" to 78,
        "java" to 62,
        "python" to 71,
        "javascript" to 63,
        "cpp" to 54,
        "c" to 50
    )

    // Primary method: Try local compilation first, fallback to online
    fun compileAndRunKotlinFile(file: File, callback: (CompilationResult) -> Unit) {
        compilerScope.launch {
            try {
                // First try local compilation
                val localResult = attemptLocalCompilation(file)

                if (localResult.status == CompilationStatus.SUCCESS) {
                    // If local compilation succeeds, run it
                    val executionResult = runKotlinFileInternal(file)
                    val combinedResult = localResult.copy(
                        executionOutput = executionResult,
                        status = if (executionResult.contains("error", ignoreCase = true))
                            CompilationStatus.ERROR else CompilationStatus.SUCCESS
                    )

                    withContext(Dispatchers.Main) {
                        callback(combinedResult)
                    }
                } else {
                    // If local compilation fails, try online
                    val code = file.readText()
                    val onlineResult = executeOnline(code, "kotlin")

                    withContext(Dispatchers.Main) {
                        callback(onlineResult)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(CompilationResult(
                        CompilationStatus.ERROR,
                        "Compilation error: ${e.message}",
                        ""
                    ))
                }
            }
        }
    }

    // Local compilation attempt
    private suspend fun attemptLocalCompilation(file: File): CompilationResult {
        return withContext(Dispatchers.IO) {
            try {
                // Create output directory
                val outputDir = File(context.cacheDir, "compiled")
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }

                val className = file.nameWithoutExtension
                val jarFile = File(outputDir, "$className.jar")

                // Check for Kotlin compiler
                val kotlincPath = getKotlincPath()
                if (kotlincPath == null) {
                    return@withContext CompilationResult(
                        CompilationStatus.ERROR,
                        "Local Kotlin compiler not found. Switching to online compilation...",
                        ""
                    )
                }

                val startTime = System.currentTimeMillis()

                val command = listOf(
                    kotlincPath,
                    file.absolutePath,
                    "-include-runtime",
                    "-d", jarFile.absolutePath
                )

                // Execute compilation
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val output = StringBuilder()
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }

                val completed = process.waitFor(30, TimeUnit.SECONDS)
                val compilationTime = System.currentTimeMillis() - startTime

                if (!completed) {
                    process.destroyForcibly()
                    return@withContext CompilationResult(
                        CompilationStatus.TIMEOUT,
                        "Local compilation timeout",
                        "",
                        compilationTime
                    )
                }

                val exitCode = process.exitValue()
                if (exitCode == 0 && jarFile.exists()) {
                    CompilationResult(
                        CompilationStatus.SUCCESS,
                        output.toString().trim().ifEmpty { "Compilation successful" },
                        "",
                        compilationTime
                    )
                } else {
                    CompilationResult(
                        CompilationStatus.ERROR,
                        output.toString().trim().ifEmpty { "Compilation failed with exit code: $exitCode" },
                        "",
                        compilationTime
                    )
                }

            } catch (e: IOException) {
                CompilationResult(CompilationStatus.ERROR, "IO Error: ${e.message}", "")
            } catch (e: Exception) {
                CompilationResult(CompilationStatus.ERROR, "Local compilation error: ${e.message}", "")
            }
        }
    }

    // Enhanced local execution with better output capture
    private suspend fun runKotlinFileInternal(file: File): String {
        return withContext(Dispatchers.IO) {
            try {
                val outputDir = File(context.cacheDir, "compiled")
                val className = file.nameWithoutExtension
                val jarFile = File(outputDir, "$className.jar")

                if (!jarFile.exists()) {
                    return@withContext "Error: Compiled jar file not found. Please compile first."
                }

                val javaPath = getJavaPath()
                if (javaPath == null) {
                    return@withContext "Java runtime not found. Cannot execute compiled program."
                }

                val command = listOf(
                    javaPath,
                    "-jar",
                    jarFile.absolutePath
                )

                val process = ProcessBuilder(command)
                    .redirectErrorStream(false) // Keep stdout and stderr separate
                    .start()

                val output = StringBuilder()
                val errorOutput = StringBuilder()

                // Read stdout
                val stdoutReader = process.inputStream.bufferedReader()
                val stderrReader = process.errorStream.bufferedReader()

                // Read both streams concurrently
                val stdoutJob = launch {
                    var line: String?
                    while (stdoutReader.readLine().also { line = it } != null) {
                        output.appendLine(line)
                    }
                }

                val stderrJob = launch {
                    var line: String?
                    while (stderrReader.readLine().also { line = it } != null) {
                        errorOutput.appendLine(line)
                    }
                }

                val completed = process.waitFor(10, TimeUnit.SECONDS)

                // Wait for both readers to finish
                stdoutJob.join()
                stderrJob.join()

                if (!completed) {
                    process.destroyForcibly()
                    return@withContext "Program execution timeout"
                }

                val exitCode = process.exitValue()
                val stdoutResult = output.toString().trim()
                val stderrResult = errorOutput.toString().trim()

                when {
                    exitCode == 0 && stdoutResult.isNotEmpty() -> stdoutResult
                    exitCode == 0 && stdoutResult.isEmpty() -> "Program executed successfully (no output)"
                    stderrResult.isNotEmpty() -> "Execution error:\n$stderrResult"
                    else -> "Program exited with error code $exitCode"
                }

            } catch (e: IOException) {
                "IO Error during execution: ${e.message}"
            } catch (e: Exception) {
                "Runtime error: ${e.message}"
            }
        }
    }

    // Enhanced online execution
    suspend fun executeOnline(code: String, language: String = "kotlin"): CompilationResult {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()

                // Submit code
                val submissionToken = submitCodeToJudge0(code, language)
                    ?: return@withContext CompilationResult(
                        CompilationStatus.ERROR,
                        "Failed to submit code to online compiler",
                        ""
                    )

                // Poll for results
                var attempts = 0
                val maxAttempts = 30 // Increased for longer programs

                while (attempts < maxAttempts) {
                    delay(1000)

                    val result = getExecutionResult(submissionToken)
                    if (result != null) {
                        val totalTime = System.currentTimeMillis() - startTime
                        return@withContext result.copy(compilationTime = totalTime)
                    }

                    attempts++
                }

                CompilationResult(
                    CompilationStatus.TIMEOUT,
                    "Online execution timeout",
                    ""
                )

            } catch (e: Exception) {
                CompilationResult(
                    CompilationStatus.ERROR,
                    "Online execution error: ${e.message}",
                    ""
                )
            }
        }
    }

    private suspend fun submitCodeToJudge0(code: String, language: String): String? {
        val languageId = languageMap[language] ?: languageMap["kotlin"]!!

        val jsonBody = JSONObject().apply {
            put("source_code", code)
            put("language_id", languageId)
            put("wait", false)
            // Add some basic configuration
            put("cpu_time_limit", 5) // 5 seconds max
            put("memory_limit", 128000) // 128MB max
        }

        val requestBody = jsonBody.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$judge0BaseUrl/submissions")
            .post(requestBody)
            .addHeader("X-RapidAPI-Key", "8db4f2dbf2mshf8a7548225d2ecdp1ca5a9jsn384f6a988d31")
            .addHeader("X-RapidAPI-Host", "judge0-ce.p.rapidapi.com")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                jsonResponse.getString("token")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getExecutionResult(token: String): CompilationResult? {
        val request = Request.Builder()
            .url("$judge0BaseUrl/submissions/$token")
            .get()
            .addHeader("X-RapidAPI-Key", "8db4f2dbf2mshf8a7548225d2ecdp1ca5a9jsn384f6a988d31")
            .addHeader("X-RapidAPI-Host", "judge0-ce.p.rapidapi.com")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")

                val status = jsonResponse.getJSONObject("status")
                val statusId = status.getInt("id")

                // Status 1 = In Queue, 2 = Processing
                if (statusId == 1 || statusId == 2) {
                    return null // Still processing
                }

                val stdout = jsonResponse.optString("stdout", "")
                val stderr = jsonResponse.optString("stderr", "")
                val compileOutput = jsonResponse.optString("compile_output", "")
                val time = jsonResponse.optString("time", "0.0").toDoubleOrNull() ?: 0.0

                val success = statusId == 3 // Status 3 = Accepted

                val compilationMessage = when {
                    success -> "Online compilation and execution successful"
                    compileOutput.isNotEmpty() -> "Compilation failed:\n$compileOutput"
                    stderr.isNotEmpty() -> "Runtime error occurred"
                    else -> "Execution failed with status: ${status.getString("description")}"
                }

                val executionOutput = when {
                    stdout.isNotEmpty() -> stdout
                    success -> "Program executed successfully (no output)"
                    stderr.isNotEmpty() -> "Runtime Error:\n$stderr"
                    else -> ""
                }

                CompilationResult(
                    status = if (success) CompilationStatus.SUCCESS else CompilationStatus.ERROR,
                    output = compilationMessage,
                    executionOutput = executionOutput,
                    executionTime = (time * 1000).toLong()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Quick execute without file (for direct code execution)
    fun executeCodeDirectly(code: String, callback: (CompilationResult) -> Unit) {
        compilerScope.launch {
            try {
                val result = executeOnline(code, "kotlin")
                withContext(Dispatchers.Main) {
                    callback(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(CompilationResult(
                        CompilationStatus.ERROR,
                        "Direct execution error: ${e.message}",
                        ""
                    ))
                }
            }
        }
    }

    // Legacy method compatibility
    fun compileKotlinFile(file: File, callback: (CompilationResult) -> Unit) {
        compileAndRunKotlinFile(file, callback)
    }

    fun runKotlinFile(file: File, callback: (String, String) -> Unit) {
        compilerScope.launch {
            try {
                val execOutput = runKotlinFileInternal(file)
                withContext(Dispatchers.Main) {
                    callback("Local execution finished", execOutput)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback("Runtime error", "Runtime error: ${e.message}")
                }
            }
        }
    }

    private fun getKotlincPath(): String? {
        val possiblePaths = listOf(
            "/usr/local/bin/kotlinc",
            "/usr/bin/kotlinc",
            "kotlinc",
            "${System.getenv("KOTLIN_HOME")}/bin/kotlinc",
            "/opt/kotlin/bin/kotlinc"
        )

        for (path in possiblePaths) {
            if (path.isNotEmpty()) {
                try {
                    val process = ProcessBuilder("which", path).start()
                    if (process.waitFor() == 0) {
                        return path
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return null
    }

    private fun getJavaPath(): String? {
        val possiblePaths = listOf(
            "java",
            "/usr/bin/java",
            "/usr/local/bin/java",
            "${System.getenv("JAVA_HOME")}/bin/java"
        )

        for (path in possiblePaths) {
            if (path.isNotEmpty()) {
                try {
                    val process = ProcessBuilder("which", path).start()
                    if (process.waitFor() == 0) {
                        return path
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return null
    }

    fun cleanup() {
        compilerScope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}

// Extension function for testing environment
fun CompilerManager.testKotlinEnvironment(callback: (Boolean, String) -> Unit) {
    val testCode = """
        fun main() {
            println("Hello, Kotlin!")
            println("Environment test successful!")
        }
    """.trimIndent()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            executeOnline(testCode, "kotlin").let { result ->
                withContext(Dispatchers.Main) {
                    val success = result.status == CompilationStatus.SUCCESS
                    val message = if (success) {
                        "Kotlin environment is working!\nOutput: ${result.executionOutput}"
                    } else {
                        "Environment test failed: ${result.output}"
                    }
                    callback(success, message)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                callback(false, "Environment test error: ${e.message}")
            }
        }
    }
}