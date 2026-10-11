package com.pumsok.story

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

private val Brown = Color(0xFF795548)
private val Green = Color(0xFF2E7D32)
private val Purple = Color(0xFF6A1B9A)

class MainActivity : ComponentActivity() {
    private class VoiceChoice(val key: String, val label: String)

    private val updater by lazy { Updater(this) }
    private val onDeviceBook by lazy { OnDeviceBook(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val threads get() = prefs.getInt("ondevice_threads", 6)

    private var tab by mutableIntStateOf(0)
    private var message by mutableStateOf("")

    // Story tab
    private var story by mutableStateOf<Story?>(null)
    private var index by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var readerVoice by mutableStateOf("tts")
    private var reading by mutableStateOf(false)
    private var readerStatus by mutableStateOf("")
    private var samples by mutableStateOf<List<Pair<String, String>>>(emptyList())

    // Voice tab
    private val totalSteps = Speakers.SCRIPT.size
    private var speakers by mutableStateOf<List<Speaker>>(emptyList())
    private var speakerId by mutableStateOf("MOTHER")
    private val speaker: Speaker? get() = speakers.firstOrNull { it.id == speakerId } ?: speakers.firstOrNull()
    private var recordStartedAt by mutableLongStateOf(0L)
    private var speakerDialog by mutableStateOf<String?>(null)  // "add" | "rename" | "delete"
    private var dialogName by mutableStateOf("")
    private var consent by mutableStateOf(false)
    private var currentStep by mutableIntStateOf(0)
    private var stepRecorded by mutableStateOf(List(8) { false })
    private var recording by mutableStateOf(false)
    private var voiceWorking by mutableStateOf(false)
    private var voiceProgress by mutableFloatStateOf(0f)
    private var voiceStatus by mutableStateOf("")
    // Bumped when a voice is built or trained, so cards re-read what is on disk.
    private var voiceVersion by mutableIntStateOf(0)

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)

    private fun stepFile(id: String, step: Int) = Speakers.recording(this, id, step)
    private fun refreshRecorded() {
        val id = speaker?.id
        stepRecorded = (0 until totalSteps).map { id != null && stepFile(id, it).exists() }
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && consent) startRecording() else message = "녹음하려면 마이크 권한과 동의가 필요해요."
    }
    private val importBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        executor.execute {
            val temp = File(filesDir, "import.tmp")
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= 32 * 1024 * 1024) { "32MB 이하 EPUB을 선택해 주세요" }
                            out.write(buffer, 0, n)
                        }
                    }
                } ?: error("파일을 열 수 없어요")
                val parsed = EpubReader.read(temp)
                check(temp.renameTo(File(filesDir, "book.epub"))) { "책을 저장하지 못했어요" }
                parsed
            }.onSuccess { parsed -> runOnUiThread { openStory(parsed); busy = false } }
                .onFailure { e -> temp.delete(); runOnUiThread { message = e.message ?: "가져오기 실패"; busy = false } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater.checkOnLaunch()
        speakers = Speakers.load(this)
        speakerId = prefs.getString("speaker", null) ?: prefs.getString("role", null) ?: speakers.firstOrNull()?.id ?: ""
        migrateRecordings()
        Speakers.migrateOldRecordings(this)
        consent = prefs.getBoolean("consent", false)
        refreshRecorded()
        readerVoice = prefs.getString("reader_voice", null) ?: defaultReaderVoice()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                ttsReady = result != null && result >= TextToSpeech.LANG_AVAILABLE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) { runOnUiThread { reading = false } }
                    override fun onDone(id: String?) {
                        val i = id?.removePrefix("p")?.toIntOrNull() ?: return
                        runOnUiThread { if (reading) speakFrom(i + 1) }
                    }
                })
            }
        }
        executor.execute {
            val found = (assets.list("")?.filter { it.endsWith(".epub") } ?: emptyList()).mapNotNull { name ->
                runCatching {
                    val f = File(cacheDir, name)
                    assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    name to EpubReader.read(f).title
                }.getOrNull()
            }
            runOnUiThread { samples = found }
            val saved = File(filesDir, "book.epub")
            val parsed = runCatching { EpubReader.read(saved) }.getOrNull()
                ?: runCatching {
                    assets.open("sun_and_moon.epub").use { i -> saved.outputStream().use { o -> i.copyTo(o) } }
                    EpubReader.read(saved)
                }.getOrNull()
            if (parsed != null) runOnUiThread {
                story = parsed
                index = prefs.getInt("index", 0).coerceIn(parsed.paragraphs.indices)
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Brown, background = Color(0xFFFFF8EF), surface = Color(0xFFFFF8EF)
            )) {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf("📖" to "동화", "🎙️" to "모델", "⚙️" to "설정").forEachIndexed { i, (icon, label) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Text(icon, fontSize = 20.sp) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                                .safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (tab) {
                                0 -> StoryTab()
                                1 -> VoiceTab()
                                else -> SettingsTab()
                            }
                            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    /** Recordings were once shared by both roles; keep them for the role that was selected. */
    private fun migrateRecordings() {
        val id = prefs.getString("role", "MOTHER") ?: "MOTHER"
        (0 until 8).forEach { step ->
            val old = File(filesDir, "voice_step_$step.m4a")
            if (old.exists() && !stepFile(id, step).exists()) old.renameTo(stepFile(id, step))
        }
    }

    // ---------------------------------------------------------------- 📖 Story

    private fun voiceChoices(): List<VoiceChoice> {
        val list = mutableListOf<VoiceChoice>()
        speakers.forEach { sp ->
            if (OnDeviceVoices.hasLocal(this, sp.id)) {
                val kind = if (OnDeviceVoices.isTrained(this, sp.id)) "더 닮게 학습" else "기본"
                list += VoiceChoice("local:${sp.id}", "${sp.name} ($kind)")
            }
        }
        if (OnDeviceVoices.serverBundleUrl(this).isNotBlank()) list += VoiceChoice("server", "서버에서 학습한 목소리")
        list += VoiceChoice("tts", "기기 기본 음성")
        return list
    }

    private fun defaultReaderVoice() = voiceChoices().first().key

    private fun chooseReaderVoice(key: String) {
        stopReading()
        readerVoice = key
        prefs.edit().putString("reader_voice", key).apply()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun StoryTab() {
        Text("품속동화", style = MaterialTheme.typography.headlineMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            samples.forEach { (asset, title) ->
                FilterChip(
                    selected = story?.title == title,
                    onClick = { loadAssetStory(asset) },
                    label = { Text(title) },
                    enabled = !busy
                )
            }
            AssistChip(
                onClick = { importBook.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                label = { Text(if (busy) "불러오는 중…" else "+ 내 EPUB") },
                enabled = !busy
            )
        }
        val book = story ?: return
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("${index + 1} / ${book.paragraphs.size}", style = MaterialTheme.typography.labelLarge)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(book.paragraphs[index], style = MaterialTheme.typography.bodyLarge, lineHeight = 30.sp, modifier = Modifier.padding(18.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { move(-1) }, enabled = index > 0) { Text("← 이전") }
            TextButton(onClick = { move(1) }, enabled = index < book.paragraphs.lastIndex) { Text("다음 →") }
        }

        val choices = remember(voiceVersion, tab) { voiceChoices() }
        LaunchedEffect(choices) { if (choices.none { it.key == readerVoice }) chooseReaderVoice(choices.first().key) }
        val selection = remember(readerVoice, voiceVersion) { OnDeviceVoices.selectionFor(this, readerVoice) }
        LaunchedEffect(book, selection?.voiceDir, selection?.modelUrl, voiceVersion) { onDeviceBook.attach(book, selection) }
        LaunchedEffect(onDeviceBook.running) {
            // Generating takes minutes; keep the screen (and the app) in the foreground meanwhile.
            if (onDeviceBook.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E0)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("누구 목소리로 읽을까요?", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c ->
                        FilterChip(selected = readerVoice == c.key, onClick = { chooseReaderVoice(c.key) }, label = { Text(c.label) })
                    }
                }
                if (choices.size == 1) {
                    Text("‘🎙️ 모델’ 탭에서 가족 목소리 모델을 만들면 여기서 고를 수 있어요.", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (reading) {
                        Button(onClick = { stopReading() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("⏹ 멈추기")
                        }
                    } else {
                        Button(onClick = { startReading() }, enabled = readerVoice != "tts" || ttsReady) { Text("▶ 여기서부터 읽어주기") }
                    }
                }
                if (readerStatus.isNotBlank()) Text(readerStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (selection != null) {
                    HorizontalDivider()
                    val ob = onDeviceBook
                    Text("책 전체 준비 ${ob.readyCount}/${ob.total} 문단 · 다 만들면 인터넷 없이 들을 수 있어요", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { ob.progress }, modifier = Modifier.fillMaxWidth())
                    if (ob.status.isNotBlank()) Text(ob.status, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ob.running) OutlinedButton(onClick = { ob.stop() }) { Text("미리 만들기 중지") }
                        else if (ob.readyCount < ob.total) OutlinedButton(onClick = { ob.start(book, selection, index) }) { Text("책 전체 미리 만들기") }
                    }
                }
            }
        }
    }

    private fun startReading() {
        if (story == null) return
        stopAllAudio()
        reading = true
        readerStatus = ""
        if (readerVoice == "tts") speakFrom(index) else playGeneratedFrom(index, startedGeneration = false)
    }

    private fun stopReading() {
        reading = false
        readerStatus = ""
        handler.removeCallbacksAndMessages(null)
        stopAllAudio()
    }

    private fun speakFrom(i: Int) {
        val book = story ?: return
        if (i > book.paragraphs.lastIndex) { reading = false; return }
        showParagraph(i)
        tts?.speak(book.paragraphs[i], TextToSpeech.QUEUE_FLUSH, null, "p$i")
    }

    /** Plays generated paragraphs from [i] on, generating ahead in the background when needed. */
    private fun playGeneratedFrom(i: Int, startedGeneration: Boolean) {
        val book = story ?: return
        if (!reading) return
        if (i > book.paragraphs.lastIndex) { reading = false; readerStatus = "끝까지 읽었어요."; return }
        showParagraph(i)
        val file = onDeviceBook.fileFor(i)
        when {
            file != null -> {
                readerStatus = ""
                playFile(file) { playGeneratedFrom(i + 1, startedGeneration) }
            }
            onDeviceBook.running -> {
                readerStatus = "이 문단을 목소리로 만드는 중…"
                handler.postDelayed({ playGeneratedFrom(i, startedGeneration) }, 500)
            }
            !startedGeneration -> {
                val sel = OnDeviceVoices.selectionFor(this, readerVoice) ?: run { reading = false; return }
                readerStatus = "목소리를 준비하는 중…"
                onDeviceBook.start(book, sel, i)
                handler.postDelayed({ playGeneratedFrom(i, true) }, 500)
            }
            else -> {
                reading = false
                readerStatus = onDeviceBook.status.ifBlank { "목소리를 만들지 못했어요." }
            }
        }
    }

    private fun showParagraph(i: Int) {
        if (index != i) {
            index = i
            prefs.edit().putInt("index", i).apply()
        }
    }

    private fun move(delta: Int) {
        stopReading()
        showParagraph((index + delta).coerceIn(0, story?.paragraphs?.lastIndex ?: 0))
    }

    private fun openStory(parsed: Story) {
        stopReading()
        story = parsed
        index = 0
        prefs.edit().putInt("index", 0).apply()
        message = ""
    }

    private fun loadAssetStory(assetName: String) {
        busy = true
        executor.execute {
            val book = File(filesDir, "book.epub")
            runCatching {
                assets.open(assetName).use { i -> book.outputStream().use { o -> i.copyTo(o) } }
                EpubReader.read(book)
            }.onSuccess { parsed -> runOnUiThread { openStory(parsed); busy = false } }
                .onFailure { e -> runOnUiThread { message = e.message ?: "동화 불러오기 실패"; busy = false } }
        }
    }

    // ---------------------------------------------------------------- 🎙️ Voice

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun VoiceTab() {
        Text("목소리 모델 만들기", style = MaterialTheme.typography.headlineMedium)
        Text("가족 목소리를 ${Speakers.MAX}명까지 만들 수 있어요. 누구 목소리를 만들까요?", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            speakers.forEach { sp ->
                FilterChip(
                    selected = speaker?.id == sp.id,
                    onClick = { selectSpeaker(sp.id) },
                    label = { Text(sp.name + if (OnDeviceVoices.hasLocal(this@MainActivity, sp.id)) " ✓" else "") },
                    enabled = !recording && !voiceWorking
                )
            }
            if (speakers.size < Speakers.MAX) {
                AssistChip(
                    onClick = { dialogName = ""; speakerDialog = "add" },
                    label = { Text("+ 추가") },
                    enabled = !recording && !voiceWorking
                )
            }
        }
        SpeakerDialogs()
        val sp = speaker ?: run {
            Text("‘+ 추가’를 눌러 목소리를 만들 사람을 정해 주세요.", style = MaterialTheme.typography.bodySmall)
            return
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { dialogName = sp.name; speakerDialog = "rename" }, enabled = !recording && !voiceWorking) { Text("이름 바꾸기") }
            TextButton(onClick = { speakerDialog = "delete" }, enabled = !recording && !voiceWorking) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, onCheckedChange = { consent = it; prefs.edit().putBoolean("consent", it).apply() }, enabled = !recording)
            Text("내 목소리이거나, 녹음하는 분의 동의를 받았어요.", style = MaterialTheme.typography.bodyMedium)
        }

        val recordedCount = stepRecorded.count { it }
        val hasVoice = remember(voiceVersion, sp.id) { OnDeviceVoices.hasLocal(this, sp.id) }
        val trained = remember(voiceVersion, sp.id) { OnDeviceVoices.isTrained(this, sp.id) }

        StepCard("①", "대본 녹음", done = recordedCount == totalSteps, summary = "$recordedCount / $totalSteps 문장") { RecordingPanel(sp) }
        StepCard("②", "모델 만들기", done = hasVoice, summary = if (hasVoice) "완료" else "녹음 1문장부터") {
            Text("녹음한 문장 하나로 ${sp.name} 목소리 모델을 이 기기에서 만들어요. 몇 초면 끝나요. 녹음은 기기 밖으로 나가지 않아요.", style = MaterialTheme.typography.bodySmall)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Text(
                    "💡 기본 모델이란? 수천 명의 목소리로 미리 학습된 공용 음성 AI예요(약 750MB, 처음 한 번만 내려받아요). 여기에는 어떤 가족의 목소리도 들어 있지 않고, 이 기본 모델이 녹음을 분석해서 ${sp.name}만의 목소리 모델을 만들어요. Wi-Fi에서 해 주세요.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)
                )
            }
            Button(onClick = { buildVoice(sp) }, enabled = consent && recordedCount > 0 && !voiceWorking && !recording) {
                Text(if (hasVoice) "모델 다시 만들기" else "모델 만들기")
            }
        }
        StepCard("③", "모델 더 닮게 학습", done = trained, summary = if (trained) "완료" else "녹음 4문장 이상") {
            Text("녹음한 문장 전부로 ${sp.name}의 말투를 이 기기에서 학습해요. 1~3분쯤 걸리고 기기가 따뜻해질 수 있어요. 충전기를 꽂고 화면을 켜 둔 채로 해 주세요. 많이 녹음할수록 더 닮아요.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { trainVoice(sp) },
                enabled = consent && hasVoice && recordedCount >= 4 && !voiceWorking && !recording,
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) { Text(if (trained) "다시 학습하기" else "학습하기") }
            if (!hasVoice) Text("먼저 ② 모델 만들기를 해 주세요.", style = MaterialTheme.typography.bodySmall)
        }

        if (voiceWorking) LinearProgressIndicator(progress = { voiceProgress }, modifier = Modifier.fillMaxWidth())
        if (voiceStatus.isNotBlank()) Text(voiceStatus, color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = { previewVoice(sp) },
            enabled = hasVoice && !voiceWorking && !recording,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) { Text("🎧 ${sp.name} 목소리 들어보기", fontSize = 16.sp) }
    }

    private fun selectSpeaker(id: String) {
        speakerId = id
        prefs.edit().putString("speaker", id).apply()
        refreshRecorded()
        currentStep = stepRecorded.indexOfFirst { !it }.coerceAtLeast(0)
        voiceStatus = ""
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun SpeakerDialogs() {
        when (speakerDialog) {
            "add", "rename" -> AlertDialog(
                onDismissRequest = { speakerDialog = null },
                title = { Text(if (speakerDialog == "add") "목소리 추가" else "이름 바꾸기") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = dialogName, onValueChange = { dialogName = it.take(10) }, label = { Text("이름") }, singleLine = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Speakers.SUGGESTED.filter { s -> speakers.none { it.name == s } }.take(6).forEach { s ->
                                SuggestionChip(onClick = { dialogName = s }, label = { Text(s) })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val sp = speaker
                        if (speakerDialog == "add") selectSpeaker(Speakers.add(this, dialogName).id.also { speakers = Speakers.load(this) })
                        else if (sp != null) Speakers.rename(this, sp.id, dialogName)
                        speakers = Speakers.load(this)
                        speakerDialog = null
                        voiceVersion++
                    }, enabled = dialogName.isNotBlank()) { Text("확인") }
                },
                dismissButton = { TextButton(onClick = { speakerDialog = null }) { Text("취소") } }
            )
            "delete" -> AlertDialog(
                onDismissRequest = { speakerDialog = null },
                title = { Text("${speaker?.name ?: ""} 삭제") },
                text = { Text("녹음과 목소리 모델을 모두 지워요. 되돌릴 수 없어요.") },
                confirmButton = {
                    TextButton(onClick = {
                        speaker?.let { Speakers.delete(this, it.id) }
                        speakers = Speakers.load(this)
                        speakers.firstOrNull()?.let { selectSpeaker(it.id) } ?: refreshRecorded()
                        speakerDialog = null
                        voiceVersion++
                    }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { speakerDialog = null }) { Text("취소") } }
            )
        }
    }

    @Composable private fun StepCard(number: String, title: String, done: Boolean, summary: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (done) Color(0xFFEFF7EE) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("$number $title", style = MaterialTheme.typography.titleMedium)
                    Text(if (done) "✓ $summary" else summary, style = MaterialTheme.typography.labelLarge, color = if (done) Green else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun RecordingPanel(sp: Speaker) {
        val recordedCount = stepRecorded.count { it }
        LinearProgressIndicator(progress = { recordedCount / totalSteps.toFloat() }, modifier = Modifier.fillMaxWidth())
        Text("조용한 곳에서, 아이에게 말해 주듯 편안하고 또박또박 읽어 주세요.", style = MaterialTheme.typography.bodySmall)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${currentStep + 1}번째 문장 / $totalSteps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(Speakers.SCRIPT[currentStep], style = MaterialTheme.typography.titleLarge, lineHeight = 32.sp)
                if (stepRecorded[currentStep] && Speakers.textFor(this@MainActivity, sp.id, currentStep) != Speakers.SCRIPT[currentStep]) {
                    Text("예전 대본으로 녹음한 문장이에요. 그대로 써도 되고, 다시 녹음해도 돼요.", style = MaterialTheme.typography.bodySmall)
                }
                var elapsed by remember { mutableIntStateOf(0) }
                LaunchedEffect(recording) {
                    elapsed = 0
                    while (recording) { kotlinx.coroutines.delay(200); elapsed = ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt() }
                }
                Button(
                    onClick = {
                        if (recording) finishRecording(sp)
                        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else microphone.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = consent && !voiceWorking,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) MaterialTheme.colorScheme.error else Brown)
                ) { Text(if (recording) "⏹" else "🎙️", fontSize = 34.sp) }
                Text(
                    when {
                        recording -> "녹음 중 ${elapsed}초 · 다 읽으면 눌러 주세요"
                        stepRecorded[currentStep] -> "녹음했어요 · 다시 누르면 새로 녹음해요"
                        consent -> "버튼을 누르고 문장을 읽어 주세요"
                        else -> "녹음하려면 위의 동의에 체크해 주세요"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentStep-- }, enabled = currentStep > 0 && !recording) { Text("← 이전") }
                    OutlinedButton(onClick = { playFile(stepFile(sp.id, currentStep)) }, enabled = stepRecorded[currentStep] && !recording) { Text("▶ 들어보기") }
                    TextButton(onClick = { currentStep++ }, enabled = currentStep < totalSteps - 1 && !recording) { Text("다음 →") }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until totalSteps).forEach { step ->
                FilterChip(
                    selected = step == currentStep,
                    onClick = { currentStep = step },
                    label = { Text("${step + 1}${if (stepRecorded[step]) " ✓" else ""}") },
                    enabled = !recording
                )
            }
        }
        if (recordedCount > 0) {
            TextButton(onClick = { deleteRecordings(sp) }, enabled = !recording && !voiceWorking) { Text("${sp.name} 녹음 모두 지우기") }
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        stopReading()
        val tmp = File(filesDir, "voice.tmp")
        val next = MediaRecorder()
        runCatching {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(24000)
            next.setAudioEncodingBitRate(96000)
            next.setOutputFile(tmp.absolutePath)
            next.prepare(); next.start()
        }.onSuccess {
            recorder = next
            recordStartedAt = System.currentTimeMillis()
            recording = true
            voiceStatus = ""
        }.onFailure {
            next.release(); tmp.delete()
            voiceStatus = "녹음을 시작하지 못했어요."
        }
    }

    private fun finishRecording(sp: Speaker? = speaker) {
        val active = recorder ?: return
        val pending = File(filesDir, "voice.tmp")
        runCatching {
            active.stop()
            check(sp != null)
            check(System.currentTimeMillis() - recordStartedAt > 1500) { "too short" }
            val target = stepFile(sp.id, currentStep)
            target.delete()
            check(pending.renameTo(target))
            Speakers.saveText(this, sp.id, currentStep)
            stepRecorded = stepRecorded.toMutableList().also { it[currentStep] = true }
            val done = stepRecorded.count { it }
            voiceStatus = "${currentStep + 1}번 문장을 저장했어요 ($done/$totalSteps)."
            stepRecorded.indexOfFirst { !it }.takeIf { it >= 0 }?.let { currentStep = it }
        }.onFailure {
            pending.delete()
            voiceStatus = "녹음이 너무 짧거나 저장하지 못했어요."
        }
        active.release(); recorder = null; recording = false
    }

    private fun deleteRecordings(sp: Speaker) {
        stopAllAudio()
        Speakers.deleteRecordings(this, sp.id)
        stepRecorded = List(totalSteps) { false }
        currentStep = 0
        voiceStatus = "${sp.name} 녹음을 지웠어요. 만든 목소리 모델은 그대로 있어요."
    }

    private fun runVoiceJob(start: String, job: ((String, Float) -> Unit) -> String) {
        stopReading()
        voiceWorking = true
        voiceProgress = 0f
        voiceStatus = start
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val result = runCatching { job { s, p -> runOnUiThread { voiceStatus = s; voiceProgress = p } } }
            runOnUiThread {
                voiceStatus = result.getOrElse { "실패: ${it.message ?: it.javaClass.simpleName}" }
                voiceWorking = false
                voiceVersion++
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.start()
    }

    private fun buildVoice(sp: Speaker) {
        runVoiceJob("준비 중…") { onStatus ->
            val info = OnDeviceVoices.build(this, sp.id, Speakers.clips(this, sp.id), threads, onStatus)
            runOnUiThread { chooseReaderVoice("local:${sp.id}") }
            "${sp.name} 목소리 모델을 만들었어요 (녹음 %.1f초 사용). 아래 ‘들어보기’로 확인해 보세요.".format(info.seconds)
        }
    }

    private fun trainVoice(sp: Speaker) {
        runVoiceJob("학습 준비 중…") { onStatus ->
            val info = OnDeviceVoices.train(this, sp.id, Speakers.clips(this, sp.id), threads, onStatus)
            reportTraining(info)
            runOnUiThread { chooseReaderVoice("local:${sp.id}") }
            "학습을 마쳤어요 (%.1f분). 아래 ‘들어보기’로 확인해 보세요.".format(info.millis / 60000f)
        }
    }

    /** Synthesizes a sample sentence with [sp]'s on-device voice (including trained adapters) and plays it. */
    private fun previewVoice(sp: Speaker) {
        stopReading()
        voiceWorking = true
        voiceProgress = 0.5f
        voiceStatus = "샘플 문장을 만드는 중…"
        Thread {
            val result = runCatching {
                val baseDir = OnDeviceTts.bundleDir(this, OnDeviceVoices.BASE_BUNDLE_URL)
                val sample = JSONArray(File(baseDir, "bench.json").readText()).getJSONObject(0)
                val phones = sample.getJSONArray("phones").let { a -> LongArray(a.length()) { a.getLong(it) } }
                val wav = File(cacheDir, "voice_preview.wav")
                OnDeviceTts(baseDir, threads, OnDeviceVoices.localDir(this, sp.id), prefs.getString("ondevice_accel", "cpu") ?: "cpu").use { t ->
                    OnDeviceTts.writeWav(wav, t.synthesize(phones).audio, t.sampleRate)
                }
                sample.getString("text") to wav
            }
            runOnUiThread {
                voiceWorking = false
                result.onSuccess { (text, wav) -> voiceStatus = "“$text”"; playFile(wav) }
                    .onFailure { voiceStatus = "들어보기 실패: ${it.message ?: it.javaClass.simpleName}" }
            }
        }.start()
    }

    /** Training speed only (no audio or voice data), like the engine test report. */
    private fun reportTraining(info: OnDeviceTrainer.Info) = runCatching {
        val body = org.json.JSONObject()
            .put("kind", "train")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("soc", if (android.os.Build.VERSION.SDK_INT >= 31) "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE)
            .put("app_version", packageManager.getPackageInfo(packageName, 0).versionName)
            .put("threads", threads)
            .put("steps", info.steps).put("train_ms", info.millis)
            .put("first_loss", info.firstLoss.toDouble()).put("last_loss", info.lastLoss.toDouble())
        val conn = java.net.URL("https://d32.org/api/voice/bench").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.responseCode; conn.disconnect()
    }

    // ---------------------------------------------------------------- ⚙️ Settings

    @Composable private fun SettingsTab() {
        Text("설정", style = MaterialTheme.typography.headlineMedium)
        OnDeviceBenchCard(enabled = !recording && !voiceWorking, playFile = { playFile(it) })
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("저장 공간", style = MaterialTheme.typography.titleMedium)
                Text("미리 만들어 둔 책 음성을 지워요. 목소리와 모델은 그대로 있어요.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    stopReading()
                    File(filesDir, "ondevice/books").deleteRecursively()
                    voiceVersion++
                    message = "만든 책 음성을 지웠어요."
                }, enabled = !onDeviceBook.running) { Text("만든 책 음성 지우기") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("앱 정보", style = MaterialTheme.typography.titleMedium)
                Text("버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { updater.checkOnLaunch(manual = true) }) { Text("업데이트 확인") }
            }
        }
    }

    // ---------------------------------------------------------------- audio

    private fun playFile(file: File, onComplete: (() -> Unit)? = null) {
        if (!file.exists()) return
        stopAllAudio()
        runCatching {
            MediaPlayer().also { p ->
                player = p
                p.setDataSource(file.absolutePath)
                p.setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    runOnUiThread { onComplete?.invoke() }
                }
                p.prepare()
                p.start()
            }
        }.onFailure {
            player?.release()
            player = null
            message = "소리를 재생하지 못했어요."
        }
    }

    private fun stopAllAudio() {
        tts?.stop()
        player?.release()
        player = null
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onStop() {
        if (recording) finishRecording()
        stopReading()
        super.onStop()
    }

    override fun onDestroy() {
        updater.release()
        recorder?.release(); player?.release(); tts?.shutdown()
        executor.shutdown()
        super.onDestroy()
    }
}
