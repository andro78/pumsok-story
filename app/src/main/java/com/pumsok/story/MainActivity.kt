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
    enum class SpeakerRole(val label: String) { MOTHER("엄마"), FATHER("아빠") }
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
    private val totalSteps = 8
    private var role by mutableStateOf(SpeakerRole.MOTHER)
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

    private fun stepScript(step: Int, r: SpeakerRole): String = when (step) {
        0 -> "우리 아가, 오늘 하루도 재미있고 씩씩하게 잘 보냈니?"
        1 -> "창밖으로 별들이 반짝이고, 달님이 은은하게 미소를 짓는 밤이야."
        2 -> "${r.label}가 들려주는 신비롭고 따뜻한 동화 나라로 함께 여행을 떠나보자."
        3 -> "오늘 숲속에서는 어떤 신기하고 재미있는 일들이 일어났을까?"
        4 -> "와아! 알록달록 무지개 언덕에 달콤한 열매들이 가득 열렸네!"
        5 -> "쉿, 아기 곰이 새근새근 잠들었으니 발소리를 죽이고 조용히 걸어가자."
        6 -> "아무 걱정하지 마렴, 언제나 ${r.label}가 네 곁에서 든든하게 지켜줄게."
        7 -> "사랑하는 우리 아가, 오늘 밤도 예쁜 꿈 꾸렴. 편안하게 잘 자렴."
        else -> ""
    }

    private fun stepFile(r: SpeakerRole, step: Int) = File(filesDir, "voice_${r.name}_$step.m4a")
    private fun clips(r: SpeakerRole) = (0 until totalSteps).map { stepFile(r, it) to stepScript(it, r) }

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
        role = if (prefs.getString("role", "MOTHER") == "FATHER") SpeakerRole.FATHER else SpeakerRole.MOTHER
        migrateRecordings()
        consent = prefs.getBoolean("consent", false)
        stepRecorded = (0 until totalSteps).map { stepFile(role, it).exists() }
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
                            listOf("📖" to "동화", "🎙️" to "목소리", "⚙️" to "설정").forEachIndexed { i, (icon, label) ->
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

    /** Recordings used to be shared by both roles; keep them for the role that was selected. */
    private fun migrateRecordings() {
        (0 until totalSteps).forEach { step ->
            val old = File(filesDir, "voice_step_$step.m4a")
            if (old.exists() && !stepFile(role, step).exists()) old.renameTo(stepFile(role, step))
        }
    }

    // ---------------------------------------------------------------- 📖 Story

    private fun voiceChoices(): List<VoiceChoice> {
        val list = mutableListOf<VoiceChoice>()
        SpeakerRole.entries.forEach { r ->
            if (OnDeviceVoices.hasLocal(this, r.name)) {
                val kind = if (OnDeviceVoices.isTrained(this, r.name)) "기기 학습" else "빠르게 만든"
                list += VoiceChoice("local:${r.name}", "${r.label} 목소리 ($kind)")
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
                    Text("‘🎙️ 목소리’ 탭에서 엄마·아빠 목소리를 만들면 여기서 고를 수 있어요.", style = MaterialTheme.typography.bodySmall)
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

    @Composable private fun VoiceTab() {
        Text("목소리 만들기", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeakerRole.entries.forEach { r ->
                FilterChip(
                    selected = role == r,
                    onClick = {
                        role = r
                        prefs.edit().putString("role", r.name).apply()
                        stepRecorded = (0 until totalSteps).map { stepFile(r, it).exists() }
                        currentStep = stepRecorded.indexOfFirst { !it }.coerceAtLeast(0)
                        voiceStatus = ""
                    },
                    label = { Text("${r.label} 목소리") },
                    enabled = !recording && !voiceWorking
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, onCheckedChange = { consent = it; prefs.edit().putBoolean("consent", it).apply() }, enabled = !recording)
            Text("내 목소리이거나, 녹음하는 분의 동의를 받았어요.", style = MaterialTheme.typography.bodyMedium)
        }

        val recordedCount = stepRecorded.count { it }
        val hasVoice = remember(voiceVersion, role) { OnDeviceVoices.hasLocal(this, role.name) }
        val trained = remember(voiceVersion, role) { OnDeviceVoices.isTrained(this, role.name) }

        StepCard("①", "대본 녹음", done = recordedCount == totalSteps, summary = "$recordedCount / $totalSteps 문장") { RecordingPanel() }
        StepCard("②", "목소리 만들기", done = hasVoice, summary = if (hasVoice) "완료" else "녹음 1문장부터 가능") {
            Text("녹음한 문장 하나로 이 기기에서 바로 ${role.label} 목소리를 만들어요. 녹음은 기기 밖으로 나가지 않아요. 처음 한 번 기본 모델(약 750MB)을 내려받으니 Wi-Fi에서 해 주세요.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { buildVoice() }, enabled = consent && recordedCount > 0 && !voiceWorking && !recording) {
                Text(if (hasVoice) "다시 만들기" else "목소리 만들기")
            }
        }
        StepCard("③", "더 닮게 학습 (실험)", done = trained, summary = if (trained) "완료" else "녹음 4문장 이상") {
            Text("녹음한 문장으로 이 기기에서 말투를 학습해요. 1~3분쯤 걸리고 기기가 따뜻해질 수 있어요. 충전기를 꽂고 화면을 켜 둔 채로 해 주세요.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { trainVoice() },
                enabled = consent && hasVoice && recordedCount >= 4 && !voiceWorking && !recording,
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) { Text(if (trained) "다시 학습하기" else "학습하기") }
            if (!hasVoice) Text("먼저 ②를 해 주세요.", style = MaterialTheme.typography.bodySmall)
        }

        if (voiceWorking) LinearProgressIndicator(progress = { voiceProgress }, modifier = Modifier.fillMaxWidth())
        if (voiceStatus.isNotBlank()) Text(voiceStatus, color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = { previewVoice() },
            enabled = hasVoice && !voiceWorking && !recording,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) { Text("🎧 ${role.label} 목소리 들어보기", fontSize = 16.sp) }
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

    @Composable private fun RecordingPanel() {
        Text("“${stepScript(currentStep, role)}”", style = MaterialTheme.typography.titleMedium)
        listOf(0..3, 4..7).forEach { range ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                range.forEach { step ->
                    val done = stepRecorded[step]
                    OutlinedButton(
                        onClick = { currentStep = step },
                        enabled = !recording,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        colors = if (step == currentStep) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) { Text("${step + 1}${if (done) " ✓" else ""}", fontSize = 13.sp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (recording) finishRecording()
                    else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else microphone.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = consent && !voiceWorking,
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) { Text(if (recording) "⏹ 녹음 끝내기" else if (stepRecorded[currentStep]) "🎙️ 다시 녹음" else "🎙️ 녹음 시작") }
            if (stepRecorded[currentStep] && !recording) {
                OutlinedButton(onClick = { playFile(stepFile(role, currentStep)) }) { Text("들어보기") }
            }
        }
        if (!consent) Text("녹음하려면 위의 동의에 체크해 주세요.", style = MaterialTheme.typography.bodySmall)
        if (stepRecorded.any { it }) {
            TextButton(onClick = { deleteRecordings() }, enabled = !recording && !voiceWorking) { Text("${role.label} 녹음 모두 지우기") }
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
            recording = true
            voiceStatus = "${currentStep + 1}번 문장을 읽어 주세요. 다 읽으면 ‘녹음 끝내기’를 눌러요."
        }.onFailure {
            next.release(); tmp.delete()
            voiceStatus = "녹음을 시작하지 못했어요."
        }
    }

    private fun finishRecording() {
        val active = recorder ?: return
        val pending = File(filesDir, "voice.tmp")
        runCatching {
            active.stop()
            val target = stepFile(role, currentStep)
            target.delete()
            check(pending.renameTo(target))
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

    private fun deleteRecordings() {
        stopAllAudio()
        (0 until totalSteps).forEach { stepFile(role, it).delete() }
        stepRecorded = List(totalSteps) { false }
        currentStep = 0
        voiceStatus = "${role.label} 녹음을 지웠어요. 만든 목소리는 그대로 있어요."
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

    private fun buildVoice() {
        val r = role
        runVoiceJob("준비 중…") { onStatus ->
            val info = OnDeviceVoices.build(this, r.name, clips(r), threads, onStatus)
            runOnUiThread { chooseReaderVoice("local:${r.name}") }
            "${r.label} 목소리를 만들었어요 (녹음 %.1f초 사용). 아래 ‘들어보기’로 확인해 보세요.".format(info.seconds)
        }
    }

    private fun trainVoice() {
        val r = role
        runVoiceJob("학습 준비 중…") { onStatus ->
            val info = OnDeviceVoices.train(this, r.name, clips(r), threads, onStatus)
            reportTraining(info)
            runOnUiThread { chooseReaderVoice("local:${r.name}") }
            "학습을 마쳤어요 (%.1f분). 아래 ‘들어보기’로 확인해 보세요.".format(info.millis / 60000f)
        }
    }

    /** Synthesizes a sample sentence with [role]'s on-device voice (including trained adapters) and plays it. */
    private fun previewVoice() {
        val r = role
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
                OnDeviceTts(baseDir, threads, OnDeviceVoices.localDir(this, r.name), prefs.getString("ondevice_accel", "cpu") ?: "cpu").use { t ->
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
