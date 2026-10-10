"""
Korean story text -> GPT-SoVITS v2 phoneme ids for the on-device engine.

Paragraphs are split into sentence-sized segments first: the on-device GPT decodes at most
~50 s per call, and short segments keep prosody stable.
"""
import os
import re
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "gsv_text"))

from text import cleaned_text_to_sequence  # noqa: E402
from text.cleaner import clean_text  # noqa: E402

# Bump when segmentation or the frontend changes, so devices regenerate cached audio.
PHONEME_VERSION = 1
MAX_SEGMENT_CHARS = 80
MIN_SEGMENT_CHARS = 8

_lock = threading.Lock()  # g2pk2/mecab are not documented as thread-safe
_SENTENCE_END = re.compile(r'(?<=[.!?…。])["”’\')\]]*\s+')
_CLAUSE_BREAK = re.compile(r"(?<=[,，、;:])\s+")


def split_segments(paragraph: str) -> list:
    """Splits a paragraph at sentence ends, then long sentences at commas; merges tiny pieces."""
    text = re.sub(r"\s+", " ", paragraph).strip()
    if not text:
        return []
    pieces = []
    for sentence in _SENTENCE_END.split(text):
        sentence = sentence.strip()
        if not sentence:
            continue
        if len(sentence) <= MAX_SEGMENT_CHARS:
            pieces.append(sentence)
            continue
        current = ""
        for clause in _CLAUSE_BREAK.split(sentence):
            if current and len(current) + 1 + len(clause) > MAX_SEGMENT_CHARS:
                pieces.append(current)
                current = clause
            else:
                current = f"{current} {clause}".strip()
        if current:
            pieces.append(current)

    merged = []
    for piece in pieces:
        if merged and (len(piece) < MIN_SEGMENT_CHARS or len(merged[-1]) < MIN_SEGMENT_CHARS) \
                and len(merged[-1]) + 1 + len(piece) <= MAX_SEGMENT_CHARS:
            merged[-1] = f"{merged[-1]} {piece}"
        else:
            merged.append(piece)
    return merged


def phonemize(paragraph: str) -> list:
    """Returns [{"text": segment, "phones": [ids]}] for one paragraph; skips segments with no speech."""
    segments = []
    for segment in split_segments(paragraph):
        with _lock:
            phones, _, _ = clean_text(segment, "ko", "v2")
        ids = cleaned_text_to_sequence(phones, "v2")
        if any(p not in (",", ".", "!", "?", "…", "-", "SP", "UNK") for p in phones):
            segments.append({"text": segment, "phones": ids})
    return segments
