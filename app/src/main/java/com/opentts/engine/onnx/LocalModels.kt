package com.opentts.engine.onnx

/** Model families the local engine knows how to run. */
enum class ModelFamily(val label: String) {
    KOKORO("Kokoro 82M"),
    PIPER("Piper / VITS"),
    MATCHA("Matcha + HiFiGAN"),
}

data class RemoteFile(val name: String, val url: String, val bytes: Long = 0L)

/**
 * A selectable voice. For Kokoro each voice is a separate 510x256 style matrix
 * (`voices/af_heart.bin`); for Piper/Matcha the speaker lives inside a single
 * model file, so [remote] is null and [speakerId] selects a VITS speaker.
 */
data class LocalVoiceSpec(
    val id: String,
    val label: String,
    val language: String,
    val espeakVoice: String,
    val remote: RemoteFile? = null,
    val speakerId: Int = -1,
)

data class LocalModelSpec(
    val id: String,
    val label: String,
    val family: ModelFamily,
    val description: String,
    val sampleRate: Int,
    val files: List<RemoteFile>,
    val voices: List<LocalVoiceSpec>,
    val license: String,
    val espeakVoice: String = "en-us",
) {
    /** Bytes that must be on disk before the model can speak. */
    val baseBytes: Long get() = files.sumOf { it.bytes }
}

/** The curated catalogue of local models. Everything is downloaded on demand. */
object LocalModelCatalog {

    private const val KOKORO_REPO = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main"
    private const val PIPER_REPO = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
    private const val MATCHA_REPO = "https://huggingface.co/csukuangfj/matcha-icefall-en_US-ljspeech/resolve/main"

    // ---------------------------------------------------------------- Kokoro

    private fun kokoroVoice(id: String): LocalVoiceSpec {
        val language = when (id.first()) {
            'a' -> "en-US"
            'b' -> "en-GB"
            'e' -> "es-ES"
            'f' -> "fr-FR"
            'h' -> "hi-IN"
            'i' -> "it-IT"
            'j' -> "ja-JP"
            'p' -> "pt-BR"
            'z' -> "cmn-CN"
            else -> "en-US"
        }
        val espeak = when (id.first()) {
            'a' -> "en-us"
            'b' -> "en-gb"
            'e' -> "es"
            'f' -> "fr-fr"
            'h' -> "hi"
            'i' -> "it"
            'j' -> "ja"
            'p' -> "pt-br"
            'z' -> "cmn"
            else -> "en-us"
        }
        val gender = when (id.getOrNull(1)) {
            'f' -> "female"
            'm' -> "male"
            else -> ""
        }
        val pretty = id.substringAfter('_', id).replaceFirstChar { it.uppercase() }
        return LocalVoiceSpec(
            id = id,
            label = listOf(pretty.ifBlank { id }, gender, language.substringBefore('-'))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            language = language,
            espeakVoice = espeak,
            remote = RemoteFile("voices/$id.bin", "$KOKORO_REPO/voices/$id.bin", 522_240),
        )
    }

    private val KOKORO_VOICE_IDS = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore",
        "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
        "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
        "am_onyx", "am_puck", "am_santa",
        "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
        "ef_dora", "em_alex", "em_santa",
        "ff_siwis",
        "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
        "if_sara", "im_nicola",
        "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
        "pf_dora", "pm_alex", "pm_santa",
        "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
        "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
    )

    val kokoro = LocalModelSpec(
        id = "kokoro-82m",
        label = "Kokoro 82M",
        family = ModelFamily.KOKORO,
        description = "8 languages, 54 voices. StyleTTS2 backbone exported to ONNX, " +
            "int8-quantised so it runs in real time on a phone. ~92 MB + 0.5 MB per voice.",
        sampleRate = 24000,
        files = listOf(
            RemoteFile("model.onnx", "$KOKORO_REPO/onnx/model_quantized.onnx", 92_361_116),
            RemoteFile("tokenizer.json", "$KOKORO_REPO/tokenizer.json", 3_497),
        ),
        voices = KOKORO_VOICE_IDS.map { kokoroVoice(it) },
        license = "Apache-2.0",
    )

    // ----------------------------------------------------------------- Piper

    private fun piper(
        id: String,
        label: String,
        path: String,
        sampleRate: Int,
        language: String,
        espeakVoice: String,
        bytes: Long = 63_201_294,
        speakers: Int = 1,
    ): LocalModelSpec {
        val voiceId = path.substringAfterLast('/').removeSuffix(".onnx")
        return LocalModelSpec(
            id = id,
            label = label,
            family = ModelFamily.PIPER,
            description = "Piper VITS voice ($language). Fast, small, espeak phonemes.",
            sampleRate = sampleRate,
            files = listOf(
                RemoteFile("$voiceId.onnx", "$PIPER_REPO/$path", bytes),
                // The config JSON size differs per voice; 0 means "size unknown".
                RemoteFile("$voiceId.onnx.json", "$PIPER_REPO/$path.json", 0),
            ),
            voices = (0 until speakers).map { speaker ->
                LocalVoiceSpec(
                    id = if (speakers == 1) "default" else "speaker$speaker",
                    label = if (speakers == 1) "$label · default" else "$label · speaker $speaker",
                    language = language,
                    espeakVoice = espeakVoice,
                    speakerId = speaker,
                )
            },
            license = "MIT (piper) / model-specific",
            espeakVoice = espeakVoice,
        )
    }

    val piperVoices = listOf(
        piper(
            "piper-en-us-lessac", "Piper · Lessac (US)", "en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            22050, "en-US", "en-us",
        ),
        piper(
            "piper-en-gb-alan", "Piper · Alan (GB)", "en/en_GB/alan/medium/en_GB-alan-medium.onnx",
            22050, "en-GB", "en-gb",
        ),
        piper(
            "piper-de-thorsten", "Piper · Thorsten (DE)", "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx",
            22050, "de-DE", "de", bytes = 63_221_984,
        ),
        piper(
            "piper-fr-siwis", "Piper · Siwis (FR)", "fr/fr_FR/siwis/medium/fr_FR-siwis-medium.onnx",
            22050, "fr-FR", "fr-fr", bytes = 63_221_984,
        ),
        piper(
            "piper-es-davefx", "Piper · DaveFX (ES)", "es/es_ES/davefx/medium/es_ES-davefx-medium.onnx",
            22050, "es-ES", "es", bytes = 63_221_984,
        ),
    )

    // ---------------------------------------------------------------- Matcha

    val matcha = LocalModelSpec(
        id = "matcha-ljspeech",
        label = "Matcha · LJSpeech (EN)",
        family = ModelFamily.MATCHA,
        description = "Matcha-TTS acoustic model (icefall) plus a HiFiGAN vocoder. " +
            "Fast and light, single female English speaker.",
        sampleRate = 22050,
        files = listOf(
            RemoteFile("model.onnx", "$MATCHA_REPO/model-steps-3.onnx", 74_157_257),
            RemoteFile("tokens.txt", "$MATCHA_REPO/tokens.txt", 954),
            RemoteFile(
                "vocoder.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/hifigan_v2.onnx",
                3_749_714,
            ),
        ),
        voices = listOf(
            LocalVoiceSpec("default", "LJSpeech · female", "en-US", "en-us"),
        ),
        license = "Apache-2.0 (icefall) / MIT (hi-fi-gan)",
    )

    val all: List<LocalModelSpec> = listOf(kokoro, matcha) + piperVoices

    fun find(id: String?): LocalModelSpec? = all.firstOrNull { it.id == id }

    fun voice(modelId: String?, voiceId: String?): Pair<LocalModelSpec, LocalVoiceSpec>? {
        val model = find(modelId) ?: return null
        val voice = model.voices.firstOrNull { it.id == voiceId } ?: model.voices.firstOrNull() ?: return null
        return model to voice
    }
}
