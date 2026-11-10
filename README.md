# OpenTTS NG

An Android **Text-to-Speech engine** with three interchangeable backends:

- **Cloud** — [OpenRouter](https://openrouter.ai) `/audio/speech` models (the
  original OpenTTS behaviour).
- **On device** — local ONNX models running through ONNX Runtime. No API key,
  no network, nothing leaves the phone.
- **Self-hosted server** — an [OpenTTS-SRV](../OpenTTS-SRV) instance on a GPU
  machine (ONNX Runtime + CUDA). Works with a bearer token and optional mutual
  TLS.

Any app that uses the platform TTS API (Evie, Moon+ Reader, …) can speak
through either backend; you pick one in the app.


## Local models

| Model | Family | Size on disk | Languages | Licence |
| --- | --- | --- | --- | --- |
| Kokoro 82M | StyleTTS2 → ONNX (int8) | 92 MB + 0.5 MB/voice | en, es, fr, hi, it, ja, pt, zh | Apache-2.0 |
| Matcha · LJSpeech | Matcha + HiFiGAN vocoder | 78 MB | en | Apache-2.0 / MIT |
| Piper · Lessac / Alan / Thorsten / Siwis / DaveFX | Piper VITS | 63 MB each | en-US, en-GB, de, fr, es | MIT / model-specific |

Models are **downloaded on demand** from Hugging Face into
`files/models/<id>/` and can be deleted again from the same screen. Nothing is
bundled in the APK (which stays ~35 MB, mostly ONNX Runtime + espeak data).

### How local synthesis works

```
text ──► SentenceSplitter ──► espeak-ng (JNI) ──► IPA phonemes
     ──► model adapter (Kokoro | Piper | Matcha) ──► float PCM
     ──► 16-bit mono ──► SynthesisCallback.audioAvailable()
```

- **espeak-ng 1.52.0** is vendored (`app/src/main/cpp/espeak-ng`) and built with
  the NDK, compiled without audio output, MBROLA, sonic or the async thread: we
  only need `espeak_TextToPhonemes(..., espeakPHONEMES_IPA)`. Its data (~14 MB,
  24 languages) is generated on the host with `espeak-ng --compile` and shipped
  in `assets/espeak-ng-data`; it is unpacked into the app's files dir on first
  use because espeak reads real files from disk.
- Text is split at sentence boundaries (≤ 320 chars) so first audio arrives fast
  and Kokoro's 510-row style matrix is never overrun. Adjacent fragments shorter
  than 80 chars are merged to avoid paying model-startup cost per "Dr.".
- **Kokoro**: `input_ids` + `style` (the row of `voices/<id>.bin` matching the
  phoneme count) + `speed` → 24 kHz waveform.
- **Piper/VITS**: espeak phoneme ids with BOS/pad/EOS interlacing + `scales`
  (noise, 1/speed, noise_w) → 22.05 kHz audio.
- **Matcha**: acoustic graph → 80-band mel → HiFiGAN `hifigan_v2.onnx` →
  22.05 kHz audio.
- Speed is applied inside the model (Kokoro `speed` tensor, Piper/Matcha
  `length_scale = 1/speed`); the reading app's rate setting is folded in when
  *use client rate* is enabled.

### Voice names

Local voices are exposed to the framework as `local|<modelId>/<voiceId>`
(cloud voices stay `author/model|voice`), so both backends can coexist in the
system voice picker. Selecting a local voice name overrides the stored backend
for that utterance.

## Self-hosted server backend

Point the app at an [OpenTTS-SRV](../OpenTTS-SRV) instance and the phone becomes a
thin client: text goes up, 16-bit PCM comes back, no model on the device.

- **Server URL** — `http://192.168.1.10:8420` on the LAN, or the hostname/port of
  the Caddy or NPM front end.
- **Bearer token** — the server's `OPENTTS_API_TOKEN`.
- **Connect / refresh** — `GET /health` + `GET /v1/models`; the model and voice
  dropdowns are filled from what the server advertises, including whether the
  model is already downloaded there.
- **Mutual TLS** — import a PKCS#12 (`.p12`/`.pfx`, password supported) or a PEM
  holding an unencrypted PKCS#8 private key plus its certificates. Extra
  certificates in the bundle are trusted as a private CA, which is what a Caddy
  or NPM setup with an internal CA needs. Files are copied into app-private
  storage; only the client-certificate handshake uses them.

Synthesis asks the server for `response_format=pcm` and streams the bytes
straight into `SynthesisCallback`, taking the real sample rate from the
`X-Sample-Rate` response header — no decoding, so latency is the server's
synthesis time plus the network hop.

Cleartext HTTP is enabled in the manifest so a plain-LAN server works without
TLS; put Caddy in front when the server is reachable from outside.

## Project layout

```
app/src/main/java/com/opentts/engine/
  OpenRouterTtsService.kt   TextToSpeechService; routes cloud vs local
  OpenRouterClient.kt       HTTP client for /audio/speech (cloud)
  AudioDecoder.kt           PCM passthrough + MP3/container decode (cloud)
  ModelsRepository.kt       Cloud model catalogue
  Settings.kt               SharedPreferences-backed configuration
  SettingsActivity.kt       Entry point / engine config activity
  SettingsScreen.kt         Compose settings UI
  LocalModelsSection.kt     Local model manager UI (download/select/delete)
  onnx/Phonemizer.kt        espeak-ng JNI wrapper + asset unpacking
  onnx/LocalModels.kt       Model catalogue (Kokoro, Piper, Matcha)
  onnx/ModelStore.kt        Downloads, install/remove, progress flow
  onnx/LocalTtsEngine.kt    ONNX Runtime adapters + sentence splitter
  onnx/WavIo.kt             WAV writer used by the debug self-test
  server/ServerClient.kt    HTTP client for OpenTTS-SRV (/v1/models, /v1/audio/speech)
  server/ClientCertStore.kt PKCS#12 / PEM import, client-cert TLS context
  ServerSection.kt          Server settings UI (URL, token, models, mTLS)
app/src/main/cpp/
  CMakeLists.txt            NDK build: espeak-ng + JNI bridge
  phonemizer.cpp            text -> IPA over espeak-ng
  espeak-ng/                vendored espeak-ng 1.52.0 (GPL-3.0)
app/src/main/assets/espeak-ng-data/   compiled espeak data (24 languages)
app/src/main/res/xml/tts_engine.xml   Declares the config activity
```

## Build

```sh
./gradlew :app:installDebug
```

Requires the Android SDK (platform 36), NDK 27.1.12297006+, and CMake 3.22.
ABIs built: `arm64-v8a` (phones) and `x86_64` (emulators).

## Measured on an emulator (API 36, x86_64)

| Model | Audio produced | Synthesis time | RTF |
| --- | --- | --- | --- |
| Kokoro 82M int8 | 5.5 s | 4.2 s (first call, includes model load) | ~0.77 |
| Piper Lessac | 4.3 s | 0.17 s | ~0.04 |
| Matcha + HiFiGAN | 2.4 s | 0.10 s | ~0.04 |

Output was cross-checked against the same pipeline run on the host with an
identical sentence: Kokoro 143,400 host vs 144,600 device samples (0.8 %),
Matcha 54,016 vs 53,760 (0.5 %).

## Notes / limits

- One ONNX session is cached; switching models reloads the graph.
- espeak is not thread-safe, so every JNI entry point takes a single mutex.
- StyleTTS2 itself is **not** included: the published ONNX exports have
  undocumented, opaque I/O. Kokoro is the supported StyleTTS2-lineage model.
- No GPU/NNAPI execution provider yet; CPU with up to 4 threads.
- Native libraries are built with 16 KB page alignment
  (`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, `-Wl,-z,max-page-size=16384`).
  Android 15+ devices with 16 KB pages refuse to
  install an APK whose `.so` files are only 4 KB aligned, with the generic
  "App not installed" error. Verify with
  `zipalign -c -P 16 -v 4 app-debug.apk` and `readelf -lW` (p_align `0x4000`).
- espeak-ng is GPL-3.0, therefore this app is distributed under GPL-3.0
  (see `LICENSE`).

## Cloud configuration keys

Settings live in the `opentts` SharedPreferences file.

| Key | Meaning |
| --- | --- |
| `backend` | `cloud` or `local` |
| `api_key` | OpenRouter API key |
| `model` | e.g. `hexgrad/kokoro-82m` |
| `voice` | provider voice id, e.g. `af_heart` |
| `format` | `mp3` or `pcm` |
| `sample_rate` | PCM sample rate when `format = pcm` |
| `speed` | playback speed multiplier |
| `instructions` | optional delivery instructions |
| `base_url` | API base URL (`https://openrouter.ai/api/v1`) |
| `timeout` | HTTP read timeout in seconds |
| `use_client_rate` | apply the caller's speech rate |
| `catalog_json` | cached cloud model catalogue |
| `local_model` | installed local model id, e.g. `kokoro-82m` |
| `local_voice` | local voice id, e.g. `af_heart` |
| `server_url` | OpenTTS-SRV base URL |
| `server_token` | server bearer token |
| `server_model` | model id on the server |
| `server_voice` | voice id on the server |
