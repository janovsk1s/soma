package com.soma.app

/** UI-only lifecycle; the recorder remains the authority for encrypted audio capture. */
internal sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data object Starting : RecordingUiState

    data class Recording(
        val entryId: String,
        /** Monotonic clock value, unaffected by timezone or wall-clock changes. */
        val startedAtElapsedRealtimeMillis: Long,
    ) : RecordingUiState

    data object Saving : RecordingUiState
}

internal fun formatRecordingElapsed(totalSeconds: Long): String {
    val bounded = totalSeconds.coerceAtLeast(0)
    val minutes = bounded / SECONDS_PER_MINUTE
    val seconds = bounded % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val SECONDS_PER_MINUTE = 60L
