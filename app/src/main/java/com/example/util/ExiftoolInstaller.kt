package com.example.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object ExiftoolInstaller {

    /**
     * Mengekstrak biner ExifTool dari assets ke internal storage privat aplikasi.
     * Mengembalikan path absolut String dari biner yang siap dieksekusi.
     */
    fun installIfNeeded(context: Context): String? {
        // Menyimpan di internal storage privat aplikasi agar aman
        val exiftoolFile = File(context.filesDir, "exiftool")

        if (!exiftoolFile.exists()) {
            try {
                // 1. Alirkan data biner dari folder assets
                context.assets.open("exiftool").use { input ->
                    FileOutputStream(exiftoolFile).use { output ->
                        input.copyTo(output)
                    }
                }
                // 2. KRUSIAL: Berikan hak akses executable (+x) agar sistem Android mengizinkan biner berjalan
                exiftoolFile.setExecutable(true, false)
            } catch (e: Exception) {
                e.printStackTrace()
                return null // Mengembalikan null jika proses copy gagal
            }
        }
        
        // FIX: Menambahkan keyword return yang hilang di baris 20
        return exiftoolFile.absolutePath
    }
}
