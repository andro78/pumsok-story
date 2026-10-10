package com.pumsok.story

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds a zero-shot voice (voice.json + ge.bin) from one reference recording, entirely on the
 * device, using the encoders in the base bundle (ssl_codes.onnx, ge_enc.onnx). This mirrors
 * GPT-SoVITS TTS.set_ref_audio; see scripts/export_voice_enc.py in the bench.
 */
class OnDeviceVoiceBuilder(private val baseDir: File, private val threads: Int) {
    class Info(val promptTokens: Int, val seconds: Float, val millis: Long)

    /** [pcm] is mono audio in [-1, 1] at [sampleRate]; [refPhones] are the phoneme ids of what was said. */
    fun build(pcm: FloatArray, sampleRate: Int, refPhones: LongArray, outDir: File): Info {
        val seconds = pcm.size.toFloat() / sampleRate
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "기준 녹음은 3~12초여야 해요 (지금 %.1f초).".format(seconds) }
        val t0 = System.currentTimeMillis()
        val peak = pcm.maxOf { abs(it) }
        val audio = if (peak > 1f) FloatArray(pcm.size) { pcm[it] / min(2f, peak) } else pcm
        val wav16k = resample(audio, sampleRate, 16000)
        val wav32k = resample(audio, sampleRate, 32000)
        // GPT-SoVITS appends sampling_rate * 0.3 zeros (9600 samples, i.e. 0.6 s at 16 kHz).
        val padded = wav16k.copyOf(wav16k.size + 9600)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val codes = env.createSession(File(baseDir, "ssl_codes.onnx").path, opts).use { s ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(padded), longArrayOf(1, padded.size.toLong())).use { x ->
                s.run(mapOf("wav16k" to x)).use { r ->
                    val t = r[0] as OnnxTensor
                    LongArray(t.info.shape[0].toInt()).also { t.longBuffer.get(it) }
                }
            }
        }
        val (ge, geShape) = env.createSession(File(baseDir, "ge_enc.onnx").path, opts).use { s ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(wav32k), longArrayOf(1, wav32k.size.toLong())).use { a ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(wav16k), longArrayOf(1, wav16k.size.toLong())).use { b ->
                    s.run(mapOf("wav32k" to a, "wav16k" to b)).use { r ->
                        val t = r[0] as OnnxTensor
                        FloatArray(t.info.shape.fold(1L) { acc, d -> acc * d }.toInt()).also { t.floatBuffer.get(it) } to t.info.shape
                    }
                }
            }
        }

        outDir.mkdirs()
        val geBytes = ByteBuffer.allocate(ge.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        ge.forEach { geBytes.putFloat(it) }
        File(outDir, "ge.bin.tmp").writeBytes(geBytes.array())
        File(outDir, "voice.json.tmp").writeText(JSONObject()
            .put("ref_phones", JSONArray(refPhones.toList()))
            .put("prompt_semantic", JSONArray(codes.toList()))
            .put("ge_shape", JSONArray(geShape.toList()))
            .put("voice_id", "local-${System.currentTimeMillis()}")
            .put("base_bundle", JSONObject(File(baseDir, "manifest.json").readText()).optString("bundle_id"))
            .toString())
        File(outDir, "ge.bin.tmp").renameTo(File(outDir, "ge.bin"))
        File(outDir, "voice.json.tmp").renameTo(File(outDir, "voice.json"))
        return Info(codes.size, seconds, System.currentTimeMillis() - t0)
    }

    companion object {
        const val MIN_SECONDS = 3f
        const val MAX_SECONDS = 12f

        /** Hann-windowed sinc resampler (16 zero crossings, cutoff at 97% of the lower Nyquist). */
        fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to) return x.copyOf()
            val ratio = to.toDouble() / from
            val cutoff = 0.97 * min(1.0, ratio)
            val half = 16 / cutoff
            val out = FloatArray(floor(x.size * ratio).toInt())
            for (i in out.indices) {
                val t = i / ratio
                val lo = max(0, ceil(t - half).toInt())
                val hi = min(x.size - 1, floor(t + half).toInt())
                var acc = 0.0
                for (j in lo..hi) {
                    val d = t - j
                    val sinc = if (d == 0.0) 1.0 else sin(PI * cutoff * d) / (PI * cutoff * d)
                    acc += x[j] * cutoff * sinc * (0.5 + 0.5 * cos(PI * d / half))
                }
                out[i] = acc.toFloat()
            }
            return out
        }
    }
}
