package com.pumsok.story

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val updater by lazy { Updater(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private var story by mutableStateOf<Story?>(null)
    private var index by mutableIntStateOf(0)
    private var message by mutableStateOf("")
    private var recording by mutableStateOf(false)
    private var hasRecording by mutableStateOf(false)
    private var consent by mutableStateOf(false)
    private var transcript by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var ttsReady by mutableStateOf(false)
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val voiceFile get() = File(filesDir, "parent.m4a")

    enum class SpeakerRole(val label: String) { MOTHER("엄마"), FATHER("아빠") }

    private var speakerRole by mutableStateOf(SpeakerRole.MOTHER)
    private var recordingStepMode by mutableStateOf(true)
    private var currentStep by mutableIntStateOf(0)
    private var stepRecorded by mutableStateOf(listOf(false, false, false, false))

    private fun getStepScript(step: Int, role: SpeakerRole): String = when (step) {
        0 -> "우리 아가, 오늘 하루도 재미있게 잘 보냈니?"
        1 -> "창밖으로 별들이 반짝이고, 달님이 은은하게 미소를 짓는 밤이야."
        2 -> "${role.label}가 들려주는 신비롭고 따뜻한 동화 나라로 함께 여행을 떠나보자."
        3 -> "이제 편안하게 눈을 감으면, 숲속 친구들의 재미있는 이야기가 시작될 거야."
        else -> ""
    }

    private fun getFullDefaultScript(role: SpeakerRole): String =
        (0..3).joinToString(" ") { getStepScript(it, role) }

    private fun stepVoiceFile(step: Int) = File(filesDir, "voice_step_$step.m4a")

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && consent) startRecording() else message = "녹음하려면 마이크 권한과 동의가 필요합니다."
    }
    private val importBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
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
                    } ?: error("파일을 열 수 없습니다")
                    val parsed = EpubReader.read(temp)
                    check(temp.renameTo(File(filesDir, "book.epub"))) { "책을 저장하지 못했습니다" }
                    parsed
                }.onSuccess { parsed ->
                    runOnUiThread {
                        stopAllAudio()
                        story = parsed
                        index = 0
                        prefs.edit().putInt("index", 0).apply()
                        message = "‘${parsed.title}’ 동화를 가져왔습니다."
                        busy = false
                    }
                }.onFailure { e ->
                    temp.delete()
                    runOnUiThread { message = e.message ?: "가져오기 실패"; busy = false }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater.checkOnLaunch()
        val savedRole = prefs.getString("role", "MOTHER") ?: "MOTHER"
        speakerRole = if (savedRole == "FATHER") SpeakerRole.FATHER else SpeakerRole.MOTHER
        stepRecorded = (0..3).map { stepVoiceFile(it).exists() }
        hasRecording = voiceFile.exists()
        transcript = prefs.getString("transcript", "") ?: ""
        if (transcript.isBlank()) {
            transcript = getFullDefaultScript(speakerRole)
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                ttsReady = result != null && result >= TextToSpeech.LANG_AVAILABLE
            }
        }
        executor.execute {
            val saved = File(filesDir, "book.epub")
            val parsed = runCatching {
                if (!saved.exists() || saved.length() == 0L) {
                    assets.open("sun_and_moon.epub").use { input ->
                        saved.outputStream().use { out -> input.copyTo(out) }
                    }
                }
                EpubReader.read(saved)
            }.recoverCatching {
                saved.delete()
                assets.open("sun_and_moon.epub").use { input ->
                    saved.outputStream().use { out -> input.copyTo(out) }
                }
                EpubReader.read(saved)
            }.getOrNull()

            if (parsed != null) {
                runOnUiThread {
                    story = parsed
                    index = prefs.getInt("index", 0).coerceIn(parsed.paragraphs.indices)
                }
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF795548), background = Color(0xFFFFF8EF),
                surface = Color(0xFFFFF8EF)
            )) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                        if (maxWidth >= 700.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(0.4f).verticalScroll(rememberScrollState())) { Controls() }
                            Column(Modifier.weight(0.6f).verticalScroll(rememberScrollState())) { Reader() }
                        } else Column(Modifier.verticalScroll(rememberScrollState())) { Controls(); Reader() }
                    }
                }
            }
        }
    }

    @Composable private fun Controls() {
        Text("품속동화", style = MaterialTheme.typography.headlineLarge)
        Text("엄마 아빠 목소리로 들려주는 동화", modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importBook.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, enabled = !busy) {
                Text(if (busy) "동화 불러오는 중…" else "내 EPUB 가져오기")
            }
            TextButton(onClick = { updater.checkOnLaunch(manual = true) }) {
                Text("업데이트 확인")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            val isSun = story?.title?.contains("해와 달") == true
            OutlinedButton(
                onClick = { loadAssetStory("sun_and_moon.epub") },
                colors = if (isSun) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors(),
                enabled = !busy
            ) {
                Text(if (isSun) "✓ 샘플: 해와 달" else "샘플: 해와 달")
            }
            val isTurtle = story?.title?.contains("거북이") == true
            OutlinedButton(
                onClick = { loadAssetStory("rabbit_and_turtle.epub") },
                colors = if (isTurtle) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors(),
                enabled = !busy
            ) {
                Text(if (isTurtle) "✓ 샘플: 토끼와 거북이" else "샘플: 토끼와 거북이")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("부모님 목소리 등록", style = MaterialTheme.typography.titleLarge)
        Text("아이에게 들려줄 목소리를 10초씩 편안하게 나누어 녹음해 보세요.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(
                selected = speakerRole == SpeakerRole.MOTHER,
                onClick = {
                    speakerRole = SpeakerRole.MOTHER
                    prefs.edit().putString("role", "MOTHER").apply()
                    if (transcript.isBlank() || transcript == getFullDefaultScript(SpeakerRole.FATHER)) {
                        transcript = getFullDefaultScript(SpeakerRole.MOTHER)
                    }
                },
                label = { Text("엄마 목소리") },
                enabled = !recording
            )
            FilterChip(
                selected = speakerRole == SpeakerRole.FATHER,
                onClick = {
                    speakerRole = SpeakerRole.FATHER
                    prefs.edit().putString("role", "FATHER").apply()
                    if (transcript.isBlank() || transcript == getFullDefaultScript(SpeakerRole.MOTHER)) {
                        transcript = getFullDefaultScript(SpeakerRole.FATHER)
                    }
                },
                label = { Text("아빠 목소리") },
                enabled = !recording
            )
        }

        Row(modifier = Modifier.padding(vertical = 4.dp)) {
            Checkbox(consent, onCheckedChange = { consent = it }, enabled = !recording)
            Text("내 목소리이거나, 녹음하는 부모님의 동의를 받았습니다.", Modifier.padding(top = 12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            FilterChip(
                selected = recordingStepMode,
                onClick = { recordingStepMode = true },
                label = { Text("4단계 나누어 녹음 (추천 · 10초씩)") },
                enabled = !recording
            )
            FilterChip(
                selected = !recordingStepMode,
                onClick = { recordingStepMode = false },
                label = { Text("한 번에 녹음 (자유 대본)") },
                enabled = !recording
            )
        }

        if (recordingStepMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${currentStep + 1}단계 / 4단계 (약 10초)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val isDone = stepRecorded.getOrElse(currentStep) { false }
                        Text(
                            if (isDone) "✓ 녹음 완료" else "대기 중",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isDone) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "“${getStepScript(currentStep, speakerRole)}”",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        (0..3).forEach { stepIndex ->
                            val done = stepRecorded.getOrElse(stepIndex) { false }
                            val isCurrent = stepIndex == currentStep
                            OutlinedButton(
                                onClick = { if (!recording) currentStep = stepIndex },
                                colors = if (isCurrent) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                enabled = !recording
                            ) {
                                Text("${stepIndex + 1}단계 ${if (done) "✓" else ""}")
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
                            enabled = consent,
                            colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                        ) {
                            Text(if (recording) "녹음 종료" else if (stepRecorded.getOrElse(currentStep) { false }) "다시 녹음하기" else "이 문장 녹음 시작")
                        }

                        if (stepRecorded.getOrElse(currentStep) { false } && !recording) {
                            OutlinedButton(onClick = { playFile(stepVoiceFile(currentStep)) }) {
                                Text("들어보기")
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) {
                        TextButton(
                            onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                            enabled = currentStep > 0 && !recording
                        ) {
                            Text("← 이전 문장")
                        }
                        TextButton(
                            onClick = { currentStep = (currentStep + 1).coerceAtMost(3) },
                            enabled = currentStep < 3 && !recording
                        ) {
                            Text("다음 문장 →")
                        }
                    }
                }
            }

            if (hasRecording) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("🎉 ${speakerRole.label} 목소리 등록 완료!", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                        Text("약 40초의 목소리가 안전하게 기기에 저장되었습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20))
                        Spacer(Modifier.height(4.dp))
                        Text("👉 다음 단계: 동화를 선택하고 [🌸 부모님 오프닝 + 본문 듣기]를 눌러보세요!", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = { playFile(voiceFile) }, enabled = !recording) {
                                Text("전체 목소리 듣기 (약 40초)")
                            }
                            TextButton(onClick = { resetAllRecordings() }, enabled = !recording) {
                                Text("다시 녹음하기")
                            }
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                transcript,
                onValueChange = {
                    transcript = it
                    prefs.edit().putString("transcript", it).apply()
                },
                label = { Text("녹음에서 읽을 대본") },
                enabled = !recording,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (recording) finishRecording()
                    else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else microphone.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = recording || (consent && transcript.isNotBlank())
            ) {
                Text(if (recording) "녹음 종료" else "녹음 시작")
            }
            if (hasRecording) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { playFile(voiceFile) }, enabled = !recording) { Text("녹음 듣기") }
                    TextButton(onClick = { resetAllRecordings() }, enabled = !recording) { Text("녹음 삭제") }
                }
            }
        }
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
    }

    @Composable private fun Reader() {
        val book = story
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        if (book == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📖 함께 읽을 동화를 골라주세요", style = MaterialTheme.typography.titleMedium)
                    Text("아래 추천 동화를 누르면 바로 이야기를 시작할 수 있습니다.", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { loadAssetStory("sun_and_moon.epub") }, enabled = !busy) {
                            Text("해와 달이 된 오누이")
                        }
                        Button(onClick = { loadAssetStory("rabbit_and_turtle.epub") }, enabled = !busy) {
                            Text("토끼와 거북이")
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(book.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("${index + 1} / ${book.paragraphs.size} 문단", style = MaterialTheme.typography.labelMedium)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                Text(
                    book.paragraphs[index],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                    lineHeight = 28.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { move(-1) }, enabled = index > 0) { Text("← 이전 문단") }
                TextButton(onClick = { move(1) }, enabled = index < book.paragraphs.lastIndex) { Text("다음 문단 →") }
            }

            if (hasRecording) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("🎙️ ${speakerRole.label} 목소리 동화 시간", style = MaterialTheme.typography.titleSmall, color = Color(0xFF2E7D32))
                        Text("부모님의 따뜻한 음성 오프닝과 함께 동화를 시작해보세요.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF33691E))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(
                                onClick = {
                                    playFile(voiceFile) {
                                        tts?.speak(book.paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "paragraph")
                                    }
                                },
                                enabled = !recording
                            ) {
                                Text("🌸 오프닝 + 본문 듣기")
                            }
                            OutlinedButton(
                                onClick = { playFile(voiceFile) },
                                enabled = !recording
                            ) {
                                Text("오프닝만 듣기")
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                FilledTonalButton(
                    onClick = {
                        stopAllAudio()
                        tts?.speak(book.paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "paragraph")
                    },
                    enabled = ttsReady && !recording
                ) {
                    Text("기본 TTS로 이 문단 듣기")
                }
                OutlinedButton(onClick = { stopAllAudio() }) {
                    Text("소리 정지")
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("💡 동화 읽기 안내", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (hasRecording)
                            "• [오프닝 + 본문 듣기]: 부모님의 따뜻한 인사말이 먼저 흘러나오고, 바로 동화가 시작됩니다.\n• [AI 음성 복제(Voice Cloning)]: 부모님 목소리로 본문 전체를 실시간 변환하는 기술은 후속 업데이트에서 지원됩니다."
                        else
                            "• 부모님 목소리를 녹음하시면, 동화 시작 시 다정한 부모님 목소리 오프닝을 들려줄 수 있습니다.\n• 본문 낭독은 안드로이드 기기의 한국어 TTS 엔진을 사용합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    private fun move(delta: Int) {
        stopAllAudio()
        index = (index + delta).coerceIn(0, (story?.paragraphs?.lastIndex ?: 0))
        prefs.edit().putInt("index", index).apply()
    }

    private fun loadAssetStory(assetName: String) {
        busy = true
        executor.execute {
            val book = File(filesDir, "book.epub")
            runCatching {
                assets.open(assetName).use { input ->
                    book.outputStream().use { out -> input.copyTo(out) }
                }
                EpubReader.read(book)
            }.onSuccess { parsed ->
                runOnUiThread {
                    stopAllAudio()
                    story = parsed
                    index = 0
                    prefs.edit().putInt("index", 0).apply()
                    message = "‘${parsed.title}’ 동화를 불러왔습니다."
                    busy = false
                }
            }.onFailure { e ->
                runOnUiThread {
                    message = e.message ?: "동화 불러오기 실패"
                    busy = false
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        stopAllAudio()
        val targetTmp = File(filesDir, "voice.tmp")
        val next = MediaRecorder()
        runCatching {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(24000)
            next.setAudioEncodingBitRate(96000)
            next.setOutputFile(targetTmp.absolutePath)
            next.prepare(); next.start()
        }.onSuccess {
            recorder = next
            recording = true
            message = if (recordingStepMode) "${currentStep + 1}단계 녹음 중입니다…" else "녹음 중입니다…"
        }.onFailure {
            next.release(); targetTmp.delete()
            message = "녹음을 시작하지 못했습니다."
        }
    }

    private fun finishRecording() {
        val active = recorder ?: return
        val pending = File(filesDir, "voice.tmp")
        runCatching {
            active.stop()
            if (recordingStepMode) {
                val target = stepVoiceFile(currentStep)
                if (target.exists()) target.delete()
                check(pending.renameTo(target))
                val updated = stepRecorded.toMutableList()
                updated[currentStep] = true
                stepRecorded = updated

                if (updated.all { it }) {
                    val merged = AudioConcatenator.concatM4a((0..3).map { stepVoiceFile(it) }, voiceFile)
                    if (merged) {
                        hasRecording = true
                        transcript = getFullDefaultScript(speakerRole)
                        prefs.edit().putString("transcript", transcript).apply()
                        message = "🎉 4개 문장(약 40초) 녹음이 완료되었습니다! 동화를 골라 [🌸 오프닝 + 본문 듣기]를 눌러보세요."
                    } else {
                        message = "${currentStep + 1}단계는 저장되었으나 전체 병합에 실패했습니다."
                    }
                } else {
                    message = "${currentStep + 1}단계 문장이 저장되었습니다."
                    val nextStep = (currentStep + 1).coerceAtMost(3)
                    if (!stepRecorded[nextStep]) currentStep = nextStep
                }
            } else {
                if (voiceFile.exists()) voiceFile.delete()
                check(pending.renameTo(voiceFile))
                hasRecording = true
                message = "🎉 녹음이 완료되었습니다! 동화를 골라 [🌸 오프닝 + 본문 듣기]를 눌러보세요."
            }
        }.onFailure {
            pending.delete()
            message = "녹음이 너무 짧거나 저장에 실패했습니다."
        }
        active.release(); recorder = null; recording = false
    }

    private fun stopAllAudio() {
        tts?.stop()
        player?.release()
        player = null
    }

    private fun playFile(file: File, onComplete: (() -> Unit)? = null) {
        if (!file.exists()) return
        stopAllAudio()
        runCatching {
            MediaPlayer().also { p ->
                player = p
                p.setDataSource(file.absolutePath)
                p.setOnCompletionListener {
                    it.release()
                    player = null
                    runOnUiThread { onComplete?.invoke() }
                }
                p.prepare()
                p.start()
            }
        }.onFailure {
            player?.release()
            player = null
            message = "녹음을 재생하지 못했습니다."
        }
    }

    private fun resetAllRecordings() {
        stopAllAudio()
        voiceFile.delete()
        (0..3).forEach { stepVoiceFile(it).delete() }
        stepRecorded = listOf(false, false, false, false)
        hasRecording = false
        prefs.edit().remove("transcript").apply()
        transcript = getFullDefaultScript(speakerRole)
        message = "녹음이 삭제되었습니다."
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onStop() {
        if (recording) finishRecording()
        stopAllAudio()
        super.onStop()
    }

    override fun onDestroy() {
        updater.release()
        recorder?.release(); player?.release(); tts?.shutdown()
        executor.shutdown()
        super.onDestroy()
    }
}
