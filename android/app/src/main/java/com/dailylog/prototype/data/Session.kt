package com.dailylog.prototype.data

import android.content.Context

/** The signed-in phone number and its token. Prototype: plain app-private preferences. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)
    val phone: String? get() = prefs.getString("phone", null)

    fun save(token: String, phone: String) {
        prefs.edit().putString("token", token).putString("phone", phone).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
