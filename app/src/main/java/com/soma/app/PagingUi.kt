package com.soma.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val ITEMS_PER_PAGE = 5

@Composable
fun SimpleTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: String? = null,
    capitalizeTitle: Boolean = true,
) {
    val displayed = if (capitalizeTitle) title.replaceFirstChar(Char::uppercase) else title
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            BackArrow(Modifier.align(Alignment.CenterStart).offset(x = (-30).dp), onBack)
        }
        Text(
            displayed,
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp),
        )
        if (trailing != null) {
            Text(
                trailing,
                color = DimInk,
                fontSize = 14.sp,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.align(Alignment.CenterEnd).width(64.dp),
            )
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = DimInk, fontSize = 18.sp, fontWeight = FontWeight.Normal)
    }
}

/** Exactly five equal-height row slots; a vertical release hard-cuts one full page. */
@Composable
fun <T> PagedList(
    items: List<T>,
    modifier: Modifier = Modifier,
    endPadding: Dp = 14.dp,
    resetKey: Any? = null,
    startAtEnd: Boolean = false,
    followEndOnGrowth: Boolean = false,
    onPageChange: ((List<T>) -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val pages = remember(items) { items.chunked(ITEMS_PER_PAGE) }
    if (pages.isEmpty()) return
    HardCutPager(
        pageCount = pages.size,
        modifier = modifier,
        resetKey = resetKey,
        startAtEnd = startAtEnd,
        followEndOnGrowth = followEndOnGrowth,
        contentKey = items,
        onPageChange = onPageChange?.let { report -> { page -> report(pages[page]) } },
    ) { page ->
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp, end = endPadding, bottom = 8.dp),
        ) {
            pages[page].forEach { item ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) { content(item) }
            }
            repeat(ITEMS_PER_PAGE - pages[page].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun HardCutPager(
    pageCount: Int,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    showIndicator: Boolean = true,
    startAtEnd: Boolean = false,
    followEndOnGrowth: Boolean = false,
    contentKey: Any? = null,
    onPageChange: ((Int) -> Unit)? = null,
    content: @Composable (Int) -> Unit,
) {
    if (pageCount <= 0) return
    val context = LocalContext.current
    val feedback = LocalHapticFeedback.current
    var page by remember(resetKey) { mutableIntStateOf(if (startAtEnd) pageCount - 1 else 0) }
    var previousPageCount by remember(resetKey) { mutableIntStateOf(pageCount) }
    val current = page.coerceIn(0, pageCount - 1)
    val position = stringResource(R.string.accessibility_page_position, current + 1, pageCount)
    val previous = stringResource(R.string.accessibility_previous_page)
    val next = stringResource(R.string.accessibility_next_page)

    LaunchedEffect(pageCount) {
        val wasAtEnd = page >= previousPageCount - 1
        page = when {
            page >= pageCount -> pageCount - 1
            followEndOnGrowth && pageCount > previousPageCount && wasAtEnd -> pageCount - 1
            else -> page
        }
        previousPageCount = pageCount
    }
    LaunchedEffect(current, contentKey) { onPageChange?.invoke(current) }

    val semantics = Modifier.semantics {
        stateDescription = position
        customActions = buildList {
            if (current > 0) add(CustomAccessibilityAction(previous) {
                page = current - 1
                performSomaHaptic(context, feedback)
                true
            })
            if (current < pageCount - 1) add(CustomAccessibilityAction(next) {
                page = current + 1
                performSomaHaptic(context, feedback)
                true
            })
        }
    }
    Box(
        modifier = modifier.fillMaxSize().then(semantics).pointerInput(pageCount, current, resetKey) {
            val threshold = 24.dp.toPx()
            var distance = 0f
            var feedbackSent = false
            detectVerticalDragGestures(
                onDragStart = {
                    distance = 0f
                    feedbackSent = false
                },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    distance += amount
                    val canMove =
                        (distance <= -threshold && current < pageCount - 1) ||
                            (distance >= threshold && current > 0)
                    if (canMove && !feedbackSent) {
                        performSomaHaptic(context, feedback)
                        feedbackSent = true
                    }
                },
                onDragEnd = {
                    page = when {
                        distance <= -threshold -> (current + 1).coerceAtMost(pageCount - 1)
                        distance >= threshold -> (current - 1).coerceAtLeast(0)
                        else -> current
                    }
                },
                onDragCancel = { distance = 0f },
            )
        },
    ) {
        content(current)
        if (showIndicator && pageCount > 1) {
            PageIndicator(current, pageCount, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun PageIndicator(page: Int, pageCount: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.offset(x = 18.dp).fillMaxHeight().width(6.dp)) {
        val top = 20.dp.toPx()
        val bottom = 6.dp.toPx()
        val track = (size.height - top - bottom).coerceAtLeast(1f)
        val thumb = (track / pageCount).coerceAtLeast(24.dp.toPx()).coerceAtMost(track)
        val travel = track - thumb
        val thumbY = top + if (pageCount <= 1) 0f else travel * page / (pageCount - 1)
        val x = size.width / 2f
        drawRect(Ink.copy(alpha = 0.3f), Offset(x - 0.5.dp.toPx(), top), Size(1.dp.toPx(), track))
        drawRect(Ink, Offset(x - 2.dp.toPx(), thumbY), Size(4.dp.toPx(), thumb))
    }
}

@Composable
fun Modifier.daySwipe(
    canGoNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
): Modifier {
    val context = LocalContext.current
    val feedback = LocalHapticFeedback.current
    return pointerInput(canGoNewer) {
        val threshold = 36.dp.toPx()
        var distance = 0f
        var sent = false
        detectHorizontalDragGestures(
            onDragStart = {
                distance = 0f
                sent = false
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                distance += amount
                val possible = distance <= -threshold || (distance >= threshold && canGoNewer)
                if (possible && !sent) {
                    performSomaHaptic(context, feedback)
                    sent = true
                }
            },
            onDragEnd = {
                when {
                    distance <= -threshold -> onOlder()
                    distance >= threshold && canGoNewer -> onNewer()
                }
            },
            onDragCancel = { distance = 0f },
        )
    }
}
