# OpenTTS

An Android **Text-to-Speech (TTS) engine** that lets any app using Android's system TTS API—such as ebook readers (Evie, Moon+ Reader, Librera) or accessibility tools—speak using your choice of three interchangeable backends:

- **On-device (Offline ONNX)** — Run neural voices directly on your phone using ONNX Runtime and `espeak-ng`. No API key, no internet connection after model download, and nothing leaves your device.
- **OpenRouter (Cloud)** — Stream speech from any text-to-speech model available through [OpenRouter](https://openrouter.ai) (or a compatible `/audio/speech` API).
- **Self-hosted server ([OpenTTS-SRV](https://github.com/elpocoburrito/OpenTTS-SRV))** — Offload synthesis to your own GPU/CPU TTS server over LAN or HTTPS, with bearer token authentication and optional mutual TLS (mTLS) client certificates.

---

## Features

- **Works with any Android TTS app** — Registers as a standard Android `TextToSpeechService`.
- **On-demand model downloads** — The APK stays compact; local models download directly from Hugging Face into app-private storage and can be removed at any time.
- **Smooth streaming playback** — Synthesizes the next sentence ahead of playback and enforces frame-aligned 16-bit PCM output to prevent dropouts and audio glitches.
- **Automatic per-device tuning** — Benchmarks CPU thread counts and XNNPACK in the background on first use and warms up the local model when the service starts.
- **Graceful backend fallback** — If your preferred backend is unavailable (for example, offline when Cloud is selected), OpenTTS can automatically fall back to another configured backend instead of failing silently.
- **In-app diagnostics** — Records recent synthesis errors right inside the settings screen (**Last speech error**) so you can see why a reading app failed without needing `adb`.
- **Android 15+ ready** — Native libraries are built with 16 KB page alignment for modern Android devices.

---

## Getting Started

### Requirements

- Android **8.0 (API 26)** or newer (`arm64-v8a` phones/tablets or `x86_64` emulators)

### 1. Install OpenTTS

Install an APK from Releases (if available) or [build from source](#building-from-source).

### 2. Configure a Backend

Open **OpenTTS** from your app launcher and choose one of the three backends at the top:

#### Option A: On-device (Offline ONNX)

1. Select **On device (ONNX)**.
2. Tap **Download** on a model card (for example, **Kokoro 82M** or a **Piper** voice).
3. Tap **Use** on the installed model, then choose a voice from **Local voice**.
   - For **Kokoro 82M**, each individual voice is a ~0.5 MB file—tap **Download voice** the first time you pick a new voice.
4. Once downloaded, synthesis runs 100% offline.

#### Option B: OpenRouter (Cloud)

1. Select **OpenRouter (cloud)**.
2. Paste your [OpenRouter](https://openrouter.ai) API key.
3. Tap **Refresh models** and select a **Model** and **Voice**.
4. Choose an **Audio format**:
   - **MP3 (reliable)** — Decoded on device; automatically detects sample rate.
   - **PCM (low latency)** — Streamed directly without decoding; set the sample rate to match your model (usually `24000` Hz).
5. Optionally adjust **Delivery instructions**, **API base URL**, or **Request timeout**.

#### Option C: Self-hosted Server ([OpenTTS-SRV](https://github.com/elpocoburrito/OpenTTS-SRV))

Point OpenTTS at an [OpenTTS-SRV](https://github.com/elpocoburrito/OpenTTS-SRV) instance (or any compatible server exposing `/health`, `/v1/models`, and `/v1/audio/speech` returning 16-bit mono PCM with an `X-Sample-Rate` header):

1. Set up [OpenTTS-SRV](https://github.com/elpocoburrito/OpenTTS-SRV) on your desktop or home server (Docker Compose with CUDA or CPU).
2. In OpenTTS, select **Self-hosted server**.
3. Enter your **Server URL** (e.g., `http://192.168.1.10:8420` on your LAN, or an `https://` reverse-proxy URL).
4. Enter the **Bearer token** (`OPENTTS_API_TOKEN` from your server's `.env`).
5. *(Optional)* Under **Mutual TLS**, import a client certificate if your reverse proxy (e.g., Caddy or Nginx) requires mTLS:
   - Supports **PKCS#12** (`.p12` / `.pfx`, with optional password) or **PEM** (an unencrypted PKCS#8 `PRIVATE KEY` plus its certificate chain).
   - Extra certificates included in the bundle are trusted as a private CA alongside Android's system CAs.
6. Tap **Connect / refresh**, then pick a **Model** and **Voice**.

### 3. Test and Set as Your System TTS Engine

1. Tap **Test voice** in OpenTTS to verify audio playback and adjust **Speed** as needed.
   - Enable **Also apply the reading app's speed setting** if you want your ebook reader's speed slider to multiply with OpenTTS's speed.
2. Tap **System settings** (or open Android **Settings → System / Accessibility → Text-to-speech output**).
3. Set **Preferred engine** to **OpenTTS**.
4. Open your reading app and start listening.

---

## On-Device Models

All local models are downloaded on demand into app-private storage and can be deleted from the **On device (ONNX)** screen.

| Model | Family | Download Size | Sample Rate | Voices / Languages | License |
| --- | --- | --- | --- | --- | --- |
| **Kokoro 82M** (int8) | StyleTTS2 → ONNX | ~92 MB + 0.5 MB/voice | 24 kHz | 54 voices (`en-US`, `en-GB`, `es`, `fr`, `hi`, `it`, `ja`, `pt-BR`, `zh`) | Apache-2.0 |
| **Matcha · LJSpeech** | Matcha-TTS + HiFiGAN | ~78 MB | 22.05 kHz | 1 voice (`en-US`) | Apache-2.0 / MIT |
| **Piper · Lessac** | Piper / VITS | ~63 MB | 22.05 kHz | `en-US` | MIT / model-specific |
| **Piper · Alan** | Piper / VITS | ~63 MB | 22.05 kHz | `en-GB` | MIT / model-specific |
| **Piper · Thorsten** | Piper / VITS | ~63 MB | 22.05 kHz | `de-DE` | MIT / model-specific |
| **Piper · Siwis** | Piper / VITS | ~63 MB | 22.05 kHz | `fr-FR` | MIT / model-specific |
| **Piper · DaveFX** | Piper / VITS | ~63 MB | 22.05 kHz | `es-ES` | MIT / model-specific |

### Voice Names in Reading Apps

If your reading app lets you pick a specific voice from the Android TTS picker, OpenTTS exposes voices using a structured naming scheme so all three backends can coexist:

- **On-device:** `local|<modelId>/<voiceId>` (e.g., `local|kokoro-82m/af_heart`)
- **Self-hosted server:** `server|<modelId>/<voiceId>`
- **OpenRouter cloud:** `<author>/<model>|<voice>` (e.g., `hexgrad/kokoro-82m|af_heart`)

Selecting a voice in a reading app routes that utterance to the matching backend when available, falling back to another ready backend if it cannot be served.

---

## Companion Server: OpenTTS-SRV

**[OpenTTS-SRV](https://github.com/elpocoburrito/OpenTTS-SRV)** is the companion self-hosted Docker/CUDA TTS server built specifically to pair with OpenTTS (and also works with any OpenAI-compatible `/v1/audio/speech` client).

### Why use OpenTTS-SRV alongside OpenTTS?

- **Faster synthesis & higher quality:** Phones run compact quantized models on CPU (such as Kokoro int8). OpenTTS-SRV runs full-precision (`fp32`) and larger models on a desktop or home-server GPU with ONNX Runtime + CUDA, well faster than real time.
- **More models & voices without using phone storage:** In addition to **Kokoro 82M (fp32)**, **Matcha**, and **Piper**, OpenTTS-SRV also serves **Kitten TTS mini** (8 English voices), **MMS-TTS**, and **VITS · VCTK** (109 English speakers)—all stored and cached on the server rather than on your phone.
- **Low battery usage & low latency:** The Android app acts as a thin client—it sends text and streams raw 16-bit PCM back sentence by sentence with no on-device audio decoding.
- **Private & zero per-character fees:** Everything stays on your own hardware, secured by a bearer token and optional Caddy mutual-TLS client certificates when accessed outside your LAN.
- **Seamless offline fallback:** Because OpenTTS automatically falls back across configured backends, you can use **OpenTTS-SRV** at home and seamlessly fall back to an **On-device** model whenever your server is unreachable.

See the [OpenTTS-SRV repository](https://github.com/elpocoburrito/OpenTTS-SRV) for Docker Compose setup, API documentation, and mutual-TLS instructions.

---

## Privacy & Permissions

- **`INTERNET` & `ACCESS_NETWORK_STATE`** — Used to download on-device models (from Hugging Face / GitHub Releases) and to communicate with OpenRouter or your self-hosted server when those backends are used. In **On device** mode, once a model and voice are downloaded, no text or audio is sent over the network.
- **Cleartext HTTP** — Enabled so self-hosted servers on a local network can be used over plain HTTP. If you expose a self-hosted server outside your LAN, put it behind HTTPS (and optionally mTLS).
- **Credential storage** — API keys, bearer tokens, and imported client certificates are stored in Android app-private storage (isolated from other apps on non-rooted devices).

---

## Troubleshooting

- **My reading app says "speech error":** Open the **OpenTTS** app and check **Last speech error** near the bottom of the screen for the exact failure reason.
- **The first utterance after installing a model is slow:** The first run loads the ONNX graph and kicks off a one-time background thread benchmark. Subsequent utterances are much faster, and the model warms up automatically when the TTS service starts.
- **OpenRouter returns HTTP 400 when speed is changed:** Some cloud providers reject non-default speed values. OpenTTS automatically retries without speed for models that reject it, or you can leave the speed slider at `1.00×`.
- **Self-hosted server takes a long time on the first request:** If the selected model is not yet downloaded on the server, the server downloads it on first use.

---

## Building from Source

### Prerequisites

- **JDK 17**
- **Android SDK** with platform **API 36**
- **Android NDK** `27.1.12297006` (or compatible)
- **CMake** `3.22.1`

### Build & Install

```sh
# Build the debug APK
./gradlew :app:assembleDebug

# Install directly to a connected device or emulator
./gradlew :app:installDebug

# Run JVM unit tests
./gradlew testDebugUnitTest
```

### Architecture Overview

```text
Text ─► SentenceSplitter ─► espeak-ng (JNI) ─► IPA phonemes
     ─► ONNX model adapter (Kokoro | Piper | Matcha) ─► float PCM
     ─► PcmSink (frame-aligned 16-bit mono) ─► SynthesisCallback
```

- **Phonemization:** Vendored `espeak-ng` 1.52.0 (`app/src/main/cpp/espeak-ng`) compiled via NDK/CMake without audio output dependencies, paired with compiled language data in `app/src/main/assets/espeak-ng-data` (unpacked to app-private storage on first use).
- **ABIs:** Builds `arm64-v8a` and `x86_64` with 16 KB ELF page alignment (`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, `-Wl,-z,max-page-size=16384`).
- **Source layout:**
  - `app/src/main/java/com/opentts/engine/` — `TextToSpeechService`, settings UI, OpenRouter client, audio decoder, frame-aligned `PcmSink`, and diagnostics.
  - `app/src/main/java/com/opentts/engine/onnx/` — Local model catalog, downloader, `espeak-ng` JNI wrapper, ONNX Runtime adapters, lookahead synthesis pipeline, and thread tuner.
  - `app/src/main/java/com/opentts/engine/server/` — Self-hosted server HTTP client and PKCS#12/PEM mutual-TLS certificate store.
  - `app/src/main/cpp/` — CMake configuration, JNI bridge (`phonemizer.cpp`), and vendored `espeak-ng`.

---

## License & Credits

Because this project bundles **espeak-ng** (GPL-3.0), OpenTTS is distributed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE) for full terms.

Built with:
- [espeak-ng](https://github.com/espeak-ng/espeak-ng) (GPL-3.0)
- [ONNX Runtime](https://onnxruntime.ai/) (MIT)
- [Kokoro-82M](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) (Apache-2.0), [Piper](https://github.com/rhasspy/piper) (MIT), and [Matcha-TTS](https://github.com/shivammehta25/Matcha-TTS) / [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
