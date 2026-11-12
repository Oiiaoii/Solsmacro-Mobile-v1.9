package com.solmacro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Hook {
    // one or more webhook URLs, separated by new lines, spaces or commas
    private fun urls(c: Context): List<String> =
        Cfg.gets(c, "webhook").split(Regex("[\\s,;]+")).map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }

    // true when the text looks like a Discord webhook link (the settings screen uses it to warn about typos)
    fun looksValid(u: String): Boolean =
        Regex("^https://(www\\.|ptb\\.|canary\\.)?discord(app)?\\.com/api(/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+(\\?.*)?$").matches(u.trim().trimEnd('/'))

    // how many usable webhook links are saved right now
    fun count(c: Context): Int = urls(c).size

    // warms the guild/channel lookup so the first biome message does not wait for it
    fun prefetch(c: Context) {
        val list = urls(c)
        if (list.isEmpty()) return
        Thread { for (u in list) webhookInfo(u) }.start()
    }

    // keeps a "?thread_id=..." part of a webhook link working when we add our own parts
    private fun addQ(url: String, extra: String): String {
        val i = url.indexOf('?')
        return if (i < 0) url + "?" + extra else url + "&" + extra
    }

    private fun msgUrl(url: String, id: String): String {
        val i = url.indexOf('?')
        return if (i < 0) url + "/messages/" + id else url.substring(0, i) + "/messages/" + id + url.substring(i)
    }

    // "<@id>" for a user, "<@&id>" for a role (type & before the number in the settings). Empty if not set.
    fun mention(c: Context): String {
        val raw = Cfg.gets(c, "pingid").trim()
        val id = raw.filter { it.isDigit() }
        if (id.isEmpty()) return ""
        return if (raw.startsWith("&")) "<@&" + id + ">" else "<@" + id + ">"
    }

    fun send(c: Context, title: String, desc: String, color: Int = 0x3EA6FF, ping: Boolean = false) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        val m = if (ping) mention(c) else ""
        val content = if (m.isEmpty()) "" else "\"content\":\"" + m + "\","
        Thread {
            for (url in list) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("User-Agent", "SolMacro/1.0")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = "{\"username\":\"Sol Macro\"," + content + "\"embeds\":[{\"title\":\"" + esc(title) +
                        "\",\"description\":\"" + esc(desc) + "\",\"color\":" + color + "}]}"
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    Dbg.add("Webhook replied HTTP " + conn.responseCode)
                    conn.disconnect()
                } catch (e: Exception) {
                    Dbg.add("Webhook failed: " + e.message)
                }
            }
        }.start()
    }

    // sends a JPEG picture (periodic screenshots) to every webhook
    fun sendImage(c: Context, text: String, jpg: ByteArray) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        Thread {
            for (url in list) {
                try {
                    val b = "----solmacro" + System.currentTimeMillis()
                    val nl = "\r\n"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b)
                    conn.setRequestProperty("User-Agent", "SolMacro/1.0")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 20000
                    conn.outputStream.use { o ->
                        o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"payload_json\"" + nl +
                            "Content-Type: application/json" + nl + nl +
                            "{\"username\":\"Sol Macro\",\"content\":\"" + esc(text) + "\"}" + nl).toByteArray())
                        o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"screen.jpg\"" + nl +
                            "Content-Type: image/jpeg" + nl + nl).toByteArray())
                        o.write(jpg)
                        o.write((nl + "--" + b + "--" + nl).toByteArray())
                    }
                    Dbg.add("Screenshot webhook replied HTTP " + conn.responseCode)
                    conn.disconnect()
                } catch (e: Exception) {
                    Dbg.add("Screenshot webhook failed: " + e.message)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------------------------------
    // Biome message, built like the reference picture:
    //   ### Rainy Biome            (heading)
    //   <your private server link> (only if you set one)
    //   embed: left bar in the biome color, title "BIOME STARTED - RAINY Found", the biome icon at the right,
    //          fields Channel (channel mention), Time (Discord relative time), Message (Jump to Message link),
    //          small footer text at the bottom.
    // ---------------------------------------------------------------------------------------------
    private val infoCache = HashMap<String, Array<String>>()

    // guild and channel of a webhook (Discord returns them when the URL is opened), so Channel and Jump to Message work
    private fun webhookInfo(url: String): Array<String>? {
        synchronized(infoCache) { val k = infoCache[url]; if (k != null) return k }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "SolMacro/1.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()
            if (body.isEmpty()) null else {
                val j = JSONObject(body)
                val r = arrayOf(j.optString("guild_id", ""), j.optString("channel_id", ""))
                if (r[1].isEmpty()) null else { synchronized(infoCache) { infoCache[url] = r }; r }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun footerText(c: Context): String {
        val custom = Cfg.gets(c, "whfooter").trim()
        if (custom.isNotEmpty()) return custom
        val m = mention(c)
        return if (m.isNotEmpty()) "Message $m for any bugs" else "Brry - Sol's RNG macro"
    }

    private fun field(name: String, value: String, inline: Boolean = false): JSONObject =
        JSONObject().put("name", name).put("value", value).put("inline", inline)

    private fun dur(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "${h}h ${m}m ${sec}s" else if (m > 0) "${m}m ${sec}s" else "${sec}s"
    }

    private fun biomeEmbed(b: Biome.Info, started: Boolean, durMs: Long, channel: String, ts: Long, jump: String, footer: String, icon: Boolean): JSONObject {
        val e = JSONObject()
        e.put("title", if (started) "BIOME STARTED - " + b.title + " Found" else "BIOME ENDED - " + b.title + " Ended")
        e.put("color", b.color)
        if (icon) e.put("thumbnail", JSONObject().put("url", "attachment://biome.png"))
        val f = JSONArray()
        f.put(field("Channel", channel))
        f.put(field("Time", "<t:$ts:R>"))
        f.put(field("Message", "[Jump to Message]($jump)"))
        if (!started && durMs > 0) f.put(field("Duration", dur(durMs)))
        e.put("fields", f)
        e.put("footer", JSONObject().put("text", footer))
        return e
    }

    fun biome(c: Context, b: Biome.Info, started: Boolean, durMs: Long, ping: Boolean) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        val link = Cfg.gets(c, "pslink").trim().let { if (it.startsWith("http")) it else "" }
        val m = if (ping) mention(c) else ""
        val content = (if (m.isEmpty()) "" else m + "\n") + "### " + Biome.nice(b) + " Biome" + (if (link.isEmpty()) "" else "\n" + link)
        val footer = footerText(c)
        val icon: ByteArray? = try { c.applicationContext.assets.open("biomes/" + b.key + ".png").use { it.readBytes() } } catch (e: Exception) { null }
        Thread {
            for (url in list) {
                try {
                    val info = webhookInfo(url)
                    val ts = System.currentTimeMillis() / 1000
                    val ch = if (info != null) "<#" + info[1] + ">" else "#biome-alerts"
                    val base = if (info != null && info[0].isNotEmpty()) "https://discord.com/channels/" + info[0] + "/" + info[1] else "https://discord.com/app"
                    val p = JSONObject()
                    p.put("username", "Brry")
                    p.put("content", content)
                    p.put("embeds", JSONArray().put(biomeEmbed(b, started, durMs, ch, ts, base, footer, icon != null)))
                    if (icon != null) p.put("attachments", JSONArray().put(JSONObject().put("id", 0).put("filename", "biome.png")))
                    val resp = post(addQ(url, "wait=true"), p.toString(), icon)
                    if (resp == null || info == null || info[0].isEmpty()) continue
                    // now the message exists: point "Jump to Message" at it
                    val j = JSONObject(resp)
                    val id = j.optString("id", "")
                    if (id.isEmpty()) continue
                    val p2 = JSONObject()
                    p2.put("embeds", JSONArray().put(biomeEmbed(b, started, durMs, ch, ts, "$base/$id", footer, icon != null)))
                    val att = j.optJSONArray("attachments")?.optJSONObject(0)?.optString("id", "") ?: ""
                    if (att.isNotEmpty()) p2.put("attachments", JSONArray().put(JSONObject().put("id", att)))
                    try {
                        val code = patch(msgUrl(url, id), p2.toString())
                        if (code !in 200..299) Dbg.add("Jump link edit replied HTTP $code (the link points to the channel)")
                    } catch (e: Exception) {
                        Dbg.add("Jump link edit not possible on this phone (the link points to the channel)")
                    }
                } catch (e: Exception) {
                    Dbg.add("Biome webhook failed: " + e.message)
                }
            }
        }.start()
    }

    // a small status report (used every N minutes while the macro runs)
    fun status(c: Context, title: String, color: Int, fields: List<Triple<String, String, Boolean>>, footer: String) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        val e = JSONObject()
        e.put("title", title)
        e.put("color", color)
        val f = JSONArray()
        for (t in fields) f.put(field(t.first, t.second, t.third))
        e.put("fields", f)
        e.put("footer", JSONObject().put("text", footer))
        val p = JSONObject()
        p.put("username", "Brry")
        p.put("embeds", JSONArray().put(e))
        Thread {
            for (url in list) {
                try { post(url, p.toString(), null) } catch (ex: Exception) { Dbg.add("Status webhook failed: " + ex.message) }
            }
        }.start()
    }

    // Posts the message (with the icon picture attached when there is one). Returns the reply text when it worked.
    private fun post(url: String, json: String, png: ByteArray?): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", "SolMacro/1.0")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            if (png == null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            } else {
                val b = "----solmacro" + System.currentTimeMillis()
                val nl = "\r\n"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b)
                conn.outputStream.use { o ->
                    o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"payload_json\"" + nl +
                        "Content-Type: application/json" + nl + nl + json + nl).toByteArray())
                    o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"biome.png\"" + nl +
                        "Content-Type: image/png" + nl + nl).toByteArray())
                    o.write(png)
                    o.write((nl + "--" + b + "--" + nl).toByteArray())
                }
            }
            val code = conn.responseCode
            Dbg.add("Webhook replied HTTP $code")
            return if (code in 200..299) {
                try { conn.inputStream.bufferedReader().readText() } catch (e: Exception) { null }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    // Android's HttpURLConnection may refuse the PATCH word, so there is a fallback that sets it directly.
    private fun patch(url: String, json: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            try {
                conn.requestMethod = "PATCH"
            } catch (e: java.net.ProtocolException) {
                val f = HttpURLConnection::class.java.getDeclaredField("method")
                f.isAccessible = true
                f.set(conn, "PATCH")
                try {
                    val d = conn.javaClass.getDeclaredField("delegate")
                    d.isAccessible = true
                    val inner = d.get(conn)
                    val f2 = HttpURLConnection::class.java.getDeclaredField("method")
                    f2.isAccessible = true
                    f2.set(inner, "PATCH")
                } catch (_: Exception) {}
            }
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "SolMacro/1.0")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.outputStream.use { it.write(json.toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    // sample message so you can check the design in your channel
    fun biomeTest(c: Context) {
        val b = Biome.byKey("rainy") ?: return
        biome(c, b, true, 0L, false)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
