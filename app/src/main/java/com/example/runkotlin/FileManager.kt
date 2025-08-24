package com.example.runkotlin

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class FileManager(private val context: Context) {

    fun readFileFromUri(uri: Uri): String {
        val stringBuilder = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append('\n')
                }
            }
        }
        return stringBuilder.toString()
    }

    fun writeFileToUri(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream).use { writer ->
                writer.write(content)
            }
        }
    }

    fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null

        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        result = it.getString(displayNameIndex)
                    }
                }
            }
        }

        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }

        return result
    }

    fun saveKotlinFile(filename: String, content: String): Boolean {
        return try {
            // Ensure .kt extension
            val kotlinFilename = ensureKotlinExtension(filename)

            val file = File(getKotlinFilesDirectory(), kotlinFilename)
            file.writeText(content)

            true
        } catch (e: Exception) {
            false
        }
    }

    fun ensureKotlinExtension(filename: String): String {
        return when {
            filename.endsWith(".kt", ignoreCase = true) -> filename
            filename.endsWith(".kts", ignoreCase = true) -> filename
            else -> "$filename.kt"
        }
    }

    fun getKotlinFilesDirectory(): File {
        val kotlinDir = File(context.filesDir, "kotlin_files")
        if (!kotlinDir.exists()) {
            kotlinDir.mkdirs()
        }
        return kotlinDir
    }

    fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "")
    }

    fun isKotlinFile(filename: String): Boolean {
        val extension = getFileExtension(filename).lowercase()
        return extension == "kt" || extension == "kts"
    }


}
