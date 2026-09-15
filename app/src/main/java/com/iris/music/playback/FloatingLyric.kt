/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.iris.music.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.Player
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 桌面悬浮歌词：常驻全局覆盖窗，单行显示当前句，跟随播放进度滚动。
 *
 * 挂在 [MusicService] 上而非 Activity：服务是前台常驻的，App 退到桌面、切到别的
 * 应用，歌词窗依然跟随。所有可调项（开关/字号/长度/透明度）读 `iris_prefs`，
 * 注册 [SharedPreferences.OnSharedPreferenceChangeListener] 实时响应设置页改动。
 *
 * 用原生 TextView 而非 ComposeView：不引新依赖（离线仓库无 ui-view），
 * 且脱离 Activity 的悬浮窗里原生 view 无需自持 Lifecycle owner，最稳。
 */
object FloatingLyric {

    const val PREFS = "iris_prefs"
    const val KEY_ENABLED = "float_lyric_enabled"
    const val KEY_FONT = "float_lyric_font"          // sp 字号，12~40
    const val KEY_WIDTH = "float_lyric_width"         // 文本框宽度占屏比，0.3~1.0
    const val KEY_BG = "float_lyric_bg"               // 黑底透明度 0~1（0 最浓，1 完全消失）
    const val KEY_POS_X = "float_lyric_x"             // 悬浮窗 gravity-x 偏移（px）
    const val KEY_POS_Y = "float_lyric_y"             // 悬浮窗 gravity-y 偏移（px）

    const val FONT_DEFAULT = 18f
    const val WIDTH_DEFAULT = 0.86f
    const val BG_DEFAULT = 0.3f

    /** 进度轮询周期：500ms 足够跟手，又不至于高频读播放器 */
    private const val POLL_MS = 500L

    private var appContext: Context? = null
    private var player: Player? = null
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bgDrawable: GradientDrawable? = null

    /** 黑底透明档（0~1），喂给 applyStyle 计算黑底浓度。 */
    private var bgT = BG_DEFAULT

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var prefs: SharedPreferences? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 歌词缓存
    private var lyricPath: String? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private var loadingPath: String? = null

    private var shown = false

    private val poll = object : Runnable {
        override fun run() {
            updateLine()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** 由 [MusicService.onCreate] 调用一次：保存上下文与播放器引用，恢复悬浮窗状态。 */
    fun init(context: Context, p: Player) {
        appContext = context.applicationContext
        player = p
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_FONT -> applyFont(sp.getFloat(KEY_FONT, FONT_DEFAULT))
                KEY_WIDTH -> applyWidth(sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT))
                KEY_BG -> { bgT = sp.getFloat(KEY_BG, BG_DEFAULT); applyStyle() }
                KEY_ENABLED -> syncVisibility()
            }
        }
        prefListener = listener
        sp.registerOnSharedPreferenceChangeListener(listener)
        syncVisibility()
    }

    fun release() {
        handler.removeCallbacks(poll)
        prefListener?.let { prefs?.unregisterOnSharedPreferenceChangeListener(it) }
        prefListener = null
        hide()
        appContext = null
        player = null
        prefs = null
    }

    private fun enabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, false) ?: false

    /** 外部（设置页授权返回后）主动触发一次显隐同步。 */
    fun refresh() = syncVisibility()

    /** 根据开关 + 悬浮窗权限决定显示/隐藏。 */
    private fun syncVisibility() {
        val ctx = appContext ?: return
        if (enabled() && Settings.canDrawOverlays(ctx)) show(ctx) else hide()
    }

    private fun screenW(): Int =
        appContext?.resources?.displayMetrics?.widthPixels ?: 1080

    @SuppressLint("ClickableViewAccessibility")
    private fun show(ctx: Context) {
        if (shown) return
        val wm = windowManager ?: return
        val sp = prefs ?: return

        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            // 阴影提升暗背景下的可读性
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.getFloat(KEY_FONT, FONT_DEFAULT))
        }

        val lp = WindowManager.LayoutParams(
            (screenW() * sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT)).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点、不挡其它区域触摸（除自身可拖），延伸到状态栏
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_POS_X, 0)
            y = sp.getInt(KEY_POS_Y, (120 * ctx.resources.displayMetrics.density).toInt())
        }

        attachDrag(tv, lp)

        runCatching { wm.addView(tv, lp) }.onFailure { return }
        textView = tv
        params = lp
        shown = true
        // 初始化外观（黑底浓度）
        bgT = sp.getFloat(KEY_BG, BG_DEFAULT)
        applyStyle()
        handler.post(poll)
    }

    /** 文本区拖动：整体移动悬浮窗，落盘记忆位置。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(tv: TextView, lp: WindowManager.LayoutParams) {
        var lastX = 0f
        var lastY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += (e.rawX - lastX).roundToInt()
                    lp.y += (e.rawY - lastY).roundToInt()
                    lastX = e.rawX; lastY = e.rawY
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs?.edit()
                        ?.putInt(KEY_POS_X, lp.x)
                        ?.putInt(KEY_POS_Y, lp.y)
                        ?.apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun hide() {
        handler.removeCallbacks(poll)
        textView?.let { v -> runCatching { windowManager?.removeView(v) } }
        textView = null
        params = null
        bgDrawable = null
        shown = false
    }

    // —— 参数实时应用（仅在已显示时改视图，未显示则 show() 时会读取最新值）——
    private fun applyFont(sp: Float) {
        textView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /**
     * 黑底颜色：滑条档 t∈[0,1]（0 最浓 → 1 完全消失），全量程线性。
     * 文字始终不受此档影响。
     */
    private fun bgColorFor(t: Float): Int {
        val maxA = 190 // 最浓时黑底 alpha（约 75%）
        val a = ((1f - t).coerceIn(0f, 1f) * maxA).roundToInt()
        return (a shl 24)
    }

    /**
     * 统一应用外观：单层圆角黑底。黑底浓度由 [bgT] 全量程决定，文字不受影响。
     */
    private fun applyStyle() {
        val tv = textView ?: return
        val radius = 18 * tv.resources.displayMetrics.density
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgColorFor(bgT))
        }
        bgDrawable = fill
        tv.background = fill
    }

    private fun applyWidth(frac: Float) {
        val lp = params ?: return
        lp.width = (screenW() * frac).roundToInt()
        runCatching { windowManager?.updateViewLayout(textView, lp) }
    }

    /** 取当前播放句：歌曲变化时异步解析歌词，之后按 position 取行。 */
    private fun updateLine() {
        val p = player ?: return
        val item = p.currentMediaItem ?: run { setText(""); return }
        // 真实文件路径由 toMediaItem 透传在 MediaMetadata.extras 里；
        // content URI 的 .path 不是文件路径，直接读会取不到歌词，故优先取 extras。
        val path = item.mediaMetadata.extras?.getString("file_path")
            ?: item.localConfiguration?.uri?.let { if (it.scheme == "file") it.path else null }
        if (path.isNullOrBlank()) { setText(""); return }

        if (path != lyricPath && path != loadingPath) {
            loadingPath = path
            lyricPath = path
            lyricLines = emptyList()
            val duration = if (p.duration > 0) p.duration else 0L
            scope.launch {
                val lines = withContext(Dispatchers.IO) {
                    runCatching { LyricParser.loadLyrics(path, duration) }.getOrDefault(emptyList())
                }
                if (loadingPath == path) {
                    lyricLines = lines
                    loadingPath = null
                }
            }
        }

        val lines = lyricLines
        if (lines.isEmpty()) {
            // 还没解析出来 / 没歌词：显示歌名占位，避免空白
            setText(item.mediaMetadata.title?.toString() ?: "")
            return
        }
        val idx = LyricParser.currentIndex(lines, p.currentPosition)
        setText(if (idx in lines.indices) lines[idx].text else "")
    }

    private fun setText(s: String) {
        val tv = textView ?: return
        if (tv.text != s) tv.text = s
    }

    /** 跳转系统悬浮窗授权页。 */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
}