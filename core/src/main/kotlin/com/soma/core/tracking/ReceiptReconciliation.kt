package com.soma.core.tracking

import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptMoney

/**
 * Transparent receipt arithmetic for the confirmation screen.
 *
 * A difference is information, not an error: discounts, deposits and rounding can legitimately
 * make printed item lines differ from the final amount. Soma therefore never overwrites the
 * receipt total and never blocks registration solely because the arithmetic differs.
 */
data class ReceiptReconciliation(
    val pricedItemCount: Int,
    val itemCount: Int,
    val pricedItemSum: ReceiptMoney?,
    val allItemsPriced: Boolean,
    val itemDifferenceMinorUnits: Long?,
    val subtotalTaxDifferenceMinorUnits: Long?,
)

object ReceiptReconciler {
    fun reconcile(receipt: ReceiptDetails): ReceiptReconciliation {
        val priced = receipt.items.mapNotNull { it.lineTotal }
        val sameCurrency = priced.all { it.currencyCode == receipt.currencyCode }
        val sum = if (priced.isNotEmpty() && sameCurrency) {
            runCatching {
                ReceiptMoney(
                    minorUnits = priced.fold(0L) { total, amount -> Math.addExact(total, amount.minorUnits) },
                    currencyCode = receipt.currencyCode,
                )
            }.getOrNull()
        } else {
            null
        }
        val allItemsPriced = receipt.items.isNotEmpty() && priced.size == receipt.items.size
        return ReceiptReconciliation(
            pricedItemCount = priced.size,
            itemCount = receipt.items.size,
            pricedItemSum = sum,
            allItemsPriced = allItemsPriced,
            itemDifferenceMinorUnits = if (allItemsPriced && sum != null && receipt.total != null) {
                receipt.total.minorUnits - sum.minorUnits
            } else {
                null
            },
            subtotalTaxDifferenceMinorUnits = if (
                receipt.subtotal != null && receipt.tax != null && receipt.total != null
            ) {
                receipt.total.minorUnits - receipt.subtotal.minorUnits - receipt.tax.minorUnits
            } else {
                null
            },
        )
    }
}
