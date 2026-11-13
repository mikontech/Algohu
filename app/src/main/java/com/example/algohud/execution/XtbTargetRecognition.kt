package com.example.algohud.execution

/** Rozpoznanie biletu niezależne od chwilowej widoczności notowań. */
object XtbTargetRecognition {
    fun matches(pkg: String?, title: String?, target: String, ticketMarkers: Boolean): Boolean {
        if (pkg != "com.xtb.xmobile2" || !ticketMarkers || title == null) return false
        val normalized = title.trim().replace(Regex("\\s+"), " ")
        if (normalized.contains("24/7")) return false
        return normalized.substringBefore(' ').equals(target, ignoreCase = true)
    }
}
