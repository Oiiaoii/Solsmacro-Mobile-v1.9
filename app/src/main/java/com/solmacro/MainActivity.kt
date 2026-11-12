package com.solmacro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.animation.OvershootInterpolator
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

private const val FILL = ViewGroup.LayoutParams.MATCH_PARENT
private const val HUG = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val cBg = 0xFF060608.toInt()
    private val cCard = 0xFF121216.toInt()
    private val cBd = 0xFF2A2A31.toInt()
    private val cTx = 0xFFE8EEFC.toInt()
    private val cMut = 0xFF9A9AA5.toInt()
    private val cAcc = 0xFFEDEDF0.toInt()
    private val cOk = 0xFF38D39F.toInt()
    private val cBad = 0xFFFF6B6B.toInt()
    private val cWarn = 0xFFFFB84D.toInt()
    private val cTile = 0xFF1A1A21.toInt()
    private val cSec = 0xFF1C1C23.toInt()
    private val cLine = 0xFF34343D.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var pill: TextView
    private lateinit var pillBox: LinearLayout
    private lateinit var pillDot: ImageView
    private lateinit var svcBtn: View
    private lateinit var stateTv: TextView
    private lateinit var tCatch: TextView
    private lateinit var tFail: TextView
    private lateinit var tSell: TextView
    private lateinit var tRun: TextView
    private lateinit var kCap: TextView
    private lateinit var kTaps: TextView
    private lateinit var kChecks: TextView
    private lateinit var kLat: TextView
    private lateinit var kBite: TextView
    private lateinit var kAim: TextView
    private lateinit var posStatus: TextView
    private val posVals = HashMap<String, TextView>()
    private lateinit var logo: ImageView
    private lateinit var prevIcon: ImageView
    private var cardN = 0
    private var autoReturn = false
    private var logoAnim: ObjectAnimator? = null
    private lateinit var logTv: TextView

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    // The text never grows with the phone's font size setting, so nothing gets cranked or cut off.
    override fun attachBaseContext(base: Context) {
        val cfg = android.content.res.Configuration(base.resources.configuration)
        if (cfg.fontScale > 1.0f) cfg.fontScale = 1.0f
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int = cTx, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.12f)
    }

    private fun ic(res: Int, sizeDp: Int, tint: Int = cTx) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun TextView.setIfChanged(s: String) {
        if (text.toString() != s) text = s
    }

    private fun add(p: LinearLayout, v: View, top: Int = 8) {
        p.addView(v, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(top) })
    }

    private fun card(title: String?, icon: Int = 0): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cCard, 18, cBd)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        add(root, c, 12)
        cardN++
        if (title != null) {
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != 0) {
                val chip = FrameLayout(this).apply { background = shape(0xFF1E1E26.toInt(), 10) }
                chip.addView(ic(icon, 18, cOk), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                h.addView(chip, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) })
            }
            h.addView(tv(title, 16f, cTx, true), LinearLayout.LayoutParams(0, HUG, 1f))
            c.addView(h, LinearLayout.LayoutParams(FILL, HUG))
        }
        return c
    }

    // a real button: icon + text, never cut off, 48dp tall at least
    private fun btn(t: String, color: Int, icon: Int = 0, onClick: () -> Unit): LinearLayout {
        val bg = if (color == cBd) cSec else color
        val fg = if (color == cOk) 0xFF06251C.toInt() else if (color == cAcc) 0xFF0A0A0C.toInt() else cTx
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = shape(bg, 14, if (color == cBd) cLine else 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
        }
        if (icon != 0) b.addView(ic(icon, 18, fg), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
        val label = tv(t, 14f, fg, true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        b.addView(label, LinearLayout.LayoutParams(HUG, HUG))
        b.tag = label
        b.setOnClickListener { onClick() }
        press(b)
        return b
    }

    private fun row(p: LinearLayout, vararg b: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, x) in b.withIndex()) {
            r.addView(x, LinearLayout.LayoutParams(0, FILL, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        add(p, r, 12)
    }

    private fun styleEdit(e: EditText) {
        e.setTextColor(cTx)
        e.setHintTextColor(0xFF6C6C77.toInt())
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), shape(cBg, 12, cOk))
        sl.addState(intArrayOf(), shape(cBg, 12, cBd))
        e.background = sl
        e.setPadding(dp(14), dp(11), dp(14), dp(11))
    }

    private fun field(p: LinearLayout, label: String, key: String) {
        add(p, tv(label, 12f, cMut), 12)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Cfg.get(this@MainActivity, key).toString())
            setSelectAllOnFocus(true)
            styleEdit(this)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    Cfg.put(this@MainActivity, key, s.toString().toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    // one small tile: icon, label, big number
    private fun tile(p: LinearLayout, icon: Int, tint: Int, label: String, first: Boolean): TextView {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(cTile, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        t.addView(ic(icon, 22, tint), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 11f, cMut))
        val v = tv("0", 18f, cTx, true)
        col.addView(v)
        t.addView(col, LinearLayout.LayoutParams(0, HUG, 1f))
        p.addView(t, LinearLayout.LayoutParams(0, HUG, 1f).apply { if (!first) leftMargin = dp(8) })
        return v
    }

    // small label over value, two per line
    private fun kv(p: LinearLayout, label: String, first: Boolean): TextView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 11f, cMut))
        val v = tv("-", 13f, cTx, true)
        col.addView(v)
        p.addView(col, LinearLayout.LayoutParams(0, HUG, 1f).apply { if (!first) leftMargin = dp(8) })
        return v
    }

    private fun hrow(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun step(p: LinearLayout, n: Int, text: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val badge = tv(n.toString(), 12f, 0xFF0A0A0C.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(cAcc) }
        }
        r.addView(badge, LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(1); rightMargin = dp(10) })
        r.addView(tv(text, 13f, cMut), LinearLayout.LayoutParams(0, HUG, 1f))
        add(p, r, 10)
    }

    private fun bump(t: TextView, text: String) {
        if (t.text.toString() == text) return
        t.text = text
        t.scaleX = 1.12f
        t.scaleY = 1.12f
        t.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }

    // text fields that edit the same saved value (webhook links, private server link) stay in step with each other,
    // and a small line under the field says whether the text looks right
    private val linked = HashMap<String, MutableList<EditText>>()

    private fun webhookCheck(s: String): String {
        val parts = s.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val bad = parts.count { !Hook.looksValid(it) }
        return if (bad == 0) "OK: " + parts.size + " webhook link" + (if (parts.size > 1) "s" else "") + " saved"
        else "$bad link(s) do not look like a Discord webhook (https://discord.com/api/webhooks/ID/TOKEN)"
    }

    private fun serverLinkCheck(s: String): String {
        if (s.isEmpty()) return ""
        return if (s.startsWith("https://www.roblox.com/") || s.startsWith("https://roblox.com/") || s.startsWith("https://ro.blox.com/"))
            "OK: link saved"
        else "This does not look like a Roblox private server link (it starts with https://www.roblox.com/)"
    }

    private fun linkField(p: LinearLayout, label: String, key: String, hintText: String, multi: Boolean, check: (String) -> String) {
        add(p, tv(label, 12f, cMut), 12)
        val status = tv("", 11f, cMut)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            setText(Cfg.gets(this@MainActivity, key))
            hint = hintText
            styleEdit(this)
        }
        val list = linked.getOrPut(key) { ArrayList() }
        list.add(e)
        fun refresh(t: String) {
            val m = check(t)
            status.text = m
            status.setTextColor(if (m.startsWith("OK")) cOk else if (m.isEmpty()) cMut else cBad)
        }
        refresh(e.text.toString().trim())
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s.toString().trim()
                Cfg.puts(this@MainActivity, key, t)
                refresh(t)
                for (o in list) if (o !== e && o.text.toString().trim() != t) o.setText(t)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // when you leave the webhook field, look up its channel now so the first biome alert is not delayed
        e.setOnFocusChangeListener { _, has -> if (!has && key == "webhook") Hook.prefetch(this@MainActivity) }
        add(p, e, 4)
        add(p, status, 2)
    }

    private fun textField(p: LinearLayout, label: String, key: String, hintText: String = "") {
        add(p, tv(label, 12f, cMut), 12)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Cfg.gets(this@MainActivity, key))
            hint = hintText
            styleEdit(this)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Cfg.puts(this@MainActivity, key, s.toString().trim()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun sw(p: LinearLayout, label: String, key: String) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        val s = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(cOk, 0xFFB4B4BE.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x6638D39F, 0xFF2E2E36.toInt()))
            isChecked = Cfg.get(this@MainActivity, key) == 1
            setOnCheckedChangeListener { _, on -> Cfg.put(this@MainActivity, key, if (on) 1 else 0) }
        }
        r.addView(tv(label, 14f, cTx), LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(12) })
        r.addView(s, LinearLayout.LayoutParams(HUG, HUG))
        r.setOnClickListener { s.toggle() }
        add(p, r, 6)
    }

    private fun roundIcon(sizeDp: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0xFF000000.toInt()) }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(10) }
    }

    private fun applyIcon(v: ImageView) {
        val f = File(filesDir, "float_icon.png")
        if (Cfg.get(this, "icon_custom") == 1 && f.exists()) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    private fun pickIcon() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, 101)
    }

    private fun resetIcon() {
        Cfg.put(this, "icon_custom", 0)
        applyIcon(prevIcon)
        applyIcon(logo)
        MacroService.inst?.refreshIcon()
    }

    private fun saveIcon(uri: android.net.Uri) {
        try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            val o2 = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(opt.outWidth, opt.outHeight) / 1024) }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: throw Exception("no image")
            val side = minOf(bmp.width, bmp.height)
            val sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = android.graphics.Bitmap.createScaledBitmap(sq, 192, 192, true)
            FileOutputStream(File(filesDir, "float_icon.png")).use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Cfg.put(this, "icon_custom", 1)
            applyIcon(prevIcon)
            applyIcon(logo)
            MacroService.inst?.refreshIcon()
            Toast.makeText(this, "Icon updated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not use that image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun script(p: LinearLayout, label: String, key: String) {
        add(p, tv(label, 12f, cMut), 12)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            setText(Cfg.gets(this@MainActivity, key))
            styleEdit(this)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Cfg.puts(this@MainActivity, key, s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 101 && res == RESULT_OK && data?.data != null) saveIcon(data.data!!)
        if (req == 100) {
            if (res == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                Toast.makeText(this, "Fast capture starting", Toast.LENGTH_SHORT).show()
                if (autoReturn) {
                    autoReturn = false
                    // go back to Roblox so you can press Start right away
                    handler.postDelayed({
                        val i2 = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
                        if (i2 != null) startActivity(i2)
                    }, 900L)
                }
            } else {
                autoReturn = false
                Toast.makeText(this, "Permission not given", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun preset(cast: Int, wait: Int, taps: Int, gap: Int) {
        Cfg.put(this, "cast", cast); Cfg.put(this, "wait", wait); Cfg.put(this, "taps", taps); Cfg.put(this, "gap", gap)
        recreate()
    }

    // ---------- left menu ----------
    private val secNames = listOf("Home", "Fishing", "Detection", "Scripts", "Watchers", "Discord", "Rejoin", "Look", "Log")
    private val secIcons = listOf(R.drawable.ic_home, R.drawable.ic_fish, R.drawable.ic_robot, R.drawable.ic_compass,
        R.drawable.ic_eye, R.drawable.ic_chat, R.drawable.ic_repeat, R.drawable.ic_palette, R.drawable.ic_log)
    private val menuIcons = ArrayList<ImageView>()
    private val menuTints = IntArray(9) { 0xFFE8EEFC.toInt() }
    private val pages = ArrayList<LinearLayout>()
    private val scrolls = ArrayList<ScrollView>()
    private val menuRows = ArrayList<LinearLayout>()
    private val menuLabels = ArrayList<TextView>()
    private var curSec = -1
    private var expanded = true
    private lateinit var menuScroll: ScrollView
    private lateinit var indicator: View
    private lateinit var burger: ImageView
    private lateinit var secTv: TextView
    private lateinit var monTv: TextView
    private lateinit var meter: View

    private lateinit var bioTv: TextView

    // one tappable chip per biome: off -> message -> message and ping
    private fun biomeChips(p: LinearLayout) {
        var rowL: LinearLayout? = null
        for ((i, b) in Biome.all.withIndex()) {
            if (i % 2 == 0) {
                rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                add(p, rowL, 6)
            }
            val chip = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            fun paint() {
                val md = Biome.mode(this, b.key)
                chip.text = Biome.nice(b) + "\n" + (if (md == 0) "off" else if (md == 1) "message" else "message + ping")
                if (md == 0) { chip.background = shape(cBg, 12, cBd); chip.setTextColor(cMut) }
                else if (md == 1) { chip.background = shape(cBg, 12, cOk); chip.setTextColor(cTx) }
                else { chip.background = shape(cAcc, 12); chip.setTextColor(0xFF0A0A0C.toInt()) }
            }
            paint()
            chip.setOnClickListener {
                Biome.setMode(this, b.key, (Biome.mode(this, b.key) + 1) % 3)
                paint()
            }
            press(chip)
            rowL!!.addView(chip, LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(6) })
        }
    }

    private val rowH get() = dp(48)
    private val rowGap get() = dp(4)

    private fun rowTop(i: Int) = dp(4) + rowGap + i * (rowH + rowGap)

    private fun useSection(i: Int) { root = pages[i] }

    private fun press(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun buildShell(): View {
        expanded = Cfg.get(this, "menu_open") == 1
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(10), dp(14), dp(10))
        }
        burger = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu)
            setColorFilter(cTx)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            rotation = if (expanded) 0f else 90f
            setOnClickListener { toggleMenu() }
        }
        press(burger)
        head.addView(burger, LinearLayout.LayoutParams(dp(48), dp(48)))
        logo = roundIcon(44)
        logo.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        applyIcon(logo)
        head.addView(logo)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("Brry", 24f, cTx, true))
        secTv = tv("", 12f, cMut)
        titles.addView(secTv)
        head.addView(titles, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(10) })
        outer.addView(head, LinearLayout.LayoutParams(FILL, HUG))
        val rule = View(this).apply { setBackgroundColor(0xFF17171D.toInt()) }
        outer.addView(rule, LinearLayout.LayoutParams(FILL, dp(1)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // menu
        val box = FrameLayout(this).apply { setBackgroundColor(0xFF09090C.toInt()) }
        indicator = View(this).apply { background = shape(cAcc, 14) }
        box.addView(indicator, FrameLayout.LayoutParams(FILL, rowH).apply { leftMargin = dp(6); rightMargin = dp(6) })
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(8)) }
        for (i in secNames.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(6), 0)
                setOnClickListener { showSection(i) }
            }
            val icon = ic(secIcons[i], 22, menuTints[i])
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val lab = tv(secNames[i], 13f, cTx, true).apply {
                maxLines = 1
                visibility = if (expanded) View.VISIBLE else View.GONE
            }
            row.addView(lab, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(12) })
            press(row)
            menu.addView(row, LinearLayout.LayoutParams(FILL, rowH).apply { topMargin = rowGap })
            menuRows.add(row)
            menuLabels.add(lab)
            menuIcons.add(icon)
        }
        box.addView(menu, FrameLayout.LayoutParams(FILL, HUG))
        menuScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box, FrameLayout.LayoutParams(FILL, HUG)) }
        body.addView(menuScroll, LinearLayout.LayoutParams(dp(if (expanded) 140 else 64), FILL))
        // pages
        val content = FrameLayout(this)
        for (i in secNames.indices) {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(0), dp(14), dp(40))
            }
            val sv = ScrollView(this).apply { visibility = View.GONE; isVerticalScrollBarEnabled = false; addView(page) }
            pages.add(page)
            scrolls.add(sv)
            content.addView(sv, FrameLayout.LayoutParams(FILL, FILL))
        }
        body.addView(content, LinearLayout.LayoutParams(0, FILL, 1f))
        outer.addView(body, LinearLayout.LayoutParams(FILL, 0, 1f))
        return outer
    }

    private fun toggleMenu() {
        expanded = !expanded
        Cfg.put(this, "menu_open", if (expanded) 1 else 0)
        val from = menuScroll.width
        val to = dp(if (expanded) 140 else 64)
        if (expanded) {
            for (l in menuLabels) { l.visibility = View.VISIBLE; l.alpha = 0f; l.animate().alpha(1f).setStartDelay(120).setDuration(220).start() }
        } else {
            for (l in menuLabels) l.animate().alpha(0f).setStartDelay(0).setDuration(120).withEndAction { if (!expanded) l.visibility = View.GONE }.start()
        }
        ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val lp = menuScroll.layoutParams
                lp.width = it.animatedValue as Int
                menuScroll.layoutParams = lp
            }
            start()
        }
        burger.animate().rotation(if (expanded) 0f else 90f).setDuration(280).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun moveIndicator(i: Int, animate: Boolean) {
        val y = rowTop(i).toFloat()
        indicator.animate().cancel()
        if (animate) indicator.animate().translationY(y).setDuration(340).setInterpolator(OvershootInterpolator(1.5f)).start()
        else indicator.translationY = y
    }

    private fun stagger(page: LinearLayout) {
        for (j in 0 until page.childCount) {
            val v = page.getChildAt(j)
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(j * 55L).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun showSection(i: Int) {
        if (i == curSec) return
        val prev = curSec
        curSec = i
        for (k in scrolls.indices) {
            val sv = scrolls[k]
            sv.animate().cancel()
            if (k == i) {
                sv.visibility = View.VISIBLE
                sv.scrollTo(0, 0)
                if (prev >= 0) {
                    sv.alpha = 0f
                    sv.translationX = dp(if (i > prev) 30 else -30).toFloat()
                    sv.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
                } else {
                    sv.alpha = 1f
                    sv.translationX = 0f
                }
                stagger(pages[k])
            } else {
                sv.visibility = View.GONE
                sv.alpha = 1f
                sv.translationX = 0f
            }
        }
        for (k in menuLabels.indices) {
            val to = if (k == i) 0xFF0A0A0C.toInt() else cTx
            val l = menuLabels[k]
            val from = l.currentTextColor
            if (from != to) ObjectAnimator.ofArgb(l, "textColor", from, to).apply { duration = 240; start() }
            menuRows[k].animate().translationX(if (k == i) dp(2).toFloat() else 0f).setDuration(240).start()
            val ico = menuIcons[k]
            val fromT = menuTints[k]
            if (fromT != to) {
                ValueAnimator.ofArgb(fromT, to).apply {
                    duration = 240
                    addUpdateListener { ico.setColorFilter(it.animatedValue as Int) }
                    start()
                }
                menuTints[k] = to
            }
        }
        moveIndicator(i, prev >= 0)
        bump(secTv, secNames[i])
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // the bars at the top and bottom are black like the app (no blue bar)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        var flags = window.decorView.systemUiVisibility
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = flags

        val shell = buildShell()
        useSection(0)
        add(root, tv("Fishing and selling helper for Sol’s RNG. Not affiliated with Roblox. Macros can break game rules and get accounts banned.", 12f, cMut), 4)

        val st = card(null)
        pillBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
        }
        pillDot = ic(R.drawable.ic_dot, 14, cBad)
        pillBox.addView(pillDot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(6) })
        pill = tv("", 13f, cBad, true)
        pillBox.addView(pill, LinearLayout.LayoutParams(HUG, HUG))
        st.addView(pillBox, LinearLayout.LayoutParams(HUG, HUG))
        add(st, tv("State", 11f, cMut), 12)
        stateTv = tv("", 18f, cTx, true)
        add(st, stateTv, 2)

        val r1 = hrow()
        tCatch = tile(r1, R.drawable.ic_fish, cOk, "Catches", true)
        tFail = tile(r1, R.drawable.ic_cancel, cBad, "Failed", false)
        add(st, r1, 12)
        val r2 = hrow()
        tSell = tile(r2, R.drawable.ic_tag, cWarn, "Sells", true)
        tRun = tile(r2, R.drawable.ic_timer, cAcc, "Run time", false)
        add(st, r2, 8)

        val k1 = hrow()
        kCap = kv(k1, "Capture", true)
        kTaps = kv(k1, "Taps ok / cancelled", false)
        add(st, k1, 14)
        val k2 = hrow()
        kChecks = kv(k2, "Checks per second", true)
        kLat = kv(k2, "Latency", false)
        add(st, k2, 10)
        val k3 = hrow()
        kBite = kv(k3, "Bite time", true)
        kAim = kv(k3, "AI aim", false)
        add(st, k3, 10)

        svcBtn = btn("Turn on service", cAcc, R.drawable.ic_power) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        row(st,
            svcBtn,
            btn("Turn off service", cBd, R.drawable.ic_stop) {
                val s = MacroService.inst
                if (s != null) s.turnOff() else Toast.makeText(this, "The service is already off", Toast.LENGTH_SHORT).show()
            })
        add(st, btn("Open Roblox", cBd, R.drawable.ic_open) {
            val i = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
            if (i != null) startActivity(i) else Toast.makeText(this, "Roblox is not installed", Toast.LENGTH_SHORT).show()
        }, 8)

        val pc = card("Button positions", R.drawable.ic_target)
        posStatus = tv("", 12f, cMut)
        add(pc, posStatus, 8)
        for (k in Cfg.keys) {
            val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pr.addView(tv(Cfg.label[k] ?: k, 12f, cMut), LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(10) })
            val v = tv("", 12f, cTx, true)
            pr.addView(v, LinearLayout.LayoutParams(HUG, HUG))
            add(pc, pr, 8)
            posVals[k] = v
        }

        val how = card("How to use", R.drawable.ic_compass)
        step(how, 1, "Turn on the service, then open Roblox. A round Brry button appears.")
        step(how, 2, "Tap it to open the panel and tap it again to close it.")
        step(how, 3, "Panel > Settings > Set button positions: tap the 9 spots it asks for (the last one is the X on the caught or failed panel).")
        step(how, 4, "Optional: start fast capture (Detection page) for the best speed. Android asks for permission. You can also press Start without it.")
        step(how, 5, "Press the big play button or the Toggle to start switch under it. If Start is refused, the panel shows what is missing and you can tap the red row to fix it. Use Test detection while the reeling bar shows.")

        useSection(1)
        val fish = card("Fishing", R.drawable.ic_fish)
        field(fish, "Cast hold (ms)", "cast")
        field(fish, "Stop after N catches (0 = never)", "maxcatch")

        useSection(2)
        val mon = card("Live detection", R.drawable.ic_speed)
        monTv = tv("", 12f, cTx).apply { typeface = Typeface.MONOSPACE; background = shape(cBg, 12, cBd); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        add(mon, monTv, 8)
        add(mon, tv("Zone match", 11f, cMut), 10)
        val track = FrameLayout(this).apply { background = shape(cBg, 6, cBd) }
        meter = View(this).apply { background = shape(cOk, 6); pivotX = 0f; scaleX = 0f }
        track.addView(meter, FrameLayout.LayoutParams(FILL, dp(12)))
        add(mon, track, 4)
        add(mon, tv("Updates live while the macro runs. To test: open Roblox with the reeling bar showing and use Test detection in the Brry panel (Settings).", 11f, cMut), 8)
        val det = card("Detection (whole screen)", R.drawable.ic_robot)
        sw(det, "AI Autopilot (learns the best aim, recovers when stuck)", "ai")
        add(det, btn("Reset what the AI learned", cBd, R.drawable.ic_trash) {
            Ai.reset(this)
            Toast.makeText(this, "AI memory cleared", Toast.LENGTH_SHORT).show()
        }, 8)
        field(det, "AI: seconds without progress before it recovers", "stuck")
        field(det, "Tap press length (ms)", "taphold")
        sw(det, "Debug overlay (green, yellow, red box)", "debug")
        field(det, "Minimum bar size (pixels)", "minpx")
        field(det, "Max seconds to wait for the bar", "maxwait")
        field(det, "Time between reel taps (ms)", "tapms")
        field(det, "Close button X (caught or failed panel)", "dismiss_x")
        field(det, "Close button Y (caught or failed panel)", "dismiss_y")
        sw(det, "Learn the real drain speed while reeling", "learn")
        sw(det, "Widen the color match after a missed bar", "widen")
        add(det, tv("Timing", 14f, cTx, true), 14)
        field(det, "Result panel wait (ms)", "resultms")
        field(det, "Wait after closing the result (ms)", "dismisswait")
        sw(det, "Adaptive recast time (learns how long bites take)", "adapt")
        field(det, "Wait after casting before looking (ms)", "castdelay")
        field(det, "Bar counts as finished after (ms)", "gonems")
        field(det, "Pause after a catch (ms)", "postcatch")

        useSection(7)
        val fl = card("Floating button", R.drawable.ic_image)
        add(fl, tv("A round button stays on screen over Roblox. Tap it to open the control bar and tap it again to close it. Drag it to move it.", 12f, cMut), 6)
        prevIcon = roundIcon(72)
        applyIcon(prevIcon)
        fl.addView(prevIcon)
        row(fl, btn("Pick an icon", cAcc, R.drawable.ic_image) { pickIcon() }, btn("Default icon", cBd, R.drawable.ic_repeat) { resetIcon() })

        useSection(2)
        val cap = card("Fast screen capture (optional)", R.drawable.ic_play)
        add(cap, tv("Optional. Without it Brry uses screenshots (about 3 a second) and predicts the marker between them. With it the screen is read about 30 times a second. Android asks for permission each time you start it.", 12f, cMut), 6)
        add(cap, btn("Start fast capture", cOk, R.drawable.ic_play) { requestCapture() }, 12)
        add(cap, btn("Stop fast capture", cBd, R.drawable.ic_stop) { Cap.release(); stopService(Intent(this, CaptureService::class.java)) }, 8)

        useSection(3)
        val mv = card("Movement, camera and Shift Lock", R.drawable.ic_compass)
        add(mv, tv("Scripts run with swipes on Roblox's on-screen joystick and camera area. One command per line:\nmove up 1.5   (also down, left, right, w, a, s, d)\ncamera left 250\nshiftlock\ntap inv\nwait 1\n# comment", 12f, cMut), 6)
        field(mv, "Joystick push distance (pixels)", "joyr")
        script(mv, "Run once when you press START", "script_start")
        script(mv, "Run before selling (walk to the seller)", "script_pre")
        script(mv, "Run after selling (walk back)", "script_post")

        useSection(1)
        val sell = card("Selling", R.drawable.ic_tag)
        sw(sell, "Auto sell", "sell")
        field(sell, "Sell every N catches", "every")
        field(sell, "Menu delay (ms)", "mdelay")

        useSection(5)
        val wh = card("Discord webhook", R.drawable.ic_send)
        sw(wh, "Send webhook messages", "whon")
        linkField(wh, "Webhook URLs (one per line, you can add several channels)", "webhook",
            "https://discord.com/api/webhooks/...", true) { webhookCheck(it) }
        field(wh, "Progress message every N catches (0 = off)", "whevery")
        add(wh, btn("Send test message", cAcc, R.drawable.ic_send) {
            Hook.send(this, "Test", "Sol Macro webhook works", 0x38D39F)
            Toast.makeText(this, "Sent, check the log below", Toast.LENGTH_SHORT).show()
        }, 12)

        val ba = card("Biome alerts", R.drawable.ic_chat)
        add(ba, tv("Pick which biomes send a Discord message. The message copies the SolsScope biome alert: a heading, your private server link (Rejoin settings), then an embed with the biome icon, Channel, Time, Jump to Message and a small footer.", 12f, cMut), 4)
        linkField(ba, "Webhook URL for biome alerts (same list as the Discord webhook card)", "webhook",
            "https://discord.com/api/webhooks/...", true) { webhookCheck(it) }
        linkField(ba, "Private server link (shown under the biome heading)", "pslink",
            "https://www.roblox.com/share?code=...&type=Server", false) { serverLinkCheck(it) }
        biomeChips(ba)
        sw(ba, "Also send a message when a biome ends", "bioend")
        textField(ba, "Footer text (empty = Message <@your ID> for any bugs)", "whfooter", "")
        add(ba, btn("Send test biome message", cAcc, R.drawable.ic_send) {
            Hook.biomeTest(this)
            Toast.makeText(this, "Sent a sample RAINY message, check your channel", Toast.LENGTH_SHORT).show()
        }, 12)
        sw(ba, "Status report to the webhooks", "statuson")
        field(ba, "Status report every N minutes (minimum 5)", "statusmin")

        useSection(4)
        val ex = card("Anti-kick and screenshots", R.drawable.ic_gear)
        sw(ex, "Anti-kick (small camera move when nothing was tapped)", "afk")
        field(ex, "Anti-kick after N minutes without taps", "afkmin")
        sw(ex, "Send a screenshot to the webhooks (needs fast capture)", "shoton")
        field(ex, "Screenshot every N minutes", "shotmin")

        useSection(4)
        val bw = card("Biome watch (reads the screen text)", R.drawable.ic_eye)
        add(bw, tv("Reads the biome name from the screen and sends the alert you set up in Discord > Biome alerts when it changes. Works with or without fast capture (without it Android 11 or newer is needed). Set it up in the Brry panel while Roblox is open: Settings > Set biome area, tap the top-left and bottom-right corner of the biome name, then Test biome read to see what it reads.", 12f, cMut), 4)
        sw(bw, "Biome watch", "bio")
        field(bw, "Check every N seconds (minimum 1, lower = faster alerts)", "biosec")
        bioTv = tv("", 12f, cTx)
        add(bw, bioTv, 8)

        useSection(6)
        val rj = card("Auto rejoin", R.drawable.ic_repeat)
        add(rj, tv("If Roblox closes, crashes or shows a disconnect screen, the macro opens Roblox again (and your private server link if you add one) and carries on. Needs fast capture for the disconnect screen check.", 12f, cMut), 6)
        sw(rj, "Auto rejoin", "rejoin")
        linkField(rj, "Private server link (optional)", "pslink",
            "https://www.roblox.com/games/...?privateServerLinkCode=...", false) { serverLinkCheck(it) }
        field(rj, "Seconds to wait for Roblox to load", "rejoinsec")

        useSection(4)
        val ww = card("Word watch (merchant, auras, anything)", R.drawable.ic_eye)
        add(ww, tv("Reads the screen text and sends a webhook message with a picture when one of your words shows up, for example Mari, Jester or an aura name. Set the area in the panel: Settings > Set watch area (leave it unset to read the whole screen, slower). Reading can be wrong. The same word is only reported once every 5 minutes.", 12f, cMut), 6)
        sw(ww, "Word watch", "watch")
        textField(ww, "Words (separated by commas)", "watchwords", "mari, jester")
        field(ww, "Check every N seconds (minimum 3)", "watchsec")
        sw(ww, "Ping me when a word is seen", "pingwatch")
        sw(ww, "Ping me on rare biomes (needs biome watch)", "pingrare")
        textField(ww, "Discord user ID to ping (type & before a role ID)", "pingid", "123456789012345678")

        useSection(3)
        val tk = card("Timed tasks (auto craft, buy, biome items)", R.drawable.ic_timer)
        add(tk, tv("Runs your script every N minutes between casts. Use the same commands as above, plus: tap p1 (extra points P1 to P6, set in the panel: Settings > Set extra points) or tap 540 1200 (screen pixels). Example for a craft: tap p1, wait 1, tap p2, wait 1.", 12f, cMut), 6)
        for (n in 1..3) {
            sw(tk, "Task $n on", "t" + n + "on")
            field(tk, "Task $n every N minutes", "t" + n + "min")
            script(tk, "Task $n script", "t" + n + "s")
        }

        useSection(8)
        val lg = card("Log", R.drawable.ic_log)
        logTv = tv("", 11f, cMut).apply { typeface = Typeface.MONOSPACE; background = shape(cBg, 12, cBd); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        add(lg, logTv, 8)
        add(lg, btn("Copy log", cBd, R.drawable.ic_copy) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Sol Macro log", Dbg.tail(150)))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }, 10)

        if (intent.getBooleanExtra("capture", false)) {
            autoReturn = true
            requestCapture()
        }
        setContentView(shell)
        showSection(0)
    }

    // the panel asked for fast capture while this screen was already open
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra("capture", false)) {
            autoReturn = true
            requestCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        logoAnim?.cancel()
        logoAnim = ObjectAnimator.ofFloat(logo, "rotation", -4f, 4f).apply {
            duration = 2400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        logoAnim?.cancel()
        logoAnim = null
    }

    private fun refresh() {
        val on = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("$packageName/")
        bump(pill, if (on) "Service ON" else "Service OFF")
        pillBox.background = shape(if (on) 0xFF10352A.toInt() else 0xFF3A1A1E.toInt(), 20, if (on) 0x6638D39F else 0x66FF6B6B)
        pill.setTextColor(if (on) cOk else cBad)
        pillDot.setColorFilter(if (on) cOk else cBad)
        bump(stateTv, Dbg.state)
        val run = if (Dbg.startMs > 0 && Dbg.state != "Idle") (SystemClock.uptimeMillis() - Dbg.startMs) / 1000 else 0L
        bump(tCatch, Dbg.catches.toString())
        bump(tFail, Dbg.fails.toString())
        bump(tSell, Dbg.sells.toString())
        tRun.setIfChanged("${run / 60}:${(run % 60).toString().padStart(2, '0')}")
        kCap.setIfChanged(if (Cap.active) "Fast capture" else "Screenshots")
        kTaps.setIfChanged("${Dbg.tapOk} / ${Dbg.tapCancel}")
        kChecks.setIfChanged(Dbg.fps.toString())
        kLat.setIfChanged("${Dbg.latency} ms")
        kBite.setIfChanged("${Dbg.biteMs} ms")
        kAim.setIfChanged(Dbg.aiBias)
        val posOk = Cfg.get(this, "pts_ok") == 1
        posStatus.setIfChanged(if (posOk) "All 9 positions are saved." else "Not set yet. Open Roblox, tap the Brry button, then Settings > Set button positions. The numbers below are only defaults.")
        posStatus.setTextColor(if (posOk) cOk else cWarn)
        for (k in Cfg.keys) posVals[k]?.setIfChanged("${Cfg.get(this, k + "_x")}, ${Cfg.get(this, k + "_y")}")
        logTv.setIfChanged(Dbg.tail(25).ifEmpty { "No log yet" })
        if (curSec == 4) {
            bioTv.setIfChanged("Now: " + (Biome.cur?.title ?: "not read yet") + "\n" + Biome.summary())
        }
        if (curSec == 2) {
            monTv.setIfChanged("Capture   " + (if (Cap.active) "fast" else "screenshots") + "\nChecks/s  ${Dbg.fps}\nLatency   ${Dbg.latency} ms\nBar       " +
                (if (Dbg.barFound) "found" else "not seen") + "\nZone px   ${Dbg.zonePx}\nMarker px ${Dbg.markerPx}\nIn zone   ${Dbg.inZone}%\nTaps      ${Dbg.tapOk} ok, ${Dbg.tapCancel} cancelled\nAI brain  ${Dbg.aiInfo}")
            val pct = minOf(100, Dbg.zonePx * 100 / maxOf(Cfg.get(this, "minpx"), 1))
            meter.animate().scaleX(pct / 100f).setDuration(160).start()
            (meter.background as? GradientDrawable)?.setColor(if (pct >= 100) cOk else if (pct >= 50) cWarn else cBad)
        }
    }
}
