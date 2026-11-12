package com.solmacro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

// Biome list, name matching (tolerates a letter or two read wrong), reading helpers and session stats.
object Biome {
    class Info(val key: String, val title: String, val color: Int, val rare: Boolean)

    // color = the left bar of the Discord embed (Rainy is the exact color of the reference picture)
    val all = listOf(
        Info("normal", "NORMAL", 0x8BC34A, false),
        Info("windy", "WINDY", 0x9FD8CB, false),
        Info("rainy", "RAINY", 0x829DB8, false),
        Info("snowy", "SNOWY", 0xC4E3F3, false),
        Info("sandstorm", "SAND STORM", 0xC8A25A, false),
        Info("hell", "HELL", 0xB3261E, false),
        Info("starfall", "STARFALL", 0x6E5BD6, false),
        Info("heaven", "HEAVEN", 0xF5D76E, false),
        Info("corruption", "CORRUPTION", 0x7B2D9A, false),
        Info("null", "NULL", 0x4D4D4D, true),
        Info("glitched", "GLITCHED", 0x00E5FF, true),
        Info("dreamspace", "DREAMSPACE", 0xFF8AD8, true),
        Info("cyberspace", "CYBERSPACE", 0x30C6FF, true),
        Info("bloodrain", "BLOOD RAIN", 0x8B0000, false),
        Info("aurora", "AURORA", 0x4DE0B0, false),
        Info("pumpkinmoon", "PUMPKIN MOON", 0xFF7A18, false),
        Info("graveyard", "GRAVEYARD", 0x6F8F7A, false),
        Info("limbo", "LIMBO", 0x9A9A9A, false),
        Info("eggland", "EGGLAND", 0xFFB6C1, false)
    )

    @Volatile var cur: Info? = null
    @Volatile var sinceMs = 0L
    private val counts = HashMap<String, Int>()
    private val timeMs = HashMap<String, Long>()

    fun byKey(k: String): Info? = all.firstOrNull { it.key == k }

    fun nice(b: Info): String = b.title.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // 0 = ignore, 1 = webhook message, 2 = webhook message and ping
    fun mode(c: Context, key: String): Int = Cfg.get(c, "bio_$key")
    fun setMode(c: Context, key: String, v: Int) = Cfg.put(c, "bio_$key", v)

    @Synchronized fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
    @Synchronized fun addTime(key: String, ms: Long) { timeMs[key] = (timeMs[key] ?: 0L) + ms }
    @Synchronized fun resetStats() { counts.clear(); timeMs.clear() }

    @Synchronized fun summary(): String {
        val parts = ArrayList<String>()
        for (b in all) {
            val n = counts[b.key] ?: 0
            if (n == 0 || b.key == "normal") continue
            val m = (timeMs[b.key] ?: 0L) / 60000L
            parts.add(b.title + " x" + n + (if (m > 0) " (" + m + " min)" else ""))
        }
        return if (parts.isEmpty()) "No biomes seen yet" else parts.joinToString(", ")
    }

    // The biome whose name is closest to the text (a small number of misread letters is fine, short names must be exact).
    fun match(text: String): Info? {
        val flat = text.lowercase().filter { it in 'a'..'z' }
        if (flat.length < 4) return null
        var best: Info? = null
        var bestD = 99
        for (b in all) {
            val w = b.key
            val allowed = if (w.length <= 4) 0 else if (w.length <= 7) 1 else 2
            val d = dist(flat, w)
            if (d > allowed) continue
            if (d < bestD || (d == bestD && best != null && w.length > best.key.length)) {
                best = b
                bestD = d
            }
        }
        return best
    }

    // smallest edit distance between the word and any part of the text
    private fun dist(text: String, w: String): Int {
        val n = text.length
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)
        for (i in 1..w.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (w[i - 1] == text[j - 1]) 0 else 1
                curr[j] = minOf(prev[j - 1] + cost, prev[j] + 1, curr[j - 1] + 1)
            }
            val t = prev
            prev = curr
            curr = t
        }
        var best = Int.MAX_VALUE
        for (j in 0..n) if (prev[j] < best) best = prev[j]
        return best
    }

    // Several versions of the same picture (bigger, black and white with strong contrast, inverted).
    // The caller recycles everything except the original it passed in.
    fun variants(src: Bitmap): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        val sc = if (src.height < 110) minOf(110f / src.height, 4f) else 1f
        val big = if (sc > 1.01f) Bitmap.createScaledBitmap(src, minOf((src.width * sc).toInt(), 1800), (src.height * sc).toInt(), true) else src
        out.add(big)
        out.add(filtered(big, false))
        out.add(filtered(big, true))
        return out
    }

    private fun filtered(b: Bitmap, invert: Boolean): Bitmap {
        val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val k = 2.2f
        val t = (-0.5f * k + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        if (invert) cm.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(o).drawBitmap(b, 0f, 0f, p)
        return o
    }
}
