package com.soma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.FoodItem
import com.soma.core.model.FoodQuantityUnit
import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.LogRevision
import com.soma.core.model.NutritionBasis
import com.soma.core.model.NutritionEstimate
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptMoney
import com.soma.core.model.WorkoutExercise
import com.soma.core.model.WorkoutSet
import com.soma.core.tracking.EuropeanFoodReference
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun TrackingLogsScreen(
    viewModel: SomaViewModel,
    onAdd: (LogKind?) -> Unit,
    onDetail: (LogRecord) -> Unit,
    onOptions: (LogRecord) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val allLogs by viewModel.trackingLogs.collectAsState()
    var filter by remember { mutableStateOf<LogKind?>(null) }
    val logs = allLogs.filter { filter == null || it.kind == filter }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        TrackingHeader(
            filter = filter,
            onFilter = { filter = nextFilter(filter) },
            onBack = onBack,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (logs.isEmpty()) {
                EmptyHint(stringResource(R.string.logs_empty))
            } else {
                PagedList(logs, resetKey = filter) { log ->
                    TrackingLogRow(log, onDetail = { onDetail(log) }, onOptions = { onOptions(log) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CaptureBar(
                modifier = Modifier.weight(1f),
                placeholder = stringResource(
                    when (filter) {
                        LogKind.MEAL -> R.string.add_meal
                        LogKind.RECIPE -> R.string.add_recipe
                        LogKind.WORKOUT -> R.string.add_workout
                        LogKind.RECEIPT -> R.string.add_receipt
                        null -> R.string.add_log
                    },
                ),
                onOpen = { onAdd(filter) },
                onLongPress = { onAdd(null) },
            )
            PlusButton(
                onClick = { onAdd(filter) },
                onLongClick = { onAdd(null) },
                modifier = Modifier.offset(x = 8.dp),
            )
        }
    }
}

@Composable
private fun TrackingHeader(filter: LogKind?, onFilter: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BackArrow(Modifier.align(Alignment.CenterStart).offset(x = (-30).dp), onBack)
        Text(stringResource(R.string.logs), color = Ink, fontSize = 16.sp)
        Text(
            text = trackingFilterLabel(filter),
            color = DimInk,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd).then(tapModifier(onFilter, "filter logs")),
        )
    }
}

@Composable
private fun trackingFilterLabel(kind: LogKind?): String = stringResource(
    when (kind) {
        null -> R.string.logs_all
        LogKind.MEAL -> R.string.meals
        LogKind.RECIPE -> R.string.recipes
        LogKind.WORKOUT -> R.string.workouts
        LogKind.RECEIPT -> R.string.receipts
    },
)

private fun nextFilter(current: LogKind?): LogKind? = when (current) {
    null -> LogKind.MEAL
    LogKind.MEAL -> LogKind.RECIPE
    LogKind.RECIPE -> LogKind.WORKOUT
    LogKind.WORKOUT -> LogKind.RECEIPT
    LogKind.RECEIPT -> null
}

@Composable
private fun TrackingLogRow(log: LogRecord, onDetail: () -> Unit, onOptions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().then(tapLongModifier(onDetail, onOptions, "log")),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = displayLogTitle(log),
            color = Ink,
            fontSize = 22.sp,
            // Two wrapped lines plus the metadata row must fit one fixed
            // ~190px PagedList slot; the default line height clips the
            // metadata line mid-glyph.
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(logKindLabel(log.kind), color = DimInk, fontSize = 12.sp, lineHeight = 13.sp)
            Text(formatLogTime(log), color = DimInk, fontSize = 12.sp, lineHeight = 13.sp)
            logSummary(log)?.let { Text(it, color = DimInk, fontSize = 12.sp, lineHeight = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
fun LogKindScreen(
    fromEntry: Boolean = false,
    onSelect: (LogKind) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(if (fromEntry) R.string.register else R.string.add_log), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(LogKind.entries) { kind ->
                SettingsItem(label = logKindLabel(kind), onClick = { onSelect(kind) })
            }
        }
    }
}

@Composable
fun WorkoutQuickAddScreen(
    saving: Boolean,
    saveFailed: Boolean,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    var exercise by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf("") }
    var repetitions by remember { mutableStateOf("") }
    var kilograms by remember { mutableStateOf("") }
    var choosingExercise by remember { mutableStateOf(false) }
    var exerciseQuery by remember { mutableStateOf("") }
    var focusSetsAfterChoice by remember { mutableStateOf(false) }
    val exerciseFocus = remember { FocusRequester() }
    val setsFocus = remember { FocusRequester() }
    val repetitionsFocus = remember { FocusRequester() }
    val kilogramsFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = { if (choosingExercise) choosingExercise = false else onBack() })
    if (choosingExercise) {
        val results = remember(exerciseQuery) {
            val query = exerciseQuery.trim().lowercase(Locale.ROOT)
            if (query.length < 2) LOCAL_EXERCISES else LOCAL_EXERCISES.filter { it.lowercase(Locale.ROOT).contains(query) }
        }
        Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
            SimpleTopBar(stringResource(R.string.choose_exercise), { choosingExercise = false })
            LineInput(
                value = exerciseQuery,
                onValueChange = { exerciseQuery = it },
                placeholder = stringResource(R.string.exercise_search_hint),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (results.isEmpty()) {
                    EmptyHint(stringResource(R.string.exercise_search_empty))
                } else {
                    PagedList(results, resetKey = exerciseQuery) { result ->
                        SettingsItem(
                            label = result,
                            onClick = {
                                exercise = result
                                focusSetsAfterChoice = true
                                choosingExercise = false
                            },
                        )
                    }
                }
            }
        }
        return
    }
    val setCount = sets.toIntOrNull()
    val repCount = repetitions.toIntOrNull()
    val weight = kilograms.replace(',', '.').toDoubleOrNull()
    val pairedCounts = sets.isBlank() && repetitions.isBlank() ||
        setCount in 1..WorkoutExercise.MAX_SETS && repCount in 1..WorkoutSet.MAX_REPETITIONS
    val validWeight = kilograms.isBlank() || weight != null && weight in 0.0..WorkoutSet.MAX_WEIGHT_KILOGRAMS
    val canSave = !saving && exercise.isNotBlank() && pairedCounts && validWeight

    fun submit() {
        if (!canSave) return
        keyboard?.hide()
        val text = if (setCount != null && repCount != null) {
            buildString {
                append(exercise.trim()).append(' ').append(setCount).append('×').append(repCount)
                weight?.let { append(' ').append(formatNumber(it)).append(" kg") }
            }
        } else {
            exercise.trim()
        }
        onSave(text)
    }

    LaunchedEffect(choosingExercise, focusSetsAfterChoice) {
        if (!choosingExercise) {
            if (focusSetsAfterChoice) {
                focusSetsAfterChoice = false
                setsFocus.requestFocus()
            } else if (exercise.isBlank()) {
                exerciseFocus.requestFocus()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(stringResource(R.string.add_workout), onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsItem(
                label = stringResource(R.string.choose_exercise),
                trailing = exercise.takeIf(String::isNotBlank),
                onClick = { choosingExercise = true },
            )
            LineInput(
                value = exercise,
                onValueChange = { exercise = it },
                placeholder = stringResource(R.string.workout_exercise_hint),
                modifier = Modifier.fillMaxWidth().focusRequester(exerciseFocus),
                imeAction = ImeAction.Next,
                onNext = { setsFocus.requestFocus() },
            )
            LineInput(
                value = sets,
                onValueChange = { sets = it.filter(Char::isDigit).take(3) },
                placeholder = stringResource(R.string.workout_sets_hint),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth().focusRequester(setsFocus),
                imeAction = ImeAction.Next,
                onNext = { repetitionsFocus.requestFocus() },
            )
            LineInput(
                value = repetitions,
                onValueChange = { repetitions = it.filter(Char::isDigit).take(5) },
                placeholder = stringResource(R.string.workout_repetitions_hint),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth().focusRequester(repetitionsFocus),
                imeAction = ImeAction.Next,
                onNext = { kilogramsFocus.requestFocus() },
            )
            LineInput(
                value = kilograms,
                onValueChange = { value -> kilograms = value.filter { it.isDigit() || it == ',' || it == '.' }.take(8) },
                placeholder = stringResource(R.string.workout_weight_hint),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth().focusRequester(kilogramsFocus),
                imeAction = ImeAction.Done,
                onDone = ::submit,
            )
        }
        if (saveFailed) {
            Text(stringResource(R.string.save_failed_kept), color = DimInk, fontSize = 13.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canSave) {
                        tapModifier(
                            ::submit,
                            stringResource(R.string.save),
                        )
                    } else {
                        Modifier
                    },
                )
                .height(58.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(stringResource(R.string.save), color = if (canSave) Ink else DimInk, fontSize = 18.sp)
        }
    }
}

/** Small authored offline list; it is a convenience, never a restriction or a health authority. */
private val LOCAL_EXERCISES = listOf(
    "Abdominal crunch machine",
    "Assisted dip",
    "Assisted pull-up",
    "Back extension",
    "Barbell bench press",
    "Barbell row",
    "Biceps curl machine",
    "Cable fly",
    "Cable row",
    "Calf raise",
    "Chest press machine",
    "Deadlift",
    "Dumbbell bench press",
    "Dumbbell row",
    "Elliptical trainer",
    "Hack squat",
    "Hip abduction machine",
    "Hip adduction machine",
    "Hip thrust",
    "Lat pulldown",
    "Lateral raise",
    "Leg curl",
    "Leg extension",
    "Leg press",
    "Lunge",
    "Overhead press",
    "Pec deck",
    "Plank",
    "Pull-up",
    "Push-up",
    "Romanian deadlift",
    "Rowing machine",
    "Seated calf raise",
    "Seated row machine",
    "Shoulder press machine",
    "Smith machine squat",
    "Squat",
    "Stair climber",
    "Treadmill",
    "Triceps pushdown",
)

@Composable
fun TrackingLogDetailScreen(
    log: LogRecord,
    historyCount: Int?,
    onHistory: (() -> Unit)?,
    onSource: (() -> Unit)?,
    onFood: ((Int) -> Unit)?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(logKindLabel(log.kind), onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(displayLogTitle(log), color = Ink, fontSize = 30.sp, lineHeight = 38.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(formatLogTime(log), color = DimInk, fontSize = 13.sp)
                if (log.revision > 0 && historyCount != null) {
                    Text(
                        stringResource(R.string.log_earlier_versions, historyCount),
                        color = DimInk,
                        fontSize = 13.sp,
                        modifier = Modifier.then(onHistory?.let { tapModifier(it, "log history") } ?: Modifier),
                    )
                }
                if (log.source != null && onSource != null) {
                    Text(
                        stringResource(R.string.source_entry),
                        color = DimInk,
                        fontSize = 13.sp,
                        modifier = Modifier.then(tapModifier(onSource, "source entry")),
                    )
                }
            }
            when (log.kind) {
                LogKind.WORKOUT -> log.exercises.forEach { WorkoutDetail(it) }
                LogKind.MEAL, LogKind.RECIPE -> {
                    log.foods.forEachIndexed { index, food ->
                        FoodDetail(food, onFood?.let { callback -> { callback(index) } })
                    }
                }
                LogKind.RECEIPT -> log.receipt?.let { ReceiptDetail(it) }
            }
            if (log.kind != LogKind.RECEIPT && log.note.isNotBlank() && log.note != log.title) {
                Text(log.note, color = Ink, fontSize = 20.sp, lineHeight = 28.sp)
            }
        }
    }
}

@Composable
private fun FoodDetail(food: FoodItem, onSelect: (() -> Unit)?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.then(onSelect?.let { tapModifier(it, "match food") } ?: Modifier),
    ) {
        Text(food.name, color = Ink, fontSize = 22.sp)
        val quantity = foodQuantity(food)
        val nutrition = nutritionLine(food.nutrition)
        if (quantity != null) Text(quantity, color = DimInk, fontSize = 14.sp)
        if (nutrition != null) Text(nutrition, color = DimInk, fontSize = 14.sp, lineHeight = 19.sp)
        food.nutrition?.let { estimate ->
            Text(nutritionBasisLabel(estimate.basis), color = DimInk, fontSize = 12.sp)
        }
        if (onSelect != null) {
            Text(stringResource(R.string.match_food), color = DimInk, fontSize = 12.sp)
        }
    }
}

@Composable
fun EuropeanFoodSearchScreen(
    viewModel: SomaViewModel,
    initialQuery: String,
    onSelect: (EuropeanFoodReference) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val results by viewModel.foodSearchResults.collectAsState()
    val loading by viewModel.foodSearchLoading.collectAsState()
    LaunchedEffect(query) { viewModel.searchEuropeanFoods(query) }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.match_food), onBack)
        LineInput(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.food_search_hint),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> EmptyHint(stringResource(R.string.food_search_loading))
                query.trim().length < 2 -> EmptyHint(stringResource(R.string.food_search_hint))
                results.isEmpty() -> EmptyHint(stringResource(R.string.food_search_empty))
                else -> PagedList(results, resetKey = query) { food ->
                    Column(
                        modifier = Modifier.fillMaxSize().then(
                            tapModifier({ onSelect(food) }, food.displayName),
                        ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            food.displayName.lowercase().replaceFirstChar { it.titlecase() },
                            color = Ink,
                            fontSize = 21.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(food.sourceLabel, color = DimInk, fontSize = 12.sp)
                            food.energyKcalPer100Grams?.let { energy ->
                                Text(
                                    stringResource(R.string.nutrition_kcal_per_100g, formatNumber(energy)),
                                    color = DimInk,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (viewModel.packagedFoodLookupAvailable && query.trim().matches(Regex("\\d{8,14}"))) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                SettingsItem(
                    label = stringResource(R.string.lookup_barcode_online),
                    trailing = stringResource(R.string.open_food_facts),
                    onClick = { viewModel.lookupPackagedFood(query) },
                )
            }
        }
    }
}

@Composable
private fun WorkoutDetail(exercise: WorkoutExercise) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(exercise.name, color = Ink, fontSize = 22.sp)
        exercise.machine?.let { Text(it, color = DimInk, fontSize = 14.sp) }
        if (exercise.sets.isEmpty()) {
            Text(stringResource(R.string.workout_unquantified), color = DimInk, fontSize = 14.sp)
        } else {
            exercise.sets.forEachIndexed { index, set ->
                val parts = buildList {
                    set.repetitions?.let { add(stringResource(R.string.workout_reps, it)) }
                    set.weightKilograms?.let { add(stringResource(R.string.workout_kg, formatNumber(it))) }
                    set.durationSeconds?.let { add(stringResource(R.string.workout_seconds, it)) }
                }
                Text(
                    stringResource(R.string.workout_set, index + 1, parts.joinToString(" · ")),
                    color = DimInk,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ReceiptDetail(receipt: ReceiptDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        receipt.merchant?.let { Text(it, color = Ink, fontSize = 22.sp) }
        receipt.items.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        append(item.name)
                        item.quantity?.let { append(" × ").append(formatNumber(it)) }
                    },
                    color = Ink,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                item.lineTotal?.let { Text(formatReceiptMoney(it), color = DimInk, fontSize = 16.sp) }
            }
            item.category?.let { Text(it, color = DimInk, fontSize = 12.sp) }
        }
        receipt.subtotal?.let {
            Text(stringResource(R.string.receipt_subtotal, formatReceiptMoney(it)), color = DimInk, fontSize = 14.sp)
        }
        receipt.tax?.let {
            Text(stringResource(R.string.receipt_tax, formatReceiptMoney(it)), color = DimInk, fontSize = 14.sp)
        }
        receipt.total?.let {
            Text(stringResource(R.string.receipt_total, formatReceiptMoney(it)), color = Ink, fontSize = 20.sp)
        }
    }
}

@Composable
fun TrackingLogOptionsScreen(
    log: LogRecord,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onArchive: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val actions = buildList {
        add(TrackingAction.EDIT)
        if (log.revision > 0) add(TrackingAction.HISTORY)
        add(TrackingAction.ARCHIVE)
    }
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.logs), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedList(actions) { action ->
                SettingsItem(
                    label = stringResource(
                        when (action) {
                            TrackingAction.EDIT -> R.string.edit
                            TrackingAction.HISTORY -> R.string.entry_history
                            TrackingAction.ARCHIVE -> R.string.archive_log
                        },
                    ),
                    onClick = when (action) {
                        TrackingAction.EDIT -> onEdit
                        TrackingAction.HISTORY -> onHistory
                        TrackingAction.ARCHIVE -> onArchive
                    },
                )
            }
        }
    }
}

@Composable
fun TrackingLogHistoryScreen(
    revisions: List<LogRevision>?,
    onRevision: (LogRevision) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(horizontal = 28.dp)) {
        SimpleTopBar(stringResource(R.string.entry_history), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                revisions == null -> EmptyHint(stringResource(R.string.entry_history_loading))
                revisions.isEmpty() -> EmptyHint(stringResource(R.string.entry_history_empty))
                else -> PagedList(revisions) { revision ->
                    SettingsItem(
                        label = if (revision.revision == 0L) {
                            stringResource(R.string.original)
                        } else {
                            stringResource(R.string.entry_version, revision.revision + 1)
                        },
                        trailing = formatLogTime(revision.snapshot),
                        onClick = { onRevision(revision) },
                    )
                }
            }
        }
    }
}

private enum class TrackingAction { EDIT, HISTORY, ARCHIVE }

@Composable
private fun logKindLabel(kind: LogKind): String = stringResource(
    when (kind) {
        LogKind.MEAL -> R.string.meal
        LogKind.RECIPE -> R.string.recipe
        LogKind.WORKOUT -> R.string.workout
        LogKind.RECEIPT -> R.string.receipt
    },
)

@Composable
private fun nutritionBasisLabel(basis: NutritionBasis): String = stringResource(
    when (basis) {
        NutritionBasis.PACKAGE_LABEL -> R.string.nutrition_package_label
        NutritionBasis.OFFICIAL_AVERAGE -> R.string.nutrition_official_average
        NutritionBasis.ESTIMATED -> R.string.nutrition_estimated
        NutritionBasis.UNQUANTIFIED -> R.string.nutrition_unquantified
    },
)

@Composable
private fun foodQuantity(food: FoodItem): String? {
    val quantity = food.quantity ?: return null
    val unit = when (food.unit) {
        FoodQuantityUnit.GRAM -> "g"
        FoodQuantityUnit.MILLILITRE -> "ml"
        FoodQuantityUnit.PIECE -> stringResource(R.string.food_piece)
        FoodQuantityUnit.SERVING -> stringResource(R.string.food_serving)
        null -> return null
    }
    return "${formatNumber(quantity)} $unit"
}

@Composable
private fun nutritionLine(nutrition: NutritionEstimate?): String? = when {
    nutrition == null || nutrition.basis == NutritionBasis.UNQUANTIFIED -> null
    nutrition.energyKcal != null -> stringResource(
        R.string.nutrition_kcal,
        formatNumber(requireNotNull(nutrition.energyKcal)),
    )
    nutrition.energyKcalMin != null && nutrition.energyKcalMax != null -> stringResource(
        R.string.nutrition_kcal_range,
        formatNumber(requireNotNull(nutrition.energyKcalMin)),
        formatNumber(requireNotNull(nutrition.energyKcalMax)),
    )
    else -> null
}

@Composable
private fun logSummary(log: LogRecord): String? = when (log.kind) {
    LogKind.WORKOUT -> {
        val exercise = log.exercises.firstOrNull() ?: return null
        val firstSet = exercise.sets.firstOrNull() ?: return null
        buildList {
            if (exercise.sets.isNotEmpty() && firstSet.repetitions != null) {
                add("${exercise.sets.size}×${firstSet.repetitions}")
            }
            firstSet.weightKilograms?.let { add("${formatNumber(it)} kg") }
        }.joinToString(" · ").takeIf(String::isNotEmpty)
    }
    LogKind.MEAL, LogKind.RECIPE -> {
        val bases = log.foods.mapNotNull { it.nutrition?.basis }.distinct()
        bases.singleOrNull()?.let { nutritionBasisLabel(it) }
    }
    LogKind.RECEIPT -> log.receipt?.total?.let(::formatReceiptMoney)
}

private fun formatReceiptMoney(money: ReceiptMoney): String =
    "${money.currencyCode} ${money.minorUnits / 100}.${(money.minorUnits % 100).toString().padStart(2, '0')}"

@Composable
private fun displayLogTitle(log: LogRecord): String =
    if (log.kind == LogKind.RECEIPT && log.title == "Receipt") stringResource(R.string.receipt) else log.title

private fun formatLogTime(log: LogRecord): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .format(log.occurredAt.atZone(ZoneId.systemDefault()))

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
