package com.pumsok.story

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Which voice the on-device engine speaks with: a voice bundle trained on the server (secret
 * bundle URL), or a zero-shot voice built on this device from the parent's recordings on top of
 * the public base bundle.
 */
object OnDeviceVoices {
    /** Voice-independent pretrained models and encoders; contains no personal voice data. */
    const val BASE_BUNDLE_URL = "https://d32.org/pumsok/m/base-v2pp-20261011"
    private const val TARGET_SECONDS = 7f

    /** [modelUrl] is downloaded into the models folder; [voiceDir] is null when the bundle carries the voice. */
    class Selection(val modelUrl: String, val voiceDir: File?) {
        /** Stable id of the voice, so generated audio is redone when the voice changes. */
        fun voiceId(context: Context): String {
            val voiceJson = voiceDir?.let { File(it, "voice.json") }
            if (voiceJson != null) return JSONObject(voiceJson.readText()).optString("voice_id", "local")
            val manifest = File(OnDeviceTts.bundleDir(context, modelUrl), "manifest.json")
            return runCatching { JSONObject(manifest.readText()).optString("bundle_id") }.getOrNull()
                ?.ifBlank { null } ?: modelUrl
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun localDir(context: Context, role: String) = File(context.filesDir, "ondevice/voices/$role")

    fun hasLocal(context: Context, role: String) = File(localDir(context, role), "voice.json").exists()

    fun serverBundleUrl(context: Context) = prefs(context).getString("ondevice_bundle_url", "") ?: ""

    fun useLocal(context: Context) = prefs(context).getBoolean("ondevice_use_local_voice", false)

    fun setUseLocal(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("ondevice_use_local_voice", value).apply()

    /** The voice to synthesize [role] with, or null when none is available yet. */
    fun selection(context: Context, role: String): Selection? {
        val serverUrl = serverBundleUrl(context)
        val local = hasLocal(context, role)
        return when {
            local && (useLocal(context) || serverUrl.isBlank()) -> Selection(BASE_BUNDLE_URL, localDir(context, role))
            serverUrl.isNotBlank() -> Selection(serverUrl, null)
            else -> null
        }
    }

    /**
     * Builds [role]'s voice from the recorded script [clips] (file to the text that was read).
     * Picks the clip closest to 7 s within 3–12 s whose script has bundled phonemes, so this
     * works offline once the base bundle is downloaded.
     */
    fun build(
        context: Context,
        role: String,
        clips: List<Pair<File, String>>,
        threads: Int,
        onStatus: (String, Float) -> Unit,
    ): OnDeviceVoiceBuilder.Info {
        val baseDir = OnDeviceTts.bundleDir(context, BASE_BUNDLE_URL)
        OnDeviceTts.download(BASE_BUNDLE_URL, baseDir) { name, p ->
            onStatus("기본 모델 내려받는 중… $name (${(p * 100).toInt()}%)", p * 0.8f)
        }
        val refTexts = JSONObject(File(baseDir, "ref_texts.json").readText())
        onStatus("녹음을 살펴보는 중…", 0.82f)
        val candidates = clips.filter { (file, text) -> file.exists() && refTexts.has(text) }
            .mapNotNull { (file, text) -> runCatching { Triple(AudioDecoder.decode(file), text, file) }.getOrNull() }
            .filter { it.first.seconds in OnDeviceVoiceBuilder.MIN_SECONDS..OnDeviceVoiceBuilder.MAX_SECONDS }
        val (pcm, text, _) = candidates.minByOrNull { abs(it.first.seconds - TARGET_SECONDS) }
            ?: throw IllegalStateException("3~12초 길이의 단계별 녹음이 필요해요. 대본을 또박또박 다시 녹음해 주세요.")
        val phones = refTexts.getJSONArray(text).let { a -> LongArray(a.length()) { a.getLong(it) } }

        onStatus("목소리 특징을 뽑는 중… (약 10초)", 0.88f)
        val tmp = File(context.filesDir, "ondevice/voices/$role.tmp").apply { deleteRecursively() }
        val info = OnDeviceVoiceBuilder(baseDir, threads).build(pcm.samples, pcm.sampleRate, phones, tmp)
        val dir = localDir(context, role)
        dir.deleteRecursively()
        tmp.renameTo(dir)
        onStatus("완료", 1f)
        return info
    }
}
