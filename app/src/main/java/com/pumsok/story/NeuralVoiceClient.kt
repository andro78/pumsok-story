package com.pumsok.story

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object NeuralVoiceClient {
    const val BASE_URL = "https://d32.org/api/voice"

    private fun escapeJson(s: String): String =
        "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""

    fun checkHealth(): Boolean = runCatching {
        val url = URL("$BASE_URL/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.responseCode in 200..299
    }.getOrDefault(false)

    fun trainOnServer(
        roleKey: String,
        roleLabel: String,
        audioFile: File,
        onProgress: (step: Int, title: String) -> Unit
    ): VoiceProfile {
        onProgress(1, "1단계: 서버로 부모님 음성 업로드 중…")
        val boundary = "====${System.currentTimeMillis()}===="
        val url = URL("$BASE_URL/train")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        val speakerId = "speaker_${roleKey.lowercase()}"
        val out = conn.outputStream
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

        writeAscii("--$boundary\r\nContent-Disposition: form-data; name=\"role\"\r\n\r\n$roleKey\r\n")
        writeAscii("--$boundary\r\nContent-Disposition: form-data; name=\"speakerId\"\r\n\r\n$speakerId\r\n")
        writeAscii("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\nContent-Type: audio/mp4\r\n\r\n")
        audioFile.inputStream().use { it.copyTo(out) }
        writeAscii("\r\n--$boundary--\r\n")
        out.flush()

        onProgress(2, "2단계: 서버에서 24kHz 노이즈 정제 및 F0 분석 중…")
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "서버 오류 ($code)"
            error("서버 학습 실패: $err")
        }

        onProgress(3, "3단계: 성도 구조 및 고유 음색 벡터 프로파일링 중…")
        val respText = conn.inputStream.bufferedReader().readText()

        fun extract(key: String): String? =
            Regex(""""$key"\s*:\s*"?([^",}]+)"?""").find(respText)?.groupValues?.get(1)

        val f0 = extract("f0MedianHz")?.toFloatOrNull() ?: 180f
        val dur = extract("durationSec")?.toFloatOrNull() ?: 30f

        onProgress(4, "4단계: 뉴럴 보이스 클로닝 모델 생성 완료!")
        return VoiceProfile(
            roleKey = roleKey,
            roleLabel = roleLabel,
            pitch = (f0 / 160f).coerceIn(0.7f, 1.4f),
            speechRate = if (roleKey == "MOTHER") 0.92f else 0.90f,
            sampleDurationSec = dur,
            clipsCount = 1,
            trainedAt = System.currentTimeMillis(),
            isTrained = true
        )
    }

    fun tuneOnServer(profile: VoiceProfile): Boolean = runCatching {
        val speakerId = "speaker_${profile.roleKey.lowercase()}"
        val url = URL("$BASE_URL/tune")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 15000
        conn.readTimeout = 45000

        val apiKeyJson = if (profile.useElevenLabs && !profile.elevenLabsApiKey.isNullOrBlank()) {
            escapeJson(profile.elevenLabsApiKey)
        } else "null"

        val body = """{"speakerId":"$speakerId","pitchOffsetHz":${profile.pitchOffsetHz},"tau":${profile.tau},"warmthDb":${profile.warmthDb},"presenceDb":${profile.presenceDb},"speedRate":"${profile.speedRate}","elevenLabsApiKey":$apiKeyJson}"""
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        conn.responseCode in 200..299
    }.getOrDefault(false)

    fun synthesizeSpeech(
        text: String,
        profile: VoiceProfile,
        cacheFile: File
    ): Boolean {
        if (cacheFile.exists() && cacheFile.length() > 500) return true

        return runCatching {
            val speakerId = "speaker_${profile.roleKey.lowercase()}"
            val url = URL("$BASE_URL/synthesize")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 15000
            conn.readTimeout = 45000

            val body = """{"text":${escapeJson(text)},"speakerId":"$speakerId","role":"${profile.roleKey}","pitchOffsetHz":${profile.pitchOffsetHz},"tau":${profile.tau},"warmthDb":${profile.warmthDb},"speedRate":"${profile.speedRate}"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) error("음성 합성 실패 ($code)")

            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tempFile.outputStream().use { out ->
                conn.inputStream.use { it.copyTo(out) }
            }
            if (cacheFile.exists()) cacheFile.delete()
            tempFile.renameTo(cacheFile)
            true
        }.getOrDefault(false)
    }

    fun synthesizeSpeech(
        text: String,
        roleKey: String,
        cacheFile: File
    ): Boolean = synthesizeSpeech(text, VoiceProfile.defaultFor(roleKey, if (roleKey == "MOTHER") "엄마" else "아빠"), cacheFile)

    fun downloadPreview(speakerId: String, targetFile: File): Boolean = runCatching {
        val url = URL("$BASE_URL/preview/$speakerId")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        if (conn.responseCode in 200..299) {
            val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tmp.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            if (targetFile.exists()) targetFile.delete()
            tmp.renameTo(targetFile)
            true
        } else false
    }.getOrDefault(false)
}
