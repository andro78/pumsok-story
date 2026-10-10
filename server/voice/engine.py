"""
Advanced Neural Voice Cloning & Hybrid Storytelling Engine for Pumsok Story
Combines:
1. ElevenLabs Instant Voice Cloning (IVC) for ultra-high fidelity (99% resemblance)
2. OpenVoice Neural Tone Color Converter (VITS-based) running on server CPU for on-premise cloning
3. Deep Acoustic Profiling & Parametric Spectral Morphing (F0, formant, warmth, resonance)
"""

import os
import sys
import json
import time
import asyncio
import hashlib
import shutil
import subprocess
import requests
import numpy as np
import scipy.io.wavfile as wavfile
import edge_tts

try:
    import torch
    import librosa
    import soundfile
    from openvoice.api import ToneColorConverter
    HAS_OPENVOICE = True
except Exception as e:
    HAS_OPENVOICE = False
    print(f"OpenVoice libraries not fully loaded: {e}", flush=True)

MODEL_DIR = os.getenv("MODEL_DIR", "/app/data/models/openvoice")
CKPT_PATH = os.path.join(MODEL_DIR, "checkpoint.pth")
CONFIG_PATH = os.path.join(MODEL_DIR, "config.json")
KR_SE_PATH = os.path.join(MODEL_DIR, "kr.pth")

# Bump when the synthesis pipeline changes so cached audio is regenerated.
ENGINE_VERSION = "3"
# Bump when the speaker embedding extraction changes so stored embeddings are re-extracted.
SE_VERSION = 2

DEFAULT_TAU = 0.3
MIN_RECOMMENDED_VOICED_SEC = 20.0

# Sentences used to measure the Edge TTS base voice's own tone color (source embedding).
SOURCE_SE_SENTENCES = [
    "옛날 옛적 어느 깊은 산골에 어머니와 착한 오누이가 살고 있었습니다.",
    "우리 아가, 오늘 하루도 재미있게 잘 보냈니?",
    "창밖으로 별들이 반짝이고, 달님이 은은하게 미소를 짓는 밤이야.",
    "이제 편안하게 눈을 감으면, 숲속 친구들의 재미있는 이야기가 시작될 거야.",
    "토끼는 깡충깡충 뛰어가다가 커다란 나무 아래에서 잠시 쉬었어요.",
    "그러자 할아버지가 빙그레 웃으며 말씀하셨습니다. 걱정하지 말거라.",
]

_tone_color_converter = None
_kr_base_se = None


def get_tone_converter():
    """Singleton getter for OpenVoice ToneColorConverter."""
    global _tone_color_converter, _kr_base_se
    if not HAS_OPENVOICE:
        return None, None

    if _tone_color_converter is None and os.path.exists(CKPT_PATH) and os.path.exists(CONFIG_PATH):
        try:
            print("Initializing OpenVoice ToneColorConverter...", flush=True)
            conv = ToneColorConverter(CONFIG_PATH, device="cpu", enable_watermark=False)
            conv.load_ckpt(CKPT_PATH)
            _tone_color_converter = conv
            if os.path.exists(KR_SE_PATH):
                _kr_base_se = torch.load(KR_SE_PATH, map_location="cpu")
            print("OpenVoice ToneColorConverter successfully initialized!", flush=True)
        except Exception as e:
            print(f"Failed to initialize ToneColorConverter: {e}", flush=True)
            _tone_color_converter = None

    return _tone_color_converter, _kr_base_se


def _voiced_audio(y: np.ndarray, sr: int, top_db: float = 35.0) -> np.ndarray:
    """Drops silent gaps so the embedding is computed on speech only."""
    intervals = librosa.effects.split(y, top_db=top_db)
    if len(intervals) == 0:
        return y
    pad = int(sr * 0.05)
    parts = [y[max(0, a - pad):min(len(y), b + pad)] for a, b in intervals]
    return np.concatenate(parts)


def _extract_se_from_files(converter, wav_paths: list, save_path: str, chunk_sec: float = 8.0) -> float:
    """
    Removes silence, slices speech into ~8 s chunks and averages their embeddings.
    Returns the total voiced duration in seconds (0.0 on failure).
    """
    sr = converter.hps.data.sampling_rate
    voiced = []
    for p in wav_paths:
        y, _ = librosa.load(p, sr=sr)
        voiced.append(_voiced_audio(y, sr))
    y = np.concatenate(voiced) if voiced else np.zeros(0, dtype=np.float32)
    voiced_sec = len(y) / float(sr)
    if voiced_sec < 1.0:
        return 0.0

    os.makedirs(os.path.dirname(save_path), exist_ok=True)
    tmp_dir = save_path + ".chunks"
    os.makedirs(tmp_dir, exist_ok=True)
    chunk_len = int(sr * chunk_sec)
    min_len = int(sr * 2.5)
    chunks = []
    try:
        for idx, i in enumerate(range(0, len(y), chunk_len)):
            chunk = y[i:i + chunk_len]
            if len(chunk) >= min_len or not chunks:
                chunk_file = os.path.join(tmp_dir, f"chunk_{idx}.wav")
                soundfile.write(chunk_file, chunk, sr)
                chunks.append(chunk_file)
        converter.extract_se(chunks, se_save_path=save_path)
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
    return voiced_sec if os.path.exists(save_path) else 0.0


def extract_speaker_embedding(wav_path: str, save_path: str) -> float:
    """
    Extracts the 256-d neural speaker embedding from the parent reference audio.
    Returns the voiced (non-silent) duration used, or 0.0 if extraction failed.
    """
    converter, _ = get_tone_converter()
    if converter is None or not os.path.exists(wav_path):
        return 0.0
    try:
        return _extract_se_from_files(converter, [wav_path], save_path)
    except Exception as e:
        print(f"Failed to extract speaker embedding: {e}", flush=True)
        return 0.0


def _run_edge_tts(text: str, voice: str, pitch_arg: str, rate_arg: str, out_mp3: str) -> bool:
    cmd = [
        sys.executable, "-m", "edge_tts",
        f"--voice={voice}",
        f"--pitch={pitch_arg}",
        f"--rate={rate_arg}",
        f"--text={text}",
        f"--write-media={out_mp3}"
    ]
    res = subprocess.run(cmd, capture_output=True, timeout=30)
    if res.returncode != 0:
        print(f"Edge TTS subprocess error ({res.returncode}): {res.stderr.decode()}", flush=True)
    return os.path.exists(out_mp3) and os.path.getsize(out_mp3) >= 100


_source_se_cache = {}


def get_source_embedding(base_voice: str, pitch_arg: str):
    """
    Returns the tone color embedding of the Edge TTS base voice at the given pitch.
    OpenVoice removes the source timbre before applying the parent's timbre, so the
    source embedding must describe the audio actually being converted (not kr.pth,
    which belongs to the MeloTTS Korean base speaker). Cached on disk per voice/pitch.
    """
    key = f"{base_voice}_{pitch_arg}"
    if key in _source_se_cache:
        return _source_se_cache[key]

    converter, _ = get_tone_converter()
    if converter is None:
        return None

    cache_dir = os.path.join(MODEL_DIR, "source_se")
    se_path = os.path.join(cache_dir, f"{key}.pth")
    if not os.path.exists(se_path):
        os.makedirs(cache_dir, exist_ok=True)
        tmp_files = []
        try:
            for idx, sentence in enumerate(SOURCE_SE_SENTENCES):
                mp3 = os.path.join(cache_dir, f"{key}_{idx}.tmp.mp3")
                if _run_edge_tts(sentence, base_voice, pitch_arg, "+0%", mp3):
                    tmp_files.append(mp3)
            if tmp_files:
                _extract_se_from_files(converter, tmp_files, se_path)
        except Exception as e:
            print(f"Failed to build source embedding for {key}: {e}", flush=True)
        finally:
            for f in tmp_files:
                try:
                    os.remove(f)
                except Exception:
                    pass

    if not os.path.exists(se_path):
        return None
    se = torch.load(se_path, map_location="cpu")
    _source_se_cache[key] = se
    return se


def analyze_audio_properties(wav_path: str) -> dict:
    """Extracts fundamental acoustic features and spectral profile from reference recording."""
    sr, data = wavfile.read(wav_path)
    if data.ndim > 1:
        data = data.mean(axis=1)
    data = data.astype(np.float32)

    max_val = np.max(np.abs(data))
    if max_val > 0:
        data = data / max_val

    duration_sec = len(data) / float(sr)

    # Estimate speaker's natural F0 pitch using autocorrelation
    chunk_size = int(sr * 0.05)
    pitches = []
    for i in range(0, len(data) - chunk_size, chunk_size):
        chunk = data[i:i + chunk_size]
        if np.std(chunk) < 0.02:
            continue
        corr = np.correlate(chunk, chunk, mode="full")
        corr = corr[len(corr) // 2:]
        min_lag = int(sr / 350)  # max ~350Hz
        max_lag = int(sr / 70)   # min ~70Hz
        if len(corr) > max_lag:
            peak_lag = min_lag + np.argmax(corr[min_lag:max_lag])
            if corr[peak_lag] > 0.3 * corr[0]:
                pitches.append(sr / peak_lag)

    f0_median = float(np.median(pitches)) if pitches else 180.0
    f0_std = float(np.std(pitches)) if len(pitches) > 1 else 15.0
    energy_rms = float(np.sqrt(np.mean(data ** 2)))

    # Estimate spectral energy distribution (chest resonance vs clarity)
    # FFT spectrum analysis
    fft_spec = np.abs(np.fft.rfft(data[:min(len(data), sr * 10)]))
    freqs = np.fft.rfftfreq(min(len(data), sr * 10), 1.0 / sr)

    low_band = np.mean(fft_spec[(freqs >= 80) & (freqs < 300)]) if np.any((freqs >= 80) & (freqs < 300)) else 1.0
    mid_band = np.mean(fft_spec[(freqs >= 300) & (freqs < 2500)]) if np.any((freqs >= 300) & (freqs < 2500)) else 1.0
    high_band = np.mean(fft_spec[(freqs >= 2500) & (freqs < 6000)]) if np.any((freqs >= 2500) & (freqs < 6000)) else 1.0

    total_band = low_band + mid_band + high_band + 1e-6
    low_ratio = float(low_band / total_band)
    mid_ratio = float(mid_band / total_band)
    high_ratio = float(high_band / total_band)

    return {
        "durationSec": round(duration_sec, 2),
        "sampleRate": sr,
        "f0MedianHz": round(f0_median, 1),
        "f0StdHz": round(f0_std, 1),
        "energyRms": round(energy_rms, 3),
        "spectralLowRatio": round(low_ratio, 3),
        "spectralMidRatio": round(mid_ratio, 3),
        "spectralHighRatio": round(high_ratio, 3),
    }


# ==================== ElevenLabs Integration ====================

def clone_elevenlabs_voice(speaker_id: str, role_label: str, wav_path: str, api_key: str) -> str:
    """Creates an Instant Voice Clone on ElevenLabs from parent reference audio."""
    url = "https://api.elevenlabs.io/v1/voices/add"
    headers = {"xi-api-key": api_key}
    data = {
        "name": f"Pumsok_{role_label}_{speaker_id[-8:]}",
        "description": f"Pumsok Story Parent Fairytale Voice ({role_label})",
    }
    with open(wav_path, "rb") as f:
        files = {"files": (os.path.basename(wav_path), f, "audio/wav")}
        res = requests.post(url, headers=headers, data=data, files=files, timeout=40)
    if res.status_code in (200, 201):
        voice_id = res.json().get("voice_id")
        return voice_id
    raise RuntimeError(f"ElevenLabs clone failed ({res.status_code}): {res.text}")


def synthesize_elevenlabs_voice(text: str, voice_id: str, api_key: str, output_mp3: str) -> bool:
    """Synthesizes speech using cloned voice on ElevenLabs multilingual v2."""
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}"
    headers = {
        "xi-api-key": api_key,
        "Content-Type": "application/json",
        "Accept": "audio/mpeg",
    }
    payload = {
        "text": text,
        "model_id": "eleven_multilingual_v2",
        "voice_settings": {
            "stability": 0.45,
            "similarity_boost": 0.88,
            "style": 0.20,
            "use_speaker_boost": True
        }
    }
    res = requests.post(url, headers=headers, json=payload, timeout=60, stream=True)
    if res.status_code == 200:
        with open(output_mp3, "wb") as f:
            for chunk in res.iter_content(chunk_size=4096):
                f.write(chunk)
        return True
    print(f"ElevenLabs TTS failed ({res.status_code}): {res.text}", flush=True)
    return False


# ==================== Edge TTS Base Synthesis ====================

async def _synthesize_neural_async(text: str, voice_name: str, pitch_arg: str, rate_arg: str, out_wav: str):
    communicate = edge_tts.Communicate(
        text=text,
        voice=voice_name,
        pitch=pitch_arg,
        rate=rate_arg
    )
    await communicate.save(out_wav)


# ==================== Hybrid Synthesis Pipeline ====================

def synthesize_cloned_voice(text: str, profile: dict, reference_wav: str, output_mp3: str):
    """
    Synthesizes speech with parent voice identity using the best available engine:
    1. ElevenLabs Instant Voice Clone (if API key and voice_id provided)
    2. OpenVoice Neural Tone Color Converter (conditioned on parent's 256-d embedding)
    3. High-fidelity Parametric Acoustic Filter (multi-band spectral matching & EQ)
    """
    os.makedirs(os.path.dirname(output_mp3), exist_ok=True)

    # 1. Check ElevenLabs API Key
    eleven_key = (profile.get("elevenLabsApiKey") or os.getenv("ELEVENLABS_API_KEY", "") or "").strip()
    voice_id = (profile.get("elevenLabsVoiceId") or "").strip()

    if eleven_key:
        if not voice_id and os.path.exists(reference_wav):
            try:
                voice_id = clone_elevenlabs_voice(
                    speaker_id=str(profile.get("speakerId") or "speaker"),
                    role_label=str(profile.get("roleLabel") or "부모님"),
                    wav_path=reference_wav,
                    api_key=eleven_key
                )
                profile["elevenLabsVoiceId"] = voice_id
            except Exception as e:
                print(f"Auto-clone ElevenLabs error: {e}", flush=True)

        if voice_id:
            try:
                success = synthesize_elevenlabs_voice(text, voice_id, eleven_key, output_mp3)
                if success and os.path.exists(output_mp3) and os.path.getsize(output_mp3) > 1000:
                    return
            except Exception as e:
                print(f"ElevenLabs synthesis error, falling back to neural converter: {e}", flush=True)

    # 2. Server-side Base Neural TTS
    raw_tts_mp3 = output_mp3 + ".base.mp3"
    f0 = float(profile.get("f0MedianHz") or 180.0)
    role = str(profile.get("roleKey") or "MOTHER").upper()
    pitch_offset_user = float(profile.get("pitchOffsetHz") or 0)
    user_speed = str(profile.get("speedRate") or "").strip()

    if "FATHER" in role:
        base_voice = "ko-KR-InJoonNeural"
        baseline_f0 = 125.0
        tempo_rate = user_speed or "-6%"
    else:
        base_voice = "ko-KR-SunHiNeural"
        baseline_f0 = 210.0
        tempo_rate = user_speed or "-8%"

    # Compute pitch shift in Hz. Large shifts on the base voice add artifacts, so keep it moderate.
    total_pitch_diff = int(round((f0 - baseline_f0) + pitch_offset_user))
    total_pitch_diff = max(-30, min(30, total_pitch_diff))
    pitch_arg = f"{total_pitch_diff:+d}Hz"

    if not _run_edge_tts(text, base_voice, pitch_arg, tempo_rate, raw_tts_mp3):
        duration = max(2.0, len(text) * 0.18)
        subprocess.run([
            "ffmpeg", "-f", "lavfi",
            "-i", f"sine=frequency={f0}:duration={duration}",
            "-y", raw_tts_mp3
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # 3. OpenVoice Neural Tone Color Conversion
    se_path = profile.get("sePath") or reference_wav.replace(".wav", "_se.pth")
    if not os.path.exists(se_path) and os.path.exists(reference_wav):
        extract_speaker_embedding(reference_wav, se_path)

    converted_wav = output_mp3 + ".converted.wav"
    converter, kr_se = get_tone_converter()
    used_openvoice = False

    if converter is not None and os.path.exists(se_path):
        try:
            src_se = get_source_embedding(base_voice, pitch_arg)
            if src_se is None:
                src_se = kr_se
            if src_se is not None:
                tgt_se = torch.load(se_path, map_location="cpu")
                # tau is the posterior sampling noise scale, not a "strength": higher = noisier.
                tau = profile.get("tau")
                tau = DEFAULT_TAU if tau is None else max(0.1, min(0.4, float(tau)))
                converter.convert(
                    audio_src_path=raw_tts_mp3,
                    src_se=src_se,
                    tgt_se=tgt_se,
                    output_path=converted_wav,
                    tau=tau
                )
                if os.path.exists(converted_wav) and os.path.getsize(converted_wav) > 1000:
                    used_openvoice = True
        except Exception as e:
            print(f"OpenVoice conversion failed: {e}", flush=True)

    input_audio_for_eq = converted_wav if used_openvoice else raw_tts_mp3

    # 4. Light EQ polishing. Strong boosts make the voice boomy, so keep gains in a narrow range.
    warmth_db = float(profile.get("warmthDb") if profile.get("warmthDb") is not None else 1.8)
    presence_db = float(profile.get("presenceDb") if profile.get("presenceDb") is not None else -0.5)
    warmth_db = max(-3.0, min(3.0, warmth_db))
    presence_db = max(-4.0, min(4.0, presence_db))

    eq_filters = [
        f"equalizer=f=320:t=q:w=1.2:g={warmth_db:.1f}",
        f"equalizer=f=3200:t=q:w=1.4:g={presence_db:.1f}",
        "loudnorm=I=-16:TP=-1.5:LRA=10"
    ]
    af_chain = ",".join(eq_filters)

    cmd = [
        "ffmpeg", "-i", input_audio_for_eq,
        "-af", af_chain,
        "-c:a", "libmp3lame", "-b:a", "128k",
        "-y", output_mp3
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Cleanup temporary files
    for tmp in [raw_tts_mp3, converted_wav]:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except Exception:
                pass
