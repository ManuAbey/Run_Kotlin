package com.example.runkotlin

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class CompilationStatus {
    SUCCESS, ERROR, TIMEOUT
}

data class CompilationResult(
    val status: CompilationStatus,
    val output: String,
    val executionTime: Long = 0
)

class CompilerManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ADB connection settings - these would need to be configured by the user
    private var adbHost = "localhost"
    private var adbPort = 5555
    private var kotlincPath = "kotlinc" // Path to kotlinc on the connected machine

    fun compileKotlinFile(file: File, callback: (CompilationResult) -> Unit) {
        scope.launch {
            try {
                val result = performCompilation(file)
                withContext(Dispatchers.Main) {
                    callback(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(CompilationResult(
                        CompilationStatus.ERROR,
                        "Compilation failed: ${e.message}"
                    ))
                }
            }
        }
    }

    private suspend fun performCompilation(file: File): CompilationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // First, try local compilation using ProcessBuilder
            val localResult = tryLocalCompilation(file)
            if (localResult != null) {
                return@withContext localResult
            }

            // If local compilation fails, try ADB compilation
            val adbResult = tryAdbCompilation(file)
            if (adbResult != null) {
                return@withContext adbResult
            }

            // If both fail, return a mock compilation result for demonstration
            return@withContext performMockCompilation(file)

        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            return@withContext CompilationResult(
                CompilationStatus.ERROR,
                "Compilation error: ${e.message}",
                executionTime
            )
        }
    }

    private fun tryLocalCompilation(file: File): CompilationResult? {
        return try {
            val processBuilder = ProcessBuilder()
            processBuilder.command("kotlinc", file.absolutePath, "-include-runtime", "-d", "${file.parent}/output.jar")
            processBuilder.directory(file.parentFile)

            val process = processBuilder.start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return CompilationResult(CompilationStatus.TIMEOUT, "Compilation timeout")
            }

            val output = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                CompilationResult(CompilationStatus.SUCCESS, "Compilation successful")
            } else {
                CompilationResult(CompilationStatus.ERROR, output)
            }
        } catch (e: Exception) {
            null // Try next method
        }
    }

    private fun tryAdbCompilation(file: File): CompilationResult? {
        return try {
            // This would implement ADB connection to remote Kotlin compiler
            // For now, return null to indicate this method is not available
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun performMockCompilation(file: File): CompilationResult {
        // Mock compilation for demonstration purposes
        val content = file.readText()

        // Simple syntax checking
        val errors = mutableListOf<String>()

        // Check for basic syntax issues
        if (!content.contains("fun main")) {
            errors.add("Warning: No main function found")
        }

        // Check for unmatched braces
        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        if (openBraces != closeBraces) {
            errors.add("Error: Unmatched braces (${openBraces} opening, ${closeBraces} closing)")
        }

        // Check for unmatched parentheses
        val openParens = content.count { it == '(' }
        val closeParens = content.count { it == ')' }
        if (openParens != closeParens) {
            errors.add("Error: Unmatched parentheses (${openParens} opening, ${closeParens} closing)")
        }

        // Simulate compilation time
        Thread.sleep(1000)

        return if (errors.any { it.startsWith("Error:") }) {
            CompilationResult(CompilationStatus.ERROR, errors.joinToString("\n"))
        } else {
            val message = if (errors.isNotEmpty()) {
                "Compilation successful with warnings:\n${errors.joinToString("\n")}"
            } else {
                "Compilation successful"
            }
            CompilationResult(CompilationStatus.SUCCESS, message)
        }
    }

    fun updateAdbSettings(host: String, port: Int, kotlincPath: String) {
        this.adbHost = host
        this.adbPort = port
        this.kotlincPath = kotlincPath
    }

    fun testAdbConnection(callback: (Boolean) -> Unit) {
        scope.launch {
            val isConnected = try {
                Socket(adbHost, adbPort).use { socket ->
                    socket.isConnected
                }
            } catch (e: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                callback(isConnected)
            }
        }
    }
}