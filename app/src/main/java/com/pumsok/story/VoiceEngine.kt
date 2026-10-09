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
    val isTrained: Boolean
) {
    fun toJson(): String =
        """{"roleKey":"$roleKey","roleLabel":"$roleLabel","pitch":$pitch,"speechRate":$speechRate,"sampleDurationSec":$sampleDurationSec,"clipsCount":$clipsCount,"trainedAt":$trainedAt,"isTrained":$isTrained}"""

    companion object {
        fun fromJson(jsonStr: String?): VoiceProfile? {
            if (jsonStr.isNullOrBlank()) return null
            return runCatching {
                fun extract(key: String): String =
                    Regex(""""$key"\s*:\s*"?([^",}]+)"?""").find(jsonStr)?.groupValues?.get(1) ?: error("missing $key")
                VoiceProfile(
                    roleKey = extract("roleKey"),
                    roleLabel = extract("roleLabel"),
                    pitch = extract("pitch").toFloat(),
                    speechRate = extract("speechRate").toFloat(),
                    sampleDurationSec = extract("sampleDurationSec").toFloat(),
                    clipsCount = extract("clipsCount").toInt(),
                    trainedAt = extract("trainedAt").toLong(),
                    isTrained = extract("isTrained").toBoolean()
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
            isTrained = false
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
