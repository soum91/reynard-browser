package com.soum91.reynardtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

/** Local bookmarks, kept in SharedPreferences until Firefox Sync lands. */
class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun all(): List<Bookmark> {
        val raw = prefs.getString("items", null) ?: return defaults
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Bookmark(item.optString("title"), item.optString("url"))
        }
    }

    fun contains(url: String) = all().any { it.url == url }

    fun toggle(title: String, url: String) {
        val items = all().toMutableList()
        if (!items.removeAll { it.url == url }) {
            items.add(0, Bookmark(title.ifBlank { url }, url))
        }
        save(items)
    }

    fun remove(url: String) {
        save(all().filterNot { it.url == url })
    }

    private fun save(items: List<Bookmark>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private val defaults = listOf(
        Bookmark("YouTube", "https://www.youtube.com/"),
        Bookmark("Google", "https://www.google.com/"),
        Bookmark("Twitch", "https://www.twitch.tv/"),
        Bookmark("Reddit", "https://www.reddit.com/"),
    )
}
