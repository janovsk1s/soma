package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.ImportantKind
import com.soma.core.model.Todo
import com.soma.core.model.TodoState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TodosScreen(
    viewModel: SomaViewModel,
    onTodoOptions: (Todo) -> Unit,
    onDetailedAdd: () -> Unit,
    onSource: (Todo) -> Unit,
    onBack: () -> Unit,
) {
    val open by viewModel.openTodos.collectAsState()
    val closed by viewModel.closedTodos.collectAsState()
    val prompted by viewModel.promptedTodoIds.collectAsState()
    var showClosed by remember { mutableStateOf(false) }
    var quickAdd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    // System back first releases an open quick-add line so a focused field
    // never traps the user on a device without navigation gestures.
    BackHandler {
        if (quickAdd) {
            quickAdd = false
            input = ""
        } else {
            onBack()
        }
    }
    val items = if (showClosed) closed else open

    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        TodoHeader(
            showClosed = showClosed,
            onToggle = { showClosed = !showClosed },
            onBack = onBack,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyHint(stringResource(if (showClosed) R.string.done_todos else R.string.todos_empty))
            } else {
                PagedList(
                    items = items,
                    resetKey = showClosed,
                    onPageChange = { page -> if (!showClosed) page.forEach(viewModel::todoViewed) },
                ) { todo ->
                    TodoRow(
                        todo = todo,
                        stalePrompt = todo.id in prompted,
                        onToggle = { viewModel.toggleTodo(todo) },
                        onOptions = { onTodoOptions(todo) },
                        onKeep = { viewModel.keepTodo(todo) },
                        onLetGo = { viewModel.letGo(todo) },
                        onSource = { onSource(todo) },
                    )
                }
            }
        }
        if (!showClosed) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(quickAdd) { if (quickAdd) focus.requestFocus() }
            fun submitQuickTodo() {
                if (input.isNotBlank()) viewModel.addTodo(input)
                input = ""
                quickAdd = false
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (quickAdd) {
                    LineInput(
                        input,
                        { input = it },
                        stringResource(R.string.todo_quick_hint),
                        Modifier.weight(1f).focusRequester(focus),
                        onDone = ::submitQuickTodo,
                    )
                } else {
                    CaptureBar(
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.todo_quick_hint),
                        onOpen = { quickAdd = true },
                        onLongPress = { quickAdd = true },
                    )
                }
                PlusButton(
                    onClick = { if (quickAdd) submitQuickTodo() else quickAdd = true },
                    onLongClick = onDetailedAdd,
                    modifier = Modifier.offset(x = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TodoHeader(showClosed: Boolean, onToggle: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BackArrow(Modifier.align(Alignment.CenterStart).offset(x = (-30).dp), onBack)
        Text(
            stringResource(if (showClosed) R.string.done_todos else R.string.open_todos),
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
        )
        Box(
            modifier = Modifier.align(Alignment.CenterEnd).width(72.dp).then(tapModifier(onToggle, "important page")),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                stringResource(if (showClosed) R.string.open_todos else R.string.done_todos),
                color = DimInk,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TodoRow(
    todo: Todo,
    stalePrompt: Boolean,
    onToggle: () -> Unit,
    onOptions: () -> Unit,
    onKeep: () -> Unit,
    onLetGo: () -> Unit,
    onSource: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().then(
            tapLongModifier(
                if (todo.kind in SOURCE_KINDS && todo.source != null) onSource
                else if (todo.kind in ACTIONABLE_KINDS) onToggle
                else onOptions,
                onOptions,
                "important item",
            ),
        ),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                todo.text,
                color = if (todo.state == TodoState.OPEN) Ink else DimInk,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    todo.state != TodoState.OPEN -> "✓"
                    todo.kind == ImportantKind.ACTION -> "○"
                    todo.kind == ImportantKind.LIST -> "≡"
                    todo.kind == ImportantKind.REFERENCE -> "#"
                    else -> "↗"
                },
                color = DimInk,
                fontSize = 22.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (todo.kind != ImportantKind.ACTION) {
                Text(
                    stringResource(
                        when (todo.kind) {
                            ImportantKind.LIST -> R.string.important_list
                            ImportantKind.REFERENCE -> R.string.important_reference
                            else -> R.string.important_excerpt
                        },
                    ),
                    color = DimInk,
                    fontSize = 12.sp,
                )
            }
            todo.source?.let { source ->
                Text(
                    source.noteDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)),
                    color = DimInk,
                    fontSize = 12.sp,
                    modifier = Modifier.then(tapModifier(onSource, "source note")),
                )
            }
            todo.resurfaceOn?.let { date ->
                Text(
                    stringResource(
                        R.string.show_again_date,
                        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)),
                    ),
                    color = DimInk,
                    fontSize = 12.sp,
                )
            }
            if (stalePrompt) {
                Text(stringResource(R.string.keep), color = DimInk, fontSize = 14.sp, modifier = Modifier.then(tapModifier(onKeep, "keep")))
                Text(stringResource(R.string.let_go), color = DimInk, fontSize = 14.sp, modifier = Modifier.then(tapModifier(onLetGo, "let go")))
            }
        }
    }
}

private enum class TodoAction { EDIT, RESURFACE, TOGGLE, LET_GO }

@Composable
fun TodoOptionsScreen(
    todo: Todo,
    onEdit: () -> Unit,
    onResurface: () -> Unit,
    onToggle: () -> Unit,
    onLetGo: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val actions = buildList {
        add(TodoAction.EDIT)
        if (todo.state == TodoState.OPEN) add(TodoAction.RESURFACE)
        if (todo.kind in ACTIONABLE_KINDS) add(TodoAction.TOGGLE)
        add(TodoAction.LET_GO)
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.todos), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(actions) { action ->
                SettingsItem(
                    label = when (action) {
                        TodoAction.EDIT -> stringResource(R.string.edit)
                        TodoAction.RESURFACE -> stringResource(R.string.show_again)
                        TodoAction.TOGGLE -> stringResource(if (todo.state == TodoState.OPEN) R.string.mark_done else R.string.mark_open)
                        TodoAction.LET_GO -> stringResource(R.string.let_go)
                    },
                    onClick = when (action) {
                        TodoAction.EDIT -> onEdit
                        TodoAction.RESURFACE -> onResurface
                        TodoAction.TOGGLE -> onToggle
                        TodoAction.LET_GO -> onLetGo
                    },
                )
            }
        }
    }
}

private enum class ResurfaceChoice { TOMORROW, WEEK, MONTH, CLEAR }

/**
 * Deliberately small preset set: useful postponement without turning Important
 * into another calendar or due-date manager.
 */
@Composable
fun TodoResurfaceScreen(
    todo: Todo,
    today: LocalDate,
    onSelect: (LocalDate?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val choices = buildList {
        add(ResurfaceChoice.TOMORROW)
        add(ResurfaceChoice.WEEK)
        add(ResurfaceChoice.MONTH)
        if (todo.resurfaceOn != null) add(ResurfaceChoice.CLEAR)
    }
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.show_again), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(choices) { choice ->
                val date = when (choice) {
                    ResurfaceChoice.TOMORROW -> today.plusDays(1)
                    ResurfaceChoice.WEEK -> today.plusWeeks(1)
                    ResurfaceChoice.MONTH -> today.plusMonths(1)
                    ResurfaceChoice.CLEAR -> null
                }
                SettingsItem(
                    label = when (choice) {
                        ResurfaceChoice.TOMORROW -> stringResource(R.string.tomorrow_date, date!!.format(format))
                        ResurfaceChoice.WEEK -> stringResource(R.string.in_one_week_date, date!!.format(format))
                        ResurfaceChoice.MONTH -> stringResource(R.string.in_one_month_date, date!!.format(format))
                        ResurfaceChoice.CLEAR -> stringResource(R.string.clear_show_again)
                    },
                    onClick = { onSelect(date) },
                )
            }
        }
    }
}

private val ACTIONABLE_KINDS = setOf(ImportantKind.ACTION, ImportantKind.LIST)
private val SOURCE_KINDS = setOf(ImportantKind.EXCERPT, ImportantKind.REFERENCE)
