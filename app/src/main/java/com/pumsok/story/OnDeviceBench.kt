package com.pumsok.story

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val BENCH_REPORT_URL = "https://d32.org/api/voice/bench"

private class BenchRow(val text: String, val tokens: Int, val gptMs: Long, val vitsMs: Long, val audioSec: Float, val wav: File) {
    val rtf get() = (gptMs + vitsMs) / 1000f / audioSec
}

/** Experimental card that downloads an on-device voice bundle and measures synthesis speed. */
@Composable
fun OnDeviceBenchCard(enabled: Boolean, playFile: (File) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    var bundleUrl by remember { mutableStateOf(prefs.getString("ondevice_bundle_url", "") ?: "") }
    var threads by remember { mutableIntStateOf(prefs.getInt("ondevice_threads", 4)) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf<BenchRow>() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🧪 온디바이스 엔진 테스트 (실험)", style = MaterialTheme.typography.titleMedium)
            Text(
                "서버 없이 이 기기에서 바로 목소리를 만드는 속도를 재요. 처음 한 번 모델(수백 MB)을 내려받으니 Wi-Fi에서 실행해 주세요.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = bundleUrl,
                onValueChange = { bundleUrl = it.trim() },
                label = { Text("모델 번들 주소") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 4, 6, 8).forEach { n ->
                    FilterChip(
                        selected = threads == n,
                        onClick = { threads = n },
                        label = { Text("${n}스레드") },
                        enabled = !running
                    )
                }
            }
            Button(
                onClick = {
                    prefs.edit().putString("ondevice_bundle_url", bundleUrl).putInt("ondevice_threads", threads).apply()
                    running = true
                    rows.clear()
                    Thread {
                        runBench(context, bundleUrl, threads,
                            onStatus = { s, p -> status = s; progress = p },
                            onRow = { rows += it })
                        running = false
                    }.start()
                },
                enabled = enabled && !running && bundleUrl.startsWith("https://"),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "측정 중…" else "모델 받고 속도 측정하기")
            }
            if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            rows.forEachIndexed { i, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${row.text}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "GPT %.1f초 · SoVITS %.1f초 · 음성 %.1f초 · RTF %.2f".format(row.gptMs / 1000f, row.vitsMs / 1000f, row.audioSec, row.rtf),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.rtf <= 1f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { playFile(row.wav) }, enabled = !running) { Text("듣기") }
                }
            }
        }
    }
}

private fun runBench(
    context: Context,
    bundleUrl: String,
    threads: Int,
    onStatus: (String, Float) -> Unit,
    onRow: (BenchRow) -> Unit,
) {
    val key = MessageDigest.getInstance("SHA-256").digest(bundleUrl.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    val dir = File(context.filesDir, "ondevice/$key")
    val results = JSONArray()
    try {
        OnDeviceTts.download(bundleUrl, dir) { name, p -> onStatus("모델 내려받는 중… $name (${(p * 100).toInt()}%)", p) }
        onStatus("모델을 불러오는 중…", 1f)
        val bench = JSONArray(File(dir, "bench.json").readText())
        OnDeviceTts(dir, threads).use { tts ->
            for (i in 0 until bench.length()) {
                val item = bench.getJSONObject(i)
                val phonesJson = item.getJSONArray("phones")
                val phones = LongArray(phonesJson.length()) { phonesJson.getLong(it) }
                onStatus("문장 ${i + 1}/${bench.length()} 만드는 중…", i.toFloat() / bench.length())
                val r = tts.synthesize(phones)
                val wav = File(dir, "bench_$i.wav")
                OnDeviceTts.writeWav(wav, r.audio, tts.sampleRate)
                val row = BenchRow(item.getString("text"), r.tokens, r.gptMs, r.vitsMs, r.audio.size.toFloat() / tts.sampleRate, wav)
                onRow(row)
                results.put(JSONObject().put("tokens", r.tokens).put("gpt_ms", r.gptMs).put("vits_ms", r.vitsMs)
                    .put("audio_sec", row.audioSec.toDouble()).put("rtf", row.rtf.toDouble()))
            }
            val avg = (0 until results.length()).map { results.getJSONObject(it).getDouble("rtf") }.average()
            report(context, JSONObject(File(dir, "manifest.json").readText()).optString("bundle_id"), threads, tts.loadMs, results)
            onStatus("완료 · 평균 RTF %.2f (1 이하면 실시간보다 빨라요) · 결과를 서버로 보냈어요".format(avg), 1f)
        }
    } catch (e: Throwable) {
        onStatus("실패: ${e.message ?: e.javaClass.simpleName}", 0f)
    }
}

private fun report(context: Context, bundleId: String, threads: Int, loadMs: Long, results: JSONArray) {
    val body = JSONObject()
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        .put("bundle", bundleId)
        .put("threads", threads)
        .put("load_ms", loadMs)
        .put("results", results)
    runCatching {
        val conn = URL(BENCH_REPORT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }
}
