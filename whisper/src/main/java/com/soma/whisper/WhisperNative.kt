package com.soma.whisper

import android.content.res.AssetManager

internal object WhisperNative {
    init {
        System.loadLibrary("soma_whisper")
    }

    external fun createContext(assetManager: AssetManager, assetPath: String): Long

    /** [allowedLanguages] constrains language identification to the app's supported set. */
    external fun transcribe(
        context: Long,
        samples: FloatArray,
        threads: Int,
        allowedLanguages: Array<String>,
    ): Array<String>

    external fun freeContext(context: Long)
}
