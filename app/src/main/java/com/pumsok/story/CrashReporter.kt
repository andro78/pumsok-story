package com.pumsok.story

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends why the app last died (Java exception, native crash, low-memory kill) to the bench log
 * on the next launch, with a breadcrumb of what it was doing. No audio or voice data is sent.
 */
object CrashReporter {
    private const val URL_REPORT = "https://d32.org/api/voice/bench"
    private const val MAX_TRACE = 24_000

    private fun crashFile(context: Context) = File(context.filesDir, "last_crash.txt")
    private fun crumbFile(context: Context) = File(context.filesDir, "breadcrumb.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile(app).writeText("thread=${thread.name}\n" + e.stackTraceToString().take(MAX_TRACE)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** What the app is about to do; included if it dies before [clear]. */
    fun breadcrumb(context: Context, text: String) = runCatching { crumbFile(context).writeText("${System.currentTimeMillis()} $text") }

    fun clear(context: Context) = runCatching { crumbFile(context).delete() }

    fun reportPending(context: Context) = Thread {
        runCatching {
            val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
            val crumb = crumbFile(context).takeIf { it.exists() }?.readText()
            // A crash while an accelerator was on: go back to the CPU provider and tell the user.
            if (crumb != null && Regex("accel=(?!cpu)\\w+").containsMatchIn(crumb)) {
                prefs.edit().putString("ondevice_accel", "cpu").putBoolean("accel_reset", true).apply()
            }
            crashFile(context).takeIf { it.exists() }?.let { f ->
                send(context, JSONObject().put("reason", "java").put("trace", f.readText()).put("breadcrumb", crumb))
                f.delete()
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val am = context.getSystemService(ActivityManager::class.java)
                val since = prefs.getLong("last_exit_reported", System.currentTimeMillis() - 86_400_000L)
                val interesting = setOf(
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                )
                val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                    .filter { it.timestamp > since && it.reason in interesting }
                for (e in exits) {
                    val trace = runCatching { e.traceInputStream?.use { s -> printable(s.readBytes()) } }.getOrNull()
                    send(context, JSONObject()
                        .put("reason", "exit-${e.reason}")
                        .put("description", e.description)
                        .put("status", e.status)
                        .put("pss_kb", e.pss).put("rss_kb", e.rss)
                        .put("exit_time", e.timestamp)
                        .put("trace", trace)
                        .put("breadcrumb", crumb))
                }
                exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit_reported", it).apply() }
            }
            clear(context)
        }
    }.start()

    /** Tombstones are protobuf; keep the readable runs (frames, symbols, abort messages). */
    private fun printable(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else {
                if (run.length >= 6) out.append(run).append('\n')
                run.setLength(0)
            }
            if (out.length > MAX_TRACE) break
        }
        return out.toString().take(MAX_TRACE)
    }

    private fun send(context: Context, body: JSONObject) {
        body.put("kind", "crash")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val conn = URL(URL_REPORT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }
}
