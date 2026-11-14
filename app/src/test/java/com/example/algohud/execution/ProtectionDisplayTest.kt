package com.example.algohud.execution
import com.example.algohud.core.Side
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
class ProtectionDisplayTest {
    @Test fun showsOnlyBrokerStop() {
        val row = PositionRowInfo(Side.SELL, 0.007, "@3885.09", 3882.75, 3870.0)
        assertEquals("SL XTB: " + "%.2f".format(3882.75), ProtectionDisplay.line(row))
    }
    @Test fun missingReadIsNotMissingStop() {
        assertTrue(ProtectionDisplay.line(null).contains("brak świeżego potwierdzenia"))
        assertTrue(ProtectionDisplay.line(PositionRowInfo(Side.BUY, 0.007, "@3880", null, null)).contains("brak ustawionego SL"))
    }
}
