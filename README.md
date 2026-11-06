# OpenTTS

An Android **Text-to-Speech engine** that synthesizes speech with
[OpenRouter](https://openrouter.ai) TTS models, so any app that uses the
platform TTS API (Evie, Moon+ Reader, etc.) can speak through OpenRouter.

## How it works

- Implements `android.speech.tts.TextToSpeechService` and registers the
  `android.intent.action.TTS_SERVICE` intent filter.
- Each utterance is POSTed to OpenRouter's OpenAI-compatible endpoint:

  ```
  POST https://openrouter.ai/api/v1/audio/speech
  { "model": "...", "input": "...", "voice": "...", "response_format": "mp3" | "pcm" }
  ```

- The response is a raw audio byte stream. PCM is streamed straight to the
  caller; MP3 is decoded on-device with `MediaCodec` (the real sample rate and
  channel count are read from the container).
- `onStop()` disconnects the in-flight HTTP call, so reading apps can cut off
  speech immediately (synthesis runs synchronously on the platform's synthesis
  thread, as required by `TextToSpeechService`).

## Project layout

```
app/src/main/java/com/opentts/engine/
  OpenRouterTtsService.kt   TextToSpeechService implementation
  OpenRouterClient.kt       HTTP client for /audio/speech
  AudioDecoder.kt           PCM passthrough + MP3/container decode to PCM
  ModelsRepository.kt       Loads/caches the TTS model catalogue
  Settings.kt               SharedPreferences-backed configuration
  SettingsActivity.kt       Entry point / engine config activity
  SettingsScreen.kt         Compose settings UI
app/src/main/res/xml/tts_engine.xml
                            Declares the config activity to the framework
```

## Setup

1. Build and install:

   ```sh
   ./gradlew :app:installDebug
   ```

2. Open **OpenTTS**, paste your OpenRouter API key, tap **Refresh models**,
   and choose a model + voice.
3. In **Settings → Text-to-speech output** on the device, select
   **OpenTTS (OpenRouter)** as the preferred engine.
4. (Optional) Pick a specific OpenTTS voice inside your reading app.

## Notes

- Engine discovery requires `android.intent.category.DEFAULT` on the
  `TTS_SERVICE` intent filter: the framework enumerates engines with
  `PackageManager.MATCH_DEFAULT_ONLY` and silently skips filters without it.
- The settings screen is declared through an XML meta-data resource
  (`res/xml/tts_engine.xml`, tag `tts-engine`, attr `android:settingsActivity`),
  which is what the gear icon in the system TTS settings opens.
- `response_format = mp3` is the default because it is self-describing and
  universally supported (ElevenLabs, MiniMax, Fish Audio, ...). `pcm` avoids
  decoding for lower latency but you must set the correct sample rate (most
  providers use 24000 Hz); HeyGen and Gemini only return PCM, MiniMax only MP3.
- Speed is only sent when it differs from `1.0`. If a provider rejects it with
  HTTP 400, the request is retried once without the `speed` field and that model
  is remembered for the rest of the process (openai-style models accept it,
  ElevenLabs only accepts 0.7–1.2).
- The catalogue is fetched from
  `GET /api/v1/models?output_modalities=speech` (38 models at the time of
  writing) and cached in preferences.

## Configuration keys

Settings live in the `opentts` SharedPreferences file.

| Key | Meaning |
| --- | --- |
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
| `catalog_json` | cached model catalogue |
