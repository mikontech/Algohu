package com.example.algohud.execution

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Prices must belong geometrically to their exact identified buttons. */
object XtbTicketReader {
    fun read(root: AccessibilityNodeInfo?): TicketReadiness.Snapshot {
        val capturedAt = System.nanoTime()
        val missing = TicketReadiness.Side(0, false, emptyList())
        if (root == null || root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) {
            return TicketReadiness.Snapshot(false, emptyList(), 0, false, missing, missing, capturedAt)
        }
        val labels = ScreenScanner.findById(root, "ButtonTradeBottomText")
        fun side(id: String): TicketReadiness.Side {
            val buttons = ScreenScanner.findById(root, id)
            val button = buttons.singleOrNull()
                ?: return TicketReadiness.Side(buttons.size, false, emptyList())
            val bounds = Rect().also { button.getBoundsInScreen(it) }
            val prices = labels.filter { node ->
                val r = Rect().also { node.getBoundsInScreen(it) }
                !r.isEmpty && bounds.contains(r)
            }.map { node ->
                node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString().orEmpty()
            }
            return TicketReadiness.Side(buttons.size, button.isEnabled, prices)
        }
        return TicketReadiness.Snapshot(
            true,
            ScreenScanner.findById(root, "ElementTitleHorizontalText").map { it.text?.toString().orEmpty() },
            ScreenScanner.findById(root, "StepperTextField").size,
            ScreenScanner.findById(root, "ModifyPositionButton", visibleOnly = false).isNotEmpty(),
            side("TradingSellButton"), side("TradingBuyButton"), capturedAt
        )
    }
}
