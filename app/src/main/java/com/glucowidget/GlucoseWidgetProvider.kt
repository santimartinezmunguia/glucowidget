package com.glucowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class GlucoseWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.glucowidget.ACTION_REFRESH"
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // El trabajo periódico (KEEP) mantiene registrado el receptor de WorkManager,
        // lo que evita el bucle conocido de onUpdate <-> WorkManager.
        RefreshWorker.schedulePeriodic(context)
        WidgetRenderer.renderAll(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            RefreshWorker.refreshNow(context)
        }
    }
}
