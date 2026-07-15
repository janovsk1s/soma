#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

std::once_flag logging_once;

void silent_log(enum ggml_log_level, const char *, void *) {
    // Soma never writes transcription content or engine diagnostics to Logcat.
}

void configure_logging() {
    std::call_once(logging_once, [] { whisper_log_set(silent_log, nullptr); });
}

void throw_java(JNIEnv *env, const char *message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

jstring utf8_string(JNIEnv *env, const std::string &value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte *>(value.data())
    );
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(bytes);
        return nullptr;
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        env->DeleteLocalRef(bytes);
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    if (constructor == nullptr || charset == nullptr) {
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(string_class);
        return nullptr;
    }
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

size_t asset_read(void *context, void *output, size_t requested) {
    const int result = AAsset_read(static_cast<AAsset *>(context), output, requested);
    return result < 0 ? 0 : static_cast<size_t>(result);
}

bool asset_eof(void *context) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(context)) <= 0;
}

void asset_close(void *context) {
    AAsset_close(static_cast<AAsset *>(context));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_soma_whisper_WhisperNative_createContext(
    JNIEnv *env,
    jobject,
    jobject java_asset_manager,
    jstring java_asset_path
) {
    configure_logging();
    auto *manager = AAssetManager_fromJava(env, java_asset_manager);
    if (manager == nullptr) {
        throw_java(env, "Could not access model assets");
        return 0;
    }
    const char *asset_path = env->GetStringUTFChars(java_asset_path, nullptr);
    AAsset *asset = AAssetManager_open(manager, asset_path, AASSET_MODE_STREAMING);
    env->ReleaseStringUTFChars(java_asset_path, asset_path);
    if (asset == nullptr) {
        throw_java(env, "Bundled Whisper model is missing");
        return 0;
    }

    whisper_model_loader loader{};
    loader.context = asset;
    loader.read = asset_read;
    loader.eof = asset_eof;
    loader.close = asset_close;

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context *context = whisper_init_with_params(&loader, params);
    if (context == nullptr) {
        throw_java(env, "Could not load the bundled Whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_soma_whisper_WhisperNative_createContextFromFile(
    JNIEnv *env,
    jobject,
    jstring java_model_path
) {
    configure_logging();
    const char *model_path = env->GetStringUTFChars(java_model_path, nullptr);
    if (model_path == nullptr) {
        throw_java(env, "Could not read the model path");
        return 0;
    }
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context *context = whisper_init_from_file_with_params(model_path, params);
    env->ReleaseStringUTFChars(java_model_path, model_path);
    if (context == nullptr) {
        throw_java(env, "Could not load the Whisper model file");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

namespace {

// Restricts tiny-model language identification to the app's supported set.
// Unconstrained auto-detection over ~99 languages misfires on short
// utterances (German audio scoring as Russian); the argmax over the allowed
// subset of the detector's probabilities cannot leave that set.
// The app language wins ambiguous chunks: another allowed language must beat
// its probability by this factor before a chunk switches away from it.
// Clear code-switched speech exceeds the margin easily; spoken number lists
// and other short, language-poor audio no longer drift to a sibling language.
constexpr float kPreferredLanguageMargin = 1.75f;

const char *detect_allowed_language(
    JNIEnv *env,
    whisper_context *context,
    const jfloat *samples,
    jsize count,
    int threads,
    jobjectArray java_allowed,
    jstring java_preferred
) {
    if (java_allowed == nullptr) return nullptr;
    const jsize allowed_count = env->GetArrayLength(java_allowed);
    if (allowed_count <= 0) return nullptr;

    if (whisper_pcm_to_mel(context, samples, count, threads) != 0) return nullptr;
    std::vector<float> probabilities(whisper_lang_max_id() + 1, 0.0f);
    if (whisper_lang_auto_detect(context, 0, threads, probabilities.data()) < 0) return nullptr;

    int preferred_id = -1;
    if (java_preferred != nullptr) {
        const char *preferred_chars = env->GetStringUTFChars(java_preferred, nullptr);
        if (preferred_chars != nullptr) {
            preferred_id = whisper_lang_id(preferred_chars);
            env->ReleaseStringUTFChars(java_preferred, preferred_chars);
        }
    }

    int best_id = -1;
    float best_probability = -1.0f;
    bool preferred_allowed = false;
    for (jsize index = 0; index < allowed_count; ++index) {
        auto code = static_cast<jstring>(env->GetObjectArrayElement(java_allowed, index));
        if (code == nullptr) continue;
        const char *code_chars = env->GetStringUTFChars(code, nullptr);
        const int lang_id = code_chars == nullptr ? -1 : whisper_lang_id(code_chars);
        if (code_chars != nullptr) env->ReleaseStringUTFChars(code, code_chars);
        env->DeleteLocalRef(code);
        if (lang_id < 0 || lang_id >= static_cast<int>(probabilities.size())) continue;
        if (lang_id == preferred_id) preferred_allowed = true;
        if (probabilities[lang_id] > best_probability) {
            best_id = lang_id;
            best_probability = probabilities[lang_id];
        }
    }
    if (preferred_allowed && best_id != preferred_id &&
        best_probability < probabilities[preferred_id] * kPreferredLanguageMargin) {
        best_id = preferred_id;
    }
    return best_id >= 0 ? whisper_lang_str(best_id) : nullptr;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_soma_whisper_WhisperNative_transcribe(
    JNIEnv *env,
    jobject,
    jlong pointer,
    jfloatArray java_samples,
    jint requested_threads,
    jint requested_beam_size,
    jint requested_best_of,
    jobjectArray java_allowed_languages,
    jstring java_preferred_language,
    jstring java_initial_prompt
) {
    auto *context = reinterpret_cast<whisper_context *>(pointer);
    if (context == nullptr) {
        throw_java(env, "Whisper context is closed");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(java_samples);
    if (count <= 0) {
        throw_java(env, "Cannot transcribe empty audio");
        return nullptr;
    }
    jfloat *samples = env->GetFloatArrayElements(java_samples, nullptr);
    if (samples == nullptr) return nullptr;

    const int beam_size = std::clamp(static_cast<int>(requested_beam_size), 0, 5);
    const whisper_sampling_strategy strategy = beam_size > 1
        ? WHISPER_SAMPLING_BEAM_SEARCH
        : WHISPER_SAMPLING_GREEDY;
    whisper_full_params params = whisper_full_default_params(strategy);
    params.n_threads = std::clamp(static_cast<int>(requested_threads), 1, 6);
    if (strategy == WHISPER_SAMPLING_BEAM_SEARCH) {
        params.beam_search.beam_size = beam_size;
    } else {
        params.greedy.best_of = std::clamp(static_cast<int>(requested_best_of), 1, 5);
    }
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.suppress_nst = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = "auto";
    params.detect_language = false;
    params.carry_initial_prompt = false;

    const char *constrained = detect_allowed_language(
        env, context, samples, count, params.n_threads,
        java_allowed_languages, java_preferred_language);
    if (constrained != nullptr) params.language = constrained;

    const char *initial_prompt = nullptr;
    if (java_initial_prompt != nullptr) {
        initial_prompt = env->GetStringUTFChars(java_initial_prompt, nullptr);
        if (initial_prompt == nullptr) {
            env->ReleaseFloatArrayElements(java_samples, samples, JNI_ABORT);
            return nullptr;
        }
        params.initial_prompt = initial_prompt;
    }

    const int result = whisper_full(context, params, samples, count);
    if (initial_prompt != nullptr) {
        env->ReleaseStringUTFChars(java_initial_prompt, initial_prompt);
    }
    env->ReleaseFloatArrayElements(java_samples, samples, JNI_ABORT);
    if (result != 0) {
        throw_java(env, "Whisper transcription failed");
        return nullptr;
    }

    std::string transcript;
    const int segment_count = whisper_full_n_segments(context);
    for (int index = 0; index < segment_count; ++index) {
        const char *segment = whisper_full_get_segment_text(context, index);
        if (segment != nullptr) transcript.append(segment);
    }
    const int language_id = whisper_full_lang_id(context);
    const char *language = whisper_lang_str(language_id);

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray response = env->NewObjectArray(2, string_class, nullptr);
    if (response == nullptr) return nullptr;
    env->SetObjectArrayElement(response, 0, env->NewStringUTF(language == nullptr ? "und" : language));
    jstring java_transcript = utf8_string(env, transcript);
    if (java_transcript == nullptr) return nullptr;
    env->SetObjectArrayElement(response, 1, java_transcript);
    env->DeleteLocalRef(java_transcript);
    env->DeleteLocalRef(string_class);
    return response;
}

extern "C" JNIEXPORT void JNICALL
Java_com_soma_whisper_WhisperNative_freeContext(JNIEnv *, jobject, jlong pointer) {
    auto *context = reinterpret_cast<whisper_context *>(pointer);
    if (context != nullptr) whisper_free(context);
}
