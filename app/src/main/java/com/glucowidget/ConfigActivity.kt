package com.glucowidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ConfigActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var connections: List<Connection> = emptyList()

    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var units: RadioGroup
    private lateinit var low: EditText
    private lateinit var high: EditText
    private lateinit var status: TextView
    private lateinit var patientLabel: TextView
    private lateinit var patient: Spinner
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        prefs = Prefs(this)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Si se cierra sin guardar, el widget no se añade.
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) setResult(RESULT_CANCELED, resultIntent())

        email = findViewById(R.id.email)
        password = findViewById(R.id.password)
        units = findViewById(R.id.units)
        low = findViewById(R.id.low)
        high = findViewById(R.id.high)
        status = findViewById(R.id.status)
        patientLabel = findViewById(R.id.patient_label)
        patient = findViewById(R.id.patient)
        testButton = findViewById(R.id.test)

        email.setText(prefs.email.orEmpty())
        password.setText(prefs.password.orEmpty())
        units.check(if (prefs.useMmol) R.id.unit_mmol else R.id.unit_mgdl)
        low.setText(prefs.lowMgdl.toString())
        high.setText(prefs.highMgdl.toString())

        testButton.setOnClickListener { testConnection() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }

        patient.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                connections.getOrNull(pos)?.let { prefs.patientId = it.patientId }
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun storeCredentials() {
        val newEmail = email.text.toString().trim()
        val newPassword = password.text.toString()
        if (newEmail != prefs.email || newPassword != prefs.password) {
            prefs.session = null
            prefs.baseUrl = null
            prefs.lastReading = null
        }
        prefs.email = newEmail
        prefs.password = newPassword
    }

    private fun testConnection() {
        storeCredentials()
        status.setText(R.string.testing)
        testButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(LluClient(prefs).fetchConnections())
                } catch (e: LluException) {
                    Result.failure(e)
                } catch (e: IOException) {
                    Result.failure(LluException("Sin conexión: ${e.message}"))
                }
            }
            testButton.isEnabled = true
            result.onSuccess { list ->
                connections = list
                if (list.isEmpty()) {
                    status.text = "Conectado, pero no sigues a nadie en LibreLinkUp."
                    return@onSuccess
                }
                val labels = list.map { c ->
                    val r = c.reading?.let { " — ${it.mgdl} mg/dL" }.orEmpty()
                    c.name.ifBlank { c.patientId } + r
                }
                patient.adapter = ArrayAdapter(
                    this@ConfigActivity, android.R.layout.simple_spinner_dropdown_item, labels,
                )
                val selected = list.indexOfFirst { it.patientId == prefs.patientId }.coerceAtLeast(0)
                patient.setSelection(selected)
                prefs.patientId = list[selected].patientId
                patientLabel.visibility = View.VISIBLE
                patient.visibility = View.VISIBLE
                status.text = "Conectado ✓"
            }.onFailure { status.text = it.message }
        }
    }

    private fun save() {
        storeCredentials()
        prefs.useMmol = units.checkedRadioButtonId == R.id.unit_mmol
        low.text.toString().toIntOrNull()?.let { prefs.lowMgdl = it }
        high.text.toString().toIntOrNull()?.let { prefs.highMgdl = it }
        prefs.lastError = null

        WidgetRenderer.renderAll(this)
        RefreshWorker.schedulePeriodic(this)
        RefreshWorker.refreshNow(this)

        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, resultIntent())
        }
        finish()
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
