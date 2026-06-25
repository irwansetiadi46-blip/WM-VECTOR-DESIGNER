package com.example.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ExiftoolInstaller {
    suspend fun installIfNeeded(context: Context): String = withContext(Dispatchers.IO) {
        val exiftoolFile = File(context.filesDir, "exiftool")
        if (!exiftoolFile.exists()) {
            context.assets.open("exiftool").use { input ->
                FileOutputStream(exiftoolFile).use { output ->
                    input.copyTo(output)
                }
            }
            exiftoolFile.setExecutable(true, false)
        }
        exiftoolFile.absolutePath
    }
}
