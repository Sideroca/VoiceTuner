package com.sideroca.voicetuner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一条生成记录 */
data class Take(
    val id: String,
    val fileName: String,
    val voiceName: String,
    val voiceId: String,
    val text: String,
    val instruction: String,
    val rate: Double,
    val pitch: Double,
    val volume: Int,
    val seed: Int,
    val format: String,
    val durationMs: Long,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("voiceName", voiceName)
        put("voiceId", voiceId)
        put("text", text)
        put("instruction", instruction)
        put("rate", rate)
        put("pitch", pitch)
        put("volume", volume)
        put("seed", seed)
        put("format", format)
        put("durationMs", durationMs)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Take = Take(
            id = o.optString("id"),
            fileName = o.optString("fileName"),
            voiceName = o.optString("voiceName"),
            voiceId = o.optString("voiceId"),
            text = o.optString("text"),
            instruction = o.optString("instruction"),
            rate = o.optDouble("rate", 1.0),
            pitch = o.optDouble("pitch", 1.0),
            volume = o.optInt("volume", 50),
            seed = o.optInt("seed", 0),
            format = o.optString("format", "wav24"),
            durationMs = o.optLong("durationMs", 0),
            createdAt = o.optLong("createdAt", 0)
        )
    }
}

/** 自建复刻音色 */
data class CustomVoice(
    val id: String,
    val name: String,
    val prefix: String,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("prefix", prefix)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): CustomVoice = CustomVoice(
            id = o.optString("id"),
            name = o.optString("name"),
            prefix = o.optString("prefix"),
            createdAt = o.optLong("createdAt", 0)
        )
    }
}

/** 本机存储：设置 + 历史记录 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("vt", Context.MODE_PRIVATE)
    private val dir: File = File(context.filesDir, "history").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val voicesFile = File(context.filesDir, "custom_voices.json")

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) { prefs.edit().putString("apiKey", v).apply() }

    var workspace: String
        get() = prefs.getString("workspace", "ws-9y8n1gp7w6pg23tv") ?: "ws-9y8n1gp7w6pg23tv"
        set(v) { prefs.edit().putString("workspace", v).apply() }

    var lastModel: String
        get() = prefs.getString("model", "cosyvoice-v3.5-plus") ?: "cosyvoice-v3.5-plus"
        set(v) { prefs.edit().putString("model", v).apply() }

    fun newAudioFile(ext: String): File =
        File(dir, "vt_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6) + "." + ext)

    fun fileOf(take: Take): File = File(dir, take.fileName)

    fun deleteFile(take: Take) {
        try {
            fileOf(take).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun loadTakes(): MutableList<Take> {
        val list = mutableListOf<Take>()
        try {
            if (indexFile.exists()) {
                val arr = JSONArray(indexFile.readText())
                for (i in 0 until arr.length()) {
                    list.add(Take.fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    fun saveTakes(takes: List<Take>) {
        try {
            val arr = JSONArray()
            takes.forEach { arr.put(it.toJson()) }
            indexFile.writeText(arr.toString())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun loadCustomVoices(): MutableList<CustomVoice> {
        val list = mutableListOf<CustomVoice>()
        try {
            if (voicesFile.exists()) {
                val arr = JSONArray(voicesFile.readText())
                for (i in 0 until arr.length()) {
                    list.add(CustomVoice.fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    fun saveCustomVoices(list: List<CustomVoice>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            voicesFile.writeText(arr.toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}
