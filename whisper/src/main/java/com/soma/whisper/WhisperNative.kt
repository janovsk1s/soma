package com.soma.whisper

import android.content.res.AssetManager

internal object WhisperNative {
    init {
        System.loadLibrary("soma_whisper")
    }

    external fun createContext(assetManager: AssetManager, assetPath: String): Long

    /**
     * [allowedLanguages] constrains language identification to the app's supported
     * set; [preferredLanguage] wins ambiguous chunks unless another allowed
     * language clearly outscores it.
     */
    external fun transcribe(
        context: Long,
        samples: FloatArray,
        threads: Int,
        beamSize: Int,
        greedyBestOf: Int,
        allowedLanguages: Array<String>,
        preferredLanguage: String?,
        initialPrompt: String?,
    ): Array<String>

    external fun freeContext(context: Long)
}
