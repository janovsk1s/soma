package com.soma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.soma.core.model.EntryKind
import com.soma.core.model.LogKind
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
    trackingSuggestions: Map<String, LogKind>,
    recordingEntryId: String?,
    resetKey: Any?,
    onRead: (NoteEntry) -> Unit,
    onOptions: (NoteEntry) -> Unit,
    onSuggestion: (NoteEntry) -> Unit,
    onTrackingSuggestion: (NoteEntry, LogKind) -> Unit,
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
                entry.transcription?.state in setOf(
                    EntryTranscriptionState.QUEUED,
                    EntryTranscriptionState.RUNNING,
                ) -> transcribingText
                entry.kind == EntryKind.IMAGE -> ""
                else -> transcribingText
            }
        }
        val pageContentHeightPx = availablePageContentHeight(
            totalHeightPx = constraints.maxHeight,
            verticalPaddingPx = with(density) { FLOW_PAGE_VERTICAL_PADDING.roundToPx() * 2 },
        )
        val textWidthPx = with(density) {
            (constraints.maxWidth - FLOW_END_PADDING.roundToPx()).coerceAtLeast(1)
        }
        val blocks = remember(
            entries,
            displayed,
            suggestions,
            trackingSuggestions,
            pageContentHeightPx,
            textWidthPx,
            baseStyle,
        ) {
            val bodyStyle = baseStyle.copy(
                fontSize = FLOW_FONT_SIZE,
                lineHeight = FLOW_LINE_HEIGHT,
                fontWeight = FontWeight.Normal,
            )
            val spacingPx = with(density) { ENTRY_SPACING.roundToPx() }
            val passiveChipPx = with(density) { CHIP_ROW_HEIGHT.roundToPx() }
            val touchTargetPx = with(density) { MIN_TOUCH_TARGET.roundToPx() }
            val timeLinePx = with(density) { TIME_LINE_HEIGHT.roundToPx() }
            val imagePreviewPx = with(density) { IMAGE_PREVIEW_HEIGHT.roundToPx() }
            val imageCaptionSpacingPx = with(density) { IMAGE_CAPTION_SPACING.roundToPx() }
            // A single entry never shows more than one page of lines, so cap both
            // the measured line count and the measured text length. Otherwise an
            // unusually long entry makes this composition-thread layout scale with
            // its character count and can hitch a frame on a heavy day; the exact
            // per-entry clamp is still derived from the measured result below.
            val nominalLineHeightPx = with(density) { FLOW_LINE_HEIGHT.roundToPx() }
            val lineCap = measuredLineCap(pageContentHeightPx, nominalLineHeightPx)
            val charCap = lineCap * MAX_MEASURED_CHARS_PER_LINE
            entries.mapIndexed { index, entry ->
                val chipPx = when {
                    suggestions.containsKey(entry.id) || trackingSuggestions.containsKey(entry.id) -> touchTargetPx
                    entry.returnLater -> passiveChipPx
                    else -> 0
                }
                val hasImage = entry.activeImage != null
                val measuredText = displayed[index].let { if (it.length > charCap) it.take(charCap) else it }
                val full = measurer.measure(
                    AnnotatedString(measuredText.ifEmpty { " " }),
                    bodyStyle,
                    maxLines = lineCap,
                    constraints = Constraints(maxWidth = textWidthPx),
                )
                val lineHeightPx = (full.size.height / full.lineCount.coerceAtLeast(1)).coerceAtLeast(1)
                val imagePx = if (hasImage) {
                    imagePreviewPx.coerceAtMost((pageContentHeightPx - timeLinePx - chipPx).coerceAtLeast(0))
                } else {
                    0
                }
                val captionSpacingPx = if (hasImage && measuredText.isNotEmpty()) imageCaptionSpacingPx else 0
                val budget = pageContentHeightPx - timeLinePx - chipPx - imagePx - captionSpacingPx
                val maxLines = (budget / lineHeightPx).coerceAtLeast(1)
                val lines = if (measuredText.isEmpty()) 0 else full.lineCount.coerceAtMost(maxLines)
                FlowBlock(
                    entry = entry,
                    text = displayed[index],
                    maxLines = lines.coerceAtLeast(1),
                    imageHeightPx = imagePx,
                    heightPx = maxOf(
                        touchTargetPx,
                        timeLinePx + imagePx + captionSpacingPx + lines * lineHeightPx + chipPx,
                    ),
                    spacingPx = spacingPx,
                )
            }
        }
        val pages = remember(blocks, pageContentHeightPx) { packBlocks(blocks, pageContentHeightPx) }
        if (pages.isEmpty()) return@BoxWithConstraints
        HardCutPager(
            pageCount = pages.size,
            resetKey = resetKey,
            startAtEnd = true,
            followEndOnGrowth = true,
            contentKey = entries,
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize().padding(
                    top = FLOW_PAGE_VERTICAL_PADDING,
                    end = FLOW_END_PADDING,
                    bottom = FLOW_PAGE_VERTICAL_PADDING,
                ),
                verticalArrangement = Arrangement.spacedBy(ENTRY_SPACING),
            ) {
                pages[page].forEach { block ->
                    EntryFlowBlock(
                        block = block,
                        suggestion = suggestions[block.entry.id],
                        trackingSuggestion = trackingSuggestions[block.entry.id],
                        onRead = { onRead(block.entry) },
                        onOptions = { onOptions(block.entry) },
                        onSuggestion = { onSuggestion(block.entry) },
                        onTrackingSuggestion = { kind -> onTrackingSuggestion(block.entry, kind) },
                    )
                }
            }
        }
    }
}

internal fun availablePageContentHeight(totalHeightPx: Int, verticalPaddingPx: Int): Int =
    (totalHeightPx - verticalPaddingPx.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Upper bound on the lines a single entry can occupy on one page, used to cap
 * text measurement so layout cost does not scale with an entry's length. A small
 * margin keeps it at or above the exact per-entry line clamp computed afterward.
 */
internal fun measuredLineCap(pageContentHeightPx: Int, lineHeightPx: Int): Int {
    if (pageContentHeightPx <= 0 || lineHeightPx <= 0) return 1
    return (pageContentHeightPx / lineHeightPx + MEASURE_LINE_MARGIN).coerceAtLeast(1)
}

internal data class FlowBlock(
    val entry: NoteEntry,
    val text: String,
    val maxLines: Int,
    val imageHeightPx: Int = 0,
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
    trackingSuggestion: LogKind?,
    onRead: () -> Unit,
    onOptions: () -> Unit,
    onSuggestion: () -> Unit,
    onTrackingSuggestion: (LogKind) -> Unit,
) {
    val entry = block.entry
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .then(tapLongModifier(onRead, onOptions, "note entry")),
    ) {
        Row(
            modifier = Modifier.height(TIME_LINE_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.createdAt.atZone(ZoneId.systemDefault()).format(FLOW_TIME_FORMATTER),
                color = DimInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
            )
            if (entry.activeAudio != null) VoiceMark()
        }
        if (block.imageHeightPx > 0) {
            EncryptedEntryImage(
                entry = entry,
                modifier = Modifier.fillMaxWidth().height(with(density) { block.imageHeightPx.toDp() }),
            )
        }
        if (block.text.isNotEmpty()) {
            Text(
                block.text,
                color = Ink,
                fontSize = FLOW_FONT_SIZE,
                lineHeight = FLOW_LINE_HEIGHT,
                fontWeight = FontWeight.Normal,
                maxLines = block.maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (block.imageHeightPx > 0) IMAGE_CAPTION_SPACING else 0.dp),
            )
        }
        if (suggestion != null || trackingSuggestion != null || entry.returnLater) {
            Row(
                modifier = Modifier.height(
                    if (suggestion != null || trackingSuggestion != null) MIN_TOUCH_TARGET else CHIP_ROW_HEIGHT,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                            tapModifier(onSuggestion, stringResource(R.string.todo_suggestion)),
                        ),
                    )
                }
                if (trackingSuggestion != null) {
                    val label = stringResource(
                        when (trackingSuggestion) {
                            LogKind.MEAL -> R.string.meal_suggestion
                            LogKind.RECIPE -> R.string.recipe_suggestion
                            LogKind.WORKOUT -> R.string.workout_suggestion
                            LogKind.RECEIPT -> R.string.receipt_suggestion
                        },
                    )
                    Text(
                        label,
                        color = DimInk,
                        fontSize = 12.sp,
                        modifier = Modifier.then(
                            tapModifier(
                                onClick = { onTrackingSuggestion(trackingSuggestion) },
                                label = label,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

private const val MEASURE_LINE_MARGIN = 2
// Generous per-line character ceiling; the narrow phone column never approaches
// it, so truncating the measured copy caps allocation without changing line counts.
private const val MAX_MEASURED_CHARS_PER_LINE = 240
private val FLOW_END_PADDING = 14.dp
private val FLOW_PAGE_VERTICAL_PADDING = 8.dp
private val ENTRY_SPACING = 10.dp
private val CHIP_ROW_HEIGHT = 22.dp
private val MIN_TOUCH_TARGET = 48.dp
// Taller than the timestamp glyphs so the centered time isn't glued to the
// entry body directly beneath it (timeLinePx in the page budget tracks this).
private val TIME_LINE_HEIGHT = 22.dp
private val IMAGE_PREVIEW_HEIGHT = 190.dp
private val IMAGE_CAPTION_SPACING = 8.dp
private val FLOW_FONT_SIZE = 18.sp
private val FLOW_LINE_HEIGHT = 23.sp
private val FLOW_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
