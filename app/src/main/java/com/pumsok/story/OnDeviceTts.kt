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
 * the SoVITS decoder. Mirrors scripts/ort_infer.py in the bench.
 */
class OnDeviceTts(private val dir: File, threads: Int) : AutoCloseable {
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

    private val encoder: OrtSession
    private val firstStage: OrtSession
    private val stage: OrtSession
    private val vits: OrtSession
    val loadMs: Long

    init {
        val voice = JSONObject(File(dir, "voice.json").readText())
        refPhones = voice.getJSONArray("ref_phones").toLongArray()
        promptSemantic = voice.getJSONArray("prompt_semantic").toLongArray()
        geShape = voice.getJSONArray("ge_shape").toLongArray()
        val geBytes = File(dir, "ge.bin").readBytes()
        ge = FloatArray(geBytes.size / 4).also {
            ByteBuffer.wrap(geBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }

        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(File(dir, "t2s_encoder.onnx").path, opts)
        firstStage = env.createSession(File(dir, "t2s_fsdec.onnx").path, opts)
        stage = env.createSession(File(dir, "t2s_sdec.onnx").path, opts)
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

    private fun run(textPhones: LongArray, seed: Long, own: (OnnxTensor) -> OnnxTensor): Result {
        val allPhones = refPhones + textPhones
        val n = allPhones.size.toLong()
        val t0 = System.currentTimeMillis()

        val xEmb = encoder.run(mapOf(
            "x" to own(longTensor(allPhones, longArrayOf(1, n))),
            "bert" to own(OnnxTensor.createTensor(env, FloatBuffer.allocate((1024 * n).toInt()), longArrayOf(1, 1024, n))),
        ))
        val first = firstStage.run(mapOf(
            "x" to xEmb[0] as OnnxTensor,
            "prompts" to own(longTensor(promptSemantic, longArrayOf(1, promptSemantic.size.toLong()))),
        ))
        val xExample = first[4] as OnnxTensor
        var prev: OrtSession.Result? = null
        var y = first[0] as OnnxTensor
        var k = first[1] as OnnxTensor
        var v = first[2] as OnnxTensor
        var yEmb = first[3] as OnnxTensor
        var steps = 1
        while (steps < maxTokens) {
            val out = stage.run(mapOf("iy" to y, "ik" to k, "iv" to v, "iy_emb" to yEmb, "ix_example" to xExample))
            prev?.close()
            prev = out
            y = out[0] as OnnxTensor
            k = out[1] as OnnxTensor
            v = out[2] as OnnxTensor
            yEmb = out[3] as OnnxTensor
            val logits = (out[4] as OnnxTensor).floatBuffer
            // samples is int32 in the exported graph (y is int64), so read it as an int buffer.
            val sample = (out[5] as OnnxTensor).intBuffer.get(0).toLong()
            if (argmax(logits) == eos.toInt() || sample == eos) break
            steps++
        }
        val yAll = LongArray(y.info.shape[1].toInt()).also { y.longBuffer.get(it) }
        prev?.close()
        first.close()
        xEmb.close()
        val codes = yAll.copyOfRange(yAll.size - steps, yAll.size)
        codes[codes.size - 1] = 0
        val gptMs = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val rng = Random(seed)
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

    private fun longTensor(data: LongArray, shape: LongArray) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun argmax(buf: FloatBuffer): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until buf.limit()) {
            val value = buf.get(i)
            if (value > bestValue) { bestValue = value; best = i }
        }
        return best
    }

    override fun close() {
        encoder.close(); firstStage.close(); stage.close(); vits.close()
    }

    companion object {
        private fun JSONArray.toLongArray() = LongArray(length()) { getLong(it) }

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
