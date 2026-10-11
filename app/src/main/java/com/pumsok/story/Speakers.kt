package com.pumsok.story

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The people whose voices the app can learn (up to [MAX]). Each has a stable id used for file
 * names (recordings, voice model folder, reader voice key) and a display name the parent sets.
 */
data class Speaker(val id: String, val name: String)

object Speakers {
    const val MAX = 5
    val SUGGESTED = listOf("엄마", "아빠", "할머니", "할아버지", "이모", "삼촌", "언니", "형", "누나", "오빠")

    /** The recording script. Names are left out so anyone can read it. */
    val SCRIPT = listOf(
        "우리 아가, 오늘 하루도 재미있고 씩씩하게 잘 보냈니?",
        "창밖으로 별들이 반짝이고, 달님이 은은하게 미소를 짓는 밤이야.",
        "자, 이제 신비롭고 따뜻한 동화 나라로 함께 여행을 떠나보자.",
        "오늘 숲속에서는 어떤 신기하고 재미있는 일들이 일어났을까?",
        "와아! 알록달록 무지개 언덕에 달콤한 열매들이 가득 열렸네!",
        "쉿, 아기 곰이 새근새근 잠들었으니 발소리를 죽이고 조용히 걸어가자.",
        "아무 걱정하지 마렴, 언제나 네 곁에서 든든하게 지켜줄게.",
        "하늘 높이 날아오른 빨간 풍선이 구름 사이로 둥실둥실 떠갔어요.",
        "그러자 호랑이가 커다란 목소리로 외쳤어요. 거기 누구냐!",
        "사랑하는 우리 아가, 오늘 밤도 예쁜 꿈 꾸렴. 편안하게 잘 자렴.",
    )

    private fun prefs(context: Context) = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun load(context: Context): List<Speaker> {
        val raw = prefs(context).getString("speakers", null)
        if (raw == null) {
            // First run after the update: the two fixed roles become editable speakers.
            val initial = listOf(Speaker("MOTHER", "엄마"), Speaker("FATHER", "아빠"))
            save(context, initial)
            return initial
        }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Speaker(o.getString("id"), o.getString("name")) } }
    }

    private fun save(context: Context, list: List<Speaker>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs(context).edit().putString("speakers", arr.toString()).apply()
    }

    fun add(context: Context, name: String): Speaker {
        val list = load(context)
        require(list.size < MAX) { "목소리는 ${MAX}명까지 만들 수 있어요." }
        val speaker = Speaker("S${System.currentTimeMillis()}", name.trim().ifBlank { "가족" })
        save(context, list + speaker)
        return speaker
    }

    fun rename(context: Context, id: String, name: String) =
        save(context, load(context).map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })

    /** Removes the speaker with their recordings and voice model. */
    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
        deleteRecordings(context, id)
        OnDeviceVoices.localDir(context, id).deleteRecursively()
    }

    fun deleteRecordings(context: Context, id: String) =
        recordingFiles(context, id).forEach { (audio, text) -> audio.delete(); text.delete() }

    // ---- recordings: voice_<id>_<n>.m4a plus a .txt with the sentence actually read ----

    fun recording(context: Context, id: String, step: Int) = File(context.filesDir, "voice_${id}_$step.m4a")
    private fun recordingText(context: Context, id: String, step: Int) = File(context.filesDir, "voice_${id}_$step.txt")

    private fun recordingFiles(context: Context, id: String) =
        (0 until maxOf(SCRIPT.size, 8)).map { recording(context, id, it) to recordingText(context, id, it) }

    /** What was read in [step]'s recording: the saved text, or the current script line. */
    fun textFor(context: Context, id: String, step: Int): String =
        recordingText(context, id, step).takeIf { it.exists() }?.readText() ?: SCRIPT[step]

    fun saveText(context: Context, id: String, step: Int) = recordingText(context, id, step).writeText(SCRIPT[step])

    /** Recorded clips with the text that was read, for building and training the model. */
    fun clips(context: Context, id: String) = SCRIPT.indices.map { recording(context, id, it) to textFor(context, id, it) }

    /**
     * Before this version recordings were "voice_<ROLE>_<n>" for 8 lines whose 3rd and 7th
     * sentences named the role. Keep them and remember what was actually read.
     */
    fun migrateOldRecordings(context: Context) {
        val old = mapOf("MOTHER" to "엄마", "FATHER" to "아빠")
        old.forEach { (id, label) ->
            val oldScript = mapOf(
                2 to "${label}가 들려주는 신비롭고 따뜻한 동화 나라로 함께 여행을 떠나보자.",
                6 to "아무 걱정하지 마렴, 언제나 ${label}가 네 곁에서 든든하게 지켜줄게.",
                7 to "사랑하는 우리 아가, 오늘 밤도 예쁜 꿈 꾸렴. 편안하게 잘 자렴.",
            )
            // Old step 7 (good night) moves to the new last line (step 9).
            val oldLast = recording(context, id, 7)
            if (oldLast.exists() && !recordingText(context, id, 7).exists() && !recording(context, id, 9).exists()) {
                oldLast.renameTo(recording(context, id, 9))
                recordingText(context, id, 9).writeText(oldScript.getValue(7))
            }
            listOf(2, 6).forEach { step ->
                if (recording(context, id, step).exists() && !recordingText(context, id, step).exists()) {
                    recordingText(context, id, step).writeText(oldScript.getValue(step))
                }
            }
        }
    }
}
