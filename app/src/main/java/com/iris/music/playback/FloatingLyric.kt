package com.iris.music.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.Player
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.ui.theme.IrisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object FloatingLyric {

    const val PREFS = "iris_prefs"
    const val KEY_ENABLED = "float_lyric_enabled"
    const val KEY_FONT = "float_lyric_font"
    const val KEY_WIDTH = "float_lyric_width"
    const val KEY_BG = "float_lyric_bg"
    const val KEY_POS_X = "float_lyric_x"
    const val KEY_POS_Y = "float_lyric_y"
    
    const val KEY_USE_CUSTOM = "float_lyric_use_custom"
    const val KEY_KARAOKE = "float_lyric_karaoke"
    const val KEY_FIXED = "float_lyric_fixed"
    const val KEY_FIX_MODE = "float_lyric_fix_mode"
    const val KEY_THEME = "float_lyric_theme"
    const val KEY_CUSTOM_PRIMARY = "float_lyric_custom_primary"
    const val KEY_CUSTOM_SECONDARY = "float_lyric_custom_secondary"

    const val FONT_DEFAULT = 18f
    const val WIDTH_DEFAULT = 0.86f
    const val BG_DEFAULT = 0.3f

    /** 进度轮询周期：50ms 保证逐字流光丝滑跟手 */
    private const val POLL_MS = 50L

    private var appContext: Context? = null
    private var player: Player? = null
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bgDrawable: GradientDrawable? = null

    private var bgT = BG_DEFAULT
    private var isFixed = false
    private var fixMode = 0
    private var isKaraoke = false
    private var useCustom = false
    private var theme = IrisTheme.MONO
    private var customPrimary = 0xFF00F0FFL
    private var customSecondary = 0xFFFF2E97L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var prefs: SharedPreferences? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var lyricPath: String? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private var loadingPath: String? = null

    private var shown = false
    private var lastWidth = 0
    
    private var lastSplit = -1
    private var lastLineText = ""

    private val poll = object : Runnable {
        override fun run() {
            updateLine()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun init(context: Context, p: Player) {
        appContext = context.applicationContext
        player = p
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        loadPrefs(sp)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_FONT -> textView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.getFloat(KEY_FONT, FONT_DEFAULT))
                KEY_WIDTH -> {
                    loadPrefs(sp)
                    applyWidth(sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT))
                }
                KEY_BG -> { bgT = sp.getFloat(KEY_BG, BG_DEFAULT); applyStyle() }
                KEY_ENABLED -> syncVisibility()
                KEY_KARAOKE, KEY_FIXED, KEY_FIX_MODE, KEY_THEME, KEY_CUSTOM_PRIMARY, KEY_CUSTOM_SECONDARY, KEY_USE_CUSTOM -> {
                    loadPrefs(sp)
                    if (key == KEY_FIXED || key == KEY_FIX_MODE) recalcCenter()
                    lastLineText = "" // 强制刷新
                }
            }
        }
        prefListener = listener
        sp.registerOnSharedPreferenceChangeListener(listener)
        syncVisibility()
    }

    private fun loadPrefs(sp: SharedPreferences) {
        bgT = sp.getFloat(KEY_BG, BG_DEFAULT)
        isFixed = sp.getBoolean(KEY_FIXED, false)
        fixMode = sp.getInt(KEY_FIX_MODE, 0)
        isKaraoke = sp.getBoolean(KEY_KARAOKE, false)
        useCustom = sp.getBoolean(KEY_USE_CUSTOM, false)
        theme = runCatching { IrisTheme.valueOf(sp.getString(KEY_THEME, IrisTheme.MONO.name)!!) }.getOrDefault(IrisTheme.MONO)
        customPrimary = sp.getLong(KEY_CUSTOM_PRIMARY, 0xFF00F0FFL)
        customSecondary = sp.getLong(KEY_CUSTOM_SECONDARY, 0xFFFF2E97L)
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
    fun refresh() = syncVisibility()

    private fun syncVisibility() {
        val ctx = appContext ?: return
        if (enabled() && Settings.canDrawOverlays(ctx)) show(ctx) else hide()
    }

    private fun screenW(): Int = appContext?.resources?.displayMetrics?.widthPixels ?: 1080

    @SuppressLint("ClickableViewAccessibility")
    private fun show(ctx: Context) {
        if (shown) return
        val wm = windowManager ?: return
        val sp = prefs ?: return

        val tv = TextView(ctx).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.getFloat(KEY_FONT, FONT_DEFAULT))
        }

        val widthPx = (screenW() * sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT)).roundToInt()
        lastWidth = widthPx

        val lp = WindowManager.LayoutParams(
            widthPx, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_POS_X, 0)
            y = sp.getInt(KEY_POS_Y, (120 * ctx.resources.displayMetrics.density).toInt())
        }

        if (isFixed && fixMode == 0) {
            lp.x = (screenW() - widthPx) / 2
        }

        attachDrag(tv, lp, sp)
        runCatching { wm.addView(tv, lp) }.onFailure { return }
        textView = tv; params = lp; shown = true
        applyStyle()
        handler.post(poll)
    }

    private fun recalcCenter() {
        val lp = params ?: return
        if (isFixed && fixMode == 0) {
            lp.x = (screenW() - lastWidth) / 2
            runCatching { windowManager?.updateViewLayout(textView, lp) }
        }
    }

    private fun attachDrag(tv: TextView, lp: WindowManager.LayoutParams, sp: SharedPreferences) {
        var lastX = 0f; var lastY = 0f
        tv.setOnTouchListener { v, e ->
            if (isFixed && fixMode == 1) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).roundToInt()
                    val dy = (e.rawY - lastY).roundToInt()
                    if (!(isFixed && fixMode == 0)) lp.x += dx
                    lp.y += dy
                    lastX = e.rawX; lastY = e.rawY
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!(isFixed && fixMode == 0)) sp.edit().putInt(KEY_POS_X, lp.x).apply()
                    sp.edit().putInt(KEY_POS_Y, lp.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun hide() {
        handler.removeCallbacks(poll)
        textView?.let { v -> runCatching { windowManager?.removeView(v) } }
        textView = null; params = null; bgDrawable = null; shown = false
    }

    private fun applyStyle() {
        val tv = textView ?: return
        val fill = GradientDrawable().apply {
            cornerRadius = 18 * tv.resources.displayMetrics.density
            setColor(((1f - bgT).coerceIn(0f, 1f) * 190).roundToInt().let { (it shl 24) })
        }
        bgDrawable = fill; tv.background = fill
    }

    private fun applyWidth(frac: Float) {
        val lp = params ?: return
        lastWidth = (screenW() * frac).roundToInt()
        lp.width = lastWidth
        if (isFixed && fixMode == 0) lp.x = (screenW() - lastWidth) / 2
        runCatching { windowManager?.updateViewLayout(textView, lp) }
        lastLineText = "" // 宽度变化，重新计算渐变
    }

    private fun updateLine() {
        val p = player ?: return
        val item = p.currentMediaItem ?: run { setText(""); return }
        val path = item.mediaMetadata.extras?.getString("file_path") ?: item.localConfiguration?.uri?.let { if (it.scheme == "file") it.path else null }
        if (path.isNullOrBlank()) { setText(""); return }

        if (path != lyricPath && path != loadingPath) {
            loadingPath = path; lyricPath = path; lyricLines = emptyList()
            val duration = if (p.duration > 0) p.duration else 0L
            scope.launch {
                val lines = withContext(Dispatchers.IO) { runCatching { LyricParser.loadLyrics(path, duration) }.getOrDefault(emptyList()) }
                if (loadingPath == path) { lyricLines = lines; loadingPath = null; lastLineText = "" }
            }
        }

        val lines = lyricLines
        if (lines.isEmpty()) { 
            val title = item.mediaMetadata.title?.toString() ?: ""
            if (lastLineText != title) setText(title) 
            return 
        }
        
        val idx = LyricParser.currentIndex(lines, p.currentPosition)
        if (idx in lines.indices) {
            val line = lines[idx]
            val nextLine = lines.getOrNull(idx + 1)?.timeMs ?: (if (p.duration > 0) p.duration else line.timeMs + 4000)
            
            if (isKaraoke) {
                // 【核心修复 1】：加入伪逐字计算。没有词级时间标签时，按时间均分进度。
                val progressF = LyricParser.wordProgressCharsF(line, p.currentPosition, nextLine)
                val frac = if (progressF >= 0f) {
                    progressF / line.text.length.coerceAtLeast(1)
                } else {
                    val span = (nextLine - line.timeMs).coerceAtLeast(1L)
                    val est = (line.text.length * 300L).coerceAtLeast(600L)
                    val singMs = minOf(est, span).coerceAtLeast((span * 0.92f).toLong())
                    ((p.currentPosition - line.timeMs).toFloat() / singMs).coerceIn(0f, 1f)
                }
                val split = (line.text.length * frac).toInt().coerceIn(0, line.text.length)
                setRichText(line.text, split)
            } else {
                setRichText(line.text, line.text.length)
            }
        } else { setText("") }
    }

    private fun getColors(): Pair<Int, Int> {
        if (!useCustom) return Color.WHITE to Color.WHITE
        return when (theme) {
            IrisTheme.MONO -> Color.WHITE to Color.WHITE
            IrisTheme.OCEAN -> 0xFF3D7BFF.toInt() to 0xFF00F0FF.toInt()
            IrisTheme.GRASS -> 0xFF2EE6A8.toInt() to 0xFFB4FF39.toInt()
            IrisTheme.CUSTOM -> customPrimary.toInt() to customSecondary.toInt()
            else -> Color.WHITE to Color.GRAY
        }
    }

    private fun setText(s: String) {
        lastLineText = s
        lastSplit = -1
        textView?.apply {
            // 恢复默认阴影和纯色
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
            paint.shader = null
            setTextColor(Color.WHITE)
            text = s
        }
    }

    /**
     * 【核心修复 2】：彻底解决模糊问题。
     * 已唱部分不加 Span，直接让渐变色透出来；未唱部分加半透明 Span。
     * 同时，在逐字模式关闭阴影，彻底消除边缘发光！
     */
    private fun setRichText(text: String, split: Int) {
        val tv = textView ?: return
        if (text == lastLineText && split == lastSplit) return
        lastLineText = text
        lastSplit = split
        
        val (c1, c2) = getColors()
        val unsungColor = Color.parseColor("#88FFFFFF")
        
        if (split >= text.length) {
            // 已经唱完
            tv.setShadowLayer(6f, 0f, 1f, 0x99000000.toInt()) // 恢复阴影
            if (useCustom) {
                tv.paint.shader = LinearGradient(0f, 0f, tv.width.toFloat().coerceAtLeast(1f), 0f, c1, c2, Shader.TileMode.CLAMP)
                tv.setTextColor(c1)
            } else {
                tv.paint.shader = null
                tv.setTextColor(c1)
            }
            tv.text = text
        } else {
            // 逐字中
            tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) // 【关键】关闭阴影，防止透明字出现模糊光晕！
            if (useCustom) {
                tv.paint.shader = LinearGradient(0f, 0f, tv.width.toFloat().coerceAtLeast(1f), 0f, c1, c2, Shader.TileMode.CLAMP)
                tv.setTextColor(c1)
            } else {
                tv.paint.shader = null
                tv.setTextColor(c1)
            }
            
            val ss = SpannableString(text)
            // 已唱部分（0 到 split）：不添加任何 Span，让它自动使用 paint.shader 透出渐变！
            // 未唱部分（split 到 end）：添加半透明暗色覆盖。
            if (split < text.length) {
                ss.setSpan(ForegroundColorSpan(unsungColor), split, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tv.text = ss
        }
    }

    fun requestPermission(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
}
