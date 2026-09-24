package com.sideroca.voicetuner

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 多音频合并（建音色用）：每个文件 → PCM → 统一 16kHz / 16bit / 单声道 → 顺序拼接 → 输出单个 WAV。
 * 文件数量不限；合并总时长以服务端要求为准（官方建议 10~20 秒、最长 60 秒）。
 * 解码：优先 MediaExtractor/MediaCodec（mp3/m4a/aac/ogg/wav…），失败时兜底解析 16bit PCM WAV。
 */
object AudioMerge {

    const val RATE = 16000
    private const val GAP_MS = 150L              // 片段之间插入的静音
    private const val MAX_FILE_SECONDS = 180     // 单个输入最多解码 3 分钟（防异常大文件）

    data class Result(val ok: Int, val fail: Int, val durMs: Long)

    fun merge(ctx: Context, uris: List<Uri>, outFile: File, progress: (Int, Int) -> Unit = { _, _ -> }): Result? {
        val chunks = ArrayList<ShortArray>()
        var ok = 0
        var fail = 0
        var totalSamples = 0L
        var idx = 0
        for (u in uris) {
            idx++
            progress(idx, uris.size)
            val pcm = decodeToMono16k(ctx, u)
            if (pcm == null || pcm.isEmpty()) {
                fail++
                continue
            }
            if (chunks.isNotEmpty()) {
                val gap = ShortArray((RATE * GAP_MS / 1000).toInt())
                chunks.add(gap)
                totalSamples += gap.size
            }
            chunks.add(pcm)
            totalSamples += pcm.size
            ok++
        }
        if (ok == 0) return null
        writeWav(outFile, chunks, totalSamples)
        return Result(ok, fail, totalSamples * 1000 / RATE)
    }

    // ---------------------------------------------------------------- 解码

    private fun decodeToMono16k(ctx: Context, uri: Uri): ShortArray? {
        val t = extractDecode(ctx, uri) ?: parseWav(ctx, uri) ?: return null
        val mono = downMix(t.first, t.second)
        return resample(mono, t.third, RATE)
    }

    /** MediaExtractor/MediaCodec 通用路径；返回 (交织PCM, 声道数, 采样率) */
    private fun extractDecode(ctx: Context, uri: Uri): Triple<ShortArray, Int, Int>? = try {
        val afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
        if (afd == null) {
            null
        } else {
            afd.use { desc ->
                val ex = MediaExtractor()
                ex.setDataSource(desc.fileDescriptor, desc.startOffset, desc.length)
                var track = -1
                var fmt: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        track = i
                        fmt = f
                        break
                    }
                }
                if (track < 0 || fmt == null) {
                    ex.release()
                    null
                } else {
                    ex.selectTrack(track)
                    val r = readTrackPcm(ex, fmt)
                    ex.release()
                    r
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readTrackPcm(ex: MediaExtractor, fmt: MediaFormat): Triple<ShortArray, Int, Int>? {
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null

        // 1) 裸 PCM（WAV 走这里，无需解码器）
        if (mime == "audio/raw" || mime == "audio/x-raw") {
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE, RATE)
            if (ch <= 0) ch = 1
            if (rate <= 0) rate = RATE
            val cap = fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20).coerceIn(64 * 1024, 4 shl 20)
            val buf = ByteBuffer.allocate(cap).order(ByteOrder.nativeOrder())
            val chunks = ArrayList<ShortArray>()
            var samples = 0L
            val capSamples = rate.toLong() * MAX_FILE_SECONDS * ch
            while (samples < capSamples) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                buf.position(0)
                buf.limit(n)
                val s = ShortArray(n / 2)
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(s)
                chunks.add(s)
                samples += s.size
                ex.advance()
            }
            return Triple(concat(chunks), ch, rate)
        }

        // 2) 压缩音频：解码器
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            return null
        }
        codec.configure(fmt, null, null, 0)
        codec.start()
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE, RATE)
        if (ch <= 0) ch = 1
        if (rate <= 0) rate = RATE
        var floatPcm = false
        val chunks = ArrayList<ShortArray>()
        var samples = 0L
        var capSamples = rate.toLong() * MAX_FILE_SECONDS * ch
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val ib = codec.getInputBuffer(inIx)
                        val n = if (ib == null) -1 else ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIx >= 0) {
                    val ob = codec.getOutputBuffer(outIx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        if (floatPcm) {
                            val fb = ob.asFloatBuffer()
                            val s = ShortArray(fb.remaining())
                            var i = 0
                            while (fb.hasRemaining()) {
                                s[i++] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            }
                            chunks.add(s)
                            samples += s.size
                        } else {
                            val sb = ob.asShortBuffer()
                            val s = ShortArray(sb.remaining())
                            sb.get(s)
                            chunks.add(s)
                            samples += s.size
                        }
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT, ch)
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE, rate)
                    if (ch <= 0) ch = 1
                    if (rate <= 0) rate = RATE
                    capSamples = rate.toLong() * MAX_FILE_SECONDS * ch
                    if (android.os.Build.VERSION.SDK_INT >= 24 && nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                    }
                }
                if (samples >= capSamples) break
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
        }
        return Triple(concat(chunks), ch, rate)
    }

    /** 兜底：直接解析 16bit PCM 的 WAV（不依赖设备解码器） */
    private fun parseWav(ctx: Context, uri: Uri): Triple<ShortArray, Int, Int>? = try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val head = ByteArray(12)
            if (readFully(ins, head) < 12) {
                null
            } else if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
                null
            } else {
                var rate = 0
                var ch = 1
                var bits = 16
                var afmt = 1
                var data: ByteArray? = null
                while (true) {
                    val ck = ByteArray(8)
                    if (readFully(ins, ck) < 8) break
                    val id = String(ck, 0, 4, Charsets.US_ASCII)
                    val size = le32(ck, 4)
                    when {
                        id == "fmt " -> {
                            val f = ByteArray(size)
                            readFully(ins, f)
                            afmt = le16(f, 0)
                            ch = le16(f, 2)
                            rate = le32(f, 4)
                            bits = le16(f, 14)
                        }
                        id == "data" -> {
                            val d = ByteArray(size)
                            readFully(ins, d)
                            data = d
                        }
                        else -> skipFully(ins, size.toLong())
                    }
                    if (size % 2 == 1) skipFully(ins, 1)
                }
                val d = data
                if (afmt != 1 || bits != 16 || d == null || rate <= 0) {
                    null
                } else {
                    val s = ShortArray(d.size / 2)
                    ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                    Triple(s, ch, rate)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- PCM 工具

    private fun concat(chunks: List<ShortArray>): ShortArray {
        var n = 0
        for (c in chunks) n += c.size
        val out = ShortArray(n)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        return out
    }

    private fun downMix(s: ShortArray, ch: Int): ShortArray {
        if (ch <= 1) return s
        val n = s.size / ch
        val o = ShortArray(n)
        for (i in 0 until n) {
            var acc = 0
            for (c in 0 until ch) acc += s[i * ch + c]
            o[i] = (acc / ch).toShort()
        }
        return o
    }

    private fun resample(x: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate || x.isEmpty()) return x
        val n = (x.size.toLong() * outRate / inRate).toInt().coerceAtLeast(1)
        val y = ShortArray(n)
        val step = inRate.toDouble() / outRate
        for (i in 0 until n) {
            val pos = i * step
            val i0 = pos.toInt()
            if (i0 >= x.size - 1) {
                y[i] = x[x.size - 1]
            } else {
                val t = pos - i0
                y[i] = (x[i0] * (1 - t) + x[i0 + 1] * t).toInt().toShort()
            }
        }
        return y
    }

    private fun writeWav(out: File, chunks: List<ShortArray>, totalSamples: Long) {
        val dataBytes = totalSamples * 2
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)
            h.putShort(1)
            h.putShort(1)
            h.putInt(RATE)
            h.putInt(RATE * 2)
            h.putShort(2)
            h.putShort(16)
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(dataBytes.toInt())
            raf.write(h.array())
            val buf = ByteArray(64 * 1024)
            for (c in chunks) {
                var i = 0
                while (i < c.size) {
                    val n = min(buf.size / 2, c.size - i)
                    var j = 0
                    while (j < n) {
                        val v = c[i + j].toInt()
                        buf[j * 2] = (v and 0xFF).toByte()
                        buf[j * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        j++
                    }
                    raf.write(buf, 0, n * 2)
                    i += n
                }
            }
        }
    }

    private fun readFully(ins: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val n = ins.read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(buf, 0, min(left, buf.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
