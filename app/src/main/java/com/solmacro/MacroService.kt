package com.solmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ObjectAnimator
import android.view.animation.OvershootInterpolator
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private typealias WL = WindowManager.LayoutParams

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

class MacroService : AccessibilityService() {

    companion object {
        var inst: MacroService? = null
    }

    private class Step(val x: Int, val y: Int, val hold: Long, val wait: Long)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var startBtn: TextView? = null
    private var running = false
    private var catches = 0
    private var tl: List<Step> = emptyList()
    private var idx = 0
    private var lastPkg = ""

    // detection state
    private var detectMode = false
    private var phase = 0
    private var miss = 0
    private var sellIdx = 0
    private var t0 = 0L
    private var lastShot = 0L
    private var lastMark = -1.0
    private var lastHeld = false
    private var votes = 0
    // the scan thread runs at display priority so a busy phone does not make detection late
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor(java.util.concurrent.ThreadFactory { r ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
            r.run()
        }
    })
    // screen reading (OCR) can take seconds, so it gets its own thread and never blocks the reel loop
    private val bgOcr = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var dbgView: DebugView? = null
    private var pauseBtn: TextView? = null
    private var iconView: ImageView? = null
    private var panelUi: PanelUi? = null
    private var biteAvg = 0.0
    private var wZone = 0.0
    private var nextTapAt = 0L
    private var tapCount = 0
    private var framesN = 0
    private var framesIn = 0
    private var histN = 0
    private val tipHist = DoubleArray(3)
    private var lastTap = 0L
    private var screenW = 2340
    private var biteN = 0
    private var paused = false
    private var lastSeen = 0L
    private var holdUntil = 0L
    private var failCasts = 0
    private var fpsT = 0L
    private var fpsN = 0
    private var dbgCount = 0
    private var vel = 0.0
    private var lastM = -1.0
    private var lastMT = 0L
    private var roi: IntArray? = null
    private var roiN = 0
    private var roiMiss = 0
    private var lastGesture = 0L
    private var lastShotSent = 0L
    private val taskLast = LongArray(3)
    private var curTask = 0
    private var bioCur = ""
    private var bioPend = ""
    private var bioLast = 0L
    @Volatile private var bioBusy = false
    private var bioWarned = false
    // frame timing, controller, rejoin, text watch
    private var lastFrameId = -1L
    private var lastFrameAt = 0L
    private var curFrameNs = 0L
    private var lastVelNs = 0L
    private var newFrame = true
    private var lastCtl = 0L
    private var tapAcc = 0.0
    private var drainEst = 0.0
    private var drainN = 0
    private var p7t = 0L
    private var fgAt = 0L
    private var fgVal = ""
    private var rejoinTries = 0
    private var rejoining = false
    private var lastDiscCheck = 0L
    // reel-loop heartbeat: dnext() and every finished scan stamp it, a long silence means the chain broke
    @Volatile private var loopBeat = 0L
    @Volatile private var trackGen = 0
    private var loopRestarts = 0
    private var bioPendAt = 0L
    @Volatile private var bioStartAt = 0L
    private var pulse: ObjectAnimator? = null

    // ---- tap engine: its own thread, so tap timing never waits for the screen reader or the UI ----
    private class Obs(val capT: Long, val tip: Double, val haveTip: Boolean, val zc: Double, val wZone: Double, val at: Long, val zv: Double)
    @Volatile private var obs: Obs? = null
    @Volatile private var engineOn = false
    @Volatile private var engDrain = 0.0
    @Volatile private var engGain = 0.0
    @Volatile private var engLastTap = 0L
    @Volatile private var scanT = 0L
    private val tapTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val tapThread = android.os.HandlerThread("brry-tap", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val eh = Handler(tapThread.looper)
    private var engAcc = 0.0
    private var engLast = 0L
    private var gapBonus = 0L
    private var chkTaps = 0
    private var chkOk = 0
    private var chkCancel = 0
    private var gainEst = 0.0
    private var gainN = 0
    private var prevTip = 0.0
    private var prevCapT = 0L
    private var progressT = 0L
    private var aiStuck = 0
    private val zhT = LongArray(16)
    private val zhZ = DoubleArray(16)
    private var zhN = 0
    private var zvEst = 0.0
    private var qT0 = 0L
    private var qTip0 = 0.0
    private var p7Back = 0
    private var p7Resumes = 0
    private var firstCast = false
    private var curCastFirst = false
    private var lastDotAt = 0L
    private val engine = object : Runnable {
        override fun run() {
            if (!engineOn) return
            try { engineStep() } catch (e: Exception) { Dbg.add("Tap engine error: " + e.message) }
            if (engineOn) eh.postDelayed(this, 4L)
        }
    }
    @Volatile private var watchBusy = false
    private var watchLast = 0L
    private val watchSeen = HashMap<String, Long>()
    private val th = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            try { services() } catch (e: Exception) { Dbg.add("Background tick error: " + e.message) }
            th.postDelayed(this, 2000L)
        }
    }

    // biome watch has its own fast ticker (the 2 s background tick made alerts late)
    private val bioTicker = object : Runnable {
        override fun run() {
            try { biomeTick() } catch (e: Exception) { Dbg.add("Biome tick error: " + e.message) }
            th.postDelayed(this, 400L)
        }
    }

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Cv.init()
        Ai.load(applicationContext)
        showPanel()
        th.removeCallbacks(ticker)
        th.postDelayed(ticker, 2000L)
        th.removeCallbacks(bioTicker)
        th.postDelayed(bioTicker, 1000L)
        Hook.prefetch(applicationContext)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != packageName && p != "com.android.systemui") { lastPkg = p; fgVal = p; fgAt = SystemClock.uptimeMillis() }
        }
    }

    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        engineOn = false
        try { tapThread.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        th.removeCallbacks(ticker)
        th.removeCallbacks(bioTicker)
        closePanel()
        stop()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        inst = null
        return super.onUnbind(intent)
    }

    // Which app is in front. The answer is cached for 700 ms because asking Android is slow
    // and the macro loop calls this many times a second.
    private fun foreground(): String {
        val now = SystemClock.uptimeMillis()
        if (now - fgAt < 700L && fgVal.isNotEmpty()) return fgVal
        fgAt = now
        val rp = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (rp != null && rp != packageName && rp != "com.android.systemui") lastPkg = rp
        fgVal = lastPkg
        return fgVal
    }

    private fun atRoblox(): Boolean {
        val f = foreground()
        return f == Cfg.ROBLOX || f.startsWith("com.roblox.client")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    fun toastPublic(m: String) = toast(m)

    fun togglePause() {
        if (!running) return
        if (paused) {
            paused = false
            pauseBtn?.text = "PAUSE"
            label()
            Dbg.add("Resumed")
            if (phase == 6) phase = 0
            if (detectMode) dnext() else next()
        } else {
            paused = true
            stopEngine()
            h.removeCallbacksAndMessages(null)
            pauseBtn?.text = "RESUME"
            Dbg.state = "Paused"
            label()
            Dbg.add("Paused")
        }
    }

    private fun tapPoint(name: String) {
        val c = applicationContext
        tap(Cfg.get(c, name + "_x"), Cfg.get(c, name + "_y"), 60L)
    }

    private fun moveHold(dir: String, ms: Long) {
        val c = applicationContext
        val r = Cfg.get(c, "joyr")
        val d = when (dir) { "w" -> "up"; "s" -> "down"; "a" -> "left"; "d" -> "right"; else -> dir }
        val dx = when (d) { "left" -> -r; "right" -> r; else -> 0 }
        val dy = when (d) { "up" -> -r; "down" -> r; else -> 0 }
        val cx = Cfg.get(c, "joy_x").toFloat()
        val cy = Cfg.get(c, "joy_y").toFloat()
        val p1 = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 120L, true)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s1).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val p2 = Path().apply { moveTo(cx + dx, cy + dy) }
                val s2 = s1.continueStroke(p2, 0L, maxOf(ms, 1L), false)
                dispatchGesture(GestureDescription.Builder().addStroke(s2).build(), null, null)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Dbg.add("Move cancelled")
            }
        }, null)
        if (!ok) Dbg.add("Android refused the move")
    }

    private fun camSwipe(dir: String, amt: Int) {
        val c = applicationContext
        val cx = Cfg.get(c, "cam_x").toFloat()
        val cy = Cfg.get(c, "cam_y").toFloat()
        val dx = when (dir) { "left" -> -amt; "right" -> amt; else -> 0 }
        val dy = when (dir) { "up" -> -amt; "down" -> amt; else -> 0 }
        val p = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 250L)).build(), null, null)
    }

    // script lines: move up 1.5 | camera left 200 | shiftlock | tap inv | wait 1
    private fun runScript(text: String, done: () -> Unit) {
        val lines = text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        fun step(i: Int) {
            if (!running) return
            if (i >= lines.size) { done(); return }
            val p = lines[i].split(" ").filter { it.isNotEmpty() }
            var wait = 100L
            try {
                when (p[0].lowercase()) {
                    "wait" -> wait = (p[1].toDouble() * 1000).toLong()
                    "move" -> { val ms = (p[2].toDouble() * 1000).toLong(); moveHold(p[1].lowercase(), ms); wait = ms + 300 }
                    "camera" -> { camSwipe(p[1].lowercase(), p.getOrNull(2)?.toInt() ?: 250); wait = 450 }
                    "shiftlock" -> { tapPoint("shift"); wait = 350 }
                    "tap" -> {
                        val rx = p.getOrNull(1)?.toIntOrNull()
                        val ry = p.getOrNull(2)?.toIntOrNull()
                        if (rx != null && ry != null) tap(rx, ry, 60L) else tapPoint(p[1].lowercase())
                        wait = 350
                    }
                    else -> Dbg.add("Unknown script line: " + lines[i])
                }
            } catch (e: Exception) {
                Dbg.add("Bad script line: " + lines[i])
            }
            h.postDelayed({ step(i + 1) }, wait)
        }
        step(0)
    }

    // ---------- auto rejoin ----------
    private fun launchRoblox() {
        val c = applicationContext
        val link = Cfg.gets(c, "pslink").trim()
        try {
            val i = if (link.startsWith("http") || link.startsWith("roblox:"))
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(Cfg.ROBLOX)
            else packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else {
                Dbg.add("Roblox is not installed")
            }
        } catch (e: Exception) {
            Dbg.add("Could not open Roblox: " + e.message)
        }
    }

    // Roblox left the screen (closed, crashed, kicked). With auto rejoin on, open it again and carry on.
    private fun lostRoblox() {
        if (!running || rejoining) return
        if (Cfg.get(applicationContext, "rejoin") != 1) {
            stop()
            toast("Roblox closed, macro stopped")
            return
        }
        beginRejoin("Roblox is not in front")
    }

    private fun beginRejoin(why: String) {
        if (!running || rejoining) return
        val c = applicationContext
        if (rejoinTries >= 5) {
            stop()
            toast("Could not get back into Roblox, stopped")
            Hook.send(c, "Macro stopped", "Roblox did not come back after 5 tries.", 0xFF6B6B, true)
            return
        }
        rejoining = true
        rejoinTries++
        Dbg.state = "Rejoining ($rejoinTries/5)"
        Dbg.add("$why, rejoining ($rejoinTries/5)")
        Hook.send(c, "Rejoining Roblox", "$why. Attempt $rejoinTries of 5.", 0xFFB84D, true)
        stopEngine()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            launchRoblox()
            h.postDelayed({ afterRejoin() }, maxOf(Cfg.get(c, "rejoinsec"), 15) * 1000L)
        }, 2500L)
    }

    private fun afterRejoin() {
        if (!running) return
        val c = applicationContext
        fgAt = 0L
        rejoining = false
        if (atRoblox()) {
            Dbg.add("Back in Roblox, continuing")
            Hook.send(c, "Back in Roblox", "The macro continues.", 0x38D39F)
            rejoining = true
            h.postDelayed({
                rejoining = false
                rejoinTries = 0
                phase = 0
                roi = null
                failCasts = 0
                runScript(Cfg.gets(c, "script_start")) { dnext() }
            }, 6000L)
        } else {
            lostRoblox()
        }
    }

    // ---------- reading words from the screen (merchant, aura, disconnect) ----------
    private fun watchBox(): IntArray {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "wat1_x"), Cfg.get(c, "wat2_x"))
        val x2 = maxOf(Cfg.get(c, "wat1_x"), Cfg.get(c, "wat2_x"))
        val y1 = minOf(Cfg.get(c, "wat1_y"), Cfg.get(c, "wat2_y"))
        val y2 = maxOf(Cfg.get(c, "wat1_y"), Cfg.get(c, "wat2_y"))
        if (x2 - x1 < 40 || y2 - y1 < 12) return intArrayOf(0, 0, 100000, 100000)
        return intArrayOf(x1, y1, x2, y2)
    }

    // forWatch = true: look for your keywords. false: look for a disconnect screen.
    private fun ocrCheck(forWatch: Boolean) {
        val c = applicationContext
        if (watchBusy || !Cap.active) return
        watchBusy = true
        bgOcr.execute {
            try {
                val a = if (forWatch) watchBox() else intArrayOf(0, 0, 100000, 100000)
                val big = (a[2] - a[0]).toLong() * (a[3] - a[1]).toLong() > 700000L
                val reg = Cap.region(a[0], a[1], a[2] - a[0], a[3] - a[1], if (big) 2 else 1)
                if (reg == null) return@execute
                val bmp = Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888)
                val text = Ocr.read(bmp)
                if (text == null) { bmp.recycle(); return@execute }
                val flat = text.lowercase().filter { it.isLetterOrDigit() }
                if (!forWatch) {
                    bmp.recycle()
                    if (flat.contains("disconnected") || flat.contains("lostconnection") || flat.contains("youwerekicked") || flat.contains("reconnect")) {
                        h.post { beginRejoin("Disconnect screen found") }
                    }
                    return@execute
                }
                val words = Cfg.gets(c, "watchwords").ifBlank { "mari, jester" }
                    .split(",", "\n").map { it.trim().lowercase() }.filter { it.filter { ch -> ch.isLetterOrDigit() }.length >= 3 }
                val now = SystemClock.uptimeMillis()
                for (w in words) {
                    val wf = w.filter { it.isLetterOrDigit() }
                    if (!flat.contains(wf)) continue
                    val seen = watchSeen[wf] ?: 0L
                    if (seen != 0L && now - seen < 5 * 60000L) continue
                    watchSeen[wf] = now
                    Dbg.add("Watch: saw \"" + w + "\" on the screen")
                    val ping = Cfg.get(c, "pingwatch") == 1
                    val o = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, o)
                    val head = if (ping) Hook.mention(c) + " " else ""
                    Hook.sendImage(c, head + "Seen on screen: " + w.uppercase() + " (read from the screen, may be wrong)", o.toByteArray())
                }
                bmp.recycle()
            } catch (e: Throwable) {
                Dbg.add("Screen reading error: " + e.message)
            } finally {
                watchBusy = false
            }
        }
    }

    // ---------- timed macro loop ----------
    private fun build(): List<Step> {
        val c = applicationContext
        val l = ArrayList<Step>()
        l.add(Step(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong(), Cfg.get(c, "wait").toLong()))
        repeat(Cfg.get(c, "taps")) {
            l.add(Step(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), 40L, Cfg.get(c, "gap").toLong()))
        }
        val every = Cfg.get(c, "every")
        if (Cfg.get(c, "sell") == 1 && every > 0 && (catches + 1) % every == 0) {
            for (k in listOf("inv", "sellall", "confirm")) {
                l.add(Step(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L, Cfg.get(c, "mdelay").toLong()))
            }
        }
        return l
    }

    fun toggle() { if (running) stop() else start() }

    // Why Start would be refused right now, or null when everything is ready.
    fun startProblem(): String? {
        val c = applicationContext
        if (Cfg.get(c, "pts_ok") != 1) return "Set the button positions first (Settings > Set button positions)"
        if (!Cap.active && Build.VERSION.SDK_INT < 30) return "Without fast capture this needs Android 11 or newer. Turn fast capture on instead"
        if (!atRoblox()) return "Open Roblox first"
        return null
    }

    // three ready checks for the panel: label, ok
    fun checklist(): List<Pair<String, Boolean>> {
        val c = applicationContext
        fgAt = 0L
        return listOf(
            "Button positions are set" to (Cfg.get(c, "pts_ok") == 1),
            (if (Cap.active) "Fast capture is on (best speed)" else "~Fast capture is off. Optional: Brry uses screenshots, which are slower") to true,
            "Roblox is in front" to (atRoblox())
        )
    }

    fun needPoints() {
        toast("Set the button positions first (Settings > Set button positions)")
        Dbg.add("Start blocked: button positions not set")
    }

    private fun start() {
        fgAt = 0L
        val why = startProblem()
        if (why != null) {
            Dbg.blocker = why
            Dbg.add("Start blocked: $why")
            toast(why)
            return
        }
        Dbg.blocker = ""
        Cfg.put(applicationContext, "detect", 1)
        Cfg.put(applicationContext, "full", 1)
        detectMode = true
        running = true
        rejoinTries = 0
        rejoining = false
        tapAcc = 0.0
        lastFrameId = -1L
        Dbg.resetCatches()
        idx = 0
        phase = 0
        miss = 0
        catches = 0
        Dbg.catches = 0
        Dbg.sells = 0
        Dbg.fails = 0
        Dbg.startMs = SystemClock.uptimeMillis()
        lastGesture = Dbg.startMs
        lastShotSent = Dbg.startMs
        for (i in taskLast.indices) taskLast[i] = Dbg.startMs
        Dbg.state = "Starting"
        Dbg.add("Started, capture=" + (if (Cap.active) "fast" else "screenshots (slower)") + ", AI=" + (Cfg.get(applicationContext, "ai") == 1))
        Hook.send(applicationContext, "Macro started", "Capture: " + (if (Cap.active) "fast" else "screenshots") + ", AI autopilot: " + (Cfg.get(applicationContext, "ai") == 1), 0x38D39F)
        Ai.load(applicationContext)
        progressT = SystemClock.uptimeMillis()
        loopBeat = progressT
        loopRestarts = 0
        trackGen++
        aiStuck = 0
        gapBonus = 0L
        gainN = 0
        firstCast = false
        curCastFirst = false
        statusLast = 0L
        Biome.resetStats()
        paused = false
        failCasts = 0
        biteN = 0
        label()
        runScript(Cfg.gets(applicationContext, "script_start")) {
            if (detectMode) {
                dnext()
            } else {
                tl = build()
                next()
            }
        }
    }

    fun stop() {
        val was = running
        running = false
        paused = false
        loopBeat = 0L
        trackGen++
        pauseBtn?.text = "PAUSE"
        stopEngine()
        h.removeCallbacksAndMessages(null)
        rejoining = false
        hideDebug()
        Dbg.state = "Idle"
        label()
        if (was) {
            Dbg.add("Stopped after $catches catches")
            Hook.send(applicationContext, "Macro stopped", "$catches catches, ${Dbg.sells} sells", 0xFF6B6B)
        }
    }

    private fun next() {
        if (!running) return
        if (!atRoblox()) { lostRoblox(); return }
        if (idx >= tl.size) { onCatch(); if (doneCheck()) return; tl = build(); idx = 0 }
        val s = tl[idx++]
        tap(s.x, s.y, s.hold)
        h.postDelayed({ next() }, s.hold + s.wait)
    }

    private fun tap(x: Int, y: Int, hold: Long, report: Boolean = false) {
        lastGesture = SystemClock.uptimeMillis()
        try {
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, maxOf(hold, 1L)))
                .build()
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Dbg.tapOk++
                    if (report) toast("Android delivered the tap at $x,$y")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Dbg.tapCancel++
                    if (report || Dbg.tapCancel % 20 == 1) Dbg.add("Tap cancelled at $x,$y (${Dbg.tapCancel} so far)")
                    if (report) toast("Android cancelled the tap")
                }
            }, null)
            if (!ok) {
                Dbg.tapRefused++
                if (report || Dbg.tapRefused % 20 == 1) Dbg.add("Android refused the tap at $x,$y")
                if (report) h.post { toast("Android refused the tap. Is the Brry service turned on?") }
            }
        } catch (e: Exception) {
            Dbg.add("Tap error: " + e.message)
        }
    }

    // Test tap: shows a ring where Brry will tap the reel area and sends one real tap there.
    fun testTap() {
        val c = applicationContext
        val x = Cfg.get(c, "reel_x")
        val y = Cfg.get(c, "reel_y")
        showTapDot(x, y)
        h.postDelayed({ tap(x, y, Cfg.get(c, "taphold").toLong(), true) }, 450L)
    }

    private fun showTapDot(x: Int, y: Int) {
        if (!::wm.isInitialized) return
        val d = resources.displayMetrics.density
        val sz = (40 * d).toInt()
        val v = View(this).apply {
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(0x55FFFFFF)
                setStroke((3 * d).toInt(), 0xFFFF8A00.toInt())
            }
        }
        val lp = WL(sz, sz, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_TOUCHABLE or WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - sz / 2
            this.y = y - sz / 2
        }
        try { wm.addView(v, lp) } catch (e: Exception) { return }
        th.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {} }, 1200L)
    }

    private fun label() {
        startBtn?.text = if (running) "STOP ($catches)" else "START"
        val bgd = iconView?.background as? GradientDrawable
        bgd?.setStroke((2 * resources.displayMetrics.density).toInt(), if (running) 0xFF38D39F.toInt() else 0xFFFFFFFF.toInt())
        val iv = iconView
        if (iv != null) {
            if (running && !paused) {
                if (pulse == null) {
                    pulse = ObjectAnimator.ofFloat(iv, "alpha", 1f, 0.55f).apply {
                        duration = 900
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        start()
                    }
                }
            } else {
                pulse?.cancel()
                pulse = null
                iv.animate().alpha(0.92f).setDuration(200).start()
            }
        }
    }

    private fun biteLimit(): Long {
        val c = applicationContext
        val maxMs = Cfg.get(c, "maxwait") * 1000L
        if (Cfg.get(c, "adapt") == 1 && biteN >= 3) {
            return (biteAvg * 2.5).toLong().coerceIn(8000L, maxOf(maxMs, 8000L))
        }
        return maxMs
    }

    private fun onCatch() {
        progressT = SystemClock.uptimeMillis()
        catches++
        Dbg.catches = catches
        Dbg.markCatch()
        label()
        val every = Cfg.get(applicationContext, "whevery")
        if (every > 0 && catches % every == 0) {
            Hook.send(applicationContext, "Progress", "$catches catches, ${Dbg.sells} sells", 0x3EA6FF)
        }
    }

    private fun showDbg(r: DoubleArray?) {
        if (!::wm.isInitialized) return
        val c = applicationContext
        var v = dbgView
        if (v == null) {
            v = DebugView(this)
            wm.addView(v, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_NOT_TOUCHABLE or WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT))
            dbgView = v
        }
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
        v.rect.set(x1, y1, x2, y2)
        val need = maxOf(Cfg.get(c, "minpx"), 1)
        if (r == null) {
            v.color = Color.RED
            v.label = "No screenshot"
            v.zmax = -1.0
            v.mpos = -1.0
        } else {
            val zn = r[0].toInt()
            val mn = r[3].toInt()
            val pct = minOf(100, zn * 100 / need)
            v.color = if (zn >= need && mn >= 3) Color.GREEN else if (zn >= need / 2) Color.YELLOW else Color.RED
            v.label = "zone match $pct%   marker ${mn}px   ${Dbg.state}"
            val auto = r.size >= 10 && r[5] >= 0
            v.vert = if (auto) r[5] == 1.0 else Cfg.get(c, "axis") == 1
            if (auto) v.rect.set(r[6].toInt(), r[7].toInt(), r[8].toInt(), r[9].toInt())
            v.zmin = r[1]
            v.zmax = r[2]
            v.mpos = if (mn > 0) r[4] / mn else -1.0
        }
        v.invalidate()
    }

    private fun hideDebug() {
        if (!::wm.isInitialized) return
        dbgView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dbgView = null
    }

    // ---------- screen detection loop ----------
    private fun dnext() {
        if (!running) return
        loopBeat = SystemClock.uptimeMillis()
        if (!atRoblox()) { lostRoblox(); return }
        val c = applicationContext
        Dbg.state = when (phase) { 0 -> "Casting"; 1 -> "Waiting for the bar"; 2 -> "Reeling"; 3 -> "Selling"; 7 -> "Checking result"; 8 -> "Running task"; else -> "Walking" }
        when (phase) {
            0 -> {
                val due = dueTask()
                if (due >= 0) {
                    curTask = due
                    phase = 8
                    dnext()
                    return
                }
                curCastFirst = firstCast
                firstCast = false
                tap(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong())
                phase = 1
                t0 = SystemClock.uptimeMillis()
                h.postDelayed({ dnext() }, Cfg.get(c, "cast") + Cfg.get(c, "castdelay").toLong())
            }
            1 -> track { r ->
                if (!running) return@track
                val n = r?.get(0)?.toInt() ?: 0
                if (n >= Cfg.get(c, "minpx")) {
                    phase = 2
                    failCasts = 0
                    val bt = SystemClock.uptimeMillis() - t0
                    biteAvg = if (biteN == 0) bt.toDouble() else 0.7 * biteAvg + 0.3 * bt
                    biteN++
                    Dbg.biteMs = biteAvg.toInt()
                    wZone = screenW * 0.04
                    lastTap = 0L
                    nextTapAt = 0L
                    tapCount = 0
                    framesN = 0
                    framesIn = 0
                    histN = 0
                    lastMark = -1.0
                    lastHeld = false
                    votes = 0
                    roi = null
                    roiN = 0
                    roiMiss = 0
                    vel = 0.0
                    lastM = -1.0
                    lastMT = 0L
                    lastVelNs = 0L
                    lastCtl = 0L
                    tapAcc = 0.0
                    t0 = SystemClock.uptimeMillis()
                    lastSeen = t0
                    Dbg.add("Bar found (zone $n px)")
                    progressT = SystemClock.uptimeMillis()
                    prevCapT = 0L
                } else if (SystemClock.uptimeMillis() - t0 > biteLimit()) {
                    failCasts++
                    if (curCastFirst) Ai.cycleFault()
                    Dbg.add("Bar not found, cast again ($failCasts/3)")
                    if (failCasts >= 3) {
                        stop()
                        toast("Bar not found 3 times, stopped. Check COLOR and TEST.")
                        Hook.send(applicationContext, "Macro stopped", "The reeling bar was not found 3 times in a row.", 0xFF6B6B)
                        return@track
                    }
                    phase = 0
                }
                h.postDelayed({ dnext() }, 4L)
            }
            2 -> track { r ->
                if (!running) return@track
                if (Cfg.get(c, "full") == 1) { reelFull(r); return@track }
                var held = false
                if (r != null && r[0].toInt() >= Cfg.get(c, "minpx")) {
                    lastSeen = SystemClock.uptimeMillis()
                    if (r[3].toInt() >= 3) {
                        val zc = (r[1] + r[2]) / 2.0
                        val mc = r[4] / r[3]
                        var dir = Cfg.get(c, "holddir")
                        if (lastHeld && lastMark >= 0) {
                            val d = mc - lastMark
                            if (Math.abs(d) > 6) {
                                if ((d > 0) == (dir == 1)) votes++ else votes--
                                if (votes <= -3) {
                                    dir = 1 - dir
                                    Cfg.put(c, "holddir", dir)
                                    votes = 0
                                    toast("Hold direction flipped")
                                }
                            }
                        }
                        val db = Cfg.get(c, "deadband")
                        held = if (dir == 0) mc > zc + db else mc < zc - db
                        if (held && SystemClock.uptimeMillis() >= holdUntil - 40L) {
                            val hm = Cfg.get(c, "holdms").toLong()
                            tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), hm)
                            holdUntil = SystemClock.uptimeMillis() + hm
                        }
                        lastMark = mc
                    }
                }
                lastHeld = held
                dbgCount++
                if (dbgCount % 15 == 0) Dbg.add("reel zone=${r?.get(0)?.toInt()} marker=${r?.get(3)?.toInt()} held=$held")
                if (Cfg.get(c, "debug") == 1) showDbg(r)
                if (SystemClock.uptimeMillis() - lastSeen > Cfg.get(c, "gonems").toLong() || SystemClock.uptimeMillis() - t0 > 60000L) {
                    onCatch()
                    if (doneCheck()) return@track
                    val every = Cfg.get(c, "every")
                    phase = if (Cfg.get(c, "sell") == 1 && every > 0 && catches % every == 0) 4 else 0
                    sellIdx = 0
                    h.postDelayed({ dnext() }, Cfg.get(c, "postcatch").toLong())
                } else {
                    h.postDelayed({ dnext() }, 20L)
                }
            }
            7 -> track { r ->
                if (!running) return@track
                // the bar came back: the reel was called finished too early, so keep reeling
                val back = r != null && r[0].toInt() >= Cfg.get(c, "minpx") && r[3].toInt() >= 3
                if (back) {
                    p7Back++
                    if (p7Back >= 3 && p7Resumes < 2) {
                        p7Resumes++
                        p7Back = 0
                        Dbg.add("The bar came back, reeling again")
                        Ai.cycleFault()
                        phase = 2
                        lastSeen = SystemClock.uptimeMillis()
                        startEngine()
                        h.postDelayed({ dnext() }, 1L)
                        return@track
                    }
                } else {
                    p7Back = 0
                }
                val failed = r != null && r.size >= 11 && r[10] >= 150
                // the result panel can take a moment to slide in, so keep looking before calling it a catch
                if (!failed && SystemClock.uptimeMillis() - p7t < cyc(Cfg.get(c, "postcatch")) + cyc(Cfg.get(c, "resultms"))) {
                    h.postDelayed({ dnext() }, 70L)
                    return@track
                }
                var sellNext = false
                Dbg.add(if (failed) "Result: failed, tapping the close (X) button" else "Result: caught, tapping the close (X) button")
                tapPoint("dismiss")
                h.postDelayed({ tapPoint("dismiss") }, 350L)
                Ai.report(c, Dbg.inZone, !failed)
                Ai.cycleReelEnd(c)
                firstCast = true
                progressT = SystemClock.uptimeMillis()
                if (failed) {
                    Dbg.fails++
                } else {
                    onCatch()
                    if (doneCheck()) return@track
                    val every = Cfg.get(c, "every")
                    sellNext = Cfg.get(c, "sell") == 1 && every > 0 && catches % every == 0
                }
                phase = if (sellNext) 4 else 0
                sellIdx = 0
                h.postDelayed({ dnext() }, maxOf(cyc(Cfg.get(c, "dismisswait")), 250L))
            }
            4 -> {
                phase = 6
                runScript(Cfg.gets(c, "script_pre")) { phase = 3; sellIdx = 0; dnext() }
            }
            5 -> {
                phase = 6
                runScript(Cfg.gets(c, "script_post")) { phase = 0; dnext() }
            }
            8 -> {
                phase = 6
                val ti = curTask
                Dbg.add("Running timed task " + (ti + 1))
                runScript(Cfg.gets(c, "t" + (ti + 1) + "s")) {
                    taskLast[ti] = SystemClock.uptimeMillis()
                    phase = 0
                    dnext()
                }
            }
            6 -> { }
            else -> {
                val k = listOf("inv", "sellall", "confirm")[sellIdx]
                sellIdx++
                tap(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L)
                if (sellIdx >= 3) {
                    phase = 5
                    Dbg.sells++
                    Dbg.add("Sold (#${Dbg.sells})")
                    Hook.send(applicationContext, "Sold items", "Sell #${Dbg.sells} after $catches catches", 0x38D39F)
                }
                h.postDelayed({ dnext() }, Cfg.get(c, "mdelay").toLong())
            }
        }
    }

    private fun shot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        val wait = maxOf(0L, 380L - (SystemClock.uptimeMillis() - lastShot))
        h.postDelayed({
            lastShot = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    cb(bmp)
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
        }, wait)
    }

    // Waits for a NEW screen frame while the bar is being searched or reeled, so the controller never
    // works on the same picture twice. A still screen sends no new frames, so after 60 ms it scans anyway.
    private fun track(cb: (DoubleArray?) -> Unit) {
        if (running && (phase == 1 || phase == 2) && Cap.active) {
            val id = Cap.poll()
            val now = SystemClock.uptimeMillis()
            if (id == lastFrameId && now - lastFrameAt < 60L) {
                h.postDelayed({ track(cb) }, 1L)
                return
            }
            newFrame = id != lastFrameId
            if (newFrame) curFrameNs = Cap.frameNs
            lastFrameId = id
            lastFrameAt = now
        } else {
            newFrame = true
        }
        trackNow(cb)
    }

    private fun trackNow(cb: (DoubleArray?) -> Unit) {
        val t = SystemClock.uptimeMillis()
        scanT = t
        val c0 = applicationContext
        val gen = trackGen
        val after: (DoubleArray?) -> Unit = { r: DoubleArray? ->
          if (gen == trackGen) {
            val now = SystemClock.uptimeMillis()
            loopBeat = now
            Dbg.latency = (now - t).toInt()
            Dbg.zonePx = if (r == null) 0 else r[0].toInt()
            Dbg.markerPx = if (r == null) 0 else r[3].toInt()
            Dbg.barFound = r != null && r[0].toInt() >= Cfg.get(c0, "minpx")
            fpsN++
            if (now - fpsT >= 1000L) { Dbg.fps = fpsN; fpsN = 0; fpsT = now }
            // an error inside the loop must never end the loop: log it and carry on
            try {
                cb(r)
            } catch (e: Throwable) {
                Dbg.add("Reel loop error: " + e.message)
                if (running) h.postDelayed({ dnext() }, 50L)
            }
          }
        }
        val c = applicationContext
        val full = Cfg.get(c, "full") == 1
        if (Cap.active) {
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Cap.ensure(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            screenW = dm.widthPixels
            if (full) {
                // once the bar is found, only look at a small box around it (faster and finer)
                val rb = roi
                if (phase == 2 && rb != null && roiN < 150) {
                    roiN++
                    val rs = if (dm.widthPixels >= 1600) 2 else 1
                    bg.execute {
                        try {
                            val reg = Cap.region(rb[0], rb[1], rb[2] - rb[0], rb[3] - rb[1], rs)
                            val r = if (reg == null) null else roiScan(reg)
                            h.post {
                                if (r == null || r[0] < Cfg.get(c, "minpx")) {
                                    roiMiss++
                                    if (roiMiss >= 2) roi = null
                                } else {
                                    roiMiss = 0
                                }
                                after(r)
                            }
                        } catch (e: Throwable) {
                            Dbg.add("Scan error: " + e.message)
                            h.post { after(null) }
                        }
                    }
                    return
                }
                val st = maxOf(2, dm.widthPixels / 500)
                bg.execute {
                    try {
                        val reg = Cap.region(0, 0, 100000, 100000, st)
                        val res: DoubleArray? = if (reg == null) null else {
                            val cvr = if (Cv.ready && Cfg.get(c, "cv") == 1) {
                                Cv.scanFull(reg.px, reg.w, reg.h, reg.step, Cfg.get(c, "color"), Cfg.get(c, "color2"), tolNow())
                            } else null
                            cvr ?: autoScan(reg.px, reg.w, reg.h, reg.step)
                        }
                        h.post {
                            if (phase == 2 && res != null && res[0] >= Cfg.get(c, "minpx") && res[9] > res[7]) {
                                roi = intArrayOf(res[6].toInt(), res[7].toInt() - 6, res[8].toInt(), res[9].toInt() + 6)
                                roiN = 0
                                roiMiss = 0
                            }
                            after(res)
                        }
                    } catch (e: Throwable) {
                        Dbg.add("Scan error: " + e.message)
                        h.post { after(null) }
                    }
                }
                return
            }
            val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
            val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
            val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
            val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
            bg.execute {
                try {
                    val reg = Cap.region(x1, y1, x2 - x1, y2 - y1)
                    val r = if (reg == null) null else scanPx(reg.px, reg.x, reg.y, reg.w, reg.h)
                    h.post { after(r) }
                } catch (e: Throwable) {
                    Dbg.add("Scan error: " + e.message)
                    h.post { after(null) }
                }
            }
            return
        }
        shot { b ->
            val r = try { if (b == null) null else if (full) {
                val st = maxOf(2, b.width / 500)
                val sb = Bitmap.createScaledBitmap(b, maxOf(1, b.width / st), maxOf(1, b.height / st), false)
                val ar = IntArray(sb.width * sb.height)
                sb.getPixels(ar, 0, sb.width, 0, 0, sb.width, sb.height)
                val cvr = if (Cv.ready && Cfg.get(c, "cv") == 1) Cv.scanFull(ar, sb.width, sb.height, st, Cfg.get(c, "color"), Cfg.get(c, "color2"), tolNow()) else null
                val res = cvr ?: autoScan(ar, sb.width, sb.height, st)
                sb.recycle()
                b.recycle()
                res
            } else {
                scan(b).also { b.recycle() }
            } } catch (e: Throwable) { Dbg.add("Scan error: " + e.message); null }
            h.post { after(r) }
        }
    }

    // Fast scan of the small box around the bar found by the full scan (same output layout as autoScan).
    private fun roiScan(g: Reg): DoubleArray {
        val c = applicationContext
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val out = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 1.0,
            g.x.toDouble(), g.y.toDouble(), (g.x + g.w * g.step).toDouble(), (g.y + g.h * g.step).toDouble(), 0.0)
        val w = g.w
        val hh = g.h
        if (w < 4 || hh < 3) return out
        val ya = hh / 4
        val yb = maxOf(ya, hh - 1 - hh / 4)
        val rows = yb - ya + 1
        var cells = 0
        var zl = -1
        var zr = -1
        var zrun = 0
        var zStart = -1
        var tip = -1
        var bn = 0
        var brun = 0
        var bStart = -1
        for (xx in 0..w) {
            var zn = 0
            var bc = 0
            if (xx < w) {
                for (yy in ya..yb) {
                    val p = g.px[yy * w + xx]
                    if (near(p, zc, tol)) zn++
                    if (near(p, mk, tol)) bc++
                }
            }
            if (xx < w && zn * 2 >= rows) {
                if (zrun == 0) zStart = xx
                zrun++
                cells += zn
            } else {
                if (zrun >= 2) {
                    if (zl < 0) zl = zStart
                    zr = xx
                }
                zrun = 0
            }
            if (xx < w && bc * 2 >= rows) {
                if (brun == 0) bStart = xx
                brun++
            } else {
                if (brun >= 3 && bStart * g.step <= 0.03 * screenW) { tip = xx; bn = brun }
                brun = 0
            }
        }
        if (zl < 0) return out
        out[0] = cells.toDouble() * g.step * g.step
        out[1] = (g.x + zl * g.step).toDouble()
        out[2] = (g.x + zr * g.step).toDouble()
        out[3] = bn.toDouble()
        out[4] = if (tip >= 0) (g.x + tip * g.step).toDouble() else -1.0
        return out
    }

    private class Cand(var x0: Int, var x1: Int, var y0: Int, var y1: Int, var area: Int)

    // Whole-screen search for the reeling bar of Sol's RNG: a dark green zone (long and thin),
    // and the blue fill whose right end is the marker.
    // Returns [zonePx, zoneLeft, zoneRight, blueCols, tipX(-1 if none), 1, boxX1, boxY1, boxX2, boxY2, redSamples]
    private fun autoScan(px: IntArray, w: Int, h: Int, step: Int): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val red = 0xFFFF4041L.toInt()
        var redN = 0
        val cands = ArrayList<Cand>()
        var prev = ArrayList<Cand>()
        for (yy in 0 until h) {
            val cur = ArrayList<Cand>()
            val base = yy * w
            var xx = 0
            while (xx < w) {
                val p = px[base + xx]
                if (near(p, zc, tol)) {
                    var e = xx
                    while (e + 1 < w && near(px[base + e + 1], zc, tol)) e++
                    if (e - xx + 1 >= 3) {
                        var m: Cand? = null
                        for (q in prev) if (xx <= q.x1 && e >= q.x0) { m = q; break }
                        if (m != null) {
                            m.x0 = minOf(m.x0, xx)
                            m.x1 = maxOf(m.x1, e)
                            m.y1 = yy
                            m.area += e - xx + 1
                            if (!cur.contains(m)) cur.add(m)
                        } else {
                            val n = Cand(xx, e, yy, yy, e - xx + 1)
                            cands.add(n)
                            cur.add(n)
                        }
                    }
                    xx = e + 1
                } else {
                    if (near(p, red, 40)) redN++
                    xx++
                }
            }
            prev = cur
        }
        out[10] = redN.toDouble()
        val barL = w / 2.0 - 0.386 * h
        val barR = w / 2.0 + 0.386 * h
        var best: Cand? = null
        for (k in cands) {
            val cw = k.x1 - k.x0 + 1
            val ch = k.y1 - k.y0 + 1
            if (ch < 3 || cw < 2 * ch) continue
            if (ch < 0.025 * h || ch > 0.06 * h) continue
            if (cw < 0.02 * w || cw > w / 2) continue
            if (k.area < cw * ch / 2) continue
            val cx = (k.x0 + k.x1) / 2.0
            val cy = (k.y0 + k.y1) / 2.0
            if (cx < barL || cx > barR || cy < 0.4 * h || cy > 0.8 * h) continue
            if (best == null || k.area > best.area) best = k
        }
        val b = best ?: return out
        val wz = b.x1 - b.x0 + 1
        out[0] = b.area.toDouble() * step * step / 2.0
        out[1] = (b.x0 * step).toDouble()
        out[2] = ((b.x1 + 1) * step).toDouble()
        val ya = b.y0 + (b.y1 - b.y0) / 4
        val yb = b.y1 - (b.y1 - b.y0) / 4
        val xa = maxOf(0, barL.toInt() - 1)
        val xb = minOf(w - 1, b.x1 + wz)
        var tip = -1
        var bn = 0
        var run = 0
        var runStart = -1
        for (xx in xa..xb + 1) {
            var ok = false
            if (xx <= xb) {
                var cnt = 0
                for (yy in ya..yb) if (near(px[yy * w + xx], mk, tol)) cnt++
                ok = cnt * 2 >= (yb - ya + 1)
            }
            if (ok) {
                if (run == 0) runStart = xx
                run++
            } else {
                if (run >= 3 && (runStart - xa) <= 0.03 * w) { tip = xx; bn = run }
                run = 0
            }
        }
        out[3] = bn.toDouble()
        out[4] = if (tip >= 0) (tip * step).toDouble() else -1.0
        out[6] = (xa * step).toDouble()
        out[7] = ((b.y0 - 2) * step).toDouble()
        out[8] = ((xb + 1) * step).toDouble()
        out[9] = ((b.y1 + 3) * step).toDouble()
        return out
    }

    // ---------- tap engine ----------
    private fun startEngine() {
        tapTimes.clear()
        engAcc = 0.0
        engLast = SystemClock.uptimeMillis()
        engLastTap = 0L
        obs = null
        prevCapT = 0L
        zhN = 0
        zvEst = 0.0
        qT0 = 0L
        engineOn = true
        eh.removeCallbacks(engine)
        eh.post(engine)
    }

    private fun stopEngine() {
        engineOn = false
        eh.removeCallbacks(engine)
    }

    // the waits after a reel (result panel, close tap, bar gone) shrink a little when the AI has found they can
    private fun cyc(ms: Int): Long =
        if (Cfg.get(applicationContext, "ai") == 1) (ms * Ai.cycle).toLong() else ms.toLong()

    // When was the picture taken (uptime ms)? Android stamps each fast-capture frame; screenshots are taken just after asking.
    private fun capTime(now: Long): Long {
        if (Cap.active) {
            val ms = curFrameNs / 1000000L
            if (curFrameNs > 0L && ms <= now && ms >= now - 400L) return ms
            return if (Cap.arriveMs in (now - 400L)..now) Cap.arriveMs else scanT - 25L
        }
        return lastShot + 30L
    }

    // Runs every 4 ms on its own thread. It PREDICTS where the marker is right now:
    //   last seen position - drain since the picture + lift of the taps sent since then,
    // so a slow or late picture does not make the timing late. Fast capture: tap rate follows the distance to the
    // aim point. Screenshots only: one tap whenever the predicted marker drops below the aim (bang-bang).
    private fun engineStep() {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        val dt = (now - engLast).coerceIn(0L, 20L) / 1000.0
        engLast = now
        val o = obs ?: return
        val fastCap = Cap.active
        val stale = now - o.at > (if (fastCap) 350L else 1000L)
        // a late picture used to stop the taps; for up to 0.8 s keep tapping at the neutral rate so the marker holds
        if (stale && !(fastCap && now - o.at < 800L)) return
        val gain = engGain
        val drain = engDrain
        if (gain <= 0.0) return
        val aiOn = Cfg.get(c, "ai") == 1
        Ai.enabled = aiOn
        val delay = Ai.delayMs()
        var n = 0
        for (t in tapTimes) if (t > o.capT - delay) n++
        while (true) {
            val first = tapTimes.peek() ?: break
            if (first < now - 3000L) tapTimes.poll() else break
        }
        val pred = if (o.haveTip) o.tip - drain * (now - o.capT) / 1000.0 + gain * n else o.zc - 4 * gain
        // a moving zone is followed a little ahead of where it was seen
        val zcNow = o.zc + (o.zv * (now - o.capT) / 1000.0).coerceIn(-o.wZone, o.wZone)
        val target = zcNow + (if (aiOn) Ai.bias else 0.0) * o.wZone
        val kp = if (aiOn) Ai.kp else 8.0
        val hold = Cfg.get(c, "taphold").toLong()
        val minGap = maxOf(Cfg.get(c, "tapms").toLong(), hold + 20L) + gapBonus
        val fire: Boolean
        if (fastCap) {
            val en = if (stale) 0.0 else (target - pred) / maxOf(o.wZone, 1.0)
            var rate = (drain / gain) * (1.0 + kp * en)
            if (rate < 0.0) rate = 0.0
            val maxRate = 1000.0 / minGap
            if (rate > maxRate) rate = maxRate
            engAcc += rate * dt
            if (rate < 0.5 && engAcc > 0.6) engAcc = 0.6
            if (engAcc > 2.0) engAcc = 2.0
            fire = engAcc >= 1.0
        } else {
            fire = pred < target - gain * 0.5
        }
        if (!fire || now - engLastTap < minGap) return
        if (fastCap) engAcc -= 1.0
        engLastTap = now
        tapTimes.add(now)
        tapCount++
        tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), hold)
        // tap health: if Android keeps cancelling taps, leave more room between them
        chkTaps++
        if (chkTaps >= 20) {
            val ca = Dbg.tapCancel - chkCancel
            val ok = Dbg.tapOk - chkOk
            chkCancel = Dbg.tapCancel
            chkOk = Dbg.tapOk
            chkTaps = 0
            if (ca + ok >= 10) {
                if (ca * 4 > ca + ok && gapBonus < 60L) {
                    gapBonus += 10L
                    Dbg.add("Taps are being cancelled ($ca of ${ca + ok}), adding room between taps: +$gapBonus ms")
                } else if (ca * 20 < ca + ok && gapBonus > 0L) {
                    gapBonus -= 5L
                }
            }
        }
    }

    // The reel step only READS the screen and updates the estimate. The taps themselves come from the engine above.
    private fun reelFull(r: DoubleArray?) {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        val aiOn = Cfg.get(c, "ai") == 1
        if (!engineOn) startEngine()
        if (r != null && r[0].toInt() >= Cfg.get(c, "minpx")) {
            lastSeen = now
            wZone = maxOf(wZone, r[2] - r[1])
            val zr = r[2]
            val zc = zr - wZone / 2.0
            // measured on a 2340 px wide screen: one tap lifts the fill about 58 px, it drains about 215 px/s
            val gainBase = 0.0248 * screenW
            val baseDrain = 0.0919 * screenW
            val learn = Cfg.get(c, "learn") == 1
            // start from what the AI measured in earlier sessions, then follow what is measured live
            val drain0 = if (aiOn) Ai.drainFrac * screenW else baseDrain
            val gain0 = if (aiOn) Ai.gainFrac * screenW else gainBase
            val drain = if (learn && drainN >= 4) drainEst.coerceIn(baseDrain * 0.6, baseDrain * 1.6) else drain0
            val gain = if (learn && gainN >= 8) gainEst.coerceIn(gainBase * 0.6, gainBase * 1.5) else gain0
            val haveTip = r[4] >= 0
            var tip = r[4]
            if (!haveTip) tip = zc - 4 * gain
            if (newFrame || histN == 0) {
                tipHist[histN % 3] = tip
                histN++
            }
            val m = if (histN >= 3) tipHist.copyOf().also { it.sort() }[1] else tip
            val capT = capTime(now)
            val pub = if (histN >= 3 && Math.abs(tip - m) > 0.5 * wZone) m else tip
            val delay = Ai.delayMs()
            if (newFrame) {
                // zone speed over a 100-260 ms stretch (zero when the zone is still)
                zhT[zhN % 16] = capT
                zhZ[zhN % 16] = zc
                zhN++
                var bk = -1
                for (k in 0 until minOf(zhN, 16)) {
                    val dtk = capT - zhT[k]
                    if (dtk in 100L..260L && (bk < 0 || zhT[k] < zhT[bk])) bk = k
                }
                if (bk >= 0) {
                    val v = (zc - zhZ[bk]) / ((capT - zhT[bk]) / 1000.0)
                    zvEst = if (Math.abs(v) < 25.0) 0.0 else 0.6 * zvEst + 0.4 * v
                }
            }
            if (haveTip && newFrame) {
                // gain: a picture with exactly one tap in between shows how far that tap lifted the fill
                if (prevCapT > 0L && capT > prevCapT && capT - prevCapT <= 120L) {
                    var k = 0
                    for (t in tapTimes) if (t > prevCapT - delay && t <= capT - delay) k++
                    if (k == 1) {
                        val lift = (pub - prevTip) + drain * (capT - prevCapT) / 1000.0
                        if (lift > gainBase * 0.4 && lift < gainBase * 1.8) {
                            gainEst = if (gainN == 0) lift else 0.92 * gainEst + 0.08 * lift
                            gainN++
                        }
                    }
                }
                // drain: a stretch of 120 ms or more with no tap landing shows how fast the fill falls by itself
                val lastEff = engLastTap + delay
                if (engLastTap == 0L || capT > lastEff + 10L) {
                    if (qT0 == 0L || (engLastTap != 0L && lastEff >= qT0)) {
                        qT0 = capT
                        qTip0 = pub
                    } else if (capT - qT0 >= 120L) {
                        val smp = (qTip0 - pub) / ((capT - qT0) / 1000.0)
                        if (smp > baseDrain * 0.3 && smp < baseDrain * 2.5) {
                            drainEst = if (drainN == 0) smp else 0.85 * drainEst + 0.15 * smp
                            drainN++
                        }
                        qT0 = capT
                        qTip0 = pub
                    }
                } else {
                    qT0 = 0L
                }
                prevTip = pub
                prevCapT = capT
            } else if (!haveTip) {
                prevCapT = 0L
                qT0 = 0L
            }
            engDrain = drain
            engGain = gain
            obs = Obs(capT, pub, haveTip, zc, wZone, now, zvEst)
            lastMT = now
            if (newFrame) {
                framesN++
                if (m >= zr - wZone && m <= zr) framesIn++
            }
            dbgCount++
            if (dbgCount % 20 == 0) {
                Dbg.add("reel tip=${m.toInt()} zone=${(zr - wZone).toInt()}-${zr.toInt()} drain=${drain.toInt()} gain=${gain.toInt()} aim=${Dbg.aiBias} taps=$tapCount")
            }
            if (Cfg.get(c, "debug") == 1) showDbg(r)
        }
        val gone = Cfg.get(c, "gonems").toLong()
        val goneNow = if (aiOn) maxOf(cyc(Cfg.get(c, "gonems")), minOf(gone, 300L)) else gone
        if (now - lastSeen > goneNow || now - t0 > 60000L) {
            stopEngine()
            val pct = 100 * framesIn / maxOf(framesN, 1)
            Dbg.inZone = pct
            Dbg.add("Reel done: marker in the zone $pct% of the time, $tapCount taps")
            if (aiOn && gainN >= 8 && drainN >= 4) Ai.savePhys(c, gainEst, drainEst, screenW)
            phase = 7
            p7t = now
            p7Back = 0
            p7Resumes = 0
            h.postDelayed({ dnext() }, cyc(Cfg.get(c, "postcatch")))
        } else {
            h.postDelayed({ dnext() }, 1L)
        }
    }

    private fun scanPx(px: IntArray, x1: Int, y1: Int, w: Int, hh: Int): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    // color tolerance; it widens a little after every cast where the bar was not found
    private fun tolNow(): Int {
        val c = applicationContext
        return Cfg.get(c, "tol") + if (Cfg.get(c, "widen") == 1) minOf(failCasts, 3) * 10 else 0
    }

    private fun near(p: Int, t: Int, tol: Int): Boolean =
        Math.abs(((p shr 16) and 255) - ((t shr 16) and 255)) <= tol &&
        Math.abs(((p shr 8) and 255) - ((t shr 8) and 255)) <= tol &&
        Math.abs((p and 255) - (t and 255)) <= tol

    // returns [zoneCount, zoneMin, zoneMax, markerCount, markerSum] along the chosen axis
    private fun scan(b: Bitmap): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return out
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    private fun shotMain(cb: (Bitmap?) -> Unit) = shot { b -> h.post { cb(b) } }

    private fun detect(cb: (Int) -> Unit) {
        shot { b ->
            val n = if (b == null) 0 else count(b).also { b.recycle() }
            h.post { cb(n) }
        }
    }

    private fun doneCheck(): Boolean {
        val m = Cfg.get(applicationContext, "maxcatch")
        if (m > 0 && catches >= m) { stop(); toast("Reached $m catches, stopped"); return true }
        return false
    }

    private fun count(b: Bitmap): Int {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return 0
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val tc = Cfg.get(c, "color")
        val tr = (tc shr 16) and 255
        val tg = (tc shr 8) and 255
        val tb = tc and 255
        val tol = tolNow()
        var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            if (Math.abs(((p shr 16) and 255) - tr) <= tol &&
                Math.abs(((p shr 8) and 255) - tg) <= tol &&
                Math.abs((p and 255) - tb) <= tol) n++
            i += 3
        }
        return n
    }

    fun test() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        track { r ->
            toast(if (r == null) "Could not capture the screen" else "Zone px: ${r[0].toInt()}, marker px: ${r[3].toInt()} (zone needs ${Cfg.get(applicationContext, "minpx")})")
            Dbg.add("TEST zone=${r?.get(0)?.toInt()} marker=${r?.get(3)?.toInt()}")
            showDbg(r)
            if (!running) h.postDelayed({ hideDebug() }, 4000L)
        }
    }

    // ---------- calibration on a frozen screenshot ----------
    private fun calibrate() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        toast("Start fishing now. The screen is captured in 8 seconds, so have the reeling bar showing.")
        h.postDelayed({ calibrateNow() }, 8000L)
    }

    private fun calibrateNow() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        shotMain { bmp ->
            if (bmp == null) { toast("Could not capture the screen"); return@shotMain }
            val c = applicationContext
            val box = FrameLayout(this)
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            val prompts = listOf(
                "1/4: tap the TOP-LEFT corner of the reeling bar area",
                "2/4: tap the BOTTOM-RIGHT corner of the bar area",
                "3/4: tap the GREEN zone (its color is saved)",
                "4/4: tap the filled (blue) part of the bar (its color is saved)"
            )
            val first = if (Cfg.get(c, "full") == 1) 2 else 0
            val t = pill(prompts[first], 0xDD000000.toInt())
            box.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 60 })
            var step = first
            box.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val x = (e.x / v.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = (e.y / v.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    when (step) {
                        0 -> { Cfg.put(c, "r1_x", x); Cfg.put(c, "r1_y", y) }
                        1 -> { Cfg.put(c, "r2_x", x); Cfg.put(c, "r2_y", y) }
                        2 -> Cfg.put(c, "color", bmp.getPixel(x, y))
                        else -> Cfg.put(c, "color2", bmp.getPixel(x, y))
                    }
                    step++
                    if (step >= 4) {
                        wm.removeView(box)
                        toast("Saved. Tap TEST while the bar is showing.")
                    } else {
                        t.text = prompts[step]
                    }
                }
                true
            }
            wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
        }
    }

    // ---------- floating panel ----------
    private fun pill(t: String, color: Int) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        setPadding(34, 22, 34, 22)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 60f }
    }

    fun refreshIcon() { loadIcon() }

    private fun loadIcon() {
        val v = iconView ?: return
        val f = java.io.File(filesDir, "float_icon.png")
        if (Cfg.get(applicationContext, "icon_custom") == 1 && f.exists()) {
            val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    fun isRunning() = running
    fun isPaused() = paused

    // Turn the accessibility service off from inside the app or the panel
    fun turnOff() {
        try { if (running) stop() } catch (e: Exception) { Dbg.add("Stop before turning off failed: " + e.message) }
        closePanel()
        Dbg.add("Service turned off")
        if (Build.VERSION.SDK_INT >= 24) disableSelf()
    }

    fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Dbg.add("Could not open the link: " + e.message) }
    }

    fun openRobloxApp() {
        val i = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
        if (i == null) { toast("Roblox is not installed"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Dbg.add("Could not open Roblox: " + e.message) }
    }

    fun openApp(capture: Boolean) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("capture", capture)
        try { startActivity(i) } catch (e: Exception) { Dbg.add("Could not open the app: " + e.message) }
    }

    fun openPanel() {
        if (panelUi != null) return
        val dm = resources.displayMetrics
        val wPx = minOf(dm.widthPixels - (24 * dm.density).toInt(), (760 * dm.density).toInt())
        val ui = PanelUi(this, wPx)
        val lp = WL(wPx, WRAP, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
        }
        wm.addView(ui.root, lp)
        ui.root.alpha = 0f
        ui.root.scaleX = 0.96f
        ui.root.scaleY = 0.96f
        ui.root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        ui.start()
        panelUi = ui
    }

    fun closePanel() {
        val ui = panelUi ?: return
        ui.stop()
        panelUi = null
        val v = ui.root
        v.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(150)
            .withEndAction { try { wm.removeView(v) } catch (_: Exception) {} }.start()
    }

    private fun showPanel() {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(0xFF000000.toInt())
                setStroke((2 * d).toInt(), 0xFFFFFFFF.toInt())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            alpha = 0.92f
        }
        iconView = icon
        loadIcon()
        box.addView(icon, LinearLayout.LayoutParams(size, size))
        val lp = WL(WRAP, WRAP, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        drag(icon, box, lp) { if (panelUi == null) openPanel() else closePanel() }
        wm.addView(box, lp)
        panel = box
        box.scaleX = 0f
        box.scaleY = 0f
        box.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(1.9f)).start()
    }

    private fun drag(v: View, row: View, lp: WL, click: () -> Unit) {
        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false; v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start() }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt()
                    val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > 12 || Math.abs(my) > 12) moved = true
                    if (moved) { lp.x = sx + mx; lp.y = sy + my; wm.updateViewLayout(row, lp) }
                }
                MotionEvent.ACTION_UP -> { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); if (!moved) click() }
            }
            true
        }
    }

    // ---------- tap position picker ----------
    fun setPoints() = pickAt(Cfg.keys, 0) {
        Cfg.put(applicationContext, "pts_ok", 1)
        toast("Positions saved")
    }

    fun setExtraPoints() = pickAt(Cfg.extra, 0) { toast("Extra points saved") }

    fun setWatchRegion() = pickAt(Cfg.watKeys, 0) { toast("Watch area saved") }

    fun setBiomeRegion() = pickAt(Cfg.bioKeys, 0) {
        bioWarned = false
        toast("Biome area saved")
    }

    private fun pickAt(keys: List<String>, i: Int, done: () -> Unit) {
        if (i >= keys.size) { done(); return }
        val k = keys[i]
        val box = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val t = TextView(this).apply {
            text = "${i + 1}/${keys.size}: tap the ${Cfg.label[k] ?: Cfg.label2[k] ?: k}"
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
            gravity = Gravity.CENTER
        }
        box.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                Cfg.put(applicationContext, k + "_x", e.rawX.toInt())
                Cfg.put(applicationContext, k + "_y", e.rawY.toInt())
                wm.removeView(box)
                pickAt(keys, i + 1, done)
            }
            true
        }
        wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
    }

    // ---------- timed tasks (auto craft, buy, biome items: your own scripts) ----------
    private fun dueTask(): Int {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        for (i in 0..2) {
            val k = "t" + (i + 1)
            if (Cfg.get(c, k + "on") != 1) continue
            val min = Cfg.get(c, k + "min")
            if (min <= 0 || Cfg.gets(c, k + "s").isBlank()) continue
            if (now - taskLast[i] >= min * 60000L) return i
        }
        return -1
    }

    // ---------- background services: anti-kick, screenshots, biome watch ----------
    private var statusLast = 0L

    private fun services() {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        val inRoblox = atRoblox()
        // loop watchdog (works with or without the AI): the reel loop stamps loopBeat every few ms,
        // so a silence of several seconds means the chain broke. Restart it in place.
        if (running && !paused && !rejoining && inRoblox && loopBeat > 0L &&
            (phase == 0 || phase == 1 || phase == 2 || phase == 7) && now - loopBeat > 6000L) {
            loopRestart()
        }
        // AI watchdog: nothing caught, failed or found for a long time -> clear any panel and cast again
        if (running && !paused && !rejoining && inRoblox && Cfg.get(c, "ai") == 1 && (phase <= 2 || phase == 7)) {
            val lim = maxOf(Cfg.get(c, "stuck"), 30) * 1000L
            if (now - progressT > lim) aiRecover()
        }
        // anti-kick: if nothing was tapped for a while (paused, long wait) nudge the camera a little
        if (running && inRoblox && Cfg.get(c, "afk") == 1) {
            val every = maxOf(Cfg.get(c, "afkmin"), 1) * 60000L
            if (now - lastGesture >= every) {
                lastGesture = now
                Dbg.add("Anti-kick: small camera move")
                camSwipe("right", 30)
                h.postDelayed({ camSwipe("left", 30) }, 700L)
            }
        }
        // periodic screenshot to the webhooks
        if (running && Cap.active && Cfg.get(c, "shoton") == 1) {
            val every = maxOf(Cfg.get(c, "shotmin"), 1) * 60000L
            if (now - lastShotSent >= every) {
                lastShotSent = now
                bgOcr.execute {
                    val reg = Cap.region(0, 0, 100000, 100000, 2)
                    if (reg != null) {
                        val bmp = Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888)
                        val o = java.io.ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 70, o)
                        bmp.recycle()
                        Hook.sendImage(c, "Screenshot after " + catches + " catches", o.toByteArray())
                    }
                }
            }
        }
        // word watch (merchant, aura names, anything you list) and disconnect check
        if (inRoblox && Cap.active && Cfg.get(c, "watch") == 1 && !watchBusy &&
            now - watchLast >= maxOf(Cfg.get(c, "watchsec"), 3) * 1000L) {
            watchLast = now
            ocrCheck(true)
        }
        if (running && !rejoining && inRoblox && Cap.active && Cfg.get(c, "rejoin") == 1 && !watchBusy &&
            now - lastDiscCheck >= 20000L) {
            lastDiscCheck = now
            ocrCheck(false)
        }
        // (biome watch now runs on its own fast ticker, see biomeTick)
        // status report to the webhooks every N minutes while the macro runs
        if (running && Cfg.get(c, "statuson") == 1 && now - statusLast >= maxOf(Cfg.get(c, "statusmin"), 5) * 60000L) {
            if (statusLast != 0L) sendStatus()
            statusLast = now
        }
    }

    private fun loopRestart() {
        loopRestarts++
        Dbg.add("Reel loop was silent for 6 s, restarting it (" + loopRestarts + ")")
        trackGen++
        loopBeat = SystemClock.uptimeMillis()
        if (phase == 2) startEngine() else stopEngine()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ dnext() }, 20L)
    }

    private fun aiRecover() {
        val c = applicationContext
        aiStuck++
        Dbg.add("AI: no progress for a while, recovering ($aiStuck)")
        stopEngine()
        h.removeCallbacksAndMessages(null)
        if (aiStuck >= 3) {
            aiStuck = 0
            if (Cfg.get(c, "rejoin") == 1) { beginRejoin("AI: stuck for a long time"); return }
        }
        Hook.send(c, "AI recovery", "No progress, closing panels and casting again (try $aiStuck).", 0xFFB84D)
        tapPoint("dismiss")
        h.postDelayed({ tapPoint("dismiss") }, 350L)
        phase = 0
        roi = null
        failCasts = 0
        progressT = SystemClock.uptimeMillis()
        h.postDelayed({ dnext() }, 900L)
    }

    private fun sendStatus() {
        val c = applicationContext
        val min = maxOf((SystemClock.uptimeMillis() - Dbg.startMs) / 60000L, 0L)
        val perH = if (min > 0) catches * 60L / min else 0L
        Hook.status(c, "Status report", 0x3EA6FF, listOf(
            Triple("Uptime", "$min min", true),
            Triple("Catches", "$catches", true),
            Triple("Per hour", "$perH", true),
            Triple("Sells", "${Dbg.sells}", true),
            Triple("Failed reels", "${Dbg.fails}", true),
            Triple("Biome", Dbg.biome.ifEmpty { "-" }, true)
        ), Biome.summary())
    }

    // ---------- biome detection ----------
    private fun biomeBox(): IntArray? {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "bio1_x"), Cfg.get(c, "bio2_x"))
        val x2 = maxOf(Cfg.get(c, "bio1_x"), Cfg.get(c, "bio2_x"))
        val y1 = minOf(Cfg.get(c, "bio1_y"), Cfg.get(c, "bio2_y"))
        val y2 = maxOf(Cfg.get(c, "bio1_y"), Cfg.get(c, "bio2_y"))
        if (x2 - x1 < 20 || y2 - y1 < 8) return null
        return intArrayOf(x1, y1, x2, y2)
    }

    // A picture of the biome area: from fast capture when it is on, otherwise from one screenshot.
    // The callback always runs on the screen-reading thread (safe for text recognition).
    private fun grabBox(a: IntArray, cb: (Bitmap?) -> Unit) {
        if (Cap.active) {
            val reg = Cap.region(a[0], a[1], a[2] - a[0], a[3] - a[1], 1)
            cb(if (reg == null) null else Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888))
            return
        }
        shot { full ->
            bgOcr.execute {
                if (full == null) { cb(null); return@execute }
                var crop: Bitmap? = null
                try {
                    val x = a[0].coerceIn(0, full.width - 2)
                    val y = a[1].coerceIn(0, full.height - 2)
                    val w = minOf(a[2] - a[0], full.width - x)
                    val hh = minOf(a[3] - a[1], full.height - y)
                    crop = if (w > 4 && hh > 4) Bitmap.createBitmap(full, x, y, w, hh) else null
                } catch (e: Exception) {
                    Dbg.add("Biome picture error: " + e.message)
                }
                val keep = crop?.copy(Bitmap.Config.ARGB_8888, false)
                if (crop != null && crop !== full) crop.recycle()
                full.recycle()
                cb(keep)
            }
        }
    }

    // Reads the biome name with several versions of the picture and returns what was read and which biome it is.
    private fun readBiome(bmp: Bitmap): Pair<String, Biome.Info?> {
        val vs = Biome.variants(bmp)
        var firstText = ""
        var hit: Biome.Info? = null
        try {
            for (v in vs) {
                val t = Ocr.read(v) ?: continue
                if (firstText.isEmpty()) firstText = t.replace("\n", " ").trim()
                val b = Biome.match(t)
                if (b != null) { hit = b; firstText = t.replace("\n", " ").trim(); break }
            }
        } finally {
            for (v in vs) if (v !== bmp) v.recycle()
        }
        return Pair(firstText, hit)
    }

    // Runs every 0.4 s. A normal read happens every N seconds, but right after a possible change the second
    // (confirming) read follows almost at once, so the alert goes out within about a second.
    private fun biomeTick() {
        val c = applicationContext
        if (Cfg.get(c, "bio") != 1) return
        val now = SystemClock.uptimeMillis()
        if (bioBusy && now - bioStartAt > 15000L) {
            Dbg.add("Biome watch: a read hung, resetting it")
            bioBusy = false
        }
        if (bioBusy) return
        if (!(Cap.active || Build.VERSION.SDK_INT >= 30)) return
        val gap = if (bioPend.isNotEmpty()) 250L else maxOf(Cfg.get(c, "biosec"), 1) * 1000L
        if (now - bioLast < gap) return
        if (!atRoblox()) return
        biomeWatch()
    }

    private fun biomeWatch() {
        val c = applicationContext
        val a = biomeBox()
        if (a == null) {
            if (!bioWarned) {
                bioWarned = true
                Dbg.add("Biome watch: set the biome area first (Panel > Settings > Set biome area)")
            }
            return
        }
        bioLast = SystemClock.uptimeMillis()
        bioStartAt = bioLast
        bioBusy = true
        bgOcr.execute {
            try {
                grabBox(a) { bmp ->
                    try {
                        if (bmp != null) {
                            val res = readBiome(bmp)
                            bmp.recycle()
                            biomeResult(res.second)
                        }
                    } catch (e: Throwable) {
                        Dbg.add("Biome watch error: " + e.message)
                    } finally {
                        bioBusy = false
                    }
                }
            } catch (e: Throwable) {
                Dbg.add("Biome watch error: " + e.message)
                bioBusy = false
            }
        }
    }

    // A change must be read twice in a row before it counts (one bad read cannot send a message).
    private fun biomeResult(hit: Biome.Info?) {
        val c = applicationContext
        if (hit == null) return
        val cur = Biome.cur
        if (cur != null && hit.key == cur.key) { bioPend = ""; return }
        val nowU = SystemClock.uptimeMillis()
        if (hit.key != bioPend || nowU - bioPendAt > 8000L) { bioPend = hit.key; bioPendAt = nowU; return }
        bioPend = ""
        val now = System.currentTimeMillis()
        if (cur != null) {
            val d = now - Biome.sinceMs
            Biome.addTime(cur.key, d)
            if (Cfg.get(c, "bioend") == 1 && cur.key != "normal" && Biome.mode(c, cur.key) > 0) Hook.biome(c, cur, false, d, false)
        }
        Biome.cur = hit
        Biome.sinceMs = now
        Biome.count(hit.key)
        Dbg.biome = hit.title
        Dbg.add("Biome: " + hit.title)
        val mode = Biome.mode(c, hit.key)
        if (mode > 0) Hook.biome(c, hit, true, 0L, mode == 2)
    }

    // Test button: reads the biome area once and says what it saw, so you can check the area is right.
    fun testBiome() {
        val a = biomeBox()
        if (a == null) { toast("Set the biome area first (Panel > Settings > Set biome area)"); return }
        if (!Cap.active && Build.VERSION.SDK_INT < 30) { toast("Needs Android 11 or fast capture"); return }
        toast("Reading the biome area...")
        bgOcr.execute {
            grabBox(a) { bmp ->
                if (bmp == null) { h.post { toast("Could not take a picture of the screen") }; return@grabBox }
                try {
                    val res = readBiome(bmp)
                    bmp.recycle()
                    val raw = res.first.take(40).ifEmpty { "nothing" }
                    val msg = if (res.second != null) "Biome read: " + res.second!!.title + "  (text: " + raw + ")"
                        else "No biome name found. The area read: " + raw + ". Set the area again around only the biome name."
                    Dbg.add(msg)
                    h.post { toast(msg) }
                } catch (e: Throwable) {
                    Dbg.add("Biome test error: " + e.message)
                }
            }
        }
    }
}
