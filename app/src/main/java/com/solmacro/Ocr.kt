package com.solmacro

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

// Rebuilt file: reads the text in a picture with ML Kit (on the device, no internet).
// Call it from a background thread. Returns null when reading failed.
object Ocr {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun read(bmp: Bitmap): String? {
        return try {
            val result = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
            result.text
        } catch (e: Throwable) {
            Dbg.add("Text reading failed: " + e.message)
            null
        }
    }
}
