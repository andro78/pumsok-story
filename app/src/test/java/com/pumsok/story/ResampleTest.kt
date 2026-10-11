package com.pumsok.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResampleTest {
    private fun tone(hz: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int) =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test fun lengthFollowsRateRatio() {
        val out = OnDeviceVoiceBuilder.resample(FloatArray(24000), 24000, 16000)
        assertEquals(16000, out.size)
    }

    @Test fun keepsToneBelowNyquist() {
        val out = OnDeviceVoiceBuilder.resample(tone(440.0, 24000, 1.0), 24000, 16000)
        val expected = tone(440.0, 16000, 1.0)
        // Ignore the filter's edge effects at both ends.
        val err = (1000 until 15000).maxOf { abs(out[it] - expected[it]) }
        assertTrue("max error $err", err < 0.01f)
    }

    @Test fun removesToneAboveTargetNyquist() {
        val out = OnDeviceVoiceBuilder.resample(tone(10000.0, 24000, 1.0), 24000, 16000)
        assertTrue(rms(out, 1000, 15000) < 0.01)
    }

    @Test fun sameRateIsCopy() {
        val x = tone(300.0, 32000, 0.1)
        val out = OnDeviceVoiceBuilder.resample(x, 32000, 32000)
        assertTrue(x.contentEquals(out))
        assertTrue(x !== out)
    }
}
