# Soma speech-to-text benchmark

Use real Light Phone III recordings—not provider demos—to choose between Groq
Whisper Large v3 and ElevenLabs Scribe v2 for Soma's eight languages.

Create `corpus/en`, `corpus/lv`, `corpus/de`, `corpus/et`, `corpus/fi`,
`corpus/lt`, `corpus/sk`, and `corpus/sv`. Put each 16 kHz mono WAV beside an
exact UTF-8 transcript with the same stem. Include quiet speech, street noise,
short commands, longer thoughts, and code-switching.

Run one or both providers:

```sh
GROQ_API_KEY=... ELEVENLABS_API_KEY=... \
  python3 tools/benchmark_stt.py corpus --output results/stt.csv
```

The CSV contains latency and word error rate per clip. API calls may incur cost.
Groq is the privacy/default candidate because Zero Data Retention can be enabled
for all customers in its console. ElevenLabs' zero-retention request is limited
to eligible Enterprise workspaces; add `--eleven-zero-retention` only there.
