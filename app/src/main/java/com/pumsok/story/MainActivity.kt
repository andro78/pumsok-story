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

    private val voiceEngine = TrainedVoiceEngine()
    private var motherProfile by mutableStateOf<VoiceProfile?>(null)
    private var fatherProfile by mutableStateOf<VoiceProfile?>(null)
    private val currentProfile: VoiceProfile
        get() = if (speakerRole == SpeakerRole.MOTHER) {
            motherProfile ?: VoiceProfile.defaultFor("MOTHER", "엄마")
        } else {
            fatherProfile ?: VoiceProfile.defaultFor("FATHER", "아빠")
        }

    private var isTraining by mutableStateOf(false)
    private var trainingProgress by mutableFloatStateOf(0f)
    private var trainingStepTitle by mutableStateOf("")
    private var trainingStepDesc by mutableStateOf("")

    private var showVoiceTuningDialog by mutableStateOf(false)
    private var tuningPitchOffset by mutableFloatStateOf(0f)
    private var tuningTau by mutableFloatStateOf(0.22f)
    private var tuningWarmth by mutableFloatStateOf(2.0f)
    private var tuningSpeed by mutableStateOf("-6%")
    private var tuningElevenKey by mutableStateOf("")
    private var tuningUseEleven by mutableStateOf(false)
    private var isTuningPreviewLoading by mutableStateOf(false)

    private val totalSteps = 8
    private var speakerRole by mutableStateOf(SpeakerRole.MOTHER)
    private var recordingStepMode by mutableStateOf(true)
    private var currentStep by mutableIntStateOf(0)
    private var stepRecorded by mutableStateOf(List(8) { false })

    private fun getStepScript(step: Int, role: SpeakerRole): String = when (step) {
        0 -> "우리 아가, 오늘 하루도 재미있고 씩씩하게 잘 보냈니?"
        1 -> "창밖으로 별들이 반짝이고, 달님이 은은하게 미소를 짓는 밤이야."
        2 -> "${role.label}가 들려주는 신비롭고 따뜻한 동화 나라로 함께 여행을 떠나보자."
        3 -> "오늘 숲속에서는 어떤 신기하고 재미있는 일들이 일어났을까?"
        4 -> "와아! 알록달록 무지개 언덕에 달콤한 열매들이 가득 열렸네!"
        5 -> "쉿, 아기 곰이 새근새근 잠들었으니 발소리를 죽이고 조용히 걸어가자."
        6 -> "아무 걱정하지 마렴, 언제나 ${role.label}가 네 곁에서 든든하게 지켜줄게."
        7 -> "사랑하는 우리 아가, 오늘 밤도 예쁜 꿈 꾸렴. 편안하게 잘 자렴."
        else -> ""
    }

    private fun getFullDefaultScript(role: SpeakerRole): String =
        (0 until totalSteps).joinToString(" ") { getStepScript(it, role) }

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
        motherProfile = VoiceProfile.fromJson(prefs.getString("profile_MOTHER", null))
        fatherProfile = VoiceProfile.fromJson(prefs.getString("profile_FATHER", null))
        stepRecorded = (0 until totalSteps).map { stepVoiceFile(it).exists() }
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
                    if (showVoiceTuningDialog) {
                        VoiceTuningDialog()
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

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            (0..3).forEach { stepIndex ->
                                val done = stepRecorded.getOrElse(stepIndex) { false }
                                val isCurrent = stepIndex == currentStep
                                OutlinedButton(
                                    onClick = { if (!recording) currentStep = stepIndex },
                                    colors = if (isCurrent) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors(),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f),
                                    enabled = !recording
                                ) {
                                    Text("${stepIndex + 1}단계${if (done) "✓" else ""}", fontSize = 11.sp)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            (4..7).forEach { stepIndex ->
                                val done = stepRecorded.getOrElse(stepIndex) { false }
                                val isCurrent = stepIndex == currentStep
                                OutlinedButton(
                                    onClick = { if (!recording) currentStep = stepIndex },
                                    colors = if (isCurrent) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF673AB7).copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors(),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f),
                                    enabled = !recording
                                ) {
                                    Text("${stepIndex + 1}단계${if (done) "✓" else ""}", fontSize = 11.sp)
                                }
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
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) {
                        TextButton(
                            onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                            enabled = currentStep > 0 && !recording
                        ) {
                            Text("← 이전 문장")
                        }
                        val recordedCount = stepRecorded.count { it }
                        Text(
                            "$recordedCount / $totalSteps 문장",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = { currentStep = (currentStep + 1).coerceAtMost(totalSteps - 1) },
                            enabled = currentStep < totalSteps - 1 && !recording
                        ) {
                            Text("다음 문장 →")
                        }
                    }
                }
            }

            if (hasRecording) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { playFile(voiceFile) }, enabled = !recording) {
                        Text("전체 목소리 듣기 (약 40초)")
                    }
                    TextButton(onClick = { resetAllRecordings() }, enabled = !recording) {
                        Text("녹음 삭제")
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

        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("AI 목소리 트레이닝 (학습)", style = MaterialTheme.typography.titleLarge)
        Text("녹음된 목소리를 분석하여 ${speakerRole.label}의 고유한 톤과 템포로 동화를 읽을 수 있도록 모델을 만듭니다.", style = MaterialTheme.typography.bodySmall)

        if (isTraining) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🧠 ${speakerRole.label} 목소리 학습 진행 중…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(
                        progress = { trainingProgress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Text(trainingStepTitle, style = MaterialTheme.typography.labelLarge)
                    Text(trainingStepDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (currentProfile.isTrained) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("✅ ${currentProfile.roleLabel} 뉴럴 AI 목소리 학습 완료!", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                    Text("• ${currentProfile.roleLabel} 고유 음색과 F0 피치(${"%.2f".format(currentProfile.pitch)}x) 분석 완료", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20))
                    Text("• 학습 음성 길이: 약 ${currentProfile.sampleDurationSec.toInt()}초 (${currentProfile.clipsCount}개 클립)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { testTrainedVoice() }, enabled = !recording && !isTraining) {
                            Text("🎧 테스트")
                        }
                        Button(
                            onClick = { openVoiceTuningDialog() },
                            enabled = !recording && !isTraining,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                        ) {
                            Text("🎛️ 음색 튜닝")
                        }
                        OutlinedButton(onClick = { startVoiceTraining() }, enabled = !recording && !isTraining) {
                            Text("다시 학습하기")
                        }
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("⚡ ${speakerRole.label} OpenVoice 딥러닝 보이스 클로닝", style = MaterialTheme.typography.titleMedium)
                    val recordedCount = stepRecorded.count { it }
                    val canTrain = hasRecording || recordedCount >= 4
                    Text(
                        if (recordedCount == totalSteps)
                            "🌟 8단계 전체 문장(약 1분 30초) 녹음 완료! 최고 정밀도로 OpenVoice 뉴럴 AI 학습을 진행합니다."
                        else if (recordedCount >= 4)
                            "💡 현재 ${recordedCount}단계 녹음 완료! 지금 바로 OpenVoice 학습이 가능하며, 8단계까지 채우시면 음색이 더욱 정교해집니다."
                        else
                            "최소 4단계 이상 문장을 녹음하시면 무료 OpenVoice 뉴럴 AI 학습을 시작할 수 있습니다. (현재 ${recordedCount}/$totalSteps 단계)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { startVoiceTraining() },
                        enabled = canTrain && !recording && !isTraining
                    ) {
                        Text(if (recordedCount == totalSteps) "🚀 8단계 정밀 OpenVoice AI 학습 시작" else "🚀 ${recordedCount}단계 OpenVoice AI 학습 시작")
                    }
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

            if (currentProfile.isTrained) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("❤️ ${currentProfile.roleLabel} 뉴럴 AI 목소리로 읽기", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                        Text("${currentProfile.roleLabel}의 목소리 톤과 자연스러운 구연 억양을 입힌 뉴럴 AI 음성으로 동화를 읽어줍니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF33691E))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Button(
                                onClick = {
                                    readParagraphWithNeuralVoice(book.paragraphs[index], currentProfile)
                                },
                                enabled = !recording && !isTraining
                            ) {
                                Text("❤️ ${currentProfile.roleLabel} AI 목소리로 읽기")
                            }
                            IconButton(onClick = { openVoiceTuningDialog() }) {
                                Text("🎛️", fontSize = 18.sp)
                            }
                            if (hasRecording) {
                                Button(
                                    onClick = {
                                        stopAllAudio()
                                        playFile(voiceFile) {
                                            readParagraphWithNeuralVoice(book.paragraphs[index], currentProfile)
                                        }
                                    },
                                    enabled = !recording && !isTraining,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF558B2F))
                                ) {
                                    Text("🌸 오프닝 + 본문 읽기")
                                }
                            }
                        }
                    }
                }
            } else if (hasRecording) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("🎙️ ${speakerRole.label} 녹음 오프닝", style = MaterialTheme.typography.titleSmall, color = Color(0xFFE65100))
                        Text("녹음된 인사말을 듣거나, 왼쪽(위)에서 [AI 학습 시작]을 누르면 동화를 ${speakerRole.label} 목소리로 읽어줍니다.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(
                                onClick = {
                                    playFile(voiceFile) {
                                        tts?.speak(book.paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "paragraph")
                                    }
                                },
                                enabled = !recording
                            ) {
                                Text("🌸 오프닝 + 기본 낭독")
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
                        tts?.setPitch(1.0f)
                        tts?.setSpeechRate(1.0f)
                        tts?.speak(book.paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "paragraph")
                    },
                    enabled = ttsReady && !recording && !isTraining
                ) {
                    Text("기본 기기 TTS로 듣기")
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
                        if (currentProfile.isTrained)
                            "• [❤️ ${currentProfile.roleLabel} 목소리로 읽기]: AI 학습을 통해 분석된 부모님의 고유 음향 모델(피치·템포)로 낭독합니다.\n• [🌸 오프닝 + 낭독]: 부모님의 실제 녹음된 다정한 인사말이 먼저 재생된 후 동화 본문이 이어집니다."
                        else
                            "• 부모님 목소리 녹음 후 [AI 학습 시작]을 완료하시면, ${speakerRole.label}의 맞춤형 목소리로 동화를 읽을 수 있습니다.\n• 본문 낭독은 안드로이드 기기의 한국어 TTS 엔진을 사용합니다.",
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

                val recordedClips = (0 until totalSteps).map { stepVoiceFile(it) }.filter { it.exists() && it.length() > 0 }
                if (recordedClips.isNotEmpty()) {
                    AudioConcatenator.concatM4a(recordedClips, voiceFile)
                    hasRecording = voiceFile.exists() && voiceFile.length() > 1000
                }
                val countDone = updated.count { it }
                if (countDone == totalSteps) {
                    transcript = getFullDefaultScript(speakerRole)
                    prefs.edit().putString("transcript", transcript).apply()
                    message = "🎉 8단계 전체 문장(약 1분 30초) 녹음 완료! [AI 학습 시작]을 눌러 최고 정밀도로 학습해보세요."
                } else if (countDone >= 4) {
                    message = "✅ ${currentStep + 1}단계 저장 완료! (${countDone}/$totalSteps 단계 완료 - 지금 학습하거나 더 녹음 가능)"
                    val nextStep = (currentStep + 1).coerceAtMost(totalSteps - 1)
                    if (!stepRecorded[nextStep]) currentStep = nextStep
                } else {
                    message = "✅ ${currentStep + 1}단계 문장이 저장되었습니다. (${countDone}/$totalSteps 단계)"
                    val nextStep = (currentStep + 1).coerceAtMost(totalSteps - 1)
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
        tts?.let { voiceEngine.stop(it) }
        player?.release()
        player = null
    }

    private fun startVoiceTraining() {
        if (isTraining || recording) return
        stopAllAudio()
        val clips = (0 until totalSteps).map { stepVoiceFile(it) }.filter { it.exists() && it.length() > 0 }
        val filesToUse = if (clips.isNotEmpty()) clips else listOf(voiceFile).filter { it.exists() && it.length() > 0 }
        if (filesToUse.isEmpty()) {
            message = "학습을 시작하려면 먼저 부모님 목소리를 최소 4문장 이상 녹음해 주세요."
            return
        }
        isTraining = true
        trainingProgress = 0.05f
        trainingStepTitle = "학습 준비 중…"
        trainingStepDesc = "녹음 파일을 불러오고 있습니다."

        executor.execute {
            runCatching {
                if ((!voiceFile.exists() || voiceFile.length() < 1000) && clips.isNotEmpty()) {
                    AudioConcatenator.concatM4a(clips, voiceFile)
                }
                val fileToUpload = if (voiceFile.exists() && voiceFile.length() > 1000) voiceFile else filesToUse.first()
                val isOnline = NeuralVoiceClient.checkHealth()

                if (isOnline) {
                    val trained = NeuralVoiceClient.trainOnServer(
                        roleKey = speakerRole.name,
                        roleLabel = speakerRole.label,
                        audioFile = fileToUpload
                    ) { step, title ->
                        runOnUiThread {
                            trainingProgress = step / 4.0f
                            trainingStepTitle = title
                            trainingStepDesc = when (step) {
                                1 -> "부모님의 실제 음성(${clips.size}개 문장)을 d32.org OpenVoice 뉴럴 서버로 전송합니다."
                                2 -> "음성 잡음을 제거하고 기본 주파수(F0) 및 억양 곡선을 정밀 분석합니다."
                                3 -> "부모님 성도 특성과 256차원 OpenVoice 딥러닝 음색 벡터를 다중 구간 합성합니다."
                                4 -> "OpenVoice 딥러닝 보이스 클로닝 모델 생성이 완료되었습니다!"
                                else -> ""
                            }
                        }
                    }
                    val previewFile = File(cacheDir, "preview_${speakerRole.name.lowercase()}.mp3")
                    NeuralVoiceClient.downloadPreview("speaker_${speakerRole.name.lowercase()}", previewFile)
                    Pair(trained, true)
                } else {
                    val trained = VoiceTrainer.train(
                        roleKey = speakerRole.name,
                        roleLabel = speakerRole.label,
                        audioFiles = filesToUse
                    ) { state ->
                        runOnUiThread {
                            trainingProgress = state.progress
                            trainingStepTitle = state.title
                            trainingStepDesc = state.description
                        }
                    }
                    Pair(trained, false)
                }
            }.onSuccess { (trained, isOnline) ->
                runOnUiThread {
                    isTraining = false
                    if (speakerRole == SpeakerRole.MOTHER) {
                        motherProfile = trained
                        prefs.edit().putString("profile_MOTHER", trained.toJson()).apply()
                    } else {
                        fatherProfile = trained
                        prefs.edit().putString("profile_FATHER", trained.toJson()).apply()
                    }
                    message = if (isOnline) {
                        "🎉 ${trained.roleLabel} 뉴럴 AI 보이스 클로닝 완료! [🎧 학습 목소리 테스트]를 들어보세요."
                    } else {
                        "🎉 ${trained.roleLabel} 목소리 학습 완료! [🎧 학습 목소리 테스트] 또는 동화 읽기를 시작해보세요."
                    }
                }
            }.onFailure { err ->
                runOnUiThread {
                    isTraining = false
                    message = "학습 실패: ${err.message}"
                }
            }
        }
    }

    private fun testTrainedVoice() {
        stopAllAudio()
        val profile = currentProfile
        val phrase = "사랑하는 우리 아이야, ${profile.roleLabel}가 들려주는 신비롭고 따뜻한 동화 나라로 함께 가보자."
        val previewFile = File(cacheDir, "preview_${profile.roleKey.lowercase()}.mp3")

        message = "✨ ${profile.roleLabel} 뉴럴 AI 목소리로 테스트 음성을 준비 중입니다…"
        executor.execute {
            var ok = NeuralVoiceClient.downloadPreview("speaker_${profile.roleKey.lowercase()}", previewFile)
            if (!ok || !previewFile.exists()) {
                ok = NeuralVoiceClient.synthesizeSpeech(phrase, profile, previewFile)
            }
            runOnUiThread {
                if (ok && previewFile.exists()) {
                    message = "🎧 ‘${profile.roleLabel}’ 뉴럴 AI 학습 목소리를 재생합니다."
                    playFile(previewFile)
                } else {
                    tts?.let { voiceEngine.speak(phrase, profile, it, "test_phrase") }
                    message = "‘${profile.roleLabel}’ 기기 TTS로 테스트 음성을 재생합니다."
                }
            }
        }
    }

    private fun readParagraphWithNeuralVoice(text: String, profile: VoiceProfile) {
        stopAllAudio()
        val tuningSig = "${profile.pitchOffsetHz}_${(profile.tau * 100).toInt()}_${(profile.warmthDb * 10).toInt()}_${profile.speedRate}"
        val hash = (profile.roleKey + ":" + tuningSig + ":" + text).hashCode().toString().replace("-", "n")
        val cacheFile = File(cacheDir, "story_${profile.roleKey.lowercase()}_$hash.mp3")

        if (cacheFile.exists() && cacheFile.length() > 500) {
            message = "❤️ ${profile.roleLabel} 뉴럴 AI 목소리로 읽어줍니다."
            playFile(cacheFile)
            return
        }

        message = "✨ ${profile.roleLabel} 뉴럴 AI 목소리로 문장 생성 중…"
        executor.execute {
            val ok = NeuralVoiceClient.synthesizeSpeech(text, profile, cacheFile)
            runOnUiThread {
                if (ok && cacheFile.exists()) {
                    message = "❤️ ${profile.roleLabel} 뉴럴 AI 목소리로 읽어줍니다."
                    playFile(cacheFile)
                } else {
                    tts?.let { voiceEngine.speak(text, profile, it) }
                    message = "오프라인 상태이므로 기기 음성으로 재생합니다."
                }
            }
        }
    }

    private fun openVoiceTuningDialog() {
        tuningPitchOffset = currentProfile.pitchOffsetHz.toFloat()
        tuningTau = currentProfile.tau
        tuningWarmth = currentProfile.warmthDb
        tuningSpeed = currentProfile.speedRate
        tuningElevenKey = currentProfile.elevenLabsApiKey ?: ""
        tuningUseEleven = currentProfile.useElevenLabs
        showVoiceTuningDialog = true
    }

    private fun previewTunedVoice() {
        isTuningPreviewLoading = true
        val tempProfile = currentProfile.copy(
            pitchOffsetHz = tuningPitchOffset.toInt(),
            tau = tuningTau,
            warmthDb = tuningWarmth,
            speedRate = tuningSpeed,
            elevenLabsApiKey = tuningElevenKey.trim().takeIf { it.isNotBlank() },
            useElevenLabs = tuningUseEleven
        )
        executor.execute {
            NeuralVoiceClient.tuneOnServer(tempProfile)
            val previewFile = File(cacheDir, "preview_${tempProfile.roleKey.lowercase()}.mp3")
            val ok = NeuralVoiceClient.downloadPreview("speaker_${tempProfile.roleKey.lowercase()}", previewFile)
            runOnUiThread {
                isTuningPreviewLoading = false
                if (ok && previewFile.exists()) {
                    message = "🎧 튜닝된 ‘${tempProfile.roleLabel}’ 뉴럴 목소리를 재생합니다."
                    playFile(previewFile)
                } else {
                    message = "미리듣기 생성에 실패했습니다."
                }
            }
        }
    }

    private fun saveTunedVoice() {
        val updatedProfile = currentProfile.copy(
            pitchOffsetHz = tuningPitchOffset.toInt(),
            tau = tuningTau,
            warmthDb = tuningWarmth,
            speedRate = tuningSpeed,
            elevenLabsApiKey = tuningElevenKey.trim().takeIf { it.isNotBlank() },
            useElevenLabs = tuningUseEleven
        )
        if (speakerRole == SpeakerRole.MOTHER) {
            motherProfile = updatedProfile
            prefs.edit().putString("profile_MOTHER", updatedProfile.toJson()).apply()
        } else {
            fatherProfile = updatedProfile
            prefs.edit().putString("profile_FATHER", updatedProfile.toJson()).apply()
        }
        executor.execute {
            NeuralVoiceClient.tuneOnServer(updatedProfile)
        }
        showVoiceTuningDialog = false
        message = "✅ ${updatedProfile.roleLabel} 맞춤 음색 튜닝이 저장되었습니다."
    }

    @Composable
    private fun VoiceTuningDialog() {
        AlertDialog(
            onDismissRequest = { showVoiceTuningDialog = false },
            title = {
                Text("🎛️ ${currentProfile.roleLabel} 목소리 맞춤 튜닝", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "실제 부모님 목소리와 더욱 똑같아지도록 음높이, 음색 복제 강도, 울림을 미세 조절합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Pitch
                    Column {
                        val pInt = tuningPitchOffset.toInt()
                        val pDesc = if (pInt > 0) "+${pInt}Hz (더 맑고 높게)" else if (pInt < 0) "${pInt}Hz (더 낮고 묵직하게)" else "기본값 (0Hz)"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1. 음높이 미세 조절", style = MaterialTheme.typography.labelMedium)
                            Text(pDesc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = tuningPitchOffset,
                            onValueChange = { tuningPitchOffset = it },
                            valueRange = -40f..40f,
                            steps = 15
                        )
                    }

                    // 2. Tau (Neural Clone Resemblance)
                    Column {
                        val tauDesc = if (tuningTau >= 0.28f) "강한 복제 (고유 음색 강조)" else if (tuningTau <= 0.15f) "부드러운 음색 (자연스러움)" else "균형 잡힌 음색 (권장)"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("2. 뉴럴 음색 복제 강도", style = MaterialTheme.typography.labelMedium)
                            Text("${"%.2f".format(tuningTau)} - $tauDesc", style = MaterialTheme.typography.labelSmall, color = Color(0xFF673AB7))
                        }
                        Slider(
                            value = tuningTau,
                            onValueChange = { tuningTau = it },
                            valueRange = 0.10f..0.45f
                        )
                    }

                    // 3. Warmth / Resonance
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("3. 목소리 따뜻함 / 울림", style = MaterialTheme.typography.labelMedium)
                            Text("${"%.1f".format(tuningWarmth)} dB", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                        }
                        Slider(
                            value = tuningWarmth,
                            onValueChange = { tuningWarmth = it },
                            valueRange = -3.0f..6.0f
                        )
                    }

                    // 4. Speed
                    Column {
                        Text("4. 낭독 속도", style = MaterialTheme.typography.labelMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("-12%" to "느리게", "-6%" to "포근함", "+0%" to "보통", "+6%" to "빠르게").forEach { (speedVal, label) ->
                                val isSelected = tuningSpeed == speedVal
                                OutlinedButton(
                                    onClick = { tuningSpeed = speedVal },
                                    modifier = Modifier.weight(1f),
                                    colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                                ) {
                                    Text(label, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // 5. ElevenLabs Option
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("초고화질 AI 복제 (ElevenLabs)", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE65100))
                                    Text("99% 실제 부모님 목소리와 동일한 음성 복제", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBF360C))
                                }
                                Switch(
                                    checked = tuningUseEleven,
                                    onCheckedChange = { tuningUseEleven = it }
                                )
                            }
                            if (tuningUseEleven) {
                                OutlinedTextField(
                                    value = tuningElevenKey,
                                    onValueChange = { tuningElevenKey = it },
                                    label = { Text("ElevenLabs API Key") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("sk_...") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { saveTunedVoice() }) {
                    Text("💾 저장 및 적용")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { previewTunedVoice() },
                        enabled = !isTuningPreviewLoading
                    ) {
                        Text(if (isTuningPreviewLoading) "생성 중…" else "🎧 미리듣기")
                    }
                    TextButton(onClick = { showVoiceTuningDialog = false }) {
                        Text("닫기")
                    }
                }
            }
        )
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
        (0 until totalSteps).forEach { stepVoiceFile(it).delete() }
        stepRecorded = List(totalSteps) { false }
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
