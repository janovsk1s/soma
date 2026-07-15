package com.soma.whisper

import com.soma.core.model.TranscriptionEngine

/**
 * Registry of the local Whisper models Soma will load. Every entry is pinned by
 * SHA-256 and exact size; a file that does not match its digest is never
 * accepted into storage and never loaded, no matter how it arrived (in-app
 * download or user-picked file). Tiny ships inside the APK and is the fallback
 * whenever a downloadable model is absent.
 */
enum class LocalWhisperModel(
    val fileName: String,
    val sha256: String,
    val byteCount: Long,
    /** Non-null only for the model bundled as an APK asset. */
    val bundledAssetPath: String?,
    val engine: TranscriptionEngine,
) {
    TINY(
        fileName = "ggml-tiny-q5_1.bin",
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        byteCount = 32_152_673L,
        bundledAssetPath = "ggml-tiny-q5_1.bin",
        engine = TranscriptionEngine.LOCAL_WHISPER_TINY,
    ),
    BASE(
        fileName = "ggml-base-q5_1.bin",
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        byteCount = 59_707_625L,
        bundledAssetPath = null,
        engine = TranscriptionEngine.LOCAL_WHISPER_BASE,
    ),
    ;

    val bundled: Boolean
        get() = bundledAssetPath != null
}
