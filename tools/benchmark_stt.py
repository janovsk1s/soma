#!/usr/bin/env python3
"""Compare Groq Whisper Large v3 and ElevenLabs Scribe v2 on Soma's languages.

Corpus layout:
  corpus/en/quiet-01.wav
  corpus/en/quiet-01.txt  # exact reference transcript, UTF-8
  corpus/lv/...

The script makes paid/free-tier API calls. It never runs as part of an app build.
"""

import argparse
import csv
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from pathlib import Path


LANGUAGES = ("en", "lv", "de", "et", "fi", "lt", "sk", "sv")


def multipart(fields: dict[str, str], audio: bytes) -> tuple[bytes, str]:
    boundary = f"soma-{uuid.uuid4()}"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.extend(
            (
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            )
        )
    parts.extend(
        (
            f"--{boundary}\r\n".encode(),
            b'Content-Disposition: form-data; name="file"; filename="soma.wav"\r\n',
            b"Content-Type: audio/wav\r\n\r\n",
            audio,
            f"\r\n--{boundary}--\r\n".encode(),
        )
    )
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def post_audio(url: str, key_header: tuple[str, str], fields: dict[str, str], wav: Path) -> str:
    body, content_type = multipart(fields, wav.read_bytes())
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={key_header[0]: key_header[1], "Content-Type": content_type, "User-Agent": "Soma-STT-benchmark/1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))["text"].strip()
    except urllib.error.HTTPError as error:
        detail = error.read(1024).decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error


def groq(key: str, wav: Path) -> str:
    return post_audio(
        "https://api.groq.com/openai/v1/audio/transcriptions",
        ("Authorization", f"Bearer {key}"),
        {"model": "whisper-large-v3", "response_format": "json"},
        wav,
    )


def elevenlabs(key: str, wav: Path, zero_retention: bool) -> str:
    suffix = "?enable_logging=false" if zero_retention else ""
    return post_audio(
        f"https://api.elevenlabs.io/v1/speech-to-text{suffix}",
        ("xi-api-key", key),
        {"model_id": "scribe_v2"},
        wav,
    )


def words(text: str) -> list[str]:
    normalized = unicodedata.normalize("NFKC", text).casefold()
    return re.findall(r"[^\W_]+(?:['’][^\W_]+)?", normalized, flags=re.UNICODE)


def word_error_rate(reference: str, hypothesis: str) -> float:
    expected, actual = words(reference), words(hypothesis)
    if not expected:
        return 0.0 if not actual else 1.0
    previous = list(range(len(actual) + 1))
    for row, expected_word in enumerate(expected, start=1):
        current = [row]
        for column, actual_word in enumerate(actual, start=1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[column] + 1,
                    previous[column - 1] + (expected_word != actual_word),
                )
            )
        previous = current
    return previous[-1] / len(expected)


def corpus_files(root: Path) -> list[tuple[str, Path, str]]:
    result = []
    for language in LANGUAGES:
        directory = root / language
        for wav in sorted(directory.glob("*.wav")):
            reference = wav.with_suffix(".txt")
            if not reference.is_file():
                raise SystemExit(f"Missing reference: {reference}")
            result.append((language, wav, reference.read_text(encoding="utf-8").strip()))
    if not result:
        raise SystemExit("No WAV/reference pairs found. See the module docstring for the corpus layout.")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--output", type=Path, default=Path("stt-benchmark.csv"))
    parser.add_argument(
        "--eleven-zero-retention",
        action="store_true",
        help="Request ElevenLabs zero retention (Enterprise workspaces only).",
    )
    args = parser.parse_args()
    keys = {
        "groq": os.environ.get("GROQ_API_KEY"),
        "elevenlabs": os.environ.get("ELEVENLABS_API_KEY"),
    }
    if not any(keys.values()):
        raise SystemExit("Set GROQ_API_KEY and/or ELEVENLABS_API_KEY.")

    rows = []
    for language, wav, reference in corpus_files(args.corpus):
        providers = []
        if keys["groq"]:
            providers.append(("groq-whisper-large-v3", lambda: groq(keys["groq"], wav)))
        if keys["elevenlabs"]:
            providers.append(
                (
                    "elevenlabs-scribe-v2",
                    lambda: elevenlabs(keys["elevenlabs"], wav, args.eleven_zero_retention),
                )
            )
        for provider, call in providers:
            started = time.monotonic()
            try:
                transcript, error = call(), ""
            except Exception as exception:  # Keep the rest of a paid corpus run useful.
                transcript, error = "", str(exception)
            latency = time.monotonic() - started
            rows.append(
                {
                    "provider": provider,
                    "language": language,
                    "clip": str(wav),
                    "latency_seconds": f"{latency:.3f}",
                    "wer": f"{word_error_rate(reference, transcript):.4f}" if not error else "",
                    "reference": reference,
                    "transcript": transcript,
                    "error": error,
                }
            )
            print(f"{provider:26} {language} {wav.name}: {rows[-1]['wer'] or 'ERROR'}", flush=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
