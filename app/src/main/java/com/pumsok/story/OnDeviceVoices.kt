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
    const val BASE_BUNDLE_URL = "https://d32.org/pumsok/m/base-v2pp-lora-20261011"
    const val TRAIN_EPOCHS = 8
    private const val TRAIN_LR = 1e-3f
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

    /** Reader voice keys: "local:MOTHER" / "local:FATHER" (built on the device), "server", "tts". */
    fun selectionFor(context: Context, key: String): Selection? = when {
        key.startsWith("local:") -> key.removePrefix("local:").let { role ->
            if (hasLocal(context, role)) Selection(BASE_BUNDLE_URL, localDir(context, role)) else null
        }
        key == "server" -> serverBundleUrl(context).ifBlank { null }?.let { Selection(it, null) }
        else -> null
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

    fun isTrained(context: Context, role: String) = File(localDir(context, role), "lora.bin").exists()

    /**
     * Fine-tunes [role]'s on-device voice (LoRA on the GPT stage) with every recorded script
     * sentence. Needs the voice built first (it provides the reference); takes minutes.
     */
    fun train(
        context: Context,
        role: String,
        clips: List<Pair<File, String>>,
        threads: Int,
        onStatus: (String, Float) -> Unit,
    ): OnDeviceTrainer.Info {
        val dir = localDir(context, role)
        check(hasLocal(context, role)) { "먼저 ‘목소리 만들기’를 해 주세요." }
        val baseDir = OnDeviceTts.bundleDir(context, BASE_BUNDLE_URL)
        OnDeviceTts.download(BASE_BUNDLE_URL, baseDir) { name, p -> onStatus("기본 모델 확인 중… $name (${(p * 100).toInt()}%)", p * 0.1f) }
        val refTexts = JSONObject(File(baseDir, "ref_texts.json").readText())
        val trainer = OnDeviceTrainer(baseDir, threads)
        val usable = clips.filter { (f, t) -> f.exists() && refTexts.has(t) }
        check(usable.size >= 4) { "학습하려면 단계별 녹음이 4개 이상 필요해요 (지금 ${usable.size}개)." }
        val samples = usable.mapIndexed { i, (file, text) ->
            onStatus("녹음 ${i + 1}/${usable.size} 분석 중…", 0.1f + 0.1f * i / usable.size)
            val pcm = AudioDecoder.decode(file)
            val phones = refTexts.getJSONArray(text).let { a -> LongArray(a.length()) { a.getLong(it) } }
            OnDeviceTrainer.Sample(phones, trainer.semanticTokens(pcm.samples, pcm.sampleRate))
        }
        val t0 = System.currentTimeMillis()
        val info = trainer.train(samples, TRAIN_EPOCHS, TRAIN_LR, dir) { step, total, loss ->
            val perStep = (System.currentTimeMillis() - t0) / 1000f / step
            val leftMin = ((total - step) * perStep / 60).toInt() + 1
            onStatus("학습 중 $step/$total 단계 · 손실 %.2f · 남은 시간 약 ${leftMin}분".format(loss), 0.2f + 0.8f * step / total)
        }
        // A new voice id makes generated books redo their audio with the trained voice.
        val voiceJson = File(dir, "voice.json")
        voiceJson.writeText(JSONObject(voiceJson.readText()).put("voice_id", "local-lora-${System.currentTimeMillis()}").toString())
        return info
    }
}
