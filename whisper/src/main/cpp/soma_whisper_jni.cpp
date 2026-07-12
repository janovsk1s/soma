#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <algorithm>
#include <mutex>
#include <string>

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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_soma_whisper_WhisperNative_transcribe(
    JNIEnv *env,
    jobject,
    jlong pointer,
    jfloatArray java_samples,
    jint requested_threads
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

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::clamp(static_cast<int>(requested_threads), 1, 4);
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = "auto";
    params.detect_language = false;

    const int result = whisper_full(context, params, samples, count);
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
