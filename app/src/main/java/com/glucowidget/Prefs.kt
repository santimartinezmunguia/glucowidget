package com.glucowidget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Ajustes y caché, guardados cifrados con una clave del Android Keystore. */
class Prefs(context: Context) {

    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "llu_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var email: String?
        get() = sp.getString("email", null)
        set(v) = sp.edit { putString("email", v) }

    var password: String?
        get() = sp.getString("password", null)
        set(v) = sp.edit { putString("password", v) }

    var patientId: String?
        get() = sp.getString("patientId", null)
        set(v) = sp.edit { putString("patientId", v) }

    var useMmol: Boolean
        get() = sp.getBoolean("useMmol", false)
        set(v) = sp.edit { putBoolean("useMmol", v) }

    var lowMgdl: Int
        get() = sp.getInt("low", 70)
        set(v) = sp.edit { putInt("low", v) }

    var highMgdl: Int
        get() = sp.getInt("high", 180)
        set(v) = sp.edit { putInt("high", v) }

    var lastError: String?
        get() = sp.getString("lastError", null)
        set(v) = sp.edit { putString("lastError", v) }

    // --- Sesión de LibreLinkUp (para no iniciar sesión en cada actualización) ---

    var session: Session?
        get() {
            val base = sp.getString("s_base", null) ?: return null
            val token = sp.getString("s_token", null) ?: return null
            val account = sp.getString("s_account", null) ?: return null
            return Session(base, token, account, sp.getLong("s_expires", 0))
        }
        set(s) = sp.edit {
            putString("s_base", s?.baseUrl)
            putString("s_token", s?.token)
            putString("s_account", s?.accountId)
            putLong("s_expires", s?.expiresEpochSec ?: 0)
        }

    /** Último servidor regional conocido, se conserva aunque la sesión caduque. */
    var baseUrl: String?
        get() = sp.getString("baseUrl", null)
        set(v) = sp.edit { putString("baseUrl", v) }

    // --- Última lectura, para mostrarla aunque falle la red ---

    var lastReading: Reading?
        get() {
            if (!sp.contains("r_mgdl")) return null
            return Reading(
                mgdl = sp.getInt("r_mgdl", 0),
                trend = sp.getInt("r_trend", 0),
                timestampMs = sp.getLong("r_ts", 0),
            )
        }
        set(r) = sp.edit {
            if (r == null) {
                remove("r_mgdl"); remove("r_trend"); remove("r_ts")
            } else {
                putInt("r_mgdl", r.mgdl)
                putInt("r_trend", r.trend)
                putLong("r_ts", r.timestampMs)
            }
        }
}
