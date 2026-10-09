import os
import time
import shutil
import hashlib
import json
import subprocess
from typing import Optional
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
import engine

app = FastAPI(title="Pumsok Story Neural Voice Cloning API", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

DATA_DIR = os.getenv("DATA_DIR", "/app/data")
REFS_DIR = os.path.join(DATA_DIR, "references")
PROFILES_DIR = os.path.join(DATA_DIR, "profiles")
CACHE_DIR = os.path.join(DATA_DIR, "cache")

for d in [REFS_DIR, PROFILES_DIR, CACHE_DIR]:
    os.makedirs(d, exist_ok=True)


class SynthesizeRequest(BaseModel):
    text: str
    speakerId: str = "speaker_mother"
    role: str = "MOTHER"
    pitchOffsetHz: Optional[int] = None
    tau: Optional[float] = None
    warmthDb: Optional[float] = None
    speedRate: Optional[str] = None


class TuneRequest(BaseModel):
    speakerId: str
    pitchOffsetHz: int = 0
    tau: float = 0.22
    warmthDb: float = 1.8
    presenceDb: float = -0.5
    speedRate: str = "-6%"
    elevenLabsApiKey: Optional[str] = None


@app.get("/health")
@app.get("/api/voice/health")
def health():
    profiles = [f.replace(".json", "") for f in os.listdir(PROFILES_DIR) if f.endswith(".json")]
    cached_count = len([f for f in os.listdir(CACHE_DIR) if f.endswith(".mp3")])
    conv, _ = engine.get_tone_converter()
    return {
        "status": "ready",
        "service": "Pumsok Neural Voice Cloning API",
        "version": "2.0.0",
        "profilesCount": len(profiles),
        "profiles": profiles,
        "cachedAudioCount": cached_count,
        "hasNeuralOpenVoice": conv is not None,
    }


@app.post("/api/voice/train")
async def train_speaker(
    file: UploadFile = File(...),
    role: str = Form("MOTHER"),
    speakerId: Optional[str] = Form(None),
    elevenLabsApiKey: Optional[str] = Form(None)
):
    role_upper = role.upper()
    role_label = "엄마" if role_upper == "MOTHER" else "아빠"
    if not speakerId:
        speakerId = f"speaker_{role_upper.lower()}"

    ref_raw = os.path.join(REFS_DIR, f"{speakerId}_raw{os.path.splitext(file.filename)[1]}")
    ref_wav = os.path.join(REFS_DIR, f"{speakerId}.wav")

    # Save uploaded reference audio
    with open(ref_raw, "wb") as f:
        shutil.copyfileobj(file.file, f)

    # Convert to 24kHz mono normalized WAV via ffmpeg
    cmd = [
        "ffmpeg", "-i", ref_raw,
        "-ac", "1", "-ar", "24000",
        "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
        "-y", ref_wav
    ]
    res = subprocess.run(cmd, capture_output=True)
    if res.returncode != 0:
        raise HTTPException(status_code=400, detail="오디오 전처리 변환에 실패했습니다.")

    # 1. Analyze acoustic properties
    features = engine.analyze_audio_properties(ref_wav)

    # 2. Extract 256-d neural speaker embedding via OpenVoice
    se_path = os.path.join(REFS_DIR, f"{speakerId}_se.pth")
    has_neural_se = engine.extract_speaker_embedding(ref_wav, se_path)

    profile = {
        "speakerId": speakerId,
        "roleKey": role_upper,
        "roleLabel": role_label,
        "f0MedianHz": features["f0MedianHz"],
        "f0StdHz": features["f0StdHz"],
        "durationSec": features["durationSec"],
        "sampleRate": features["sampleRate"],
        "energyRms": features["energyRms"],
        "spectralLowRatio": features["spectralLowRatio"],
        "spectralMidRatio": features["spectralMidRatio"],
        "spectralHighRatio": features["spectralHighRatio"],
        "sePath": se_path if has_neural_se else None,
        "pitchOffsetHz": 0,
        "tau": 0.22,
        "warmthDb": 1.8,
        "presenceDb": -0.5,
        "speedRate": "-6%" if role_upper == "FATHER" else "-8%",
        "elevenLabsApiKey": (elevenLabsApiKey or "").strip() or None,
        "elevenLabsVoiceId": None,
        "isTrained": True,
        "trainedAt": int(time.time())
    }

    # If ElevenLabs API Key provided, register voice clone with ElevenLabs
    if profile.get("elevenLabsApiKey"):
        try:
            v_id = engine.clone_elevenlabs_voice(speakerId, role_label, ref_wav, profile["elevenLabsApiKey"])
            profile["elevenLabsVoiceId"] = v_id
        except Exception as e:
            print(f"ElevenLabs registration warning: {e}", flush=True)

    profile_path = os.path.join(PROFILES_DIR, f"{speakerId}.json")
    with open(profile_path, "w", encoding="utf-8") as f:
        json.dump(profile, f, ensure_ascii=False, indent=2)

    # Pre-synthesize preview audio
    preview_mp3 = os.path.join(CACHE_DIR, f"preview_{speakerId}.mp3")
    preview_text = f"사랑하는 우리 아이야, {role_label}가 들려주는 재미있는 동화 나라로 함께 가보자."
    try:
        engine.synthesize_cloned_voice(preview_text, profile, ref_wav, preview_mp3)
    except Exception as e:
        print(f"Preview synthesis error in train_speaker: {e}", flush=True)
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"음성 미리듣기 생성 중 오류 발생: {str(e)}")

    return {
        "status": "success",
        "speakerId": speakerId,
        "role": role_upper,
        "roleLabel": role_label,
        "f0MedianHz": features["f0MedianHz"],
        "durationSec": features["durationSec"],
        "hasNeuralOpenVoice": has_neural_se,
        "hasElevenLabs": bool(profile.get("elevenLabsVoiceId")),
        "previewUrl": f"/api/voice/preview/{speakerId}"
    }


@app.post("/api/voice/tune")
def tune_speaker(req: TuneRequest):
    profile_path = os.path.join(PROFILES_DIR, f"{req.speakerId}.json")
    if not os.path.exists(profile_path):
        raise HTTPException(status_code=404, detail="스피커 프로필을 찾을 수 없습니다.")

    with open(profile_path, "r", encoding="utf-8") as f:
        profile = json.load(f)

    profile["pitchOffsetHz"] = req.pitchOffsetHz
    profile["tau"] = req.tau
    profile["warmthDb"] = req.warmthDb
    profile["presenceDb"] = req.presenceDb
    profile["speedRate"] = req.speedRate
    if req.elevenLabsApiKey is not None:
        profile["elevenLabsApiKey"] = req.elevenLabsApiKey.strip() or None

    ref_wav = os.path.join(REFS_DIR, f"{req.speakerId}.wav")

    # If ElevenLabs key changed and not yet cloned, clone now
    if profile.get("elevenLabsApiKey") and not profile.get("elevenLabsVoiceId") and os.path.exists(ref_wav):
        try:
            v_id = engine.clone_elevenlabs_voice(req.speakerId, profile.get("roleLabel", "부모님"), ref_wav, profile["elevenLabsApiKey"])
            profile["elevenLabsVoiceId"] = v_id
        except Exception as e:
            print(f"ElevenLabs tune clone error: {e}", flush=True)

    with open(profile_path, "w", encoding="utf-8") as f:
        json.dump(profile, f, ensure_ascii=False, indent=2)

    # Re-generate preview audio with new tuning
    preview_mp3 = os.path.join(CACHE_DIR, f"preview_{req.speakerId}.mp3")
    role_label = profile.get("roleLabel", "엄마")
    preview_text = f"사랑하는 우리 아이야, {role_label}가 들려주는 재미있는 동화 나라로 함께 가보자."
    engine.synthesize_cloned_voice(preview_text, profile, ref_wav, preview_mp3)

    return {
        "status": "success",
        "profile": profile,
        "previewUrl": f"/api/voice/preview/{req.speakerId}?t={int(time.time())}"
    }


@app.post("/api/voice/synthesize")
def synthesize(req: SynthesizeRequest):
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="텍스트가 비어 있습니다.")

    profile_path = os.path.join(PROFILES_DIR, f"{req.speakerId}.json")
    if not os.path.exists(profile_path):
        fallback_id = f"speaker_{req.role.upper().lower()}"
        profile_path = os.path.join(PROFILES_DIR, f"{fallback_id}.json")
        if not os.path.exists(profile_path):
            profile = {
                "speakerId": req.speakerId,
                "roleKey": req.role.upper(),
                "roleLabel": "엄마" if req.role.upper() == "MOTHER" else "아빠",
                "f0MedianHz": 210.0 if req.role.upper() == "MOTHER" else 110.0
            }
        else:
            with open(profile_path, "r", encoding="utf-8") as f:
                profile = json.load(f)
    else:
        with open(profile_path, "r", encoding="utf-8") as f:
            profile = json.load(f)

    # Apply request overrides if provided
    active_profile = dict(profile)
    if req.pitchOffsetHz is not None:
        active_profile["pitchOffsetHz"] = req.pitchOffsetHz
    if req.tau is not None:
        active_profile["tau"] = req.tau
    if req.warmthDb is not None:
        active_profile["warmthDb"] = req.warmthDb
    if req.speedRate is not None:
        active_profile["speedRate"] = req.speedRate

    # Cache key including tuning params
    tuning_sig = f"{active_profile.get('pitchOffsetHz', 0)}:{active_profile.get('tau', 0.22)}:{active_profile.get('warmthDb', 1.8)}:{active_profile.get('speedRate', '')}"
    cache_hash = hashlib.sha256(f"{active_profile.get('speakerId', req.speakerId)}:{tuning_sig}:{req.text}".encode()).hexdigest()
    out_mp3 = os.path.join(CACHE_DIR, f"{cache_hash}.mp3")

    if not os.path.exists(out_mp3):
        ref_wav = os.path.join(REFS_DIR, f"{active_profile.get('speakerId', req.speakerId)}.wav")
        engine.synthesize_cloned_voice(req.text, active_profile, ref_wav, out_mp3)

    return FileResponse(out_mp3, media_type="audio/mpeg", filename=f"story_{cache_hash[:8]}.mp3")


@app.get("/api/voice/preview/{speaker_id}")
def get_preview(speaker_id: str):
    preview_mp3 = os.path.join(CACHE_DIR, f"preview_{speaker_id}.mp3")
    if not os.path.exists(preview_mp3):
        raise HTTPException(status_code=404, detail="미리듣기 음성이 아직 생성되지 않았습니다.")
    return FileResponse(preview_mp3, media_type="audio/mpeg")


@app.get("/api/voice/profile/{speaker_id}")
def get_profile(speaker_id: str):
    profile_path = os.path.join(PROFILES_DIR, f"{speaker_id}.json")
    if not os.path.exists(profile_path):
        raise HTTPException(status_code=404, detail="스피커 프로필이 존재하지 않습니다.")
    with open(profile_path, "r", encoding="utf-8") as fp:
        return json.load(fp)


@app.get("/api/voice/profiles")
def list_profiles():
    results = []
    for f in os.listdir(PROFILES_DIR):
        if f.endswith(".json"):
            with open(os.path.join(PROFILES_DIR, f), "r", encoding="utf-8") as fp:
                results.append(json.load(fp))
    return results
