package com.soma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.EntryTranscriptionState
import com.soma.core.model.NoteEntry
import com.soma.core.model.TodoSuggestion
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The day as one continuous, information-dense note. Pages hold as much
 * flowing text as fits and break at entry boundaries; swipes still hard-cut
 * whole screenfuls, matching Paka's motion. Only the home screen relaxes the
 * five-row rule — lists elsewhere keep it.
 */
@Composable
fun DayFlowPager(
    entries: List<NoteEntry>,
    suggestions: Map<String, TodoSuggestion>,
    recordingEntryId: String?,
    resetKey: Any?,
    onRead: (NoteEntry) -> Unit,
    onOptions: (NoteEntry) -> Unit,
    onSuggestion: (NoteEntry) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val baseStyle = LocalTextStyle.current
        val recordingText = stringResource(R.string.recording_now)
        val failedText = stringResource(R.string.voice_failed)
        val transcribingText = stringResource(R.string.voice_transcribing)

        val displayed = entries.map { entry ->
            when {
                entry.id == recordingEntryId -> recordingText
                entry.text.isNotBlank() -> entry.text
                entry.transcription?.state == EntryTranscriptionState.FAILED -> failedText
                else -> transcribingText
            }
        }
        val pageHeightPx = constraints.maxHeight
        val textWidthPx = with(density) {
            (constraints.maxWidth - (GUTTER_WIDTH + FLOW_END_PADDING).roundToPx()).coerceAtLeast(1)
        }
        val blocks = remember(entries, displayed, suggestions, pageHeightPx, textWidthPx, baseStyle) {
            val bodyStyle = baseStyle.copy(
                fontSize = FLOW_FONT_SIZE,
                lineHeight = FLOW_LINE_HEIGHT,
                fontWeight = FontWeight.Normal,
            )
            val spacingPx = with(density) { ENTRY_SPACING.roundToPx() }
            val chipPx = with(density) { CHIP_ROW_HEIGHT.roundToPx() }
            val textGutterPx = with(density) { TEXT_GUTTER_MIN_HEIGHT.roundToPx() }
            val voiceGutterPx = with(density) { VOICE_GUTTER_MIN_HEIGHT.roundToPx() }
            entries.mapIndexed { index, entry ->
                val hasChips = suggestions.containsKey(entry.id) || entry.returnLater
                val full = measurer.measure(
                    AnnotatedString(displayed[index]),
                    bodyStyle,
                    constraints = Constraints(maxWidth = textWidthPx),
                )
                val lineHeightPx = (full.size.height / full.lineCount.coerceAtLeast(1)).coerceAtLeast(1)
                val budget = pageHeightPx - (if (hasChips) chipPx else 0)
                val maxLines = (budget / lineHeightPx).coerceAtLeast(1)
                val lines = full.lineCount.coerceAtMost(maxLines)
                val gutterMinPx = if (entry.audio != null) voiceGutterPx else textGutterPx
                FlowBlock(
                    entry = entry,
                    text = displayed[index],
                    maxLines = lines,
                    heightPx = maxOf(lines * lineHeightPx, gutterMinPx) +
                        (if (hasChips) chipPx else 0),
                    spacingPx = spacingPx,
                )
            }
        }
        val pages = remember(blocks, pageHeightPx) { packBlocks(blocks, pageHeightPx) }
        if (pages.isEmpty()) return@BoxWithConstraints
        HardCutPager(
            pageCount = pages.size,
            resetKey = resetKey,
            startAtEnd = true,
            followEndOnGrowth = true,
            contentKey = entries,
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp, end = FLOW_END_PADDING, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(ENTRY_SPACING),
            ) {
                pages[page].forEach { block ->
                    EntryFlowBlock(
                        block = block,
                        suggestion = suggestions[block.entry.id],
                        onRead = { onRead(block.entry) },
                        onOptions = { onOptions(block.entry) },
                        onSuggestion = { onSuggestion(block.entry) },
                    )
                }
            }
        }
    }
}

internal data class FlowBlock(
    val entry: NoteEntry,
    val text: String,
    val maxLines: Int,
    val heightPx: Int,
    val spacingPx: Int,
)

/** Greedy page packing that never splits an entry; oversize entries were pre-clamped. */
internal fun packBlocks(blocks: List<FlowBlock>, pageHeightPx: Int): List<List<FlowBlock>> {
    if (blocks.isEmpty() || pageHeightPx <= 0) return emptyList()
    val pages = mutableListOf<MutableList<FlowBlock>>()
    var used = 0
    for (block in blocks) {
        val spacing = if (pages.lastOrNull().isNullOrEmpty()) 0 else block.spacingPx
        if (pages.isEmpty() || used + spacing + block.heightPx > pageHeightPx) {
            pages += mutableListOf(block)
            used = block.heightPx
        } else {
            pages.last() += block
            used += spacing + block.heightPx
        }
    }
    return pages
}

@Composable
private fun EntryFlowBlock(
    block: FlowBlock,
    suggestion: TodoSuggestion?,
    onRead: () -> Unit,
    onOptions: () -> Unit,
    onSuggestion: () -> Unit,
) {
    val entry = block.entry
    Row(
        modifier = Modifier.fillMaxWidth().then(inlineTapLongModifier(onRead, onOptions, "note entry")),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(GUTTER_WIDTH)) {
            Text(
                entry.createdAt.atZone(ZoneId.systemDefault()).format(FLOW_TIME_FORMATTER),
                color = DimInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
            )
            if (entry.audio != null) VoiceMark(Modifier.padding(top = 3.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                block.text,
                color = Ink,
                fontSize = FLOW_FONT_SIZE,
                lineHeight = FLOW_LINE_HEIGHT,
                fontWeight = FontWeight.Normal,
                maxLines = block.maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (suggestion != null || entry.returnLater) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (entry.returnLater) {
                        Text(
                            stringResource(R.string.return_later),
                            color = DimInk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                    if (suggestion != null) {
                        Text(
                            stringResource(R.string.todo_suggestion),
                            color = DimInk,
                            fontSize = 12.sp,
                            modifier = Modifier.then(
                                inlineTapModifier(onSuggestion, stringResource(R.string.todo_suggestion)),
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val GUTTER_WIDTH = 46.dp
private val FLOW_END_PADDING = 14.dp
private val ENTRY_SPACING = 10.dp
private val CHIP_ROW_HEIGHT = 22.dp
private val TEXT_GUTTER_MIN_HEIGHT = 16.dp
private val VOICE_GUTTER_MIN_HEIGHT = 34.dp
private val FLOW_FONT_SIZE = 18.sp
private val FLOW_LINE_HEIGHT = 23.sp
private val FLOW_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
