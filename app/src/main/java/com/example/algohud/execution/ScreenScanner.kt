package com.example.algohud.execution

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.algohud.core.ScreenQuote
import kotlin.math.abs

object ScreenScanner {
    data class TextNode(val text: String, val top: Int, val left: Int)

    private val instrumentRe = Regex("^[A-Z][A-Z0-9]{2,11}(\\.[A-Z]{2,3})?$")
    private val numberRe = Regex("[0-9]+([., ][0-9]+)*([.,][0-9]+)?")
    private val sellWords = listOf("SPRZEDAJ", "SELL")
    private val buyWords = listOf("KUP", "BUY")
    private val uiWords = setOf(
        "KUP", "SPRZEDAJ", "BUY", "SELL", "MENU", "LIVE", "DEMO", "MAX", "MIN", "OFF", "USD", "PLN", "EUR",
        "GBP", "CHF", "JPY", "XTB", "RYNEK", "MARKET", "LIMIT", "STOP", "WYKRES", "CHART", "PROFIL", "KONTO",
        "NEWS", "HOME", "SALDO", "DEPOZYT", "MARGIN", "OPEN", "CLOSE", "ZAMKNIJ", "OTWARTE", "HISTORIA", "LOT",
        "LOTY", "PIPS", "SWAP", "INFO", "OK", "ANULUJ", "CANCEL", "WSZYSTKIE", "ALL", "TOP", "NOWE",
        "CFD", "ETF", "STC", "CRYPTO", "FX", "INDEX"
    )

    const val XTB_PACKAGE = "com.xtb.xmobile2"

    private val XTB_IDS = setOf(
        "ElementTitleHorizontalText", "ButtonTradeBottomText", "TradingSellButton", "TradingBuyButton",
        "AvailableFundsValue", "ValueTextText", "StepperTextField", "TicketDetailsRowDoubleSecondValue"
    )
    private val currencyRe = Regex("\\b[A-Z]{3}\\b")

    private class Hit(val node: AccessibilityNodeInfo, val rect: Rect) {
        val text: String? get() = node.text?.toString()
    }

    fun parseNumber(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace("\u00A0", " ")
            .replace("'", "")
            .trim()
        val match = numberRe.find(cleaned) ?: return null
        var raw = match.value.replace(" ", "")
        if (',' in raw && '.' in raw) {
            val lastComma = raw.lastIndexOf(',')
            val lastDot = raw.lastIndexOf('.')
            raw = if (lastComma > lastDot) {
                raw.replace(".", "").replace(',', '.')
            } else {
                raw.replace(",", "")
            }
        } else if (',' in raw) {
            raw = raw.replace(',', '.')
        }
        return raw.toDoubleOrNull()?.takeIf { it >= 0.0 }
    }

    fun collect(root: AccessibilityNodeInfo, limit: Int = 600): List<TextNode> {
        val out = ArrayList<TextNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val r = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < limit * 3 && out.size < limit) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser) {
                val raw = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                    .distinct()
                    .flatMap { it.split('\n', ',') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.length <= 60 }
                if (raw.isNotEmpty()) {
                    n.getBoundsInScreen(r)
                    raw.forEach { out += TextNode(it, r.top, r.left) }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    fun scan(root: AccessibilityNodeInfo, pkg: String, label: String, now: Long): ScreenQuote {
        val nodes = collect(root)
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        val topZone = bounds.top + (bounds.height() * 0.45).toInt()
        val candidates = nodes.filter { it.top <= topZone }
            .mapNotNull { n -> normalizeInstrument(n.text)?.let { n.top to it } }
        val firstRow = candidates.minOfOrNull { it.first }
        val instrument = firstRow?.let { row ->
            candidates.filter { it.first - row <= 40 }.maxByOrNull { it.second.length }?.second
        }
        return ScreenQuote(
            packageName = pkg,
            appLabel = label,
            instrument = instrument,
            bid = priceNear(nodes, sellWords),
            ask = priceNear(nodes, buyWords),
            timestamp = now
        )
    }

    private fun normalizeInstrument(text: String): String? {
        val first = text.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        val t = first.replace("/", "")
        if (t.length !in 3..14) return null
        if (t != t.uppercase()) return null
        if (!instrumentRe.matches(t) || t in uiWords) return null
        return t
    }

    private fun priceNear(nodes: List<TextNode>, words: List<String>): Double? {
        for ((i, n) in nodes.withIndex()) {
            val up = n.text.uppercase()
            if (words.none { up.startsWith(it) || up.endsWith(it) }) continue
            parseNumber(n.text)?.let { return it }
            val neighbours = ((i - 3)..(i + 3)).filter { it != i && it in nodes.indices }
                .sortedBy { abs(it - i) }
            for (j in neighbours) {
                val m = nodes[j]
                if (m.top < n.top - 10 || m.top - n.top > 250 || abs(m.left - n.left) > 250) continue
                parseNumber(m.text)?.let { return it }
            }
        }
        return null
    }

    private fun index(root: AccessibilityNodeInfo, ids: Set<String>, limit: Int = 3000): Map<String, List<Hit>> {
        val out = HashMap<String, MutableList<Hit>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val n = queue.removeFirst()
            visited++
            val short = n.viewIdResourceName?.substringAfter(":id/")?.substringAfterLast('/')
            if (short != null && short in ids && n.isVisibleToUser) {
                out.getOrPut(short) { mutableListOf() } += Hit(n, Rect().also { n.getBoundsInScreen(it) })
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    fun scanXtb(root: AccessibilityNodeInfo, pkg: String, label: String, now: Long): ScreenQuote? {
        val idx = index(root, XTB_IDS)
        val title = idx["ElementTitleHorizontalText"]?.firstOrNull()?.text ?: return null
        if (title.contains("zlecenie", ignoreCase = true) || title.startsWith("Nowe", ignoreCase = true)) return null
        val instrument = title.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

        fun inside(containerId: String, childId: String): String? {
            val c = idx[containerId]?.firstOrNull() ?: return null
            return idx[childId]?.firstOrNull { c.rect.contains(it.rect.centerX(), it.rect.centerY()) }?.text
        }

        val fundsText = inside("AvailableFundsValue", "ValueTextText")
        return ScreenQuote(
            packageName = pkg,
            appLabel = label,
            instrument = instrument,
            bid = inside("TradingSellButton", "ButtonTradeBottomText")?.let { parseNumber(it) },
            ask = inside("TradingBuyButton", "ButtonTradeBottomText")?.let { parseNumber(it) },
            timestamp = now,
            availableFunds = fundsText?.let { parseNumber(it) },
            accountCurrency = fundsText?.let { currencyRe.find(it)?.value },
            volume = idx["StepperTextField"]?.firstOrNull()?.text?.let { parseNumber(it) },
            contractValueAcct = inside("TicketDetailsRowDoubleSecondValue", "ValueTextText")?.let { parseNumber(it) }
        )
    }

    fun idMatches(n: AccessibilityNodeInfo, id: String): Boolean {
        val v = n.viewIdResourceName ?: return false
        return v == id || v.endsWith(":id/$id") || v.endsWith("/$id")
    }

    fun findById(root: AccessibilityNodeInfo, id: String, limit: Int = 3000, visibleOnly: Boolean = true): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val n = queue.removeFirst()
            visited++
            if ((!visibleOnly || n.isVisibleToUser) && idMatches(n, id)) {
                out += n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    fun dump(root: AccessibilityNodeInfo, maxLines: Int = 500): String = buildString {
        appendLine("# Algo HUD – zrzut ekranu ${root.packageName}")
        val r = Rect()
        var lines = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (lines >= maxLines) return
            val id = n.viewIdResourceName?.substringAfter(":id/")
            val t = n.text?.toString()?.replace('\n', '|')?.take(160)
            val d = n.contentDescription?.toString()?.replace('\n', '|')?.take(160)
            if (n.isVisibleToUser && (id != null || t != null || d != null || n.isClickable)) {
                n.getBoundsInScreen(r)
                append("  ".repeat(depth.coerceAtMost(12)))
                append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                id?.let { append(" id=").append(it) }
                t?.let { append(" t=\"").append(it).append('"') }
                d?.let { append(" d=\"").append(it).append('"') }
                if (n.isClickable) append(" [click]")
                if (n.isEditable) append(" [edit]")
                if (n.isCheckable) append(if (n.isChecked) " [on]" else " [off]")
                append(" @").append(r.left).append(',').append(r.top)
                append(' ').append(r.width()).append('x').append(r.height())
                appendLine()
                lines++
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
    }
}
