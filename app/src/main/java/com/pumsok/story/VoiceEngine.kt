package com.pumsok.story

import android.speech.tts.TextToSpeech
import java.io.File

/**
 * Data profile representing a trained parent voice model.
 */
data class VoiceProfile(
    val roleKey: String,          // "MOTHER" or "FATHER"
    val roleLabel: String,        // "엄마" or "아빠"
    val pitch: Float,             // Formant/pitch multiplier for storytelling
    val speechRate: Float,        // Fairytale storytelling cadence
    val sampleDurationSec: Float, // Total reference audio duration
    val clipsCount: Int,          // Number of analyzed clips
    val trainedAt: Long,          // Timestamp of training
    val isTrained: Boolean,
    val pitchOffsetHz: Int = 0,   // User fine-tuning: -40 ~ +40 Hz
    val tau: Float = 0.22f,        // Neural clone transfer strength: 0.10 ~ 0.45
    val warmthDb: Float = 2.0f,   // Resonance warmth: -4.0 ~ +6.0 dB
    val presenceDb: Float = -0.5f,// Presence / clarity: -4.0 ~ +4.0 dB
    val speedRate: String = if (roleKey == "FATHER") "-6%" else "-8%",
    val elevenLabsApiKey: String? = null,
    val useElevenLabs: Boolean = false
) {
    fun toJson(): String {
        val apiKeyJson = if (elevenLabsApiKey != null) "\"$elevenLabsApiKey\"" else "null"
        return """{"roleKey":"$roleKey","roleLabel":"$roleLabel","pitch":$pitch,"speechRate":$speechRate,"sampleDurationSec":$sampleDurationSec,"clipsCount":$clipsCount,"trainedAt":$trainedAt,"isTrained":$isTrained,"pitchOffsetHz":$pitchOffsetHz,"tau":$tau,"warmthDb":$warmthDb,"presenceDb":$presenceDb,"speedRate":"$speedRate","elevenLabsApiKey":$apiKeyJson,"useElevenLabs":$useElevenLabs}"""
    }

    companion object {
        fun fromJson(jsonStr: String?): VoiceProfile? {
            if (jsonStr.isNullOrBlank()) return null
            return runCatching {
                fun extract(key: String): String? =
                    Regex(""""$key"\s*:\s*"?([^",}]+)"?""").find(jsonStr)?.groupValues?.get(1)?.takeIf { it != "null" }
                val role = extract("roleKey") ?: "MOTHER"
                VoiceProfile(
                    roleKey = role,
                    roleLabel = extract("roleLabel") ?: (if (role == "MOTHER") "엄마" else "아빠"),
                    pitch = extract("pitch")?.toFloatOrNull() ?: 1.0f,
                    speechRate = extract("speechRate")?.toFloatOrNull() ?: 0.92f,
                    sampleDurationSec = extract("sampleDurationSec")?.toFloatOrNull() ?: 0f,
                    clipsCount = extract("clipsCount")?.toIntOrNull() ?: 0,
                    trainedAt = extract("trainedAt")?.toLongOrNull() ?: 0L,
                    isTrained = extract("isTrained")?.toBoolean() ?: false,
                    pitchOffsetHz = extract("pitchOffsetHz")?.toIntOrNull() ?: 0,
                    tau = extract("tau")?.toFloatOrNull() ?: 0.22f,
                    warmthDb = extract("warmthDb")?.toFloatOrNull() ?: 2.0f,
                    presenceDb = extract("presenceDb")?.toFloatOrNull() ?: -0.5f,
                    speedRate = extract("speedRate") ?: (if (role == "FATHER") "-6%" else "-8%"),
                    elevenLabsApiKey = extract("elevenLabsApiKey"),
                    useElevenLabs = extract("useElevenLabs")?.toBoolean() ?: false
                )
            }.getOrNull()
        }

        fun defaultFor(roleKey: String, roleLabel: String): VoiceProfile = VoiceProfile(
            roleKey = roleKey,
            roleLabel = roleLabel,
            pitch = if (roleKey == "MOTHER") 1.20f else 0.83f,
            speechRate = if (roleKey == "MOTHER") 0.92f else 0.90f,
            sampleDurationSec = 0f,
            clipsCount = 0,
            trainedAt = 0L,
            isTrained = false,
            pitchOffsetHz = 0,
            tau = 0.22f,
            warmthDb = 2.0f,
            presenceDb = -0.5f,
            speedRate = if (roleKey == "FATHER") "-6%" else "-8%"
        )
    }
}

/**
 * Voice engine interface for fairytale reading synthesis.
 */
interface VoiceEngine {
    fun speak(text: String, profile: VoiceProfile, tts: TextToSpeech, utteranceId: String = "fairytale_para")
    fun stop(tts: TextToSpeech)
}

/**
 * Storytelling voice engine that applies the trained parent voice profile
 * (pitch curve, warm cadence, character pacing) to synthesis.
 */
class TrainedVoiceEngine : VoiceEngine {
    override fun speak(text: String, profile: VoiceProfile, tts: TextToSpeech, utteranceId: String) {
        tts.stop()
        tts.setPitch(profile.pitch)
        tts.setSpeechRate(profile.speechRate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop(tts: TextToSpeech) {
        tts.stop()
        tts.setPitch(1.0f)
        tts.setSpeechRate(1.0f)
    }
}
