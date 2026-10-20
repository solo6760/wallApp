package org.ambientflow.tv

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("ambient", Context.MODE_PRIVATE)
    fun str(key: String, default: String) = p.getString(key, default) ?: default
    fun bool(key: String, default: Boolean = false) = p.getBoolean(key, default)
    fun int(key: String, default: Int) = p.getInt(key, default)
    fun put(key: String, value: String) { p.edit().putString(key,value).apply() }
    fun put(key: String, value: Boolean) { p.edit().putBoolean(key,value).apply() }
    fun put(key: String, value: Int) { p.edit().putInt(key,value).apply() }
    fun cities(): Set<String> = str("cities", "Tokyo,New York,Hong Kong,Singapore,London,Chicago,Seoul").split(',').filter { it.isNotBlank() }.toSet()
    companion object { const val MUSIC = "https://www.youtube.com/watch?v=rFZHOHl-L8A" }
}
