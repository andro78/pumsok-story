package com.pumsok.story

import java.io.File

/** A cloning adapter must pass Korean quality, license, and device benchmarks before activation. */
interface VoiceEngine {
    suspend fun synthesize(text: String, referenceAudio: File, transcript: String): File
}

class UnavailableVoiceEngine : VoiceEngine {
    override suspend fun synthesize(text: String, referenceAudio: File, transcript: String): File =
        error("부모님 목소리 생성 엔진은 아직 연결되지 않았습니다.")
}
