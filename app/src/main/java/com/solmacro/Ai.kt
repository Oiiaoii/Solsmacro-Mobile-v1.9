package com.solmacro

import android.content.Context

// The "AI" of Brry: a small on-device learner (no internet, no cloud, no delay). It has three jobs.
//
// 1. Reel tuning. Three numbers decide how the taps are timed: where inside the green zone it aims (bias),
//    how hard the tap rate reacts to being off target (kp) and a small timing trim for the delay between a tap
//    and the game seeing it (trim). Every 3 reels it tries ONE slightly different value, keeps it only if the
//    marker stayed in the zone clearly longer, and shrinks its steps when a try does not help.
//    If the tuned values ever do much worse than the defaults, it goes back to the defaults by itself.
// 2. Physics memory. How far one tap lifts the bar and how fast it drains are measured live (see MacroService)
//    and remembered here, so the next session starts calibrated instead of from a guess.
// 3. Waiting-time ratchet. After a reel the macro waits for the result panel and for the close (X) tap.
//    Those waits can be shorter than the defaults. The AI lowers them a step after 3 clean reels and goes back up
//    the moment a wait was too short (the bar came back, or the next cast found no bar), and remembers the limit.
object Ai {
    const val BASE_DELAY = 35.0
    @Volatile var enabled = true
    @Volatile var bias = 0.0
    @Volatile var kp = 8.0
    @Volatile private var trimV = 0.0
    @Volatile var cycle = 1.0
    @Volatile var gainFrac = 0.0248
    @Volatile var drainFrac = 0.0919

    // tap-to-game delay in ms (the tuner adds a trim to it)
    fun delayMs(): Long = (BASE_DELAY + (if (enabled) trimV else 0.0)).toLong().coerceIn(5L, 130L)

    private val defaults = doubleArrayOf(0.0, 8.0, 0.0)
    private val lo = doubleArrayOf(-0.25, 3.0, -20.0)
    private val hi = doubleArrayOf(0.25, 14.0, 40.0)
    private val stepSize = doubleArrayOf(0.08, 2.0, 10.0)
    private val scale = doubleArrayOf(1.0, 1.0, 1.0)
    private var best = defaults.copyOf()
    private var cur = defaults.copyOf()
    private var testing = -1
    private var param = 0
    private var n = 0
    private var sum = 0.0
    private var bestScore = -1.0
    private var baseScore = -1.0
    private var blocks = 0
    private var reels = 0
    private var avg = 0.0
    // waiting-time ratchet
    private var cyClean = 0
    private var cyLong = 0
    private var cyFloor = 0.5
    private var cyFault = false

    private fun apply(v: DoubleArray) {
        bias = v[0]
        kp = v[1]
        trimV = v[2]
    }

    fun load(c: Context) {
        best = doubleArrayOf(Cfg.get(c, "ai_bias") / 100.0, Cfg.get(c, "ai_kp") / 10.0, Cfg.get(c, "ai_trim").toDouble())
        cur = best.copyOf()
        apply(if (Cfg.get(c, "ai") == 1) cur else defaults)
        cycle = if (Cfg.get(c, "ai") == 1) (Cfg.get(c, "ai_cycle") / 100.0).coerceIn(0.5, 1.0) else 1.0
        cyFloor = (Cfg.get(c, "ai_cyfloor") / 100.0).coerceIn(0.5, 1.0)
        gainFrac = Cfg.get(c, "ai_gain") / 10000.0
        drainFrac = Cfg.get(c, "ai_drain") / 10000.0
        if (gainFrac <= 0.0) gainFrac = 0.0248
        if (drainFrac <= 0.0) drainFrac = 0.0919
        testing = -1
        n = 0
        sum = 0.0
        bestScore = -1.0
        baseScore = -1.0
        blocks = 0
        cyClean = 0
        cyFault = false
        for (i in scale.indices) scale[i] = 1.0
        info()
    }

    fun reset(c: Context) {
        Cfg.put(c, "ai_bias", 0)
        Cfg.put(c, "ai_kp", 80)
        Cfg.put(c, "ai_trim", 0)
        Cfg.put(c, "ai_cycle", 100)
        Cfg.put(c, "ai_cyfloor", 50)
        Cfg.put(c, "ai_gain", 248)
        Cfg.put(c, "ai_drain", 919)
        reels = 0
        avg = 0.0
        load(c)
        Dbg.add("AI: learned values cleared")
    }

    private fun info() {
        Dbg.aiBias = Math.round(bias * 100).toString() + "%"
        Dbg.aiInfo = "aim " + Dbg.aiBias + ", kp " + String.format(java.util.Locale.US, "%.1f", kp) + ", trim " + trimV.toInt() +
            " ms, waits " + Math.round(cycle * 100) + "%, reels " + reels + ", avg " + avg.toInt() + "%"
    }

    // remember how strong one tap is and how fast the bar drains (as a share of the screen width)
    fun savePhys(c: Context, gain: Double, drain: Double, screenW: Int) {
        if (!enabled || screenW <= 0) return
        val gf = gain / screenW
        val df = drain / screenW
        if (gf in 0.012..0.04 && Math.abs(gf - gainFrac) > gainFrac * 0.02) {
            gainFrac = gf
            Cfg.put(c, "ai_gain", Math.round(gf * 10000).toInt())
        }
        if (df in 0.04..0.15 && Math.abs(df - drainFrac) > drainFrac * 0.02) {
            drainFrac = df
            Cfg.put(c, "ai_drain", Math.round(df * 10000).toInt())
        }
    }

    // pct = how long the marker stayed in the zone (0-100), caught = the reel ended with a catch
    fun report(c: Context, pct: Int, caught: Boolean) {
        if (Cfg.get(c, "ai") != 1) { apply(defaults); return }
        val score = if (caught) pct.toDouble() else pct * 0.4
        reels++
        avg = if (reels == 1) score else 0.9 * avg + 0.1 * score
        n++
        sum += score
        if (n >= 3) {
            val a = sum / n
            n = 0
            sum = 0.0
            blocks++
            if (testing < 0) {
                bestScore = if (bestScore < 0) a else 0.5 * bestScore + 0.5 * a
                if (baseScore < 0) baseScore = a
                val p = param
                param = (param + 1) % 3
                val dir = if (Math.random() < 0.5) -1.0 else 1.0
                val cand = best.copyOf()
                cand[p] = (cand[p] + dir * stepSize[p] * scale[p]).coerceIn(lo[p], hi[p])
                cur = cand
                testing = p
                apply(cur)
            } else {
                val p = testing
                if (a > bestScore + 2.0) {
                    best = cur.copyOf()
                    bestScore = a
                    Cfg.put(c, "ai_bias", Math.round(best[0] * 100).toInt())
                    Cfg.put(c, "ai_kp", Math.round(best[1] * 10).toInt())
                    Cfg.put(c, "ai_trim", Math.round(best[2]).toInt())
                    Dbg.add("AI: better timing found (in zone " + a.toInt() + "%)")
                } else {
                    scale[p] = maxOf(0.25, scale[p] * 0.85)
                }
                cur = best.copyOf()
                testing = -1
                apply(cur)
                // safety: far worse than the defaults did at the start, so go back to them
                if (blocks >= 8 && baseScore >= 0 && bestScore < baseScore - 8.0) {
                    Dbg.add("AI: tuned timing did worse than the defaults, going back to them")
                    best = defaults.copyOf()
                    cur = best.copyOf()
                    Cfg.put(c, "ai_bias", 0)
                    Cfg.put(c, "ai_kp", 80)
                    Cfg.put(c, "ai_trim", 0)
                    bestScore = -1.0
                    baseScore = -1.0
                    apply(cur)
                }
            }
        }
        info()
    }

    // ---- waiting-time ratchet ----
    // a wait was too short (the bar came back after the reel was called finished, or the cast right after a reel found no bar)
    fun cycleFault() { cyFault = true }

    // call once per finished reel
    fun cycleReelEnd(c: Context) {
        if (Cfg.get(c, "ai") != 1) return
        if (cyFault) {
            cyFault = false
            cyFloor = minOf(1.0, maxOf(cyFloor, cycle + 0.05))
            cycle = minOf(1.0, cycle + 0.15)
            cyClean = 0
            Dbg.add("AI: a wait was too short, back to " + Math.round(cycle * 100) + "% of the normal waits")
        } else {
            cyClean++
            cyLong++
            if (cyClean >= 3 && cycle - 0.1 >= cyFloor - 1e-9) {
                cycle = Math.round((cycle - 0.1) * 100) / 100.0
                cyClean = 0
                Dbg.add("AI: trying shorter waits (" + Math.round(cycle * 100) + "% of normal)")
            }
            if (cyLong >= 40) {
                cyFloor = maxOf(0.5, cyFloor - 0.05)
                cyLong = 0
            }
        }
        Cfg.put(c, "ai_cycle", Math.round(cycle * 100).toInt())
        Cfg.put(c, "ai_cyfloor", Math.round(cyFloor * 100).toInt())
        info()
    }
}
