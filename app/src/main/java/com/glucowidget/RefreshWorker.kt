package com.glucowidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Descarga la última lectura y redibuja los widgets. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        try {
            val connections = LluClient(prefs).fetchConnections()
            val conn = connections.firstOrNull { it.patientId == prefs.patientId }
                ?: connections.firstOrNull()
                ?: throw LluException("No sigues a nadie en LibreLinkUp")
            val reading = conn.reading ?: throw LluException("Sin lectura disponible")
            prefs.lastReading = reading
            prefs.lastError = null
        } catch (e: LluException) {
            prefs.lastError = e.message
        } catch (e: IOException) {
            prefs.lastError = "Sin conexión"
        } finally {
            WidgetRenderer.renderAll(applicationContext)
        }
        Result.success()
    }

    companion object {
        private const val PERIODIC = "glucose-periodic"
        private const val NOW = "glucose-now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Android no permite trabajos periódicos de menos de 15 minutos. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(NOW)
            }
        }
    }
}
