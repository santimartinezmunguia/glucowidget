package com.glucowidget

import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LluException(message: String) : Exception(message)

data class Session(
    val baseUrl: String,
    val token: String,
    val accountId: String,
    val expiresEpochSec: Long,
)

data class Reading(
    val mgdl: Int,
    /** 1 = baja rápido, 2 = baja, 3 = estable, 4 = sube, 5 = sube rápido, 0 = desconocida. */
    val trend: Int,
    val timestampMs: Long,
)

data class Connection(val patientId: String, val name: String, val reading: Reading?)

/**
 * Cliente mínimo de la API (no oficial) que usa la app LibreLinkUp.
 * Abbott puede cambiarla sin aviso; si deja de funcionar, lo primero que
 * suele hacer falta es subir [APP_VERSION] a la versión actual de la app.
 */
class LluClient(private val prefs: Prefs) {

    companion object {
        const val DEFAULT_BASE = "https://api.libreview.io"
        const val PRODUCT = "llu.android"
        const val APP_VERSION = "4.16.0"
        private const val TIMEOUT_MS = 15_000
    }

    /** Inicia sesión siguiendo la redirección regional si la hay. */
    fun login(email: String, password: String): Session {
        var base = prefs.baseUrl ?: DEFAULT_BASE
        val body = JSONObject().put("email", email).put("password", password)

        repeat(3) {
            val (code, json) = request(base, "/llu/auth/login", "POST", body, null)
            if (code == 429) throw LluException("Demasiados intentos; espera unos minutos")
            if (json == null) throw LluException("Respuesta inválida del servidor (HTTP $code)")

            val data = json.optJSONObject("data")
            if (data != null && data.optBoolean("redirect", false)) {
                val region = data.optString("region")
                if (region.isEmpty()) throw LluException("Redirección regional sin región")
                base = "https://api-$region.libreview.io"
                return@repeat
            }

            when (val status = json.optInt("status", -1)) {
                0 -> {
                    val ticket = data?.optJSONObject("authTicket")
                    val userId = data?.optJSONObject("user")?.optString("id")
                    val token = ticket?.optString("token")
                    if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
                        throw LluException("Inicio de sesión incompleto; abre la app LibreLinkUp y revisa avisos pendientes")
                    }
                    prefs.baseUrl = base
                    return Session(base, token, sha256Hex(userId), ticket?.optLong("expires", 0) ?: 0)
                }
                2 -> throw LluException("Correo o contraseña incorrectos")
                4 -> throw LluException("Abre la app LibreLinkUp y acepta los términos o avisos pendientes")
                else -> {
                    val msg = json.optJSONObject("error")?.optString("message")
                    throw LluException(if (msg.isNullOrEmpty()) "Error de inicio de sesión (estado $status)" else msg)
                }
            }
        }
        throw LluException("Demasiadas redirecciones regionales")
    }

    /** Devuelve las personas que sigues, con su última lectura. */
    fun fetchConnections(): List<Connection> {
        val email = prefs.email
        val password = prefs.password
        if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
            throw LluException("Abre GlucoWidget y configura tu cuenta")
        }

        val nowSec = System.currentTimeMillis() / 1000
        var session = prefs.session?.takeIf { it.expiresEpochSec > nowSec + 60 }
            ?: login(email, password).also { prefs.session = it }

        var (code, json) = request(session.baseUrl, "/llu/connections", "GET", null, session)
        if (code == 401 || code == 403) {
            // Token caducado o revocado: una nueva sesión y un solo reintento.
            session = login(email, password).also { prefs.session = it }
            val retry = request(session.baseUrl, "/llu/connections", "GET", null, session)
            code = retry.first
            json = retry.second
        }
        if (code == 429) throw LluException("LibreView limita las peticiones; espera unos minutos")
        if (code !in 200..299 || json == null) throw LluException("Error al leer datos (HTTP $code)")

        val arr = json.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Connection(
                patientId = c.optString("patientId"),
                name = listOf(c.optString("firstName"), c.optString("lastName"))
                    .filter { it.isNotBlank() }.joinToString(" "),
                reading = c.optJSONObject("glucoseMeasurement")?.let(::parseReading),
            )
        }
    }

    private fun parseReading(m: JSONObject): Reading? {
        val mgdl = m.optDouble("ValueInMgPerDl", Double.NaN)
        if (mgdl.isNaN()) return null
        return Reading(
            mgdl = Math.round(mgdl).toInt(),
            trend = m.optInt("TrendArrow", 0),
            timestampMs = parseFactoryTimestamp(m.optString("FactoryTimestamp")),
        )
    }

    /** FactoryTimestamp está en UTC con formato "M/d/yyyy h:mm:ss a". */
    private fun parseFactoryTimestamp(s: String): Long {
        if (s.isEmpty()) return 0
        return try {
            SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(s)?.time ?: 0
        } catch (e: java.text.ParseException) {
            0
        }
    }

    private fun request(
        base: String,
        path: String,
        method: String,
        body: JSONObject?,
        session: Session?,
    ): Pair<Int, JSONObject?> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("product", PRODUCT)
            conn.setRequestProperty("version", APP_VERSION)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (session != null) {
                conn.setRequestProperty("Authorization", "Bearer ${session.token}")
                conn.setRequestProperty("Account-Id", session.accountId)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val json = try {
                if (text.isNullOrBlank()) null else JSONObject(text)
            } catch (e: JSONException) {
                null
            }
            return code to json
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
