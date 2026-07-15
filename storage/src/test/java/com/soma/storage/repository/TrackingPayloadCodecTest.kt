package com.soma.storage.repository

import com.soma.core.model.LogKind
import com.soma.core.model.LogRecord
import com.soma.core.model.ReceiptDetails
import com.soma.core.model.ReceiptItem
import com.soma.core.model.ReceiptMoney
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingPayloadCodecTest {
    private val now = Instant.parse("2026-07-14T12:00:00Z")

    @Test
    fun `receipt payload round trip keeps exact money and item metadata`() {
        val receipt = LogRecord(
            id = "receipt-1",
            kind = LogKind.RECEIPT,
            title = "Rimi",
            note = "Rimi receipt",
            occurredAt = now,
            createdAt = now,
            updatedAt = now,
            receipt = ReceiptDetails(
                merchant = "Rimi",
                currencyCode = "EUR",
                tax = ReceiptMoney(75, "EUR"),
                total = ReceiptMoney(429, "EUR"),
                items = listOf(
                    ReceiptItem(
                        name = "Milk",
                        quantity = 1.0,
                        lineTotal = ReceiptMoney(129, "EUR"),
                        category = "groceries",
                    ),
                    // A printed discount keeps its minus sign through the codec.
                    ReceiptItem(
                        name = "Nimm mehr",
                        lineTotal = ReceiptMoney(-50, "EUR"),
                    ),
                ),
            ),
        )

        assertEquals(receipt, TrackingPayloadCodec.decode(TrackingPayloadCodec.encode(receipt)))
    }

    @Test
    fun `version one tracking payloads remain readable`() {
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeUTF("meal-1")
                output.writeUTF("MEAL")
                output.writeUTF("Lunch")
                output.writeUTF("Lunch")
                repeat(3) { output.writeInstantV1(now) }
                output.writeBoolean(false) // source
                output.writeInt(0) // foods
                output.writeInt(0) // exercises
                output.writeLong(0) // revision
                output.writeBoolean(false) // archivedAt
            }
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }

        val decoded = TrackingPayloadCodec.decode(encoded)

        assertEquals(LogKind.MEAL, decoded.kind)
        assertEquals("Lunch", decoded.title)
        assertNull(decoded.receipt)
    }

    private fun DataOutputStream.writeInstantV1(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }
}
