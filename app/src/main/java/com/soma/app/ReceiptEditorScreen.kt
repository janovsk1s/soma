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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soma.core.model.LogKind
import com.soma.core.model.NoteEntry
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptMoney
import com.soma.core.model.SupportedLanguage
import com.soma.core.tracking.QuickLogParser
import com.soma.core.tracking.ReceiptReconciler
import java.math.BigDecimal
import kotlin.math.absoluteValue

@Composable
fun ReceiptEditorScreen(
    sourceEntry: NoteEntry?,
    draftText: String,
    saving: Boolean,
    saveFailed: Boolean,
    suggestionFailed: Boolean,
    suggesting: Boolean,
    canSuggest: Boolean,
    onDraftChange: (String) -> Unit,
    onSuggest: (() -> Unit)?,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val defaultCurrency = if (SomaPrefs.language(context) == SupportedLanguage.SWEDISH) "SEK" else "EUR"
    var form by remember(defaultCurrency) { mutableStateOf(ReceiptForm.decode(draftText, defaultCurrency)) }
    var emittedText by remember(defaultCurrency) { mutableStateOf(draftText) }
    val keyboard = LocalSoftwareKeyboardController.current
    val languageTag = SomaPrefs.language(context).languageTag
    val sourcePhotoEntry = sourceEntry?.takeIf { it.activeImage != null }
    val sourceHasPhoto = sourcePhotoEntry != null

    LaunchedEffect(draftText) {
        if (draftText != emittedText) {
            form = ReceiptForm.decode(draftText, defaultCurrency)
            emittedText = draftText
        }
    }

    fun update(next: ReceiptForm) {
        form = next
        emittedText = next.encode()
        onDraftChange(emittedText)
    }

    val validation = remember(form, sourceHasPhoto) { form.validate(sourceHasPhoto) }
    val details = remember(form) { form.detailsOrNull() }
    val reconciliation = remember(details) { details?.let(ReceiptReconciler::reconcile) }
    val navigateBack = { if (!saving) onBack() }
    BackHandler(onBack = navigateBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
    ) {
        SimpleTopBar(stringResource(R.string.register_receipt), navigateBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sourcePhotoEntry != null) {
                EncryptedEntryImage(
                    entry = sourcePhotoEntry,
                    modifier = Modifier.fillMaxWidth().height(132.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    backgroundColor = Paper,
                )
                Text(
                    stringResource(R.string.receipt_photo_linked),
                    color = DimInk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            } else {
                Text(
                    stringResource(R.string.receipt_review_help),
                    color = DimInk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            ReceiptField(
                label = stringResource(R.string.receipt_merchant_hint),
                value = form.merchant,
                languageTag = languageTag,
                onValueChange = { update(form.copy(merchant = it.take(RECEIPT_TEXT_LIMIT))) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ReceiptField(
                    label = stringResource(R.string.receipt_currency_hint),
                    value = form.currency,
                    languageTag = languageTag,
                    capitalization = KeyboardCapitalization.Characters,
                    modifier = Modifier.width(82.dp),
                    onValueChange = {
                        update(form.copy(currency = it.filter(Char::isLetter).uppercase().take(3)))
                    },
                )
                ReceiptField(
                    label = stringResource(R.string.receipt_total_hint),
                    value = form.total,
                    languageTag = languageTag,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                    onValueChange = { update(form.copy(total = it.moneyInput())) },
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.receipt_items),
                    color = Ink,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.receipt_add_item),
                    color = Ink,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .then(tapModifier({ update(form.addItem()) }, stringResource(R.string.receipt_add_item)))
                        .padding(vertical = 12.dp),
                )
            }

            form.items.forEachIndexed { index, item ->
                ReceiptItemEditor(
                    number = index + 1,
                    item = item,
                    languageTag = languageTag,
                    onChange = { changed -> update(form.updateItem(index, changed)) },
                    onRemove = { update(form.removeItem(index)) },
                )
            }

            Text(stringResource(R.string.receipt_optional_amounts), color = DimInk, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ReceiptField(
                    label = stringResource(R.string.receipt_subtotal_hint),
                    value = form.subtotal,
                    languageTag = languageTag,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                    onValueChange = { update(form.copy(subtotal = it.moneyInput())) },
                )
                ReceiptField(
                    label = stringResource(R.string.receipt_tax_hint),
                    value = form.tax,
                    languageTag = languageTag,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                    onValueChange = { update(form.copy(tax = it.moneyInput())) },
                )
            }

            reconciliation?.pricedItemSum?.let { sum ->
                Text(
                    stringResource(R.string.receipt_items_sum, sum.display()),
                    color = DimInk,
                    fontSize = 13.sp,
                )
            }
            reconciliation?.itemDifferenceMinorUnits?.let { difference ->
                Text(
                    if (difference == 0L) {
                        stringResource(R.string.receipt_total_matches)
                    } else {
                        stringResource(
                            R.string.receipt_total_difference,
                            difference.absoluteValue.display(form.currency),
                        )
                    },
                    color = if (difference == 0L) DimInk else Ink,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            reconciliation?.subtotalTaxDifferenceMinorUnits?.takeIf { it != 0L }?.let { difference ->
                Text(
                    stringResource(
                        R.string.receipt_subtotal_difference,
                        difference.absoluteValue.display(form.currency),
                    ),
                    color = Ink,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            if (!validation.valid) {
                Text(
                    stringResource(
                        if (validation.hasInvalidField) R.string.receipt_invalid_field
                        else R.string.receipt_nothing_to_register,
                    ),
                    color = Ink,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            if (suggestionFailed) {
                Text(
                    stringResource(R.string.tracking_suggestion_failed),
                    color = DimInk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            if (saveFailed) {
                Text(
                    stringResource(R.string.save_failed_kept),
                    color = DimInk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Box(Modifier.height(4.dp))
        }

        if (canSuggest && onSuggest != null) {
            val label = stringResource(
                if (suggesting) R.string.suggesting_with_groq else R.string.read_receipt_with_groq,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (!saving && !suggesting) tapModifier(onSuggest, label) else Modifier)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, color = if (!saving && !suggesting) Ink else DimInk, fontSize = 18.sp)
            }
        }
        val canRegister = validation.valid && !saving
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (canRegister) {
                        tapModifier(
                            {
                                keyboard?.hide()
                                onSave(form.encode())
                            },
                            stringResource(R.string.register),
                        )
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                stringResource(if (saving) R.string.registering else R.string.register),
                color = if (canRegister) Ink else DimInk,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun ReceiptItemEditor(
    number: Int,
    item: ReceiptFormItem,
    languageTag: String,
    onChange: (ReceiptFormItem) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", color = DimInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.receipt_remove_item),
                color = DimInk,
                fontSize = 13.sp,
                modifier = Modifier
                    .then(tapModifier(onRemove, stringResource(R.string.receipt_remove_item)))
                    .padding(vertical = 10.dp),
            )
        }
        ReceiptField(
            label = stringResource(R.string.receipt_item_name_hint),
            value = item.name,
            languageTag = languageTag,
            onValueChange = { onChange(item.copy(name = it.take(RECEIPT_TEXT_LIMIT))) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ReceiptField(
                label = stringResource(R.string.receipt_item_quantity_hint),
                value = item.quantity,
                languageTag = languageTag,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
                onValueChange = { onChange(item.copy(quantity = it.moneyInput())) },
            )
            ReceiptField(
                label = stringResource(R.string.receipt_item_amount_hint),
                value = item.amount,
                languageTag = languageTag,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
                onValueChange = { onChange(item.copy(amount = it.moneyInput())) },
            )
        }
        ReceiptField(
            label = stringResource(R.string.receipt_item_category_hint),
            value = item.category,
            languageTag = languageTag,
            onValueChange = { onChange(item.copy(category = it.take(100))) },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(DimInk.copy(alpha = 0.45f)))
    }
}

@Composable
private fun ReceiptField(
    label: String,
    value: String,
    languageTag: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    onValueChange: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = DimInk, fontSize = 12.sp, fontWeight = FontWeight.Light)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 21.sp, lineHeight = 27.sp).withSomaFont(),
            cursorBrush = SolidColor(Ink),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = capitalization,
                keyboardType = keyboardType,
                imeAction = ImeAction.Next,
                hintLocales = LocaleList(languageTag),
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink))
    }
}

private data class ReceiptFormItem(
    val name: String = "",
    val quantity: String = "",
    val amount: String = "",
    val category: String = "",
)

private data class ReceiptForm(
    val merchant: String = "",
    val currency: String,
    val total: String = "",
    val subtotal: String = "",
    val tax: String = "",
    val items: List<ReceiptFormItem> = listOf(ReceiptFormItem()),
) {
    fun addItem(): ReceiptForm = copy(items = items + ReceiptFormItem())

    fun updateItem(index: Int, item: ReceiptFormItem): ReceiptForm =
        copy(items = items.mapIndexed { itemIndex, current -> if (itemIndex == index) item else current })

    fun removeItem(index: Int): ReceiptForm = copy(items = items.filterIndexed { itemIndex, _ -> itemIndex != index })

    fun encode(): String = buildList {
        merchant.trim().takeIf(String::isNotEmpty)?.let { add("merchant: ${it.safePart()}") }
        add("currency: ${currency.trim().uppercase()}")
        items.filter { it.hasAnyValue() }.forEach { item ->
            add(
                "item: ${item.name.safePart()} | ${item.quantity.safePart()} | " +
                    "${item.amount.safePart()} | ${item.category.safePart()}",
            )
        }
        subtotal.trim().takeIf(String::isNotEmpty)?.let { add("subtotal: $it") }
        tax.trim().takeIf(String::isNotEmpty)?.let { add("tax: $it") }
        total.trim().takeIf(String::isNotEmpty)?.let { add("total: $it") }
    }.joinToString("\n")

    fun validate(sourceHasPhoto: Boolean): ReceiptValidation {
        val currencyValid = Regex("[A-Z]{3}").matches(currency)
        val amountValid = listOf(total, subtotal, tax).all { it.isBlank() || QuickLogParser.parseReceiptMoney(it, currency) != null }
        val itemValid = items.all { item ->
            if (!item.hasAnyValue()) {
                true
            } else {
                item.name.isNotBlank() &&
                    (item.quantity.isBlank() || item.quantity.decimalOrNull()?.let { it > BigDecimal.ZERO } == true) &&
                    (item.amount.isBlank() || QuickLogParser.parseReceiptMoney(item.amount, currency) != null)
            }
        }
        val hasContent = merchant.isNotBlank() || total.isNotBlank() || subtotal.isNotBlank() || tax.isNotBlank() ||
            items.any(ReceiptFormItem::hasAnyValue)
        val invalid = !currencyValid || !amountValid || !itemValid
        return ReceiptValidation(
            valid = !invalid && (sourceHasPhoto || hasContent),
            hasInvalidField = invalid,
        )
    }

    fun detailsOrNull(): ReceiptDetails? = runCatching {
        QuickLogParser.parse(LogKind.RECEIPT, encode()).receipt
    }.getOrNull()

    companion object {
        fun decode(text: String, defaultCurrency: String): ReceiptForm {
            if (text.isBlank()) return ReceiptForm(currency = defaultCurrency)
            val receipt = runCatching { QuickLogParser.parse(LogKind.RECEIPT, text).receipt }.getOrNull()
                ?: return ReceiptForm(currency = defaultCurrency)
            return ReceiptForm(
                merchant = receipt.merchant.orEmpty(),
                currency = receipt.currencyCode,
                total = receipt.total?.input().orEmpty(),
                subtotal = receipt.subtotal?.input().orEmpty(),
                tax = receipt.tax?.input().orEmpty(),
                items = receipt.items.map { item ->
                    ReceiptFormItem(
                        name = item.name,
                        quantity = item.quantity?.let(::plainDecimal).orEmpty(),
                        amount = item.lineTotal?.input().orEmpty(),
                        category = item.category.orEmpty(),
                    )
                }.ifEmpty { listOf(ReceiptFormItem()) },
            )
        }
    }
}

private data class ReceiptValidation(val valid: Boolean, val hasInvalidField: Boolean)

private fun ReceiptFormItem.hasAnyValue(): Boolean =
    name.isNotBlank() || quantity.isNotBlank() || amount.isNotBlank() || category.isNotBlank()

private fun String.safePart(): String = trim().replace('|', '/')

private fun String.moneyInput(): String = filter { it.isDigit() || it in ",. '’" }.take(24)

private fun String.decimalOrNull(): BigDecimal? =
    trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()

private fun ReceiptMoney.input(): String = "${minorUnits / 100}.${(minorUnits % 100).toString().padStart(2, '0')}"

private fun ReceiptMoney.display(): String = "${currencyCode} ${input()}"

private fun Long.display(currency: String): String =
    "${currency.uppercase()} ${this / 100}.${(this % 100).toString().padStart(2, '0')}"

private fun plainDecimal(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private const val RECEIPT_TEXT_LIMIT = 500
