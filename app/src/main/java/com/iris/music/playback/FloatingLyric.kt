package com.iris.music.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
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
import android.widget.FrameLayout
import androidx.media3.common.Player
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.ui.theme.IrisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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

    private const val POLL_MS = 16L

    private var appContext: Context? = null
    private var player: Player? = null
    private var windowManager: WindowManager? = null
    
    private var container: FadeEdgeLayout? = null
    private var lyricView: LyricRenderView? = null
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
    
    private var lastFrac = -1f
    private var lastLineIdx = -1

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
                KEY_FONT -> {
                    lyricView?.textSizeSp = sp.getFloat(KEY_FONT, FONT_DEFAULT)
                    lyricView?.requestLayoutAndInvalidate()
                }
                KEY_WIDTH -> {
                    loadPrefs(sp)
                    applyWidth(sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT))
                }
                KEY_BG -> { bgT = sp.getFloat(KEY_BG, BG_DEFAULT); applyStyle() }
                KEY_ENABLED -> syncVisibility()
                KEY_KARAOKE, KEY_FIXED, KEY_FIX_MODE, KEY_THEME, KEY_CUSTOM_PRIMARY, KEY_CUSTOM_SECONDARY, KEY_USE_CUSTOM -> {
                    loadPrefs(sp)
                    if (key == KEY_FIXED || key == KEY_FIX_MODE) recalcCenter()
                    lastLineIdx = -1 
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

        val view = LyricRenderView(ctx).apply {
            textSizeSp = sp.getFloat(KEY_FONT, FONT_DEFAULT)
        }

        val box = FadeEdgeLayout(ctx)

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

        attachDrag(box, lp, sp)
        
        val density = ctx.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (12 * density).toInt()
        box.setPadding(padH, padV, padH, padV)
        
        box.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        runCatching { wm.addView(box, lp) }.onFailure { return }
        lyricView = view
        container = box
        params = lp
        shown = true
        applyStyle()
        handler.post(poll)
    }

    private fun recalcCenter() {
        val lp = params ?: return
        if (isFixed && fixMode == 0) {
            lp.x = (screenW() - lastWidth) / 2
            runCatching { windowManager?.updateViewLayout(container, lp) }
        }
    }

    private fun attachDrag(box: FrameLayout, lp: WindowManager.LayoutParams, sp: SharedPreferences) {
        var lastX = 0f; var lastY = 0f
        box.setOnTouchListener { v, e ->
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
        container?.let { v -> runCatching { windowManager?.removeView(v) } }
        container = null
        lyricView = null
        params = null
        bgDrawable = null
        shown = false
    }

    private fun applyStyle() {
        val box = container ?: return
        val fill = GradientDrawable().apply {
            cornerRadius = 18f * box.resources.displayMetrics.density
            setColor(((1f - bgT).coerceIn(0f, 1f) * 190f).roundToInt().let { (it shl 24) })
        }
        bgDrawable = fill
        box.background = fill
    }

    private fun applyWidth(frac: Float) {
        val lp = params ?: return
        lastWidth = (screenW() * frac).roundToInt()
        lp.width = lastWidth
        if (isFixed && fixMode == 0) lp.x = (screenW() - lastWidth) / 2
        runCatching { windowManager?.updateViewLayout(container, lp) }
        lastLineIdx = -1 
    }

    private fun updateLine() {
        val p = player ?: return
        val item = p.currentMediaItem ?: run { lyricView?.updateContent("", "", "", 0f, 0, 0, 0, false); return }
        val path = item.mediaMetadata.extras?.getString("file_path") ?: item.localConfiguration?.uri?.let { if (it.scheme == "file") it.path else null }
        if (path.isNullOrBlank()) { lyricView?.updateContent("", "", "", 0f, 0, 0, 0, false); return }

        if (path != lyricPath && path != loadingPath) {
            loadingPath = path; lyricPath = path; lyricLines = emptyList()
            val duration = if (p.duration > 0) p.duration else 0L
            scope.launch {
                val lines = withContext(Dispatchers.IO) { runCatching { LyricParser.loadLyrics(path, duration) }.getOrDefault(emptyList()) }
                if (loadingPath == path) { lyricLines = lines; loadingPath = null; lastLineIdx = -1 }
            }
        }

        val lines = lyricLines
        if (lines.isEmpty()) { 
            val title = item.mediaMetadata.title?.toString() ?: ""
            lyricView?.updateContent("", title, "", 0f, 0, 0, 0, false) 
            return 
        }
        
        val idx = LyricParser.currentIndex(lines, p.currentPosition)
        if (idx in lines.indices) {
            val line = lines[idx]
            val nextLine = lines.getOrNull(idx + 1)?.timeMs ?: (if (p.duration > 0) p.duration else line.timeMs + 4000L)
            
            var frac = 1f
            if (isKaraoke) {
                val progressF = LyricParser.wordProgressCharsF(line, p.currentPosition, nextLine)
                frac = if (progressF >= 0f) {
                    progressF / line.text.length.coerceAtLeast(1).toFloat()
                } else {
                    val span = (nextLine - line.timeMs).coerceAtLeast(1L).toFloat()
                    val est = (line.text.length * 300L).coerceAtLeast(600L).toFloat()
                    val singMs = minOf(est, span).coerceAtLeast((span * 0.92f))
                    ((p.currentPosition - line.timeMs).toFloat() / singMs).coerceIn(0f, 1f)
                }
            }
            updateRenderView(lines, idx, frac)
        } else { lyricView?.updateContent("", "", "", 0f, 0, 0, 0, false) }
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

    private fun updateRenderView(lines: List<LyricLine>, idx: Int, frac: Float) {
        val view = lyricView ?: return
        if (idx == lastLineIdx && abs(frac - lastFrac) < 0.001f) return
        lastLineIdx = idx
        lastFrac = frac

        val prevText = lines.getOrNull(idx - 1)?.text ?: ""
        val currText = lines[idx].text
        val nextText = lines.getOrNull(idx + 1)?.text ?: ""
        val (c1, c2) = getColors()

        view.updateContent(prevText, currText, nextText, frac, c1, c2, Color.parseColor("#66FFFFFF"), isKaraoke)
    }

    fun requestPermission(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
}

class LyricRenderView(context: Context) : View(context) {
    var textSizeSp: Float = 18f
        set(value) {
            field = value
            paint.textSize = value * context.resources.displayMetrics.scaledDensity
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizeSp * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }

    private var prevText = ""
    private var currText = ""
    private var nextText = ""
    private var frac = 0f
    private var isKaraoke = false
    private var c1 = Color.WHITE
    private var c2 = Color.WHITE
    private var unsungColor = Color.GRAY

    private val GAP = "   "

    fun updateContent(p: String, c: String, n: String, f: Float, col1: Int, col2: Int, unsung: Int, karaoke: Boolean) {
        prevText = p
        currText = c
        nextText = n
        frac = f
        c1 = col1
        c2 = col2
        unsungColor = unsung
        isKaraoke = karaoke
        invalidate()
    }

    fun requestLayoutAndInvalidate() {
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val height = (fm.bottom - fm.top).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currText.isEmpty()) return

        // 把所有尺寸强制转为 Float，杜绝类型推导报错
        val viewW = (width - paddingLeft - paddingRight).toFloat()
        if (viewW <= 0f) return

        val fullText = buildString {
            if (prevText.isNotEmpty()) { append(prevText); append(GAP) }
            append(currText)
            if (nextText.isNotEmpty()) { append(GAP); append(nextText) }
        }

        val prevW = if (prevText.isNotEmpty()) paint.measureText(prevText + GAP) else 0f
        val currW = paint.measureText(currText)
        val totalW = paint.measureText(fullText)

        val focusX = prevW + currW * frac

        val translateX = (viewW / 2f) - focusX
        
        canvas.save()
        // 全部显式转化为 Float
        val finalX = paddingLeft.toFloat() + translateX
        val finalY = paddingTop.toFloat()
        canvas.translate(finalX, finalY)

        paint.shader = null
        paint.color = if (c1 != c2) c1 else (if (isKaraoke && frac < 0.999f) unsungColor else c1)
        
        if (isKaraoke && frac > 0f) {
            paint.color = unsungColor
            canvas.drawText(fullText, 0f, -paint.fontMetrics.top, paint)
            
            canvas.save()
            
            // 显式转化为 Float，彻底解决参数推断问题
            val cLeft = 0f
            val cTop = -paint.fontMetrics.ascent
            val cRight = focusX
            val cBottom = -paint.fontMetrics.top
            canvas.clipRect(cLeft, cTop, cRight, cBottom)
            
            if (c1 != c2) {
                paint.shader = LinearGradient(0f, 0f, totalW, 0f, intArrayOf(c1, c2), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            } else {
                paint.shader = null
                paint.color = c1
            }
            canvas.drawText(fullText, 0f, -paint.fontMetrics.top, paint)
            canvas.restore()
            
        } else {
            if (c1 != c2) {
                paint.shader = LinearGradient(0f, 0f, totalW, 0f, intArrayOf(c1, c2), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            } else {
                paint.shader = null
                paint.color = c1
            }
            canvas.drawText(fullText, 0f, -paint.fontMetrics.top, paint)
        }
        
        canvas.restore()
    }
}

class FadeEdgeLayout(context: Context) : FrameLayout(context) {
    private val paint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            val fadeRatio = 0.15f
            // 强制 Float
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, fadeRatio, 1f - fadeRatio, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // canvas.saveLayer 参数全部转为 Float
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restoreToCount(sc)
    }
}
