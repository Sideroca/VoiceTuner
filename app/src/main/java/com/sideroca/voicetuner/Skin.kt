package com.sideroca.voicetuner

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * 换肤引擎 v1：按「角色」重绘视图树，不改布局。
 * 识别：文字按 v0.1 原色常量（E8EEF8 / 8D99AD / 6B7689 / FFFFFF），形状按几何（圆角半径 / 描边）。
 * 重绘保持几何不变 → 可重复执行；角色缓存于 view.tag（"r:xxx"），第二次起按缓存上色。
 */
object Skin {

    class Colors(
        val bg: Int, val card: Int, val card2: Int, val line: Int, val row: Int,
        val txt: Int, val dim: Int, val hint: Int, val acc: Int, val acc2: Int,
        val light: Boolean, val cardAlphaPct: Int
    ) {
        val onAcc: Int get() = Color.WHITE

        companion object {
            /** 本机原色（v0.1 深蓝） */
            fun original(cardAlphaPct: Int): Colors = Colors(
                0xFF0E1116.toInt(), 0xFF151A22.toInt(), 0xFF1B2230.toInt(), 0xFF26303F.toInt(), 0xFF171D27.toInt(),
                0xFFE8EEF8.toInt(), 0xFF8D99AD.toInt(), 0xFF6B7689.toInt(),
                0xFF4F8CFF.toInt(), 0xFF7A5CFF.toInt(), false, cardAlphaPct
            )

            /** 由一套配色推导本应用的角色色 */
            fun of(p: Pal, cardAlphaPct: Int): Colors {
                val c2 = if (p.panelBg == p.card) mix(p.card, p.text, 0.06f) else p.panelBg
                return Colors(
                    bg = p.bg,
                    card = p.card,
                    card2 = c2,
                    line = if (p.stroke != 0) p.stroke else mix(p.bg, p.text, 0.18f),
                    row = mix(p.card, c2, 0.55f),
                    txt = p.text,
                    dim = p.subText,
                    hint = mix(p.subText, p.bg, 0.5f),
                    acc = p.accent,
                    acc2 = if (p.barBg != 0) p.barBg else mix(p.accent, p.text, 0.25f),
                    light = !p.dark,
                    cardAlphaPct = cardAlphaPct
                )
            }

            fun mix(a: Int, b: Int, t: Float): Int {
                val k = t.coerceIn(0f, 1f)
                fun ch(x: Int, y: Int): Int = (x * (1 - k) + y * k).toInt().coerceIn(0, 255)
                return Color.rgb(
                    ch(Color.red(a), Color.red(b)),
                    ch(Color.green(a), Color.green(b)),
                    ch(Color.blue(a), Color.blue(b))
                )
            }
        }
    }

    /** 当前生效的角色色（未选主题 = 本机原色） */
    fun colors(ctx: Context): Colors {
        val store = Store(ctx)
        val p = Palettes.byId(store.themeId)
        return if (p == null) Colors.original(store.cardAlphaPct) else Colors.of(p, store.cardAlphaPct)
    }

    /** 窗口级：底色 + 系统栏（含状态栏图标明暗） */
    fun applyWindow(a: Activity, c: Colors) {
        a.window.setBackgroundDrawable(ColorDrawable(c.bg))
        @Suppress("DEPRECATION")
        run {
            a.window.statusBarColor = c.bg
            a.window.navigationBarColor = c.bg
        }
        val ic = WindowInsetsControllerCompat(a.window, a.window.decorView)
        ic.isAppearanceLightStatusBars = c.light
        ic.isAppearanceLightNavigationBars = c.light
    }

    /** 视图树重绘（幂等） */
    fun apply(root: View, c: Colors) {
        visit(root, c, root.resources.displayMetrics.density)
    }

    // v0.1 原色常量（用于角色识别）
    private val T_TXT = 0xFFE8EEF8.toInt()
    private val T_DIM = 0xFF8D99AD.toInt()
    private val T_HINT = 0xFF6B7689.toInt()
    private val T_WHITE = 0xFFFFFFFF.toInt()

    private fun visit(v: View, c: Colors, d: Float) {
        when (v) {
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(c.acc)
                v.thumbTintList = ColorStateList.valueOf(c.acc)
                v.progressBackgroundTintList = ColorStateList.valueOf(c.line)
            }
            is EditText -> {
                textRole(v)?.let { paintText(v, it, c) }
                v.setHintTextColor(c.hint)
            }
            is TextView -> textRole(v)?.let { paintText(v, it, c) }
        }
        if (v is CompoundButton) v.buttonTintList = ColorStateList.valueOf(c.acc)
        repaintBg(v, c, d)
        if (v is ViewGroup) {
            var i = 0
            while (i < v.childCount) {
                visit(v.getChildAt(i), c, d)
                i++
            }
        }
    }

    private fun textRole(v: TextView): String? {
        val tag = v.tag as? String
        if (tag != null && tag.startsWith("r:")) return tag.substring(2)
        val r = when (v.currentTextColor) {
            T_TXT -> "txt"
            T_DIM -> "dim"
            T_HINT -> "hint"
            T_WHITE -> "white"
            else -> null
        }
        if (r != null) v.tag = "r:$r"
        return r
    }

    private fun paintText(v: TextView, role: String, c: Colors) {
        when (role) {
            "txt" -> v.setTextColor(c.txt)
            "dim" -> v.setTextColor(c.dim)
            "hint" -> v.setTextColor(c.hint)
            "white" -> v.setTextColor(c.onAcc)
        }
    }

    private val BG_RES = intArrayOf(
        R.drawable.bg_card, R.drawable.bg_input, R.drawable.bg_btn,
        R.drawable.bg_chip, R.drawable.bg_row, R.drawable.bg_btn_primary
    )

    private fun repaintBg(v: View, c: Colors, d: Float) {
        val gd = v.background as? GradientDrawable ?: return
        // 按「圆角半径」识别角色（不依赖渐变方向——纯色形状的渐变方向字段也可能非空）
        val r = gd.cornerRadius
        var role = 0
        when {
            near(r, 12f * d) -> role = R.drawable.bg_btn_primary
            near(r, 14f * d) -> role = R.drawable.bg_card
            near(r, 100f * d) -> role = R.drawable.bg_chip
            near(r, 10f * d) -> role = if (v.tag == "r:row") R.drawable.bg_row else R.drawable.bg_btn
        }
        if (role == 0) role = matchByConstantState(v, gd)
        val sel = v.isSelected
        when (role) {
            R.drawable.bg_btn_primary -> v.background = grad(c.acc, c.acc2, 12f * d)
            R.drawable.bg_card -> v.background = shape(c.card, c.line, 14f * d, c.cardAlphaPct, d)
            R.drawable.bg_chip -> if (!sel) v.background = shape(c.card2, c.line, 100f * d, 100, d)
            R.drawable.bg_row -> v.background = shape(c.row, null, 10f * d, 100, 0f)
            R.drawable.bg_input, R.drawable.bg_btn ->
                v.background = shape(c.card2, if (sel) c.acc else c.line, 10f * d, 100, if (sel) 3f else d)
        }
    }

    /** 备用识别：与同资源同主题新加载的 drawable 比对 ConstantState */
    private fun matchByConstantState(v: View, gd: GradientDrawable): Int {
        val cs = gd.constantState ?: return 0
        for (id in BG_RES) {
            val ref = androidx.core.content.ContextCompat.getDrawable(v.context, id) ?: continue
            if (ref.constantState == cs) return id
        }
        return 0
    }

    private fun near(a: Float, b: Float): Boolean = a > 0f && abs(a - b) <= 2.5f

    /** 供页面代码复用的形状（radiusDp / strokeDp 以 dp 计） */
    fun shapeDp(ctx: Context, fill: Int, stroke: Int?, radiusDp: Float, alphaPct: Int = 100, strokeDp: Float = 1f): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return shape(fill, stroke, radiusDp * d, alphaPct, strokeDp * d)
    }

    fun shape(fill: Int, stroke: Int?, radiusPx: Float, alphaPct: Int, strokePx: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = radiusPx
        if (stroke != null) g.setStroke(strokePx.toInt().coerceAtLeast(2), stroke)
        if (alphaPct < 100) g.alpha = (alphaPct * 255 / 100).coerceIn(0, 255)
        return g
    }

    fun grad(a: Int, b: Int, radiusPx: Float): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b))
        g.cornerRadius = radiusPx
        return g
    }
}
