package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** One recorded successful replacement — the durable core of the history timeline feature. */
data class HistoryEntry(
    val trigger: String,
    val appPackage: String?,
    val timestamp: Long,
    val success: Boolean
)

class StatsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOTAL = "total_requests"
        private const val KEY_MONTHLY = "monthly_requests"
        private const val KEY_MONTH = "current_month"
        private const val KEY_COMMAND_COUNTS = "command_counts"
        private const val KEY_DAILY_COUNTS = "daily_counts"
        private const val KEY_RECENT_HISTORY = "recent_history"
        private const val MAX_HISTORY_ENTRIES = 50
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
    private fun currentMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(System.currentTimeMillis())

    /** Parses a stored JSON object, resetting to empty on corruption instead of crashing. */
    private fun readJsonObject(key: String): JSONObject =
        try { JSONObject(prefs.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /**
     * Typed prefs reads can throw ClassCastException when a key was ever written with a
     * different type (corruption, backup/restore). This manager is read on the accessibility
     * service's bind path and from the Dashboard — an escape there kills the whole process.
     */
    private fun safeInt(key: String, def: Int): Int =
        try { prefs.getInt(key, def) } catch (_: Exception) { def }

    private fun safeString(key: String, def: String?): String? =
        try { prefs.getString(key, def) } catch (_: Exception) { def }

    /**
     * Call after a command is successfully processed.
     *
     * [appPackage] and [success] also feed the recent-history ring: a failed attempt is
     * recorded too (the timeline distinguishes it visually), but a record with a null package
     * (older callers, or hosts that do not report one) means simply "unknown app".
     */
    @Synchronized
    fun recordUsage(commandName: String, appPackage: String? = null, success: Boolean = true) {
        val editor = prefs.edit()

        // Total
        val total = safeInt(KEY_TOTAL, 0) + 1
        editor.putInt(KEY_TOTAL, total)

        // Monthly — reset on rollover
        val storedMonth = safeString(KEY_MONTH, null)
        val month = currentMonth()
        val monthly = if (storedMonth == month) safeInt(KEY_MONTHLY, 0) + 1 else 1
        editor.putString(KEY_MONTH, month)
        editor.putInt(KEY_MONTHLY, monthly)

        // Per-command counts
        val cmdJson = readJsonObject(KEY_COMMAND_COUNTS)
        cmdJson.put(commandName, cmdJson.optInt(commandName, 0) + 1)
        editor.putString(KEY_COMMAND_COUNTS, cmdJson.toString())

        // Daily counts — keep last 7 days
        val dailyJson = readJsonObject(KEY_DAILY_COUNTS)
        val day = today()
        dailyJson.put(day, dailyJson.optInt(day, 0) + 1)
        // Prune old entries
        val keys = dailyJson.keys().asSequence().sorted().toList()
        if (keys.size > 7) {
            keys.dropLast(7).forEach { dailyJson.remove(it) }
        }
        editor.putString(KEY_DAILY_COUNTS, dailyJson.toString())

        // Recent history — a capped ring so a year of typing never grows the pref beyond
        // a handful of KB. Failures are kept (marked) so the timeline explains gaps where
        // "the app didn't change the text at all".
        run {
            val json = try { JSONArray(prefs.getString(KEY_RECENT_HISTORY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            val entry = JSONObject()
            entry.put("t", System.currentTimeMillis())
            entry.put("c", commandName)
            if (appPackage != null) entry.put("p", appPackage)
            entry.put("ok", success)
            json.put(entry)
            while (json.length() > MAX_HISTORY_ENTRIES) {
                json.remove(0)  // oldest at index 0
            }
            editor.putString(KEY_RECENT_HISTORY, json.toString())
        }

        editor.apply()
    }

    /** Newest-first. Corrupt history degrades to empty — never wakes the process up sad. */
    fun recentHistory(): List<HistoryEntry> {
        return try {
            val j = JSONArray(prefs.getString(KEY_RECENT_HISTORY, "[]") ?: "[]")
            (j.length() - 1 downTo 0).mapNotNull { i ->
                val e = j.optJSONObject(i) ?: return@mapNotNull null
                val trigger = e.optString("c", "")
                if (trigger.isEmpty()) return@mapNotNull null
                HistoryEntry(
                    trigger = trigger,
                    appPackage = e.optString("p", "").ifEmpty { null },
                    timestamp = e.optLong("t", 0L),
                    success = e.optBoolean("ok", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearHistory() {
        prefs.edit().putString(KEY_RECENT_HISTORY, "[]").apply()
    }

    val totalRequests: Int get() = safeInt(KEY_TOTAL, 0)

    val monthlyRequests: Int
        get() {
            if (safeString(KEY_MONTH, null) != currentMonth()) return 0
            return safeInt(KEY_MONTHLY, 0)
        }

    /** Returns the command name with the highest usage, or null. */
    val favoriteCommand: String?
        get() {
            val json = readJsonObject(KEY_COMMAND_COUNTS)
            var best: String? = null
            var bestCount = 0
            json.keys().forEach { key ->
                val count = json.optInt(key, 0)
                if (count > bestCount) { bestCount = count; best = key }
            }
            return best
        }

    /** Returns daily counts for the last 7 days, ordered oldest-first. Missing days are 0. */
    fun dailyCounts(): List<Pair<String, Int>> {
        val json = readJsonObject(KEY_DAILY_COUNTS)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val result = mutableListOf<Pair<String, Int>>()
        // Go back 6 days from today
        cal.add(java.util.Calendar.DAY_OF_YEAR, -6)
        repeat(7) {
            val day = dayFmt.format(cal.time)
            result.add(day to json.optInt(day, 0))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }
}
