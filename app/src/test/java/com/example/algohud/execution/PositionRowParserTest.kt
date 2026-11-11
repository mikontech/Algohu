package com.example.algohud.execution

import com.example.algohud.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Współrzędne i teksty przepisane z prawdziwego zrzutu ekranu XTB (wiersz PositionRow0). */
class PositionRowParserTest {
    private fun item(id: String, text: String, l: Int, t: Int, w: Int, h: Int) = UiItem(id, text, l, t, l + w, t + h)

    private fun rowItems(sl: String, tp: String) = listOf(
        item("Badge", "Buy", 149, 1543, 89, 44),
        item("ValueTextLabel", "Wolumen", 74, 1633, 111, 34),
        item("ValueTextText", "0.001", 74, 1667, 296, 34),
        item("ValueTextSubtitle", "@ 4 106.51", 74, 1701, 130, 34),
        item("ValueTextLabel", "Wartość kontraktu", 391, 1633, 214, 34),
        item("ValueTextText", "1 615.27", 391, 1667, 297, 34),
        item("ValueTextLabel", "Zysk netto", 882, 1633, 124, 34),
        item("ValueTextText", "-1.09", 709, 1667, 297, 34),
        item("ValueTextSubtitle", "-1.36%", 916, 1701, 90, 34),
        item("ValueTextLabel", "Stop Loss", 74, 1761, 117, 34),
        item("ValueTextText", sl, 74, 1795, 296, 34),
        item("ValueTextLabel", "Take Profit", 391, 1761, 126, 34),
        item("ValueTextText", tp, 391, 1795, 297, 34)
    )

    @Test fun parsesRowWithoutSlTp() {
        val r = PositionRowParser.parse(rowItems("-", "-"))
        assertEquals(Side.BUY, r.side)
        assertEquals(0.001, r.volume!!, 1e-12)
        assertEquals("@ 4 106.51", r.entryText)
        assertNull(r.sl)
        assertNull(r.tp)
    }

    @Test fun parsesRowWithSlTp() {
        val r = PositionRowParser.parse(rowItems("4 100.94", "4 115.20"))
        assertEquals(4100.94, r.sl!!, 1e-9)
        assertEquals(4115.20, r.tp!!, 1e-9)
    }

    @Test fun parsesSellSide() {
        val items = rowItems("-", "-").map { if (it.id == "Badge") it.copy(text = "Sell") else it }
        assertEquals(Side.SELL, PositionRowParser.parse(items).side)
    }
}
