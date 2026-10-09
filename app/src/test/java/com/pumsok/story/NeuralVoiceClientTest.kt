package com.pumsok.story

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NeuralVoiceClientTest {

    @Test
    fun testBaseUrlIsHttpsD32Org() {
        assertEquals("https://d32.org/api/voice", NeuralVoiceClient.BASE_URL)
    }

    @Test
    fun testHealthCheckEndpointReachable() {
        // d32.org is live and responding
        val healthy = NeuralVoiceClient.checkHealth()
        assertTrue("d32.org voice microservice should be accessible", healthy)
    }

    @Test
    fun testSpeechSynthesisProducesAudioFile() {
        val tempFile = File.createTempFile("pumsok_test_", ".mp3")
        try {
            val success = NeuralVoiceClient.synthesizeSpeech(
                text = "반가워! 품속동화 이야기야.",
                roleKey = "MOTHER",
                cacheFile = tempFile
            )
            assertTrue("Speech synthesis should succeed", success)
            assertTrue("Generated audio file should exist", tempFile.exists())
            assertTrue("Generated audio file should contain valid MP3 data", tempFile.length() > 500)
        } finally {
            tempFile.delete()
        }
    }
}
