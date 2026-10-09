import os
import shutil
import hashlib
import json
import subprocess
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
import engine

app = FastAPI(title="Pumsok Story Neural Voice Cloning API", version="1.0.0")

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
    speakerId: str = "default_mother"
    role: str = "MOTHER"

@app.get("/health")
@app.get("/api/voice/health")
def health():
    profiles = [f.replace(".json", "") for f in os.listdir(PROFILES_DIR) if f.endswith(".json")]
    cached_count = len([f for f in os.listdir(CACHE_DIR) if f.endswith(".mp3")])
    return {
        "status": "ready",
        "service": "Pumsok Neural Voice Cloning API",
        "profilesCount": len(profiles),
        "profiles": profiles,
        "cachedAudioCount": cached_count
    }

@app.post("/api/voice/train")
async def train_speaker(
    file: UploadFile = File(...),
    role: str = Form("MOTHER"),
    speakerId: str = Form(None)
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

    # Analyze acoustic features
    features = engine.analyze_audio_properties(ref_wav)
    profile = {
        "speakerId": speakerId,
        "roleKey": role_upper,
        "roleLabel": role_label,
        "f0MedianHz": features["f0MedianHz"],
        "durationSec": features["durationSec"],
        "sampleRate": features["sampleRate"],
        "energyRms": features["energyRms"],
        "isTrained": True
    }

    profile_path = os.path.join(PROFILES_DIR, f"{speakerId}.json")
    with open(profile_path, "w", encoding="utf-8") as f:
        json.dump(profile, f, ensure_ascii=False, indent=2)

    # Pre-synthesize preview audio
    preview_mp3 = os.path.join(CACHE_DIR, f"preview_{speakerId}.mp3")
    preview_text = f"사랑하는 우리 아이야, {role_label}가 들려주는 재미있는 동화 나라로 함께 가보자."
    engine.synthesize_cloned_voice(preview_text, profile, ref_wav, preview_mp3)

    return {
        "status": "success",
        "speakerId": speakerId,
        "role": role_upper,
        "roleLabel": role_label,
        "f0MedianHz": features["f0MedianHz"],
        "durationSec": features["durationSec"],
        "previewUrl": f"/api/voice/preview/{speakerId}"
    }

@app.post("/api/voice/synthesize")
def synthesize(req: SynthesizeRequest):
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="텍스트가 비어 있습니다.")

    profile_path = os.path.join(PROFILES_DIR, f"{req.speakerId}.json")
    if not os.path.exists(profile_path):
        # Fallback to default role profile if custom not found
        fallback_id = f"speaker_{req.role.upper().lower()}"
        profile_path = os.path.join(PROFILES_DIR, f"{fallback_id}.json")
        if not os.path.exists(profile_path):
            profile = {
                "speakerId": req.speakerId,
                "roleKey": req.role.upper(),
                "roleLabel": "엄마" if req.role.upper() == "MOTHER" else "아빠",
                "f0MedianHz": 220.0 if req.role.upper() == "MOTHER" else 120.0
            }
        else:
            with open(profile_path, "r", encoding="utf-8") as f:
                profile = json.load(f)
    else:
        with open(profile_path, "r", encoding="utf-8") as f:
            profile = json.load(f)

    cache_hash = hashlib.sha256(f"{req.speakerId}:{req.text}".encode()).hexdigest()
    out_mp3 = os.path.join(CACHE_DIR, f"{cache_hash}.mp3")

    if not os.path.exists(out_mp3):
        ref_wav = os.path.join(REFS_DIR, f"{profile.get('speakerId', req.speakerId)}.wav")
        engine.synthesize_cloned_voice(req.text, profile, ref_wav, out_mp3)

    return FileResponse(out_mp3, media_type="audio/mpeg", filename=f"story_{cache_hash[:8]}.mp3")

@app.get("/api/voice/preview/{speaker_id}")
def get_preview(speaker_id: str):
    preview_mp3 = os.path.join(CACHE_DIR, f"preview_{speaker_id}.mp3")
    if not os.path.exists(preview_mp3):
        raise HTTPException(status_code=404, detail="미리듣기 음성이 아직 생성되지 않았습니다.")
    return FileResponse(preview_mp3, media_type="audio/mpeg")

@app.get("/api/voice/profiles")
def list_profiles():
    results = []
    for f in os.listdir(PROFILES_DIR):
        if f.endswith(".json"):
            with open(os.path.join(PROFILES_DIR, f), "r", encoding="utf-8") as fp:
                results.append(json.load(fp))
    return results
