package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Long-press on the day title lands here: a monochrome month of past days.
 * Days holding at least one entry carry a small dot; future days stay inert.
 */
@Composable
fun CalendarScreen(
    viewModel: SomaViewModel,
    onSelect: (LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val today = viewModel.today()
    var month by remember { mutableStateOf(YearMonth.from(viewModel.selectedDate.value)) }
    var marked by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    LaunchedEffect(month) {
        marked = viewModel.datesWithEntries(month.atDay(1), month.atEndOfMonth()).toSet()
    }
    val currentMonth = YearMonth.from(today)
    val locale = LocalConfiguration.current.locales.get(0) ?: Locale.ROOT

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.calendar_title), onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = DimInk,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.then(tapModifier({ month = month.minusMonths(1) }, "previous month")),
            )
            Text(
                month.month.getDisplayName(JavaTextStyle.FULL_STANDALONE, locale)
                    .replaceFirstChar { it.uppercase(locale) } + " " + month.year,
                color = Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                "›",
                color = if (month < currentMonth) DimInk else Paper,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.then(
                    tapModifier(
                        { if (month < currentMonth) month = month.plusMonths(1) },
                        "next month",
                    ),
                ),
            )
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            DayOfWeek.entries.forEach { day ->
                Text(
                    day.getDisplayName(JavaTextStyle.NARROW_STANDALONE, locale),
                    color = DimInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val leadingBlanks = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        cells.chunked(DAYS_PER_WEEK).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date -> DayCell(date, today, marked, onSelect, Modifier.weight(1f)) }
                repeat(DAYS_PER_WEEK - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    today: LocalDate,
    marked: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (date == null) {
        Box(modifier.height(52.dp))
        return
    }
    val selectable = !date.isAfter(today)
    Column(
        modifier = modifier.height(52.dp).then(
            if (selectable) tapModifier({ onSelect(date) }, "day ${date.dayOfMonth}") else Modifier,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${date.dayOfMonth}",
            color = if (selectable) Ink else DimInk.copy(alpha = 0.35f),
            fontSize = 18.sp,
            fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
        )
        if (date in marked) {
            Canvas(Modifier.padding(top = 3.dp).size(4.dp)) { drawCircle(DimInk) }
        } else {
            Box(Modifier.padding(top = 3.dp).size(4.dp))
        }
    }
}

private const val DAYS_PER_WEEK = 7
