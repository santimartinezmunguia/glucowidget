package com.glucowidget

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Cliente mínimo de la Bot API de Telegram. */
object Telegram {

    private const val TIMEOUT_MS = 15_000

    fun send(token: String, chatId: String, text: String) {
        val body = JSONObject().put("chat_id", chatId).put("text", text)
        val json = call(token, "sendMessage", body)
        if (!json.optBoolean("ok")) {
            throw LluException("Telegram: " + json.optString("description", "error desconocido"))
        }
    }

    /**
     * Busca el chat de la última persona que escribió al bot (hay que mandarle /start antes).
     * Devuelve id y nombre, o null si nadie le ha escrito.
     */
    fun findChat(token: String): Pair<String, String>? {
        val json = call(token, "getUpdates", null)
        if (!json.optBoolean("ok")) {
            throw LluException("Telegram: " + json.optString("description", "token inválido"))
        }
        val updates = json.optJSONArray("result") ?: return null
        for (i in updates.length() - 1 downTo 0) {
            val chat = updates.getJSONObject(i).optJSONObject("message")?.optJSONObject("chat") ?: continue
            val name = chat.optString("first_name").ifBlank { chat.optString("title") }
            return chat.get("id").toString() to name
        }
        return null
    }

    private fun call(token: String, method: String, body: JSONObject?): JSONObject {
        val conn = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IOException("Telegram respondió HTTP $code sin contenido")
            return try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw LluException("Telegram: respuesta inválida (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }
}
