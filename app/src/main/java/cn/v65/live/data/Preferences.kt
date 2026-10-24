package cn.v65.live.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("living-room", Context.MODE_PRIVATE)
    var softwareDecoder: Boolean
        get() = prefs.getBoolean("software-decoder", true)
        set(value) { prefs.edit().putBoolean("software-decoder", value).apply() }
    var lastChannel: String?
        get() = prefs.getString("last-channel", null)
        set(value) { prefs.edit().putString("last-channel", value).apply() }
    val favorites: Set<String> get() = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    fun toggleFavorite(id: String): Boolean {
        val values = favorites.toMutableSet()
        val added = if (values.remove(id)) false else { values.add(id); true }
        prefs.edit().putStringSet("favorites", values).apply()
        return added
    }
    val recents: List<String> get() = runCatching {
        val array = JSONArray(prefs.getString("recents", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
    fun opened(id: String) {
        lastChannel = id
        prefs.edit().putString("recents", JSONArray((listOf(id) + recents).distinct().take(20)).toString()).apply()
    }
    fun record(channel: Channel, mode: String, reason: String) {
        val entries = runCatching { JSONArray(prefs.getString("diagnostics", "[]")) }.getOrDefault(JSONArray())
        val bounded = JSONArray()
        for (index in (entries.length() - 79).coerceAtLeast(0) until entries.length()) bounded.put(entries.get(index))
        bounded.put(JSONObject().put("time", BeijingTime.timestamp()).put("channel", channel.name)
            .put("mode", mode).put("reason", reason.take(180)))
        prefs.edit().putString("diagnostics", bounded.toString()).apply()
    }
    fun diagnostics(): String = runCatching {
        val entries = JSONArray(prefs.getString("diagnostics", "[]"))
        (0 until entries.length()).reversed().take(20).joinToString("\n\n") { i ->
            val entry = entries.getJSONObject(i)
            "${entry.optString("time")}  ${entry.optString("channel")}\n${entry.optString("mode")} · ${entry.optString("reason")}"
        }.ifBlank { "尚无播放测试记录" }
    }.getOrDefault("尚无播放测试记录")
}
