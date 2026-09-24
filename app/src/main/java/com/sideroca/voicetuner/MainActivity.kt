package com.sideroca.voicetuner

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音调控台 v0.2
 * 调参面板 → 百炼 CosyVoice 直连合成 → 试听 / 保存 / 分享 → 本地记录（回填、重抽）
 */
class MainActivity : AppCompatActivity() {

    // ---------------------------------------------------------------- 数据
    private data class Voice(val name: String, val id: String, val note: String)

    private data class Fmt(val key: String, val label: String, val format: String, val sampleRate: Int, val bitRate: Int?, val ext: String)

    private data class InstrChip(val label: String, val text: String, val forVoice: String? = null)

    private class BadJsonException(val label: String) : Exception()

    private data class RowRef(val take: Take, val playBtn: TextView)

    private val voices = listOf(
        Voice("苏沐橙（成年·动画）", "cosyvoice-v3.5-plus-suchenga-aa83bcc828914d1bba289b7c6a41f21b", "来源：动画版原声"),
        Voice("苏沐橙（幼年·动画）", "cosyvoice-v3.5-plus-suchengy-a7b9c7381c8b4cce86f77a7e6c6b38c9", "来源：《巅峰荣耀》原声"),
        Voice("艾丽妮", "cosyvoice-v3.5-plus-ailini-e577e0e261a14032866623c65e4f8e2f", "明日方舟 · 任命助理"),
        Voice("艾雅法拉", "cosyvoice-v3.5-plus-eyjafjalla-ddd756ac929a420fbe35b31fc8120045", "明日方舟 · 报到 / 角色语音")
    )
    private val customLabel = "✏️ 自定义音色 ID…"

    private val formats = listOf(
        Fmt("wav24", "wav24 · 推荐", "wav", 24000, null, "wav"),
        Fmt("wav48", "wav48", "wav", 48000, null, "wav"),
        Fmt("wav16", "wav16", "wav", 16000, null, "wav"),
        Fmt("mp3_256", "mp3_256", "mp3", 24000, null, "mp3"),
        Fmt("mp3_128", "mp3_128", "mp3", 16000, null, "mp3")
    )

    private val instrChips = listOf(
        InstrChip("温柔", "语气温柔治愈，语速偏慢，像在轻声安慰"),
        InstrChip("开心", "语气开心活泼，语速稍快，句尾轻快上扬"),
        InstrChip("悲伤", "语气悲伤低沉，语速缓慢，带一点哽咽感"),
        InstrChip("平静", "语气平静自然，语速平稳"),
        InstrChip("战斗", "语气凌厉果断，短促有力，字字分明"),
        InstrChip("从容讲述", "语气自信从容，带一点骄傲与得意；重点词稍加重音，句间有停顿；语速从容偏慢，情感有起伏。"),
        InstrChip("热情安利", "热情安利新发现：语气明亮带笑意，句尾轻快上扬；重点词稍加重音，句间留出停顿；语速从容不赶。"),
        InstrChip("演讲感", "像一段演讲：开场平稳，讲到重点时情绪上扬、放慢并加重；句间有呼吸感，结尾干脆有力。"),
        InstrChip("惊喜分享", "像对前辈分享惊喜：声音温柔轻软，但讲到重点时忍不住兴奋，语调明显起伏。"),
        InstrChip("艾丽妮·默认", "语气清冷平稳，带着审慎的高傲，吐字干脆利落", "艾丽妮"),
        InstrChip("艾丽妮·柔和", "语气放软放缓，带有一点不易察觉的温和与关切", "艾丽妮"),
        InstrChip("艾丽妮·不耐烦", "语气冷淡，语速略快，透出些许不耐烦", "艾丽妮"),
        InstrChip("艾雅法拉·默认", "声音温柔轻快，亲切自然，带一点点害羞", "艾雅法拉"),
        InstrChip("艾雅法拉·认真", "语气认真专注，语速平稳，像在耐心讲解研究", "艾雅法拉"),
        InstrChip("艾雅法拉·害羞", "声音轻软迟疑，带一点腼腆和试探", "艾雅法拉")
    )

    private val cTxt = Color.parseColor("#E8EEF8")
    private val cDim = Color.parseColor("#8D99AD")

    // ---------------------------------------------------------------- 状态
    private lateinit var store: Store
    private val client = DashScopeClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val takes = mutableListOf<Take>()
    private val rowRefs = mutableListOf<RowRef>()
    private val customVoices = mutableListOf<CustomVoice>()

    // 建音色（声音复刻）
    private val REQ_PICK_AUDIO = 1001
    private val MAX_SAMPLE_BYTES = 15L * 1024 * 1024
    private var createDlg: AlertDialog? = null
    private var createFileTv: TextView? = null
    private var createStatus: TextView? = null
    private var createPrefixEt: EditText? = null
    private var createNameEt: EditText? = null
    private var pendingSample: File? = null
    private var pendingSampleDurMs = 0L
    private var pendingSampleMime = "audio/wav"

    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private var currentTake: Take? = null
    private var busy = false

    // ---------------------------------------------------------------- 视图
    private lateinit var svRoot: ScrollView
    private lateinit var btnSettings: TextView
    private lateinit var spVoice: Spinner
    private lateinit var etCustomVoice: EditText
    private lateinit var tvVoiceNote: TextView
    private lateinit var btnCreateVoice: TextView
    private lateinit var etText: EditText
    private lateinit var etInstr: EditText
    private lateinit var llChips: LinearLayout
    private lateinit var etSeed: EditText
    private lateinit var btnDice: TextView
    private lateinit var sbRate: SeekBar
    private lateinit var tvRate: TextView
    private lateinit var sbPitch: SeekBar
    private lateinit var tvPitch: TextView
    private lateinit var sbVol: SeekBar
    private lateinit var tvVol: TextView
    private lateinit var spFormat: Spinner
    private lateinit var tvAdvanced: TextView
    private lateinit var llAdvanced: LinearLayout
    private lateinit var etModel: EditText
    private lateinit var etLangHints: EditText
    private lateinit var etHotfix: EditText
    private lateinit var etExtra: EditText
    private lateinit var cbSsml: CheckBox
    private lateinit var btnGenerate: TextView
    private lateinit var btnCancel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var cardResult: LinearLayout
    private lateinit var tvResultInfo: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnShare: TextView
    private lateinit var btnExport: TextView
    private lateinit var btnClearHistory: TextView
    private lateinit var llHistory: LinearLayout

    // ---------------------------------------------------------------- 生命周期
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        customVoices.addAll(store.loadCustomVoices())
        bindViews()
        setupSpinners()
        setupSliders()
        setupActions()

        takes.addAll(store.loadTakes())
        migrateDurations()
        renderHistory()

        if (store.apiKey.isBlank()) {
            tvStatus.text = "首次使用：请点右上角「设置」填入 API Key（可从剪贴板粘贴）"
        }
    }

    private fun bindViews() {
        svRoot = findViewById(R.id.svRoot)
        btnSettings = findViewById(R.id.btnSettings)
        spVoice = findViewById(R.id.spVoice)
        etCustomVoice = findViewById(R.id.etCustomVoice)
        tvVoiceNote = findViewById(R.id.tvVoiceNote)
        btnCreateVoice = findViewById(R.id.btnCreateVoice)
        etText = findViewById(R.id.etText)
        etInstr = findViewById(R.id.etInstr)
        llChips = findViewById(R.id.llChips)
        etSeed = findViewById(R.id.etSeed)
        btnDice = findViewById(R.id.btnDice)
        sbRate = findViewById(R.id.sbRate)
        tvRate = findViewById(R.id.tvRate)
        sbPitch = findViewById(R.id.sbPitch)
        tvPitch = findViewById(R.id.tvPitch)
        sbVol = findViewById(R.id.sbVol)
        tvVol = findViewById(R.id.tvVol)
        spFormat = findViewById(R.id.spFormat)
        tvAdvanced = findViewById(R.id.tvAdvanced)
        llAdvanced = findViewById(R.id.llAdvanced)
        etModel = findViewById(R.id.etModel)
        etLangHints = findViewById(R.id.etLangHints)
        etHotfix = findViewById(R.id.etHotfix)
        etExtra = findViewById(R.id.etExtra)
        cbSsml = findViewById(R.id.cbSsml)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnCancel = findViewById(R.id.btnCancel)
        tvStatus = findViewById(R.id.tvStatus)
        cardResult = findViewById(R.id.cardResult)
        tvResultInfo = findViewById(R.id.tvResultInfo)
        btnPlay = findViewById(R.id.btnPlay)
        btnShare = findViewById(R.id.btnShare)
        btnExport = findViewById(R.id.btnExport)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        llHistory = findViewById(R.id.llHistory)
    }

    private fun setupSpinners() {
        rebuildVoiceSpinner(null)
        spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                syncVoiceUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val fmtAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, formats.map { it.label })
        fmtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFormat.adapter = fmtAdapter

        etModel.setText(store.lastModel)
        syncVoiceUi()
    }

    /** 全部音色 = 内置 + 自建 */
    private fun allVoices(): List<Voice> =
        voices + customVoices.map { Voice(it.name, it.id, "自建音色（前缀 " + it.prefix + "）") }

    /** 重建音色下拉；selectId 不为空时选中它 */
    private fun rebuildVoiceSpinner(selectId: String?) {
        val all = allVoices()
        val names = all.map { it.name } + customLabel
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spVoice.adapter = adapter
        if (selectId != null) {
            val idx = all.indexOfFirst { it.id == selectId }
            if (idx >= 0) spVoice.setSelection(idx)
        }
    }

    private fun setupSliders() {
        bindSlider(sbRate, tvRate, 0.5, 0.01, "%.2f")
        bindSlider(sbPitch, tvPitch, 0.5, 0.01, "%.2f")
        bindSlider(sbVol, tvVol, 0.0, 1.0, "%.0f")
        sbRate.progress = 50
        sbPitch.progress = 50
        sbVol.progress = 50
    }

    private fun bindSlider(sb: SeekBar, tv: TextView, from: Double, step: Double, fmt: String) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = String.format(Locale.US, fmt, from + progress * step)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tv.text = String.format(Locale.US, fmt, from + sb.progress * step)
    }

    private fun setupActions() {
        btnSettings.setOnClickListener { openSettings() }
        btnCreateVoice.setOnClickListener { openCreateVoice() }
        btnDice.setOnClickListener { etSeed.setText((0..65535).random().toString()) }
        tvAdvanced.setOnClickListener {
            val show = llAdvanced.visibility != View.VISIBLE
            llAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            tvAdvanced.text = if (show) "高级参数 ▾" else "高级参数 ▸"
        }
        btnGenerate.setOnClickListener { generate(null) }
        btnCancel.setOnClickListener {
            if (busy) {
                client.cancel()
                finishBusy()
                tvStatus.text = "已取消"
            }
        }
        btnPlay.setOnClickListener { currentTake?.let { toggleTake(it) } }
        btnShare.setOnClickListener { currentTake?.let { shareTake(it) } }
        btnExport.setOnClickListener { currentTake?.let { exportTake(it) } }
        btnClearHistory.setOnClickListener { confirmClearHistory() }
    }

    private fun syncVoiceUi() {
        val idx = spVoice.selectedItemPosition
        val all = allVoices()
        if (idx == all.size) {
            etCustomVoice.visibility = View.VISIBLE
            tvVoiceNote.text = "粘贴完整音色 ID（cosyvoice-v3.5-plus-…）"
        } else {
            etCustomVoice.visibility = View.GONE
            tvVoiceNote.text = all.getOrNull(idx)?.note ?: ""
        }
        renderChips()
    }

    private fun selectedVoiceId(): String {
        val idx = spVoice.selectedItemPosition
        val all = allVoices()
        return if (idx == all.size) {
            etCustomVoice.text.toString().trim()
        } else {
            all.getOrNull(idx)?.id ?: ""
        }
    }

    private fun voiceNameOf(id: String): String = allVoices().firstOrNull { it.id == id }?.name ?: "自定义音色"

    private fun renderChips() {
        llChips.removeAllViews()
        val vName = allVoices().getOrNull(spVoice.selectedItemPosition)?.name ?: ""
        val list = instrChips.filter { it.forVoice == null || vName.contains(it.forVoice) }
        for (chip in list) {
            val tv = TextView(this)
            tv.text = chip.label
            tv.setTextColor(cDim)
            tv.textSize = 13f
            tv.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
            tv.setPadding(dp(12), dp(6), dp(12), dp(6))
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener { etInstr.setText(chip.text) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(8)
            tv.layoutParams = lp
            llChips.addView(tv)
        }
        // 清空
        val clear = TextView(this)
        clear.text = "清空"
        clear.setTextColor(cDim)
        clear.textSize = 13f
        clear.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
        clear.setPadding(dp(12), dp(6), dp(12), dp(6))
        clear.isClickable = true
        clear.isFocusable = true
        clear.setOnClickListener { etInstr.setText("") }
        llChips.addView(clear)
    }

    // ---------------------------------------------------------------- 生成
    private fun generate(seedOverride: Int?) {
        if (busy) {
            toast("正在合成中，请稍候…")
            return
        }
        val key = store.apiKey.trim()
        if (key.isEmpty()) {
            toast("请先在「设置」里填写 API Key")
            openSettings()
            return
        }
        val text = etText.text.toString().trim()
        if (text.isEmpty()) {
            toast("请先输入文本")
            return
        }
        val voiceId = selectedVoiceId()
        if (voiceId.isEmpty()) {
            toast("请选择音色或输入自定义音色 ID")
            return
        }

        val hotFix: JSONObject? = try {
            optionalJson(etHotfix, "hot_fix")
        } catch (e: BadJsonException) {
            toast(e.label + " 不是合法 JSON，请检查格式")
            return
        }
        val extra: JSONObject? = try {
            optionalJson(etExtra, "额外参数")
        } catch (e: BadJsonException) {
            toast(e.label + " 不是合法 JSON，请检查格式")
            return
        }

        val seed = (seedOverride ?: etSeed.text.toString().trim().toIntOrNull() ?: (0..65535).random()).coerceIn(0, 65535)
        // 留空 = 每次随机，不回填；仅「重抽」时回填实际种子
        if (seedOverride != null) {
            etSeed.setText(seed.toString())
        }

        val fmt = formats[spFormat.selectedItemPosition.coerceIn(0, formats.size - 1)]
        val instr = etInstr.text.toString().trim().take(128)
        val model = etModel.text.toString().trim().ifEmpty { "cosyvoice-v3.5-plus" }
        store.lastModel = model
        val hints = etLangHints.text.toString()
            .split(',', '，', ' ', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { null }

        val req = SynthRequest(
            apiKey = key,
            workspace = store.workspace.trim().ifEmpty { "ws-9y8n1gp7w6pg23tv" },
            model = model,
            voice = voiceId,
            text = text,
            instruction = instr.ifEmpty { null },
            rate = 0.5 + sbRate.progress * 0.01,
            pitch = 0.5 + sbPitch.progress * 0.01,
            volume = sbVol.progress,
            seed = seed,
            format = fmt.format,
            sampleRate = fmt.sampleRate,
            bitRate = fmt.bitRate,
            languageHints = hints,
            hotFixJson = hotFix,
            extraJson = extra,
            ssml = cbSsml.isChecked
        )

        busy = true
        btnGenerate.alpha = 0.55f
        btnCancel.visibility = View.VISIBLE
        svRoot.smoothScrollTo(0, 0)
        tvStatus.text = "连接中…"

        var lastTick = 0L
        client.synthesize(req, object : SynthCallback {
            override fun onConnected() {
                ui { tvStatus.text = "已连接…" }
            }

            override fun onStarted() {
                ui { tvStatus.text = "合成中…" }
            }

            override fun onProgress(receivedBytes: Int) {
                val now = System.currentTimeMillis()
                if (now - lastTick < 80) return
                lastTick = now
                val s = if (fmt.format == "wav") {
                    String.format(Locale.US, "合成中… %.1f 秒音频", receivedBytes / (2.0 * fmt.sampleRate))
                } else {
                    String.format(Locale.US, "合成中… %.0f KB", receivedBytes / 1024.0)
                }
                ui { tvStatus.text = s }
            }

            override fun onFinished(audio: ByteArray) {
                try {
                    val take = saveTake(req, fmt, audio)
                    ui { onTakeReady(take) }
                } catch (e: Exception) {
                    ui {
                        finishBusy()
                        tvStatus.text = "❌ 保存失败"
                        toast("保存失败：" + e.message)
                    }
                }
            }

            override fun onError(message: String) {
                ui {
                    finishBusy()
                    tvStatus.text = "❌ " + message
                    toast("合成失败：" + message)
                }
            }
        })
    }

    private fun optionalJson(et: EditText, label: String): JSONObject? {
        val t = et.text.toString().trim()
        if (t.isEmpty()) return null
        return try {
            JSONObject(t)
        } catch (e: JSONException) {
            throw BadJsonException(label)
        }
    }

    private fun saveTake(req: SynthRequest, fmt: Fmt, audio: ByteArray): Take {
        val file = store.newAudioFile(fmt.ext)
        file.writeBytes(audio)
        fixWavHeader(file)
        val take = Take(
            id = file.nameWithoutExtension,
            fileName = file.name,
            voiceName = voiceNameOf(req.voice),
            voiceId = req.voice,
            text = req.text,
            instruction = req.instruction ?: "",
            rate = req.rate,
            pitch = req.pitch,
            volume = req.volume,
            seed = req.seed,
            format = fmt.key,
            durationMs = probeDurationMs(file),
            createdAt = System.currentTimeMillis()
        )
        takes.add(0, take)
        while (takes.size > 100) {
            val old = takes.removeAt(takes.size - 1)
            store.deleteFile(old)
        }
        store.saveTakes(takes)
        return take
    }

    private fun onTakeReady(take: Take) {
        finishBusy()
        tvStatus.text = "✅ 完成"
        currentTake = take
        cardResult.visibility = View.VISIBLE
        tvResultInfo.text = take.voiceName + " · " + take.format + " · 语速 " + fmtNum(take.rate) +
                " · 音调 " + fmtNum(take.pitch) + " · 音量 " + take.volume +
                " · 🎲 " + take.seed + " · 时长 " + fmtDur(take.durationMs)
        renderHistory()
        startPlayback(take)
    }

    private fun finishBusy() {
        busy = false
        btnGenerate.alpha = 1f
        btnCancel.visibility = View.GONE
    }

    // ---------------------------------------------------------------- 播放
    private fun toggleTake(take: Take) {
        val mp = player
        if (mp != null && playingId == take.id) {
            if (mp.isPlaying) mp.pause() else mp.start()
            refreshPlayButtons()
        } else {
            startPlayback(take)
        }
    }

    private fun startPlayback(take: Take) {
        stopPlayback()
        val file = store.fileOf(take)
        if (!file.exists()) {
            toast("文件不存在")
            return
        }
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                stopPlayback()
                refreshPlayButtons()
            }
            mp.setOnErrorListener { _, what, extra ->
                stopPlayback()
                refreshPlayButtons()
                toast("播放出错（$what/$extra）")
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            playingId = take.id
        } catch (e: Exception) {
            stopPlayback()
            toast("播放失败：" + e.message)
        }
        refreshPlayButtons()
    }

    private fun stopPlayback() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            player?.release()
        } catch (e: Exception) {
            // ignore
        }
        player = null
        playingId = null
    }

    private fun refreshPlayButtons() {
        val cur = currentTake
        btnPlay.text = if (cur != null && playingId == cur.id && player?.isPlaying == true) "⏸ 暂停" else "▶ 播放"
        for (r in rowRefs) {
            r.playBtn.text = if (playingId == r.take.id) "⏸ 暂停" else "▶ 播放"
        }
    }

    // ---------------------------------------------------------------- 分享 / 导出
    private fun shareTake(take: Take) {
        val file = store.fileOf(take)
        if (!file.exists()) {
            toast("文件不存在")
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享音频"))
        } catch (e: Exception) {
            toast("分享失败：" + e.message)
        }
    }

    private fun exportTake(take: Take) {
        val file = store.fileOf(take)
        if (!file.exists()) {
            toast("文件不存在")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceTuner")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建下载项")
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                toast("已导出到 下载/VoiceTuner/")
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
        } else {
            try {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VoiceTuner")
                dir.mkdirs()
                val dst = File(dir, file.name)
                file.copyTo(dst, overwrite = true)
                toast("已导出到：" + dst.absolutePath)
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
        }
    }

    private fun mimeOf(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/*"
        }

    // ---------------------------------------------------------------- 历史
    private fun renderHistory() {
        llHistory.removeAllViews()
        rowRefs.clear()
        if (takes.isEmpty()) {
            val tv = TextView(this)
            tv.text = "暂无记录"
            tv.setTextColor(cDim)
            tv.textSize = 13f
            tv.setPadding(0, dp(8), 0, 0)
            llHistory.addView(tv)
            return
        }
        for (t in takes) {
            llHistory.addView(buildRow(t))
        }
    }

    private fun buildRow(take: Take): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = ContextCompat.getDrawable(this, R.drawable.bg_row)
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(8)
        row.layoutParams = rlp

        val title = TextView(this)
        title.text = if (take.text.length > 40) take.text.take(40) + "…" else take.text
        title.setTextColor(cTxt)
        title.textSize = 14f
        row.addView(title)

        val meta = TextView(this)
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(take.createdAt))
        meta.text = time + " · " + take.voiceName + " · " + take.format +
                " · 语速" + fmtNum(take.rate) + " 音调" + fmtNum(take.pitch) + " 音量" + take.volume +
                " · 🎲" + take.seed + " · 时长 " + fmtDur(take.durationMs)
        meta.setTextColor(cDim)
        meta.textSize = 12f
        meta.setPadding(0, dp(3), 0, dp(6))
        row.addView(meta)

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        hs.addView(btnRow)
        row.addView(hs)

        val bPlay = smallBtn("▶ 播放")
        val bFill = smallBtn("回填")
        val bReroll = smallBtn("🎲 重抽")
        val bShare = smallBtn("分享")
        val bDel = smallBtn("删除")

        bPlay.setOnClickListener { toggleTake(take) }
        bFill.setOnClickListener {
            fillFrom(take)
            toast("已回填参数")
        }
        bReroll.setOnClickListener {
            fillFrom(take)
            generate((0..65535).random())
        }
        bShare.setOnClickListener { shareTake(take) }
        bDel.setOnClickListener { confirmDelete(take) }

        btnRow.addView(bPlay)
        btnRow.addView(bFill)
        btnRow.addView(bReroll)
        btnRow.addView(bShare)
        btnRow.addView(bDel)

        rowRefs.add(RowRef(take, bPlay))
        return row
    }

    private fun fillFrom(take: Take) {
        val all = allVoices()
        val idx = all.indexOfFirst { it.id == take.voiceId }
        if (idx >= 0) {
            spVoice.setSelection(idx)
        } else {
            spVoice.setSelection(all.size)
            etCustomVoice.setText(take.voiceId)
        }
        syncVoiceUi()
        etText.setText(take.text)
        etInstr.setText(take.instruction)
        etSeed.setText(take.seed.toString())
        sbRate.progress = Math.round((take.rate - 0.5) * 100).toInt().coerceIn(0, 150)
        sbPitch.progress = Math.round((take.pitch - 0.5) * 100).toInt().coerceIn(0, 150)
        sbVol.progress = take.volume.coerceIn(0, 100)
        val fi = formats.indexOfFirst { it.key == take.format }
        if (fi >= 0) spFormat.setSelection(fi)
    }

    private fun confirmDelete(take: Take) {
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage(take.text.take(60))
            .setPositiveButton("删除") { _, _ ->
                if (playingId == take.id) stopPlayback()
                takes.remove(take)
                store.deleteFile(take)
                store.saveTakes(takes)
                if (currentTake?.id == take.id) {
                    currentTake = null
                    cardResult.visibility = View.GONE
                }
                renderHistory()
                refreshPlayButtons()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearHistory() {
        if (takes.isEmpty()) {
            toast("没有记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空全部记录？")
            .setMessage("将删除 " + takes.size + " 条记录及其音频文件")
            .setPositiveButton("清空") { _, _ ->
                stopPlayback()
                takes.forEach { store.deleteFile(it) }
                takes.clear()
                store.saveTakes(takes)
                currentTake = null
                cardResult.visibility = View.GONE
                renderHistory()
                refreshPlayButtons()
                toast("已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 设置
    private fun openSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), dp(4))

        val etKey = EditText(this)
        etKey.hint = "sk-ws-…"
        etKey.setText(store.apiKey)
        etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        etKey.setTextColor(cTxt)
        etKey.setHintTextColor(cDim)
        etKey.textSize = 14f

        val btnPaste = smallBtn("📋 粘贴剪贴板")
        btnPaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                etKey.setText(clip.getItemAt(0).coerceToText(this).toString().trim())
                toast("已粘贴")
            } else {
                toast("剪贴板为空")
            }
        }

        val etWs = EditText(this)
        etWs.hint = "ws-xxxxxxxx"
        etWs.setText(store.workspace)
        etWs.inputType = InputType.TYPE_CLASS_TEXT
        etWs.setTextColor(cTxt)
        etWs.setHintTextColor(cDim)
        etWs.textSize = 14f

        val etModelDlg = EditText(this)
        etModelDlg.hint = "cosyvoice-v3.5-plus"
        etModelDlg.setText(store.lastModel)
        etModelDlg.inputType = InputType.TYPE_CLASS_TEXT
        etModelDlg.setTextColor(cTxt)
        etModelDlg.setHintTextColor(cDim)
        etModelDlg.textSize = 14f

        box.addView(labelView("API Key（仅保存在本机）"))
        box.addView(etKey)
        box.addView(btnPaste)
        box.addView(labelView("业务空间 ID"))
        box.addView(etWs)
        box.addView(labelView("默认 model"))
        box.addView(etModelDlg)

        val sc = ScrollView(this)
        sc.addView(box)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(sc)
            .setPositiveButton("保存") { _, _ ->
                store.apiKey = etKey.text.toString().trim()
                store.workspace = etWs.text.toString().trim()
                store.lastModel = etModelDlg.text.toString().trim()
                etModel.setText(store.lastModel)
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 建音色（声音复刻）
    private fun openCreateVoice() {
        if (store.apiKey.isBlank()) {
            toast("请先在「设置」里填写 API Key")
            openSettings()
            return
        }
        pendingSample = null
        pendingSampleDurMs = 0L
        pendingSampleMime = "audio/wav"

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(4))

        val tvTip = TextView(this)
        tvTip.text = "样本要求：干净人声、无背景音乐/杂音；推荐 ≤20 秒（最长 60 秒）；支持 wav / mp3 / m4a 等。"
        tvTip.setTextColor(cDim)
        tvTip.textSize = 12f
        box.addView(tvTip)

        val btnPick = smallBtn("选择音频文件…")
        btnPick.setOnClickListener { pickAudioFile() }
        val rowPick = LinearLayout(this)
        rowPick.orientation = LinearLayout.HORIZONTAL
        rowPick.setPadding(0, dp(8), 0, 0)
        rowPick.addView(btnPick)
        box.addView(rowPick)

        val tvFile = TextView(this)
        tvFile.text = "未选择样本"
        tvFile.setTextColor(cDim)
        tvFile.textSize = 12f
        tvFile.setPadding(0, dp(4), 0, 0)
        box.addView(tvFile)

        box.addView(labelView("前缀（用于生成音色 ID；1~10 位小写字母/数字）"))
        val etPrefix = EditText(this)
        etPrefix.hint = "如 ailin2"
        etPrefix.inputType = InputType.TYPE_CLASS_TEXT
        etPrefix.setTextColor(cTxt)
        etPrefix.setHintTextColor(cDim)
        etPrefix.textSize = 14f
        etPrefix.filters = arrayOf(InputFilter.LengthFilter(10))
        box.addView(etPrefix)

        box.addView(labelView("显示名称（留空则用前缀）"))
        val etName = EditText(this)
        etName.hint = "如：艾丽妮（新版）"
        etName.inputType = InputType.TYPE_CLASS_TEXT
        etName.setTextColor(cTxt)
        etName.setHintTextColor(cDim)
        etName.textSize = 14f
        box.addView(etName)

        val tvSt = TextView(this)
        tvSt.setTextColor(cDim)
        tvSt.textSize = 12f
        tvSt.setPadding(0, dp(8), 0, 0)
        box.addView(tvSt)

        val sc = ScrollView(this)
        sc.addView(box)

        val dlg = AlertDialog.Builder(this)
            .setTitle("建音色（声音复刻）")
            .setView(sc)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create()

        createDlg = dlg
        createFileTv = tvFile
        createStatus = tvSt
        createPrefixEt = etPrefix
        createNameEt = etName

        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { doCreateVoice() }
        }
        dlg.setOnDismissListener {
            createDlg = null
            createFileTv = null
            createStatus = null
            createPrefixEt = null
            createNameEt = null
        }
        dlg.show()
    }

    private fun pickAudioFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择音频样本"), REQ_PICK_AUDIO)
        } catch (e: Exception) {
            toast("没有可用的文件选择器")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importSample(uri)
        }
    }

    private fun importSample(uri: Uri) {
        createStatus?.text = "读取样本中…"
        Thread {
            var tmp: File? = null
            try {
                val dn = displayNameOf(uri) ?: "sample"
                var mime = contentResolver.getType(uri) ?: ""
                var ext = dn.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus")) {
                    ext = when {
                        mime.startsWith("audio/wav") -> "wav"
                        mime.startsWith("audio/mpeg") -> "mp3"
                        mime.startsWith("audio/mp4") -> "m4a"
                        mime.startsWith("audio/aac") -> "aac"
                        else -> "wav"
                    }
                }
                mime = when (ext) {
                    "wav" -> "audio/wav"
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "aac" -> "audio/aac"
                    "ogg", "opus" -> "audio/ogg"
                    "flac" -> "audio/flac"
                    else -> if (mime.startsWith("audio/")) mime else "audio/wav"
                }
                val dst = File(cacheDir, "voice_sample_" + System.currentTimeMillis() + "." + ext)
                tmp = dst
                val ins = contentResolver.openInputStream(uri) ?: throw Exception("无法打开所选文件")
                ins.use { input ->
                    dst.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_SAMPLE_BYTES) {
                                throw Exception("文件过大（超过 " + (MAX_SAMPLE_BYTES / 1024 / 1024) + " MB）")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                val durMs = probeDurationMs(dst)
                pendingSample = dst
                pendingSampleDurMs = durMs
                pendingSampleMime = mime
                ui {
                    createFileTv?.text = dn + " · " + (if (durMs > 0) fmtDur(durMs) else "时长未知") +
                            " · " + (dst.length() / 1024) + " KB"
                    createStatus?.text = when {
                        durMs > 60000 -> "⚠️ 样本超过 60 秒，请先裁剪再创建（建议 ≤20 秒）"
                        durMs > 20000 -> "⚠️ 样本超过 20 秒，仍可创建（建议 ≤20 秒效果更佳）"
                        else -> ""
                    }
                    if ((createPrefixEt?.text?.toString() ?: "").isBlank()) {
                        createPrefixEt?.setText(suggestPrefix(dn))
                    }
                }
            } catch (e: Exception) {
                tmp?.delete()
                pendingSample = null
                ui { createStatus?.text = "❌ 读取失败：" + (e.message ?: "") }
            }
        }.start()
    }

    private fun displayNameOf(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun suggestPrefix(name: String): String {
        var base = name.substringBeforeLast('.', name).lowercase(Locale.US)
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(10)
        if (base.isNotEmpty() && base[0] in '0'..'9') base = "v" + base.take(9)
        return base.ifEmpty { "v" + System.currentTimeMillis().toString().takeLast(5) }
    }

    private fun doCreateVoice() {
        val sample = pendingSample ?: run {
            createStatus?.text = "❌ 请先选择音频样本"
            return
        }
        if (!sample.exists()) {
            createStatus?.text = "❌ 样本文件不存在，请重新选择"
            return
        }
        if (pendingSampleDurMs > 60000) {
            createStatus?.text = "❌ 样本超过 60 秒，请先裁剪后再来（建议 ≤20 秒）"
            return
        }
        val prefix = (createPrefixEt?.text?.toString() ?: "").trim().lowercase(Locale.US)
        if (!Regex("^[a-z0-9]{1,10}$").matches(prefix)) {
            createStatus?.text = "❌ 前缀需为 1~10 位小写字母/数字"
            return
        }
        val name = (createNameEt?.text?.toString() ?: "").trim().ifEmpty { prefix }
        val key = store.apiKey.trim()
        if (key.isEmpty()) {
            createStatus?.text = "❌ 请先在「设置」里填写 API Key"
            return
        }
        val ws = store.workspace.trim().ifEmpty { "ws-9y8n1gp7w6pg23tv" }
        val model = store.lastModel.trim().ifEmpty { "cosyvoice-v3.5-plus" }
        val posBtn = createDlg?.getButton(DialogInterface.BUTTON_POSITIVE)
        posBtn?.isEnabled = false
        createStatus?.text = "编码样本…"

        Thread {
            try {
                val b64 = Base64.encodeToString(sample.readBytes(), Base64.NO_WRAP)
                val dataUri = "data:" + pendingSampleMime + ";base64," + b64
                ui { createStatus?.text = "上传创建中…（几秒到几十秒）" }
                client.createVoice(key, ws, model, prefix, dataUri, onResult = { vid ->
                    ui {
                        customVoices.add(CustomVoice(vid, name, prefix, System.currentTimeMillis()))
                        store.saveCustomVoices(customVoices)
                        rebuildVoiceSpinner(vid)
                        syncVoiceUi()
                        createDlg?.dismiss()
                        toast("✅ 音色已创建：" + name)
                        tvStatus.text = "✅ 音色已创建并选中：" + name
                    }
                }, onError = { msg ->
                    ui {
                        createStatus?.text = "❌ " + msg
                        posBtn?.isEnabled = true
                    }
                })
            } catch (e: Exception) {
                ui {
                    createStatus?.text = "❌ " + (e.message ?: "创建失败")
                    posBtn?.isEnabled = true
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 小工具
    private fun smallBtn(label: String): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(cTxt)
        tv.textSize = 13f
        tv.background = ContextCompat.getDrawable(this, R.drawable.bg_btn)
        tv.setPadding(dp(12), dp(7), dp(12), dp(7))
        tv.isClickable = true
        tv.isFocusable = true
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = dp(8)
        tv.layoutParams = lp
        return tv
    }

    private fun labelView(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(cDim)
        tv.textSize = 13f
        tv.setPadding(0, dp(12), 0, dp(2))
        return tv
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ui(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun fmtNum(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmtDur(ms: Long): String =
        if (ms <= 0) "未知" else String.format(Locale.US, "%.1f 秒", ms / 1000.0)

    private fun probeDurationMs(file: File): Long {
        // WAV 按文件实际长度自己算：服务端头里的 size 是 ≈2GB 流式占位值，
        // 系统解析会得出 "44739.2 秒" 这种离谱数字（2147483547 ÷ 48000）
        if (file.name.endsWith(".wav", ignoreCase = true)) {
            wavDurationMs(file)?.let { return it }
        }
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            d
        } catch (e: Exception) {
            0L
        }
    }

    /** 解析 WAV 头求时长（data 块按文件实际剩余长度截断）；失败返回 null 交给系统兜底 */
    private fun wavDurationMs(file: File): Long? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 44) return null
                val head = ByteArray(minOf(len, 4096L).toInt())
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                var byteRate = 0L
                var dataOffset = -1L
                var p = 12
                while (p + 8 <= head.size) {
                    val id = String(head, p, 4, Charsets.US_ASCII)
                    val sz = u32(head, p + 4)
                    if (id == "fmt ") {
                        if (sz >= 16 && p + 20 <= head.size) byteRate = u32(head, p + 16)
                    } else if (id == "data") {
                        dataOffset = (p + 8).toLong()
                        break
                    }
                    if (sz > head.size.toLong()) break
                    p += 8 + sz.toInt() + (sz.toInt() and 1)
                }
                if (byteRate <= 0 || dataOffset < 0 || dataOffset >= len) return null
                (len - dataOffset) * 1000 / byteRate
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 把 WAV 头里的流式占位大小改写为真实大小（导出/分享后其他播放器也能显示正确时长） */
    private fun fixWavHeader(file: File) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                if (len < 44) return
                val head = ByteArray(minOf(len, 4096L).toInt())
                raf.readFully(head)
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return
                if (u32(head, 4) != len - 8) writeU32(raf, 4L, len - 8)
                var p = 12
                while (p + 8 <= head.size) {
                    val id = String(head, p, 4, Charsets.US_ASCII)
                    val sz = u32(head, p + 4)
                    if (id == "data") {
                        val actual = len - (p + 8)
                        if (sz != actual) writeU32(raf, (p + 4).toLong(), actual)
                        break
                    }
                    if (sz > head.size.toLong()) break
                    p += 8 + sz.toInt() + (sz.toInt() and 1)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 修正旧记录里离谱的时长：重算 + 修头（一次性，之后无需重复） */
    private fun migrateDurations() {
        var fixed = false
        for (i in takes.indices) {
            val t = takes[i]
            if (!t.format.startsWith("wav")) continue
            val f = store.fileOf(t)
            if (!f.exists()) continue
            fixWavHeader(f)
            val d = probeDurationMs(f)
            if (d > 0 && d != t.durationMs) {
                takes[i] = t.copy(durationMs = d)
                fixed = true
            }
        }
        if (fixed) store.saveTakes(takes)
    }

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun writeU32(raf: RandomAccessFile, off: Long, v: Long) {
        raf.seek(off)
        raf.write(
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte()
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }
}
