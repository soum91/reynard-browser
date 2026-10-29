package com.soum91.reynardtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val url: String, val title: String, val time: Long)

/** Local, private-tab-excluded history. Firefox history synchronization is separate. */
class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("reynard_history", Context.MODE_PRIVATE)

    fun all(): List<HistoryEntry> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                HistoryEntry(value.optString("url"), value.optString("title"), value.optLong("time"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun record(url: String, title: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val existing = all()
        if (existing.firstOrNull()?.url == url) return
        save(listOf(HistoryEntry(url, title, System.currentTimeMillis())) + existing.filterNot { it.url == url }.take(499))
    }

    fun updateTitle(url: String, title: String) {
        val items = all()
        if (title.isNotBlank() && items.any { it.url == url && it.title != title }) {
            save(items.map { if (it.url == url) it.copy(title = title) else it })
        }
    }

    fun clear() = prefs.edit().remove("entries").apply()

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time)) }
        prefs.edit().putString("entries", array.toString()).apply()
    }
}
