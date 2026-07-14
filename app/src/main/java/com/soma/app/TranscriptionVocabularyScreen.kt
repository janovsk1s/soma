package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.TranscriptionVocabulary
import java.util.Locale

/**
 * A visible, user-owned vocabulary list. Terms are persisted immediately, five
 * per page like Paka lists; tap edits and the deliberate long press exposes the
 * destructive remove action.
 */
@Composable
fun TranscriptionVocabularyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TranscriptionVocabularyStore(context) }
    var terms by remember { mutableStateOf(store.read()) }
    var input by remember { mutableStateOf("") }
    var inputOpen by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var removalIndex by remember { mutableStateOf<Int?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun closeInput() {
        input = ""
        inputOpen = false
        editingIndex = null
        saveFailed = false
        keyboard?.hide()
    }

    fun navigateBack() {
        when {
            removalIndex != null -> removalIndex = null
            inputOpen -> closeInput()
            else -> onBack()
        }
    }
    BackHandler(onBack = ::navigateBack)

    fun persist(updated: List<String>): Boolean {
        saveFailed = false
        return runCatching { store.writeTerms(updated) }
            .onSuccess { terms = updated }
            .onFailure { saveFailed = true }
            .isSuccess
    }

    removalIndex?.let { index ->
        val term = terms.getOrNull(index)
        if (term == null) {
            removalIndex = null
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Paper)
                    .systemBarsPadding()
                    .padding(horizontal = 28.dp),
            ) {
                SimpleTopBar(stringResource(R.string.settings_transcription_vocabulary), ::navigateBack)
                Text(
                    term,
                    color = Ink,
                    fontSize = 28.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PagedList(listOf(Unit)) {
                        SettingsItem(
                            label = stringResource(R.string.transcription_vocabulary_remove),
                            onClick = {
                                if (persist(terms.filterIndexed { termIndex, _ -> termIndex != index })) {
                                    removalIndex = null
                                }
                            },
                        )
                    }
                }
                if (saveFailed) {
                    Text(
                        stringResource(R.string.save_failed_kept),
                        color = DimInk,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 18.dp),
                    )
                }
            }
            return
        }
    }

    val candidate = remember(input) {
        runCatching { TranscriptionVocabulary.parse(input).singleOrNull() }.getOrNull()
    }
    val duplicateIndex = candidate?.let { value ->
        terms.indexOfFirst { it.lowercase(Locale.ROOT) == value.lowercase(Locale.ROOT) }
    } ?: -1
    val changed = editingIndex?.let { terms.getOrNull(it) != candidate } ?: true
    val duplicate = duplicateIndex >= 0 && duplicateIndex != editingIndex
    val canSave = candidate != null && changed && !duplicate &&
        (editingIndex != null || terms.size < TranscriptionVocabulary.MAX_TERMS)

    fun submit() {
        val value = candidate ?: return
        if (!canSave) return
        val updated = editingIndex?.let { index ->
            terms.toMutableList().apply { this[index] = value }
        } ?: (terms + value)
        if (persist(updated)) closeInput()
    }

    fun beginAdd() {
        if (terms.size >= TranscriptionVocabulary.MAX_TERMS) return
        input = ""
        editingIndex = null
        inputOpen = true
        saveFailed = false
    }

    fun beginEdit(index: Int) {
        input = terms[index]
        editingIndex = index
        inputOpen = true
        saveFailed = false
    }

    LaunchedEffect(inputOpen, editingIndex) {
        if (inputOpen) focusRequester.requestFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(stringResource(R.string.settings_transcription_vocabulary), ::navigateBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                stringResource(R.string.transcription_vocabulary_help),
                color = DimInk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    R.string.transcription_vocabulary_count,
                    terms.size,
                    TranscriptionVocabulary.MAX_TERMS,
                ),
                color = DimInk,
                fontSize = 12.sp,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (terms.isEmpty()) {
                EmptyHint(stringResource(R.string.transcription_vocabulary_empty))
            } else {
                PagedList(terms, resetKey = terms) { term ->
                    val index = terms.indexOf(term)
                    Column(
                        modifier = Modifier.fillMaxSize().then(
                            tapLongModifier(
                                onClick = { beginEdit(index) },
                                onLongClick = { removalIndex = index },
                                label = term,
                            ),
                        ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            term,
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Text(
            when {
                saveFailed -> stringResource(R.string.save_failed_kept)
                inputOpen && input.isNotBlank() && candidate == null ->
                    stringResource(R.string.transcription_vocabulary_invalid)
                inputOpen && duplicate -> stringResource(R.string.transcription_vocabulary_duplicate)
                inputOpen && editingIndex != null -> stringResource(R.string.transcription_vocabulary_editing)
                else -> stringResource(R.string.transcription_vocabulary_manage_hint)
            },
            color = DimInk,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inputOpen) {
                LineInput(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.transcription_vocabulary_add),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    onDone = ::submit,
                )
            } else {
                CaptureBar(
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.transcription_vocabulary_add),
                    onOpen = ::beginAdd,
                    onLongPress = ::beginAdd,
                )
            }
            PlusButton(
                onClick = { if (inputOpen) submit() else beginAdd() },
                onLongClick = { if (inputOpen) submit() else beginAdd() },
                modifier = Modifier.offset(x = 8.dp),
            )
        }
    }
}
