package com.pumsok.story

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoiceTrainerTest {

    @Test
    fun voiceProfileSerializationRoundTrip() {
        val original = VoiceProfile(
            roleKey = "MOTHER",
            roleLabel = "엄마",
            pitch = 1.22f,
            speechRate = 0.92f,
            sampleDurationSec = 39.5f,
            clipsCount = 4,
            trainedAt = 1728460000000L,
            isTrained = true
        )

        val json = original.toJson()
        val restored = VoiceProfile.fromJson(json)

        assertNotNull(restored)
        assertEquals("MOTHER", restored!!.roleKey)
        assertEquals("엄마", restored.roleLabel)
        assertEquals(1.22f, restored.pitch, 0.001f)
        assertEquals(0.92f, restored.speechRate, 0.001f)
        assertEquals(39.5f, restored.sampleDurationSec, 0.001f)
        assertEquals(4, restored.clipsCount)
        assertTrue(restored.isTrained)
    }

    @Test
    fun fatherProfileHasDeeperPitch() {
        val mom = VoiceProfile.defaultFor("MOTHER", "엄마")
        val dad = VoiceProfile.defaultFor("FATHER", "아빠")

        assertTrue(mom.pitch > 1.0f)
        assertTrue(dad.pitch < 1.0f)
        assertTrue(mom.pitch > dad.pitch)
    }

    @Test
    fun voiceTrainerTrainsMockAudioFiles() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "voice_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val dummyFiles = (0..3).map { i ->
                File(tempDir, "voice_$i.m4a").apply {
                    writeBytes(ByteArray(1024 * 50) { 1 }) // 50KB dummy audio
                }
            }

            var lastProgress = 0f
            val progressSteps = mutableListOf<String>()

            val profile = VoiceTrainer.train(
                roleKey = "MOTHER",
                roleLabel = "엄마",
                audioFiles = dummyFiles
            ) { state ->
                lastProgress = state.progress
                progressSteps.add(state.title)
            }

            assertTrue(profile.isTrained)
            assertEquals("MOTHER", profile.roleKey)
            assertEquals(4, profile.clipsCount)
            assertEquals(1.0f, lastProgress, 0.001f)
            assertEquals(4, progressSteps.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
