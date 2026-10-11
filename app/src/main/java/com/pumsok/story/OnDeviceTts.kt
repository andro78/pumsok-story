package com.pumsok.story

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Random

/**
 * On-device GPT-SoVITS (v2ProPlus) inference with ONNX Runtime.
 *
 * The bundle is produced on the server: the reference voice is precomputed (voice.json, ge.bin)
 * and test sentences carry phoneme ids, so the device only runs the GPT encoder/decoders and
 * the SoVITS decoder. Mirrors scripts/ort_infer_kv.py in the bench.
 */
/**
 * [dir] holds the models; [voiceDir] holds voice.json + ge.bin. They are the same folder for a
 * server-made voice bundle, and differ for a voice built on the device on top of the base bundle.
 */
class OnDeviceTts(private val dir: File, threads: Int, voiceDir: File = dir) : AutoCloseable {
    class Result(val audio: FloatArray, val tokens: Int, val gptMs: Long, val vitsMs: Long)

    private val env = OrtEnvironment.getEnvironment()
    private val manifest = JSONObject(File(dir, "manifest.json").readText())
    val sampleRate = manifest.getInt("sampling_rate")
    private val eos = manifest.getInt("eos").toLong()
    private val maxTokens = manifest.getInt("max_tokens")
    private val interChannels = manifest.getInt("inter_channels")

    private val refPhones: LongArray
    private val promptSemantic: LongArray
    private val ge: FloatArray
    private val geShape: LongArray

    private val adapterTensors = mutableListOf<OnnxTensor>()
    private val encoder: OrtSession
    private val firstStage: OrtSession
    private val step: OrtSession
    private val vits: OrtSession
    val loadMs: Long

    init {
        require(manifest.optInt("format", 1) >= 2) { "이 앱 버전과 맞지 않는 모델 번들입니다. 번들을 다시 받아 주세요." }
        val voice = JSONObject(File(voiceDir, "voice.json").readText())
        refPhones = voice.getJSONArray("ref_phones").toLongArray()
        promptSemantic = voice.getJSONArray("prompt_semantic").toLongArray()
        geShape = voice.getJSONArray("ge_shape").toLongArray()
        val geBytes = File(voiceDir, "ge.bin").readBytes()
        ge = FloatArray(geBytes.size / 4).also {
            ByteBuffer.wrap(geBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }

        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(File(dir, "t2s_encoder.onnx").path, opts)
        // A voice fine-tuned on the device carries LoRA adapters; they replace the decoders'
        // lora.* initializers (B ~ 0 in the bundle, i.e. no adapter).
        val gptOpts = OnDeviceTrainer.readAdapters(voiceDir)?.let { adapters ->
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                adapters.forEach { (name, value) ->
                    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(value.first), value.second)
                    adapterTensors += t
                    addInitializer(name, t)
                }
            }
        } ?: opts
        firstStage = env.createSession(File(dir, "t2s_fs2.onnx").path, gptOpts)
        step = env.createSession(File(dir, "t2s_step.onnx").path, gptOpts)
        vits = env.createSession(File(dir, "vits.onnx").path, opts)
        loadMs = System.currentTimeMillis() - t0
    }

    fun synthesize(textPhones: LongArray, seed: Long = 1234L): Result {
        val owned = mutableListOf<OnnxTensor>()
        val own: (OnnxTensor) -> OnnxTensor = { t -> t.also { owned += it } }
        try {
            return run(textPhones, seed, own)
        } finally {
            owned.forEach { it.close() }
        }
    }

    /**
     * GPT decoding with an append-only KV cache: the step model returns only the new token's
     * keys/values ([1, layers, 512]); they are appended to direct buffers laid out [row, layer, 512]
     * so each step can pass the used prefix without rebuilding the cache.
     */
    private fun run(textPhones: LongArray, seed: Long, own: (OnnxTensor) -> OnnxTensor): Result {
        val allPhones = refPhones + textPhones
        val n = allPhones.size.toLong()
        val rng = Random(seed)
        val t0 = System.currentTimeMillis()

        val xEmb = encoder.run(mapOf(
            "x" to own(longTensor(allPhones, longArrayOf(1, n))),
            "bert" to own(OnnxTensor.createTensor(env, FloatBuffer.allocate((1024 * n).toInt()), longArrayOf(1, 1024, n))),
        ))
        val y = ArrayList<Long>(promptSemantic.size + maxTokens).apply { promptSemantic.forEach { add(it) } }
        var rows: Int
        var rowSize: Int
        val kBuf: FloatBuffer
        val vBuf: FloatBuffer
        var token: Long
        firstStage.run(mapOf(
            "x" to xEmb[0] as OnnxTensor,
            "prompts" to own(longTensor(promptSemantic, longArrayOf(1, promptSemantic.size.toLong()))),
        )).use { first ->
            val k0 = first[1] as OnnxTensor
            val shape = k0.info.shape  // [rows, layers, 512]
            rows = shape[0].toInt()
            rowSize = (shape[1] * shape[2]).toInt()
            val capacity = (rows + maxTokens) * rowSize
            kBuf = directFloats(capacity).apply { put(k0.floatBuffer) }
            vBuf = directFloats(capacity).apply { put((first[2] as OnnxTensor).floatBuffer) }
            token = sample((first[0] as OnnxTensor).floatBuffer, y, rng).first
        }
        xEmb.close()
        y += token

        var steps = 1
        while (steps < maxTokens) {
            val cacheShape = longArrayOf(rows.toLong(), (rowSize / 512).toLong(), 512)
            val done = step.run(mapOf(
                "token" to own(longTensor(longArrayOf(token), longArrayOf(1, 1))),
                "pos" to own(OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(y.size.toFloat())), longArrayOf(1))),
                "k_cache" to own(OnnxTensor.createTensor(env, prefix(kBuf, rows * rowSize), cacheShape)),
                "v_cache" to own(OnnxTensor.createTensor(env, prefix(vBuf, rows * rowSize), cacheShape)),
            )).use { out ->
                kBuf.position(rows * rowSize); kBuf.put((out[1] as OnnxTensor).floatBuffer)
                vBuf.position(rows * rowSize); vBuf.put((out[2] as OnnxTensor).floatBuffer)
                rows++
                val (next, eosGreedy) = sample((out[0] as OnnxTensor).floatBuffer, y, rng)
                token = next
                y += token
                eosGreedy || token == eos
            }
            if (done) break
            steps++
        }
        val codes = LongArray(steps) { y[y.size - steps + it] }
        codes[codes.size - 1] = 0
        val gptMs = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val noiseLen = interChannels * codes.size * 2
        val noise = FloatArray(noiseLen) { rng.nextGaussian().toFloat() }
        val audio = vits.run(mapOf(
            "codes" to own(longTensor(codes, longArrayOf(1, 1, codes.size.toLong()))),
            "text" to own(longTensor(textPhones, longArrayOf(1, textPhones.size.toLong()))),
            "ge" to own(OnnxTensor.createTensor(env, FloatBuffer.wrap(ge), geShape)),
            "noise" to own(OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), longArrayOf(1, interChannels.toLong(), codes.size * 2L))),
        )).use { r ->
            val t = r[0] as OnnxTensor
            FloatArray(t.info.shape[0].toInt()).also { t.floatBuffer.get(it) }
        }
        return Result(audio, steps, gptMs, System.currentTimeMillis() - t1)
    }

    /**
     * Same sampling as the GPT-SoVITS PyTorch inference path: repetition penalty 1.35 over all
     * previous tokens, top-k 15, then multinomial via an exponential race.
     * Returns the sampled token and whether the penalized argmax is EOS (the original stop rule).
     */
    private fun sample(logitsBuf: FloatBuffer, previous: List<Long>, rng: Random): Pair<Long, Boolean> {
        val logits = DoubleArray(logitsBuf.limit()) { logitsBuf.get(it).toDouble() }
        for (t in previous.toHashSet()) {
            val i = t.toInt()
            logits[i] = if (logits[i] < 0) logits[i] * REPETITION_PENALTY else logits[i] / REPETITION_PENALTY
        }
        var best = 0
        for (i in logits.indices) if (logits[i] > logits[best]) best = i
        val pivot = logits.sortedArrayDescending()[TOP_K - 1]
        val max = logits[best]
        var chosen = best
        var chosenScore = Double.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (logits[i] < pivot) continue
            // probs are proportional to exp(logit - max); dividing by Exp(1) noise samples them.
            val score = Math.exp(logits[i] - max) / -Math.log(1.0 - rng.nextDouble())
            if (score > chosenScore) { chosenScore = score; chosen = i }
        }
        return chosen.toLong() to (best.toLong() == eos)
    }

    private fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun prefix(buf: FloatBuffer, count: Int): FloatBuffer =
        buf.duplicate().apply { position(0); limit(count) }.slice()

    private fun longTensor(data: LongArray, shape: LongArray) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    override fun close() {
        encoder.close(); firstStage.close(); step.close(); vits.close()
        adapterTensors.forEach { it.close() }
    }

    companion object {
        private const val TOP_K = 15
        private const val REPETITION_PENALTY = 1.35

        private fun JSONArray.toLongArray() = LongArray(length()) { getLong(it) }

        /** Where a bundle downloaded from [bundleUrl] is stored on this device. */
        fun bundleDir(context: android.content.Context, bundleUrl: String): File {
            val key = MessageDigest.getInstance("SHA-256").digest(bundleUrl.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return File(context.filesDir, "ondevice/$key")
        }

        /** Downloads every file in the bundle manifest into [dir], skipping files already verified. */
        fun download(baseUrl: String, dir: File, onProgress: (String, Float) -> Unit) {
            dir.mkdirs()
            val base = baseUrl.trimEnd('/')
            val manifestText = fetchText("$base/manifest.json")
            val files = JSONObject(manifestText).getJSONObject("files")
            val total = files.keys().asSequence().sumOf { files.getJSONObject(it).getLong("size") }
            var done = 0L
            for (name in files.keys()) {
                val meta = files.getJSONObject(name)
                val size = meta.getLong("size")
                val target = File(dir, name)
                if (!(target.length() == size && sha256(target) == meta.getString("sha256"))) {
                    val part = File(dir, "$name.part")
                    fetchFile("$base/$name", part) { bytes -> onProgress(name, (done + bytes).toFloat() / total) }
                    if (part.length() != size || sha256(part) != meta.getString("sha256")) {
                        part.delete()
                        throw IllegalStateException("$name 파일이 손상되었습니다. 다시 시도해 주세요.")
                    }
                    part.renameTo(target)
                }
                done += size
                onProgress(name, done.toFloat() / total)
            }
            File(dir, "manifest.json").writeText(manifestText)
            // Drop files from an older bundle layout so they don't keep using storage.
            val keep = files.keys().asSequence().toSet() + "manifest.json"
            dir.listFiles()?.filter { it.isFile && it.name !in keep && !it.name.startsWith("bench_") }?.forEach { it.delete() }
        }

        private fun fetchText(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            try {
                if (conn.responseCode != 200) throw IllegalStateException("번들을 찾을 수 없습니다 (HTTP ${conn.responseCode}). 주소를 확인해 주세요.")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        /** Resumable download: continues from an existing .part file with a Range request. */
        private fun fetchFile(url: String, part: File, onBytes: (Long) -> Unit) {
            val existing = if (part.exists()) part.length() else 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            try {
                val append = conn.responseCode == 206
                if (!append && conn.responseCode != 200) throw IllegalStateException("다운로드 실패 (HTTP ${conn.responseCode})")
                RandomAccessFile(part, "rw").use { out ->
                    var written = if (append) existing else 0L
                    out.setLength(written)
                    out.seek(written)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            written += read
                            if (written - lastReport > (4 shl 20)) { onBytes(written); lastReport = written }
                        }
                    }
                    onBytes(written)
                }
            } finally {
                conn.disconnect()
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Writes mono float audio as 16-bit PCM WAV so MediaPlayer can play it. */
        fun writeWav(file: File, audio: FloatArray, sampleRate: Int) {
            val data = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in audio) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + audio.size * 2); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(audio.size * 2)
            }
            file.outputStream().use { it.write(header.array()); it.write(data.array()) }
        }
    }
}
