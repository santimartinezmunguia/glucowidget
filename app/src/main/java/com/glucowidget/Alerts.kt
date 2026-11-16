package com.glucowidget

import java.util.Locale

enum class AlertState(val repeatMinutes: Long) {
    NORMAL(0),
    URGENT_LOW(10),
    LOW(20),
    HIGH(60),
    STALE(60),
}

/** Decide si hay que mandar un aviso por Telegram y lo envía. */
object Alerts {

    private const val STALE_MS = 20 * 60 * 1000L

    fun stateFor(prefs: Prefs, reading: Reading?, now: Long = System.currentTimeMillis()): AlertState = when {
        reading == null || reading.timestampMs == 0L || now - reading.timestampMs > STALE_MS -> AlertState.STALE
        reading.mgdl < prefs.urgentLowMgdl -> AlertState.URGENT_LOW
        reading.mgdl < prefs.lowMgdl -> AlertState.LOW
        reading.mgdl > prefs.highMgdl -> AlertState.HIGH
        else -> AlertState.NORMAL
    }

    /** Llamar tras cada actualización. Errores de Telegram se ignoran (se reintentará la próxima vez). */
    fun check(prefs: Prefs, reading: Reading?) {
        if (!prefs.alertsEnabled) return
        val token = prefs.tgToken ?: return
        val chat = prefs.tgChatId ?: return

        val now = System.currentTimeMillis()
        val state = stateFor(prefs, reading, now)
        val previous = prefs.alertState?.let { runCatching { AlertState.valueOf(it) }.getOrNull() }
            ?: AlertState.NORMAL

        val text: String? = when {
            state == AlertState.NORMAL && previous != AlertState.NORMAL ->
                "✅ De vuelta en rango: ${describe(prefs, reading)}"
            state == AlertState.NORMAL -> null
            state != previous -> message(prefs, state, reading)
            now - prefs.alertSentAt >= state.repeatMinutes * 60_000 -> message(prefs, state, reading)
            else -> null
        }
        if (text == null) {
            prefs.alertState = state.name
            return
        }
        try {
            Telegram.send(token, chat, text)
            prefs.alertState = state.name
            prefs.alertSentAt = now
        } catch (e: Exception) {
            // No guardamos el estado: se volverá a intentar en la siguiente actualización.
        }
    }

    fun message(prefs: Prefs, state: AlertState, reading: Reading?): String = when (state) {
        AlertState.URGENT_LOW -> "🚨 GLUCOSA MUY BAJA: ${describe(prefs, reading)}"
        AlertState.LOW -> "🔴 Glucosa baja: ${describe(prefs, reading)}"
        AlertState.HIGH -> "🟠 Glucosa alta: ${describe(prefs, reading)}"
        AlertState.STALE -> "⚪ Sin lecturas recientes de LibreLinkUp" +
            (reading?.let { " (última: ${describe(prefs, it)})" } ?: "")
        AlertState.NORMAL -> "✅ En rango: ${describe(prefs, reading)}"
    }

    private fun describe(prefs: Prefs, r: Reading?): String {
        if (r == null) return "sin datos"
        val value = if (prefs.useMmol) {
            String.format(Locale.getDefault(), "%.1f mmol/L", r.mgdl / 18.0182)
        } else {
            "${r.mgdl} mg/dL"
        }
        val arrow = WidgetRenderer.arrowFor(r.trend)
        val mins = if (r.timestampMs > 0) (System.currentTimeMillis() - r.timestampMs) / 60_000 else -1
        val ago = if (mins >= 0) " (hace $mins min)" else ""
        return "$value $arrow$ago".replace("  ", " ")
    }
}
