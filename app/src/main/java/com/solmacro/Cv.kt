package com.solmacro

// Rebuilt file. The OpenCV bar detector is switched off here (ready stays false), so MacroService
// uses its built-in scanner (autoScan / roiScan) instead. Replace this file with your original Cv.kt
// if you find it.
object Cv {
    @Volatile var ready = false

    fun init() {
        ready = false
    }

    @Suppress("UNUSED_PARAMETER")
    fun scanFull(px: IntArray, w: Int, h: Int, step: Int, color: Int, color2: Int, tol: Int): DoubleArray? = null
}
