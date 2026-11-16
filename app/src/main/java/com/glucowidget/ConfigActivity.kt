package com.glucowidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var tgToken: EditText
    private lateinit var tgChat: EditText
    private lateinit var urgentLow: EditText
    private lateinit var alertsEnabled: CheckBox
    private lateinit var tgStatus: TextView

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

        tgToken = findViewById(R.id.tg_token)
        tgChat = findViewById(R.id.tg_chat)
        urgentLow = findViewById(R.id.urgent_low)
        alertsEnabled = findViewById(R.id.alerts_enabled)
        tgStatus = findViewById(R.id.tg_status)
        tgToken.setText(prefs.tgToken.orEmpty())
        tgChat.setText(prefs.tgChatId.orEmpty())
        urgentLow.setText(prefs.urgentLowMgdl.toString())
        alertsEnabled.isChecked = prefs.alertsEnabled
        findViewById<Button>(R.id.tg_find).setOnClickListener { findChat() }
        findViewById<Button>(R.id.tg_test).setOnClickListener { sendTest() }

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

    private fun findChat() {
        val token = tgToken.text.toString().trim()
        if (token.isEmpty()) {
            tgStatus.text = "Primero pega el token del bot"
            return
        }
        tgStatus.setText(R.string.testing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Telegram.findChat(token) } }
            result.onSuccess { chat ->
                if (chat == null) {
                    tgStatus.text = "Nadie le ha escrito al bot. Ábrelo en Telegram, envíale /start y vuelve a intentarlo."
                } else {
                    tgChat.setText(chat.first)
                    alertsEnabled.isChecked = true
                    tgStatus.text = "Chat encontrado: ${chat.second}. Pulsa «Enviar mensaje de prueba»."
                }
            }.onFailure { tgStatus.text = it.message ?: "Error al hablar con Telegram" }
        }
    }

    private fun sendTest() {
        val token = tgToken.text.toString().trim()
        val chat = tgChat.text.toString().trim()
        if (token.isEmpty() || chat.isEmpty()) {
            tgStatus.text = "Falta el token o el ID del chat"
            return
        }
        tgStatus.setText(R.string.testing)
        val reading = prefs.lastReading
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val now = Alerts.message(prefs, Alerts.stateFor(prefs, reading), reading)
                    Telegram.send(token, chat, "👋 Prueba de GlucoWidget. Así llegarán las alertas.\nAhora: $now")
                }
            }
            tgStatus.text = result.fold({ "Mensaje enviado ✓ Revisa Telegram y tu reloj." }, { it.message ?: "Error" })
        }
    }

    private fun save() {
        storeCredentials()
        prefs.tgToken = tgToken.text.toString().trim().ifEmpty { null }
        prefs.tgChatId = tgChat.text.toString().trim().ifEmpty { null }
        urgentLow.text.toString().toIntOrNull()?.let { prefs.urgentLowMgdl = it }
        prefs.alertsEnabled = alertsEnabled.isChecked && prefs.tgToken != null && prefs.tgChatId != null
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
