package com.example.algohud.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.algohud.BuildConfig
import com.example.algohud.bot.DirectionModel
import com.example.algohud.execution.UiActionRecovery
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display only: never trains, places orders, or manufactures probabilities. */
class DirectionBarsView(context: Context) : LinearLayout(context) {
    private data class Row(val bar: ProgressBar, val value: TextView)
    private val status = TextView(context).apply {
        textSize = 10f
        setTextColor(0xFFBBBBBB.toInt())
    }
    private val recoveryStatus = TextView(context).apply {
        textSize = 11f
        setPadding(0, dp(4), 0, 0)
        setTextColor(0xFFFFCC80.toInt())
    }
    private var lastFailure: UiActionRecovery.Block? = null
    private val recoveryRefresh = object : Runnable {
        override fun run() {
            renderRecovery()
            if (isAttachedToWindow) postDelayed(this, 1_000L)
        }
    }
    private val rows: List<Row>

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(TextView(context).apply {
            text = "MODEL — PRAWDOPODOBIEŃSTWA"
            textSize = 10f
            setTextColor(Color.WHITE)
        })
        rows = listOf(
            addRow("Wzrost", 0xFF66BB6A.toInt()),
            addRow("Spadek", 0xFFEF5350.toInt()),
            addRow("Neutralny", 0xFF90A4AE.toInt())
        )
        addView(status)
        addView(recoveryStatus)
        showPrediction(null)
        renderRecovery()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(recoveryRefresh)
        post(recoveryRefresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(recoveryRefresh)
        super.onDetachedFromWindow()
    }

    private fun renderRecovery() {
        val active = UiActionRecovery.blocked
        if (active != null) lastFailure = active
        recoveryStatus.text = buildString {
            append("Wersja: ").append(BuildConfig.GIT_SHA)
            when {
                active != null -> {
                    append("\nBILET — NOWE WEJŚCIA ZABLOKOWANE")
                    active.instrument?.let { append(" (").append(it).append(')') }
                    append("\n").append(active.reason)
                }
                lastFailure != null -> {
                    append("\nBrak aktywnej blokady. Ostatni błąd tej sesji:")
                    append("\n").append(lastFailure?.reason)
                }
                else -> append("\nBilet: brak zgłoszonej blokady. Nie oznacza to potwierdzonego resetu.")
            }
        }
    }

    private fun addRow(label: String, color: Int): Row {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(color)
        }, LayoutParams(dp(62), LayoutParams.WRAP_CONTENT))
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF333333.toInt())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(bar, LayoutParams(0, dp(8), 1f))
        val value = TextView(context).apply {
            textSize = 10f
            gravity = Gravity.END
            setTextColor(Color.WHITE)
        }
        row.addView(value, LayoutParams(dp(46), LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(17)))
        return Row(bar, value)
    }

    /** Call on the main thread only, with a separately validated current prediction. */
    fun showPrediction(prediction: DirectionModel.Prediction?) {
        val values = prediction?.let { listOf(it.up, it.down, it.neutral) }
        val valid = values != null && values.all { it.isFinite() && it in 0.0..1.0 } &&
            abs(values.sum() - 1.0) < 1e-6
        rows.forEachIndexed { index, row ->
            row.bar.progress = if (valid) (values!![index] * 1000).roundToInt() else 0
            row.bar.alpha = if (valid) 1f else 0.4f
            row.value.text = if (valid) String.format(Locale.ROOT, "%.1f%%", values!![index] * 100) else "—"
            row.value.contentDescription = "${listOf("Wzrost", "Spadek", "Neutralny")[index]}: ${row.value.text}"
        }
        status.text = when {
            prediction == null -> "Brak prognozy modelu. Trening offline nie zasila jeszcze HUD."
            !valid -> "Nieprawidłowa prognoza — słupki wyczyszczone."
            else -> "Podgląd modelu, nie polecenie transakcji."
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
