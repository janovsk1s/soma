package com.soma.whisper

import android.content.res.AssetManager

internal object WhisperNative {
    init {
        System.loadLibrary("soma_whisper")
    }

    external fun createContext(assetManager: AssetManager, assetPath: String): Long
    external fun transcribe(context: Long, samples: FloatArray, threads: Int): Array<String>
    external fun freeContext(context: Long)
}
