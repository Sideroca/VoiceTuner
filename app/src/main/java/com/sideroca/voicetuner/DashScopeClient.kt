package com.sideroca.voicetuner

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 合成请求参数 */
data class SynthRequest(
    val apiKey: String,
    val workspace: String,
    val model: String,
    val voice: String,
    val text: String,
    val instruction: String?,
    val rate: Double,
    val pitch: Double,
    val volume: Int,
    val seed: Int,
    val format: String,       // wav / mp3
    val sampleRate: Int,
    val bitRate: Int?,        // 仅 opus 需要
    val languageHints: List<String>?,
    val hotFixJson: JSONObject?,
    val extraJson: JSONObject?,
    val ssml: Boolean
)

interface SynthCallback {
    fun onConnected()
    fun onStarted()
    fun onProgress(receivedBytes: Int)
    fun onFinished(audio: ByteArray)
    fun onError(message: String)
}

interface Cancellable {
    fun cancel()
}

/**
 * 阿里云百炼 CosyVoice 复刻音色合成（原生 WebSocket 协议，与官方 Python SDK 消息格式一致）:
 * run-task → task-started → continue-task(一次性整段文本) → finish-task → 二进制音频流 → task-finished
 */
class DashScopeClient {

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket：读超时置空，避免长文本合成期间被误断
        .build()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // 复刻音色创建：要上传样本，单独放宽超时
    private val enrollClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    @Volatile
    private var done = false

    private val buffer = ByteArrayOutputStream()

    @Synchronized
    private fun appendData(b: ByteArray): Int {
        buffer.write(b)
        return buffer.size()
    }

    @Synchronized
    private fun snapshot(): ByteArray = buffer.toByteArray()

    @Synchronized
    private fun resetBuffer() {
        buffer.reset()
    }

    fun synthesize(req: SynthRequest, callback: SynthCallback): Cancellable {
        done = false
        resetBuffer()
        val taskId = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://" + req.workspace + ".cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + req.apiKey)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                callback.onConnected()
                webSocket.send(runTaskMessage(taskId, req))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val header = try {
                    JSONObject(text).optJSONObject("header")
                } catch (e: Exception) {
                    null
                } ?: return
                when (header.optString("event")) {
                    "task-started" -> {
                        callback.onStarted()
                        webSocket.send(continueTaskMessage(taskId, req))
                        webSocket.send(finishTaskMessage(taskId))
                    }
                    "task-finished" -> {
                        if (!done) {
                            done = true
                            val data = snapshot()
                            callback.onFinished(data)
                            webSocket.close(1000, null)
                        }
                    }
                    "task-failed" -> {
                        if (!done) {
                            done = true
                            val msg = header.optString("error_message", "合成失败")
                            callback.onError(msg)
                            webSocket.close(1000, null)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val total = appendData(bytes.toByteArray())
                if (!done) callback.onProgress(total)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!done) {
                    done = true
                    callback.onError(t.message ?: "网络连接失败")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done) {
                    done = true
                    callback.onError("连接提前关闭（$code）")
                }
            }
        }

        ws = wsClient.newWebSocket(request, listener)
        return object : Cancellable {
            override fun cancel() {
                done = true
                try {
                    ws?.cancel()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    /** 取消当前合成 */
    fun cancel() {
        done = true
        try {
            ws?.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 云端音色列表（customization: list_voice） */
    fun listVoices(
        apiKey: String,
        workspace: String,
        onResult: (List<Pair<String, String>>) -> Unit,
        onError: (String) -> Unit
    ): Cancellable {
        val body = JSONObject().apply {
            put("model", "voice-enrollment")
            put("input", JSONObject().apply {
                put("action", "list_voice")
                put("page_index", 0)
                put("page_size", 50)
            })
        }
        val request = Request.Builder()
            .url("https://" + workspace + ".cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/customization")
            .header("Authorization", "Bearer " + apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                onError(e.message ?: "请求失败")
            }

            override fun onResponse(c: Call, response: Response) {
                response.use {
                    val s = it.body?.string() ?: ""
                    try {
                        val j = JSONObject(s)
                        val arr = j.optJSONObject("output")?.optJSONArray("voice_list") ?: JSONArray()
                        val list = mutableListOf<Pair<String, String>>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(o.optString("voice_id") to o.optString("gmt_create"))
                        }
                        onResult(list)
                    } catch (e: Exception) {
                        onError("解析失败：" + s.take(160))
                    }
                }
            }
        })
        return object : Cancellable {
            override fun cancel() {
                call.cancel()
            }
        }
    }

    /** 创建复刻音色（customization: create_voice），成功回调 voice_id */
    fun createVoice(
        apiKey: String,
        workspace: String,
        targetModel: String,
        prefix: String,
        dataUri: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ): Cancellable {
        val input = JSONObject().apply {
            put("action", "create_voice")
            put("target_model", targetModel)
            put("prefix", prefix)
            put("url", dataUri)
        }
        val body = JSONObject().apply {
            put("model", "voice-enrollment")
            put("input", input)
        }
        val request = Request.Builder()
            .url("https://" + workspace + ".cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/customization")
            .header("Authorization", "Bearer " + apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = enrollClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                onError(e.message ?: "网络请求失败")
            }

            override fun onResponse(c: Call, response: Response) {
                response.use { resp ->
                    val s = resp.body?.string() ?: ""
                    try {
                        val j = JSONObject(s)
                        val vid = j.optJSONObject("output")?.optString("voice_id") ?: ""
                        if (resp.isSuccessful && vid.isNotBlank()) {
                            onResult(vid)
                        } else {
                            val msg = j.optString("message")
                                .ifBlank { j.optString("code") }
                                .ifBlank { "创建失败（HTTP " + resp.code + "）" }
                            onError(msg)
                        }
                    } catch (e: Exception) {
                        onError("解析失败：" + s.take(160))
                    }
                }
            }
        })
        return object : Cancellable {
            override fun cancel() {
                call.cancel()
            }
        }
    }

    // ---------------------------------------------------------------- 消息构造
    private fun runTaskMessage(taskId: String, req: SynthRequest): String {
        val params = JSONObject()
        params.put("voice", req.voice)
        params.put("volume", req.volume)
        params.put("text_type", "PlainText")
        params.put("sample_rate", req.sampleRate)
        params.put("rate", req.rate)
        params.put("pitch", req.pitch)
        params.put("format", req.format)
        params.put("seed", req.seed)
        params.put("type", 0)
        params.put("enable_ssml", req.ssml)
        if (req.format == "opus" && req.bitRate != null) {
            params.put("bit_rate", req.bitRate)
        }
        if (req.languageHints != null && req.languageHints.isNotEmpty()) {
            val arr = JSONArray()
            for (h in req.languageHints) arr.put(h)
            params.put("language_hints", arr)
        }
        req.hotFixJson?.let { params.put("hot_fix", it) }
        req.extraJson?.let { extra ->
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                params.put(k, extra.get(k))
            }
        }
        if (!req.instruction.isNullOrBlank()) {
            params.put("instruction", req.instruction)
        }

        val payload = JSONObject()
        payload.put("model", req.model)
        payload.put("task_group", "audio")
        payload.put("task", "tts")
        payload.put("function", "SpeechSynthesizer")
        payload.put("input", JSONObject())
        payload.put("parameters", params)

        val header = JSONObject()
        header.put("action", "run-task")
        header.put("task_id", taskId)
        header.put("streaming", "duplex")

        val root = JSONObject()
        root.put("header", header)
        root.put("payload", payload)
        return root.toString()
    }

    private fun continueTaskMessage(taskId: String, req: SynthRequest): String {
        val input = JSONObject()
        input.put("text", req.text)

        val payload = JSONObject()
        payload.put("model", req.model)
        payload.put("task_group", "audio")
        payload.put("task", "tts")
        payload.put("function", "SpeechSynthesizer")
        payload.put("input", input)

        val header = JSONObject()
        header.put("action", "continue-task")
        header.put("task_id", taskId)
        header.put("streaming", "duplex")

        val root = JSONObject()
        root.put("header", header)
        root.put("payload", payload)
        return root.toString()
    }

    private fun finishTaskMessage(taskId: String): String {
        val header = JSONObject()
        header.put("action", "finish-task")
        header.put("task_id", taskId)
        header.put("streaming", "duplex")

        val payload = JSONObject()
        payload.put("input", JSONObject())

        val root = JSONObject()
        root.put("header", header)
        root.put("payload", payload)
        return root.toString()
    }
}
