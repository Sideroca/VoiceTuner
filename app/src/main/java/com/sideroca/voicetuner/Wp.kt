package com.sideroca.voicetuner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.widget.ImageView
import java.io.File
import kotlin.math.max

/**
 * 页面壁纸 v1：主界面 / 设置页 各自独立。
 * 导入（SAF）→ 采样解码（≤3000px，EXIF 摆正）→ 垫底 + 遮罩浓度；同文件不重复解码。
 * 取景（裁剪对位）留到下一轮。
 */
object Wp {

    fun file(ctx: Context, slot: String): File = File(ctx.filesDir, "wp_$slot.jpg")

    fun import(ctx: Context, uri: Uri, slot: String): Boolean = try {
        val input = ctx.contentResolver.openInputStream(uri) ?: return false
        input.use { ins -> file(ctx, slot).outputStream().use { out -> ins.copyTo(out) } }
        true
    } catch (_: Exception) {
        false
    }

    fun clear(ctx: Context, slot: String) {
        try {
            file(ctx, slot).delete()
        } catch (_: Exception) {
        }
    }

    fun decode(path: String, maxEdge: Int = 3000): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        val longEdge = max(b.outWidth, b.outHeight)
        var s = 1
        while (longEdge / (s * 2) >= maxEdge) s *= 2
        while (longEdge / s > maxEdge) s *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        if (bmp == null) null else applyExif(bmp, path)
    } catch (_: Exception) {
        null
    }

    private fun applyExif(bmp: Bitmap, path: String): Bitmap {
        return try {
            @Suppress("DEPRECATION")
            val o = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) {
                bmp
            } else {
                val m = Matrix()
                m.postRotate(deg)
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                r
            }
        } catch (_: Exception) {
            bmp
        }
    }

    /** 应用到一个槽位：img 垫底、scrim 遮罩（主题底色 + 浓度 0~80） */
    fun applySlot(ctx: Context, img: ImageView?, scrim: View?, path: String?, scrimPct: Int, baseColor: Int) {
        if (img == null || scrim == null) return
        if (path.isNullOrEmpty() || !File(path).exists()) {
            img.setImageDrawable(null)
            img.tag = null
            img.visibility = View.GONE
            scrim.visibility = View.GONE
            return
        }
        val key = path + "@" + File(path).lastModified()
        if (img.tag != key || img.drawable == null) {
            val bmp = decode(path)
            if (bmp == null) {
                img.setImageDrawable(null)
                img.tag = null
                img.visibility = View.GONE
                scrim.visibility = View.GONE
                return
            }
            img.setImageBitmap(bmp)
            img.tag = key
        }
        img.visibility = View.VISIBLE
        val a = scrimPct.coerceIn(0, 80) * 255 / 100
        scrim.setBackgroundColor(Color.argb(a, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)))
        scrim.visibility = if (a == 0) View.GONE else View.VISIBLE
    }
}
