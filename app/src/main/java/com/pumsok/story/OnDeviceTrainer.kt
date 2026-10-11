package com.pumsok.story

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtTrainingSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Fine-tunes LoRA adapters on the GPT stage on the device (ONNX Runtime training), using the
 * parent's recorded script sentences. The base weights stay frozen; the result is lora.bin in the
 * voice folder, which OnDeviceTts feeds to the decoders as initializer overrides.
 * Mirrors scripts/lora_train.py (batch 1, AdamW, per-token mean cross entropy).
 */
class OnDeviceTrainer(private val baseDir: File, private val threads: Int) {
    class Sample(val phones: LongArray, val semantic: LongArray)
    class Info(val steps: Int, val millis: Long, val firstLoss: Float, val lastLoss: Float)

    /** Semantic tokens for a recording (16 kHz, no padding), as GPT-SoVITS dataset preparation does. */
    fun semanticTokens(pcm: FloatArray, sampleRate: Int): LongArray {
        val wav16k = OnDeviceVoiceBuilder.resample(pcm, sampleRate, 16000)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        return env.createSession(File(baseDir, "ssl_codes.onnx").path, opts).use { s ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(wav16k), longArrayOf(1, wav16k.size.toLong())).use { x ->
                s.run(mapOf("wav16k" to x)).use { r ->
                    val t = r[0] as OnnxTensor
                    LongArray(t.info.shape[0].toInt()).also { t.longBuffer.get(it) }
                }
            }
        }
    }

    fun train(samples: List<Sample>, epochs: Int, lr: Float, outDir: File, onProgress: (Int, Int, Float) -> Unit): Info {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        val total = epochs * samples.size
        var step = 0
        var first = Float.NaN
        var last = Float.NaN
        val t0 = System.currentTimeMillis()
        env.createTrainingSession(File(baseDir, "lora_checkpoint").path, File(baseDir, "lora_training_model.onnx").path,
            null, File(baseDir, "lora_optimizer_model.onnx").path, opts).use { session ->
            session.setLearningRate(lr)
            var adapters: Map<String, FloatArray> = emptyMap()
            repeat(epochs) {
                for (s in samples) {
                    withInputs(env, s) { ins ->
                        session.trainStep(ins).use { out ->
                            last = (out[0] as OnnxTensor).floatBuffer.get(0)
                            if (first.isNaN()) first = last
                        }
                    }
                    session.optimizerStep()
                    session.lazyResetGrad()
                    onProgress(++step, total, last)
                }
            }
            // One more forward to read the trained adapters (the graph echoes them as outputs).
            withInputs(env, samples.first()) { ins ->
                session.trainStep(ins).use { out -> adapters = readAdapters(session, out) }
            }
            session.lazyResetGrad()
            writeAdapters(adapters, outDir)
        }
        return Info(step, System.currentTimeMillis() - t0, first, last)
    }

    private fun withInputs(env: OrtEnvironment, s: Sample, block: (Map<String, OnnxTensor>) -> Unit) {
        val ins = inputs(env, s)
        try { block(ins) } finally { ins.values.forEach { it.close() } }
    }

    private fun inputs(env: OrtEnvironment, s: Sample): Map<String, OnnxTensor> {
        val xl = s.phones.size
        val yl = s.semantic.size
        val size = xl + yl
        // Text attends text; audio attends text and earlier audio.
        val mask = FloatArray(size * size)
        for (r in 0 until size) for (c in 0 until size) {
            val blocked = (c >= xl) && (r < xl || c > r)
            if (blocked) mask[r * size + c] = Float.NEGATIVE_INFINITY
        }
        val targets = LongArray(yl) { if (it + 1 < yl) s.semantic[it + 1] else EOS }
        return mapOf(
            "x" to OnnxTensor.createTensor(env, LongBuffer.wrap(s.phones), longArrayOf(1, xl.toLong())),
            "y" to OnnxTensor.createTensor(env, LongBuffer.wrap(s.semantic), longArrayOf(1, yl.toLong())),
            "attn_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(size.toLong(), size.toLong())),
            "targets" to OnnxTensor.createTensor(env, LongBuffer.wrap(targets), longArrayOf(1, yl.toLong())),
        )
    }

    private fun readAdapters(session: OrtTrainingSession, out: OrtSession.Result): Map<String, FloatArray> {
        val names = session.trainOutputNames.toList()
        val result = LinkedHashMap<String, FloatArray>()
        names.forEachIndexed { i, name ->
            if (!name.startsWith("lora.")) return@forEachIndexed
            val t = out[i] as OnnxTensor
            result[name.removeSuffix(".out")] = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) }
        }
        check(result.size == 96) { "학습 결과를 읽지 못했어요 (${result.size}/96)." }
        return result
    }

    companion object {
        private const val EOS = 1024L

        /** lora.bin: float32 LE tensors in the order of lora.json's names/shapes. */
        fun writeAdapters(adapters: Map<String, FloatArray>, outDir: File) {
            outDir.mkdirs()
            val total = adapters.values.sumOf { it.size }
            val buf = ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN)
            adapters.values.forEach { a -> a.forEach { buf.putFloat(it) } }
            File(outDir, "lora.bin.tmp").writeBytes(buf.array())
            val meta = JSONArray()
            adapters.forEach { (name, a) -> meta.put(JSONObject().put("name", name).put("size", a.size)) }
            File(outDir, "lora.json.tmp").writeText(meta.toString())
            File(outDir, "lora.bin.tmp").renameTo(File(outDir, "lora.bin"))
            File(outDir, "lora.json.tmp").renameTo(File(outDir, "lora.json"))
        }

        /** Adapter tensors keyed by initializer name with their shapes, or null when the voice has none. */
        fun readAdapters(voiceDir: File): Map<String, Pair<FloatArray, LongArray>>? {
            val bin = File(voiceDir, "lora.bin")
            val meta = File(voiceDir, "lora.json")
            if (!bin.exists() || !meta.exists()) return null
            val buf = ByteBuffer.wrap(bin.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val names = JSONArray(meta.readText())
            val result = LinkedHashMap<String, Pair<FloatArray, LongArray>>()
            for (i in 0 until names.length()) {
                val o = names.getJSONObject(i)
                val name = o.getString("name")
                val data = FloatArray(o.getInt("size")).also { buf.get(it) }
                result[name] = data to shapeOf(name, data.size)
            }
            return result
        }

        /** a: [512, rank]; qkv_b: [rank, 1536]; out_b: [rank, 512]. */
        private fun shapeOf(name: String, size: Int): LongArray = when {
            name.contains("_a.") -> longArrayOf(512, (size / 512).toLong())
            name.contains("qkv_b.") -> longArrayOf((size / 1536).toLong(), 1536)
            else -> longArrayOf((size / 512).toLong(), 512)
        }
    }
}
