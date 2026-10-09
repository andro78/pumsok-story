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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
                    runOnUiThread { tts?.stop(); story = parsed; index = 0; prefs.edit().putInt("index", 0).apply(); message = "동화를 가져왔습니다"; busy = false }
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
        transcript = prefs.getString("transcript", "") ?: ""
        hasRecording = voiceFile.exists()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                ttsReady = result != null && result >= TextToSpeech.LANG_AVAILABLE
            }
        }
        executor.execute {
            val saved = File(filesDir, "book.epub")
            if (saved.exists()) {
                runCatching { EpubReader.read(saved) }.onSuccess { parsed ->
                    runOnUiThread { story = parsed; index = prefs.getInt("index", 0).coerceIn(parsed.paragraphs.indices) }
                }
            } else {
                runCatching {
                    assets.open("sun_and_moon.epub").use { input ->
                        saved.outputStream().use { out -> input.copyTo(out) }
                    }
                    EpubReader.read(saved)
                }.onSuccess { parsed ->
                    runOnUiThread { story = parsed; index = 0; message = "기본 동화를 준비했습니다." }
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
            OutlinedButton(onClick = { loadAssetStory("sun_and_moon.epub") }, enabled = !busy) {
                Text("샘플: 해와 달")
            }
            OutlinedButton(onClick = { loadAssetStory("rabbit_and_turtle.epub") }, enabled = !busy) {
                Text("샘플: 토끼와 거북이")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("부모님 목소리 등록", style = MaterialTheme.typography.titleLarge)
        Text("조용한 곳에서 아래 대본을 직접 읽어주세요. 녹음은 이 기기에 저장됩니다.")
        Row {
            Checkbox(consent, onCheckedChange = { consent = it }, enabled = !recording)
            Text("내 목소리이거나, 녹음하는 부모님의 동의를 받았습니다.", Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(transcript, onValueChange = {
            transcript = it
            prefs.edit().putString("transcript", it).apply()
        }, label = { Text("녹음에서 읽을 대본") }, enabled = !recording, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (recording) finishRecording()
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }, enabled = recording || (consent && transcript.isNotBlank())) { Text(if (recording) "녹음 종료" else "녹음 시작") }
        Row {
            TextButton(onClick = { playReference() }, enabled = hasRecording && !recording) { Text("녹음 듣기") }
            TextButton(onClick = {
                player?.release(); player = null
                voiceFile.delete(); hasRecording = false
                prefs.edit().remove("transcript").apply(); transcript = ""
            }, enabled = hasRecording && !recording) { Text("녹음 삭제") }
        }
        Text("부모님 목소리 생성: 엔진 연결 준비 중", color = MaterialTheme.colorScheme.primary)
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 8.dp))
    }

    @Composable private fun Reader() {
        val book = story
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(book?.title ?: "함께 읽을 동화를 골라주세요", style = MaterialTheme.typography.headlineSmall)
        if (book != null) {
            Text("${index + 1} / ${book.paragraphs.size} 문단")
            Text(book.paragraphs[index], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
            Row {
                TextButton(onClick = { move(-1) }, enabled = index > 0) { Text("이전") }
                TextButton(onClick = { move(1) }, enabled = index < book.paragraphs.lastIndex) { Text("다음") }
            }
            Button(onClick = {
                tts?.speak(book.paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "paragraph")
            }, enabled = ttsReady && !recording) { Text("기본 목소리로 이 문단 듣기") }
            TextButton(onClick = { tts?.stop() }) { Text("낭독 정지") }
            Text("현재 낭독은 기기의 기본 TTS이며 부모님 목소리가 아닙니다.")
        }
    }

    private fun move(delta: Int) {
        tts?.stop()
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
                    tts?.stop()
                    story = parsed
                    index = 0
                    prefs.edit().putInt("index", 0).apply()
                    message = "${parsed.title} 동화를 불러왔습니다."
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
        tts?.stop(); player?.release(); player = null
        val pending = File(filesDir, "voice.tmp")
        val next = MediaRecorder()
        runCatching {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(24000)
            next.setAudioEncodingBitRate(96000)
            next.setOutputFile(pending.absolutePath)
            next.prepare(); next.start()
        }.onSuccess { recorder = next; recording = true; message = "녹음 중입니다." }
            .onFailure { next.release(); pending.delete(); message = "녹음을 시작하지 못했습니다." }
    }

    private fun finishRecording() {
        val active = recorder ?: return
        val pending = File(filesDir, "voice.tmp")
        runCatching {
            active.stop()
            check(pending.renameTo(voiceFile))
        }.onSuccess { hasRecording = true; message = "녹음을 저장했습니다." }
            .onFailure { pending.delete(); message = "녹음이 너무 짧거나 저장에 실패했습니다." }
        active.release(); recorder = null; recording = false
    }

    private fun playReference() {
        player?.release()
        runCatching {
            MediaPlayer().also { p ->
                player = p
                p.setDataSource(voiceFile.absolutePath)
                p.setOnCompletionListener { it.release(); player = null }
                p.prepare(); p.start()
            }
        }.onFailure { player?.release(); player = null; message = "녹음을 재생하지 못했습니다." }
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onStop() {
        if (recording) finishRecording()
        tts?.stop(); player?.release(); player = null
        super.onStop()
    }

    override fun onDestroy() {
        updater.release()
        recorder?.release(); player?.release(); tts?.shutdown()
        executor.shutdown()
        super.onDestroy()
    }
}
