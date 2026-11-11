package com.opentts.engine

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of the last synthesis failures, shown in the app's settings screen.
 *
 * Reading apps report "speech error" and nothing else, and the failure is often
 * on a device the developer cannot reach, so the engine records *why* it gave up
 * and the user can read it without adb.
 */
object TtsDiagnostics {

    private const val TAG = "OpenTtsDiag"
    private const val PREFS = "opentts_diag"
    private const val KEY = "recent"
    private const val MAX_LINES = 12
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun record(context: Context, reason: String) {
        Log.w(TAG, reason)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, "").orEmpty()
        val line = "${stamp.format(Date())}  $reason"
        val lines = (listOf(line) + existing.lines().filter { it.isNotBlank() }).take(MAX_LINES)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
