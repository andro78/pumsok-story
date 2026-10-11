package com.pumsok.story

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** On-disk format of LoRA adapters trained on the device (lora.bin + lora.json in the voice folder). */
object LoraAdapters {
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
