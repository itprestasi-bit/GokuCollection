package com.collectionfield.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageCompressor {
    /**
     * Compresses an image to a maximum size of approximately 300KB.
     * Resizes if necessary to avoid extremely high resolution.
     */
    fun compressImage(context: Context, uri: Uri, targetSizeKb: Int = 300): File? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null
        return compressBitmap(context, originalBitmap, targetSizeKb)
    }

    /** Same pipeline as [compressImage] but for a Bitmap captured directly from the camera. */
    fun compressBitmap(context: Context, originalBitmap: Bitmap, targetSizeKb: Int = 300): File {
        // Step 1: Resize if too large (e.g., max dimension 1280px)
        val maxDimension = 1280
        val width = originalBitmap.width
        val height = originalBitmap.height
        val resizedBitmap = if (width > maxDimension || height > maxDimension) {
            val scale = maxDimension.toFloat() / Math.max(width, height)
            Bitmap.createScaledBitmap(originalBitmap, (width * scale).toInt(), (height * scale).toInt(), true)
        } else {
            originalBitmap
        }

        // Step 2: Iterative compression
        var quality = 100
        val outputStream = ByteArrayOutputStream()
        var compressedData: ByteArray

        do {
            outputStream.reset()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            compressedData = outputStream.toByteArray()
            quality -= 5
        } while (compressedData.size > targetSizeKb * 1024 && quality > 5)

        // Step 3: Save to temporary file
        val tempFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { it.write(compressedData) }

        return tempFile
    }
}
