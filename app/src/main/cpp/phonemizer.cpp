//
// JNI bridge over espeak-ng: text -> IPA phonemes.
//
// espeak-ng's synthesis engine is single-threaded state (global variables), so
// every entry point takes the same mutex. The Android TTS framework calls us on
// its own synthesis thread; the UI may probe voices on another thread, hence the
// lock instead of "it is only ever called from one place".
//
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <espeak-ng/speak_lib.h>

namespace {

std::mutex g_mutex;
bool g_initialized = false;
std::string g_voice;

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentts_engine_onnx_Phonemizer_nativeInit(JNIEnv *env, jclass, jstring dataPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return JNI_TRUE;

    const char *path = env->GetStringUTFChars(dataPath, nullptr);
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path,
                                 espeakINITIALIZE_DONT_EXIT);
    env->ReleaseStringUTFChars(dataPath, path);

    if (rate < 0) {
        g_initialized = false;
        return JNI_FALSE;
    }
    // We never play audio ourselves.
    espeak_SetSynthCallback(nullptr);
    g_initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opentts_engine_onnx_Phonemizer_nativeSetVoice(JNIEnv *env, jclass, jstring voice) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return -1;

    const char *name = env->GetStringUTFChars(voice, nullptr);
    espeak_VOICE selector;
    std::memset(&selector, 0, sizeof(selector));
    selector.languages = name;
    espeak_ERROR err = espeak_SetVoiceByProperties(&selector);
    if (err == EE_OK) g_voice = name;
    env->ReleaseStringUTFChars(voice, name);

    if (err != EE_OK) return -1;
    return 0;
}

/**
 * Returns IPA phonemes for `text` using the voice previously selected.
 *
 * espeak decodes one clause per call and rewinds `cursor` itself; it keeps
 * returning an empty string (never NULL) once the text is exhausted, so an
 * empty chunk is the end-of-text signal. espeak already separates words with a
 * space, which is what the Kokoro/Piper tokenizers expect, but each clause ends
 * with a newline that must become a space or the last word of one clause fuses
 * with the first word of the next.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_opentts_engine_onnx_Phonemizer_nativeTextToPhonemes(JNIEnv *env, jclass, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return env->NewStringUTF("");

    const char *utf8 = env->GetStringUTFChars(text, nullptr);
    std::string out;
    const void *cursor = utf8;

    int clauses = 0;
    while (clauses++ < 512) {
        const char *chunk = espeak_TextToPhonemes(&cursor, espeakCHARS_UTF8, espeakPHONEMES_IPA);
        if (chunk == nullptr || chunk[0] == '\0') break;
        std::string part(chunk);
        for (char &c : part) {
            if (c == '\n' || c == '\r') c = ' ';
        }
        out += part;
        if (!out.empty() && out.back() != ' ') out.push_back(' ');
    }

    env->ReleaseStringUTFChars(text, utf8);
    return env->NewStringUTF(out.c_str());
}

/** espeak rate for a Kotlin-side speed multiplier, used for length scaling. */
extern "C" JNIEXPORT void JNICALL
Java_com_opentts_engine_onnx_Phonemizer_nativeSetRate(JNIEnv *, jclass, jint wordsPerMinute) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return;
    espeak_SetParameter(espeakRATE, wordsPerMinute, 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_opentts_engine_onnx_Phonemizer_nativeVersion(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *version = espeak_Info(nullptr);
    return env->NewStringUTF(version ? version : "unknown");
}
