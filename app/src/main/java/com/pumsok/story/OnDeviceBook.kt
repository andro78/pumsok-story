package com.pumsok.story

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

private const val PHONEMES_URL = "https://d32.org/api/voice/phonemes"
private const val PHONEME_BATCH = 40
private const val SEGMENT_GAP_SEC = 0.25f

/**
 * Generates a whole story with the on-device engine, one WAV per paragraph, so it can be
 * played back offline. Only the text goes to the server (for Korean phonemes); the voice is
 * synthesized on the device. Files live under filesDir/ondevice/books/<key>/.
 */
class OnDeviceBook(private val context: Context) {
    var running by mutableStateOf(false)
        private set
    var readyCount by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf("")
        private set

    private val cancel = AtomicBoolean(false)
    private var dir: File? = null

    private val prefs get() = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Points the state at [story] read with [sel] and counts paragraphs already generated. */
    fun attach(story: Story, sel: OnDeviceVoices.Selection?) {
        if (running) return
        val d = if (sel == null) File(context.filesDir, "ondevice/books/none") else bookDir(story, sel)
        dir = d
        total = story.paragraphs.size
        readyCount = story.paragraphs.indices.count { paragraphFile(d, it).exists() }
        progress = if (total == 0) 0f else readyCount.toFloat() / total
        status = if (readyCount == total && total > 0) "책 전체가 준비됐어요. 인터넷 없이 들을 수 있어요." else ""
    }

    fun fileFor(index: Int): File? = dir?.let { paragraphFile(it, index) }?.takeIf { it.exists() }

    fun stop() = cancel.set(true)

    /** Generates missing paragraphs, starting at [from] (where the reader is) and wrapping around. */
    fun start(story: Story, sel: OnDeviceVoices.Selection, from: Int = 0, onParagraphReady: (Int) -> Unit = {}) {
        if (running) return
        cancel.set(false)
        running = true
        Thread {
            try {
                generate(story, sel, from, onParagraphReady)
            } catch (e: Throwable) {
                status = "실패: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
            }
        }.start()
    }

    private fun generate(story: Story, sel: OnDeviceVoices.Selection, from: Int, onParagraphReady: (Int) -> Unit) {
        val bundleDir = OnDeviceTts.bundleDir(context, sel.modelUrl)
        OnDeviceTts.download(sel.modelUrl, bundleDir) { name, p -> status = "모델 확인 중… $name (${(p * 100).toInt()}%)" }
        // The voice may have changed with the download, so pick the book folder only now.
        val d = bookDir(story, sel).also { it.mkdirs() }
        dir = d
        readyCount = story.paragraphs.indices.count { paragraphFile(d, it).exists() }
        progress = readyCount.toFloat() / total

        val segments = loadPhonemes(story, d)
        status = "목소리 엔진을 불러오는 중…"
        val threads = prefs.getInt("ondevice_threads", 6)
        OnDeviceTts(bundleDir, threads, sel.voiceDir ?: bundleDir, prefs.getString("ondevice_accel", "cpu") ?: "cpu").use { tts ->
            var audioSec = 0f
            var workSec = 0f
            var made = 0
            val n = story.paragraphs.size
            for (i in (0 until n).map { (it + from.coerceIn(0, n - 1)) % n }) {
                if (cancel.get()) { status = "중지했어요. 다시 누르면 이어서 만들어요."; return }
                val out = paragraphFile(d, i)
                if (out.exists()) continue
                val t0 = System.currentTimeMillis()
                val pieces = segments[i].map { tts.synthesize(it).audio }
                val audio = joinWithGaps(pieces, (tts.sampleRate * SEGMENT_GAP_SEC).toInt())
                val tmp = File(d, "${out.name}.tmp")
                OnDeviceTts.writeWav(tmp, audio, tts.sampleRate)
                tmp.renameTo(out)
                made++
                audioSec += audio.size.toFloat() / tts.sampleRate
                workSec += (System.currentTimeMillis() - t0) / 1000f
                readyCount++
                progress = readyCount.toFloat() / total
                val leftMin = ((total - readyCount) * workSec / made / 60).toInt() + 1
                status = "$readyCount/$total 문단 완료 · 읽는 속도의 %.1f배로 만드는 중 · 남은 시간 약 ${leftMin}분".format(audioSec / workSec)
                onParagraphReady(i)
            }
        }
        status = "책 전체가 준비됐어요. 인터넷 없이 들을 수 있어요."
    }

    /** Phoneme ids per paragraph segment, fetched once from the server and cached with the book. */
    private fun loadPhonemes(story: Story, d: File): List<List<LongArray>> {
        val cache = File(d, "phonemes.json")
        val json = if (cache.exists()) JSONArray(cache.readText()) else {
            val all = JSONArray()
            story.paragraphs.chunked(PHONEME_BATCH).forEachIndexed { batch, chunk ->
                status = "문장을 발음으로 바꾸는 중… (${batch * PHONEME_BATCH + chunk.size}/${story.paragraphs.size})"
                val res = postJson(PHONEMES_URL, JSONObject().put("paragraphs", JSONArray(chunk)))
                val paragraphs = res.getJSONArray("paragraphs")
                for (i in 0 until paragraphs.length()) all.put(paragraphs.getJSONArray(i))
            }
            cache.writeText(all.toString())
            all
        }
        return (0 until json.length()).map { i ->
            val segs = json.getJSONArray(i)
            (0 until segs.length()).map { j ->
                val phones = segs.getJSONObject(j).getJSONArray("phones")
                LongArray(phones.length()) { phones.getLong(it) }
            }
        }
    }

    /** Concatenates segment audio with short silences; a paragraph with no speech becomes a short silence. */
    private fun joinWithGaps(pieces: List<FloatArray>, gap: Int): FloatArray {
        if (pieces.isEmpty()) return FloatArray(gap)
        val out = FloatArray(pieces.sumOf { it.size } + gap * (pieces.size - 1))
        var pos = 0
        pieces.forEachIndexed { j, p ->
            if (j > 0) pos += gap
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private fun bookDir(story: Story, sel: OnDeviceVoices.Selection): File {
        // Keyed by text and voice, so editing the book or changing/retraining the voice regenerates audio.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sel.voiceId(context).toByteArray())
        digest.update(story.title.toByteArray())
        story.paragraphs.forEach { digest.update(0); digest.update(it.toByteArray()) }
        val key = digest.digest().take(10).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "ondevice/books/$key")
    }

    private fun paragraphFile(d: File, index: Int) = File(d, "p_%04d.wav".format(index))

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) throw IllegalStateException("발음 변환 서버 오류 (HTTP ${conn.responseCode})")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
