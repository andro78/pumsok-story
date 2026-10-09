package com.pumsok.story

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks d32.org/pumsok/version.json for an updated APK on launch
 * and installs it securely via Android's PackageInstaller.
 */
class Updater(private val activity: Activity) {

    private class Release(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val sha256: String,
        val notes: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private var pending: File? = null
    private var receiver: BroadcastReceiver? = null

    fun checkOnLaunch(manual: Boolean = false) {
        Thread {
            val result = runCatching { fetchRelease() }
            val release = result.getOrNull()
            if (release != null) {
                if (release.versionCode > installedVersionCode()) {
                    main.post { if (!activity.isFinishing) offer(release) }
                } else if (manual) {
                    main.post { if (!activity.isFinishing) toast("현재 최신 버전(v${installedVersionName()})입니다.") }
                }
            } else if (manual) {
                val errorMsg = result.exceptionOrNull()?.message ?: "버전 정보를 확인할 수 없습니다."
                main.post { if (!activity.isFinishing) toast("업데이트 확인 실패: $errorMsg") }
            }
        }.start()
    }

    /** Call from Activity.onResume: continues an install parked on the unknown-sources setting. */
    fun onResume() {
        val apk = pending ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pending = null
            install(apk)
        }
    }

    fun release() {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
    }

    private fun installedVersionCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun installedVersionName(): String {
        return activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.1.0"
    }

    private fun fetchRelease(): Release {
        val conn = URL("$VERSION_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.useCaches = false
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val apkObj = json.optJSONObject("apk")
            val rawUrl = json.optString("url").ifEmpty {
                val file = apkObj?.optString("file") ?: "pumsok.apk"
                "https://d32.org/pumsok/$file"
            }
            val apkUrl = URL(rawUrl)
            require(apkUrl.protocol == "https" && (apkUrl.host == "d32.org" || apkUrl.host == "www.d32.org")) {
                "업데이트 주소가 올바르지 않습니다."
            }
            val sha256 = (json.optString("sha256").ifEmpty {
                apkObj?.optString("sha256") ?: ""
            }).lowercase()
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "업데이트 체크섬이 올바르지 않습니다." }
            return Release(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName", "새 버전"),
                url = apkUrl.toString(),
                sha256 = sha256,
                notes = json.optString("notes", ""),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(release: Release) {
        val current = installedVersionName()
        val message = buildString {
            append("현재 v$current → 최신 v${release.versionName}")
            if (release.notes.isNotBlank()) {
                append("\n\n[업데이트 내용]\n").append(release.notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("새로운 버전이 있습니다")
            .setMessage(message)
            .setPositiveButton("지금 업데이트") { _, _ -> download(release) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun download(release: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(60, 40, 60, 20)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("업데이트 다운로드 중… (v${release.versionName})")
            .setView(bar)
            .setCancelable(false)
            .show()

        Thread {
            val result = runCatching {
                val file = File(activity.cacheDir, "update.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = URL(release.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                try {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (total > 0) main.post { bar.progress = (done * 100 / total).toInt() }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == release.sha256) { "파일 검증 실패 (SHA-256 불일치)" }
                file
            }
            main.post {
                dialog.dismiss()
                result.onSuccess { install(it) }
                    .onFailure { toast("업데이트 다운로드 실패: ${it.message}") }
            }
        }.start()
    }

    private fun install(apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pending = apk
            AlertDialog.Builder(activity)
                .setTitle("설치 권한 필요")
                .setMessage("업데이트를 설치하려면 이 앱의 '출처를 알 수 없는 앱 설치'를 허용해 주세요. 허용 후 앱으로 돌아오면 설치가 계속됩니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("취소") { _, _ -> pending = null }
                .show()
            return
        }

        registerResultReceiver()
        runCatching {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(activity.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(activity, sessionId, intent, flags).intentSender)
            }
        }.onFailure { toast("설치 실패: ${it.message}") }
    }

    private fun registerResultReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.let { activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    else -> toast("설치 실패: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"}")
                }
            }
        }
        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(r, filter)
        receiver = r
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val VERSION_URL = "https://d32.org/pumsok/version.json"
        private const val ACTION_INSTALL_RESULT = "com.pumsok.story.INSTALL_RESULT"
    }
}
