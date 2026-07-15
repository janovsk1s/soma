package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.LogRecord
import com.soma.core.model.Todo
import com.soma.core.search.SearchResult
import com.soma.core.search.SearchResultKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Live search state; results stay here so leaving and returning keeps them. */
data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val performed: Boolean = false,
)

internal const val MAX_SEARCHED_NOTES = 4_000
internal const val MAX_SEARCHED_ITEMS = 10_000

/**
 * Deliberately submit-based rather than per-keystroke: every search decrypts
 * and scans the whole bag, which is fine once per query but not per letter.
 */
@Composable
fun SearchScreen(
    viewModel: SomaViewModel,
    onOpenDay: (LocalDate) -> Unit,
    onOpenTodo: (Todo) -> Unit,
    onOpenLog: (LogRecord) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.searchState.collectAsState()
    var input by rememberSaveable { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.query.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.search_title), onBack)
        LineInput(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.search_hint),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
            onDone = { viewModel.search(input) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.searching -> EmptyHint(stringResource(R.string.search_searching))
                state.performed && state.results.isEmpty() ->
                    EmptyHint(stringResource(R.string.search_empty))
                state.results.isNotEmpty() -> PagedList(
                    items = state.results,
                    resetKey = state.query,
                ) { result ->
                    SearchResultRow(
                        result = result,
                        onOpen = {
                            when (result.kind) {
                                SearchResultKind.ENTRY -> onOpenDay(result.date)
                                SearchResultKind.IMPORTANT -> result.todo?.let(onOpenTodo)
                                SearchResultKind.LOG -> result.log?.let(onOpenLog)
                            }
                        },
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onOpen: () -> Unit) {
    val match = result.match
    val prefix = if (match.leadingTruncated) "…" else ""
    val snippet = buildAnnotatedString {
        append(prefix)
        append(match.snippet)
        if (match.trailingTruncated) append("…")
        addStyle(
            SpanStyle(fontWeight = FontWeight.Bold),
            prefix.length + match.highlightStart,
            prefix.length + match.highlightEndExclusive,
        )
    }
    val kindLabel = when (result.kind) {
        SearchResultKind.ENTRY -> null
        SearchResultKind.IMPORTANT -> stringResource(R.string.open_todos)
        SearchResultKind.LOG -> stringResource(R.string.logs)
    }
    val date = result.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    val meta = if (kindLabel == null) date else "$date · $kindLabel"
    Column(Modifier.fillMaxWidth().then(tapModifier(onOpen, "search result"))) {
        Text(
            snippet,
            color = Ink,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            meta,
            color = DimInk,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
