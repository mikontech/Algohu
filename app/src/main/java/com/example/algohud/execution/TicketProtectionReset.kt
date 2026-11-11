package com.example.algohud.execution

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/** Only a recognized new-order ticket may be changed. No position modifications. */
object TicketProtectionReset {
    data class Context(
        val expectedInstrument: String,
        val title: String?,
        val hasVolume: Boolean,
        val hasBuy: Boolean,
        val hasSell: Boolean,
        val hasModifyPosition: Boolean
    )

    fun canReset(c: Context): Boolean = c.expectedInstrument.isNotBlank() &&
        c.title?.trim()?.split(Regex("\\s+"))?.firstOrNull() == c.expectedInstrument &&
        c.hasVolume && c.hasBuy && c.hasSell && !c.hasModifyPosition

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            check(result.size < 3000) { "Drzewo formularza jest zbyt duże" }
            val n = queue.removeFirst()
            result.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return result
    }

    private fun guard(root: AccessibilityNodeInfo, instrument: String) {
        check(root.packageName?.toString() == ScreenScanner.XTB_PACKAGE)
        fun found(id: String) = ScreenScanner.findById(root, id, visibleOnly = false)
        val titles = found("ElementTitleHorizontalText")
        check(titles.size == 1) { "Niejednoznaczny tytuł ekranu" }
        check(canReset(Context(instrument, titles.single().text?.toString(),
            found("StepperTextField").size == 1,
            found("TradingBuyButton").size == 1,
            found("TradingSellButton").size == 1,
            found("ModifyPositionButton").isNotEmpty()))) {
            "To nie jest jednoznacznie rozpoznany bilet nowego zlecenia"
        }
    }

    suspend fun reset(
        instrument: String,
        readRoot: () -> AccessibilityNodeInfo?,
        reveal: suspend (String) -> Boolean,
        hideKeyboard: suspend () -> Unit
    ) {
        fun checkedRoot(): AccessibilityNodeInfo {
            val root = checkNotNull(readRoot()) { "Brak okna XTB" }
            guard(root, instrument)
            return root
        }
        checkedRoot()
        hideKeyboard()
        checkedRoot()
        for ((containerId, toggleId) in listOf(
            "StopLossContainer" to "StopLossToggle",
            "TakeProfitContainer" to "TakeProfitToggle"
        )) {
            checkedRoot()
            check(reveal(containerId)) { "Nie znaleziono $containerId" }
            var root = checkedRoot()
            val container = ScreenScanner.findById(root, containerId).singleOrNull()
                ?: error("Niejednoznaczna sekcja $containerId")
            val toggleRoot = ScreenScanner.findById(root, toggleId).singleOrNull()
                ?: error("Brak przełącznika $toggleId")
            val toggle = nodes(toggleRoot).filter { it.isCheckable }.singleOrNull()
                ?: error("Nie można bezpiecznie odczytać stanu $toggleId")
            if (toggle.isChecked) {
                check(toggle.isEnabled && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    "Nie udało się wyłączyć $toggleId"
                }
                delay(350)
            } else {
                val editable = nodes(container).filter { it.isEditable }
                check(editable.size <= 1) { "Niejednoznaczne pole $containerId" }
                editable.singleOrNull()?.takeIf { !it.text.isNullOrBlank() }?.let { field ->
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                    delay(200)
                }
            }
            root = checkedRoot()
            val newToggleRoot = ScreenScanner.findById(root, toggleId).singleOrNull()
                ?: error("Nie potwierdzono stanu $toggleId po resecie")
            val newToggle = nodes(newToggleRoot).filter { it.isCheckable }.singleOrNull()
                ?: error("Nie odczytano stanu $toggleId po resecie")
            check(!newToggle.isChecked) { "$toggleId nadal aktywny" }
            val newContainer = ScreenScanner.findById(root, containerId).singleOrNull()
                ?: error("Brak sekcji po resecie $containerId")
            check(nodes(newContainer).filter { it.isEditable }.all { it.text.isNullOrBlank() }) {
                "$containerId nadal zawiera wartość"
            }
        }
    }
}
