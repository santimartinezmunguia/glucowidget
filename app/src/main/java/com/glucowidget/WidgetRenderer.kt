package com.glucowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object WidgetRenderer {

    private const val STALE_MS = 20 * 60 * 1000L

    private val COLOR_LOW = Color.parseColor("#E53935")
    private val COLOR_HIGH = Color.parseColor("#FB8C00")
    private val COLOR_OK = Color.parseColor("#43A047")
    private val COLOR_STALE = Color.parseColor("#9E9E9E")

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, GlucoseWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, build(context))
    }

    private fun build(context: Context): RemoteViews {
        val prefs = Prefs(context)
        val views = RemoteViews(context.packageName, R.layout.widget_glucose)

        // Tocar el widget fuerza una actualización.
        val refresh = Intent(context, GlucoseWidgetProvider::class.java)
            .setAction(GlucoseWidgetProvider.ACTION_REFRESH)
        views.setOnClickPendingIntent(
            R.id.root,
            PendingIntent.getBroadcast(
                context, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val reading = prefs.lastReading
        val error = prefs.lastError
        val unit = if (prefs.useMmol) "mmol/L" else "mg/dL"

        if (reading == null) {
            views.setTextViewText(R.id.value, context.getString(R.string.placeholder_value))
            views.setTextViewText(R.id.arrow, "")
            views.setTextViewText(R.id.info, error ?: context.getString(R.string.tap_to_configure))
            views.setInt(R.id.dot, "setColorFilter", COLOR_STALE)
            return views
        }

        val stale = System.currentTimeMillis() - reading.timestampMs > STALE_MS
        val color = when {
            stale -> COLOR_STALE
            reading.mgdl < prefs.lowMgdl -> COLOR_LOW
            reading.mgdl > prefs.highMgdl -> COLOR_HIGH
            else -> COLOR_OK
        }

        val value = if (prefs.useMmol) {
            String.format(Locale.getDefault(), "%.1f", reading.mgdl / 18.0182)
        } else {
            reading.mgdl.toString()
        }
        val time = if (reading.timestampMs > 0) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reading.timestampMs))
        } else {
            "?"
        }

        views.setTextViewText(R.id.value, value)
        views.setTextViewText(R.id.arrow, arrowFor(reading.trend))
        views.setInt(R.id.dot, "setColorFilter", color)
        views.setTextColor(R.id.value, if (stale) COLOR_STALE else Color.WHITE)
        views.setTextViewText(
            R.id.info,
            if (error != null) "$unit · $time\n$error" else "$unit · $time",
        )
        return views
    }

    private fun arrowFor(trend: Int): String = when (trend) {
        1 -> "↓"
        2 -> "↘"
        3 -> "→"
        4 -> "↗"
        5 -> "↑"
        else -> ""
    }
}
