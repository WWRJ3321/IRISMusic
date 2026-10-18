package com.iris.music.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
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

    private const val POLL_MS = 16L

    private var appContext: Context? = null
    private var player: Player? = null
    private var windowManager: WindowManager? = null
    
    private var container: FadeEdgeLayout? = null
    private var tvCurrent: LyricTextView? = null
    private var tvNext: LyricTextView? = null
    
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
    
    private var lastLineIdx = -1
    private var isAnimating = false

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
                    tvCurrent?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.getFloat(KEY_FONT, FONT_DEFAULT))
                    tvNext?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.getFloat(KEY_FONT, FONT_DEFAULT))
                    hide()
                    if (enabled() && Settings.canDrawOverlays(context)) show(context)
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

        val box = FadeEdgeLayout(ctx)
        box.clipChildren = true

        val widthPx = (screenW() * sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT)).roundToInt()
        lastWidth = widthPx

        val fontSp = sp.getFloat(KEY_FONT, FONT_DEFAULT)
        val density = ctx.resources.displayMetrics.density
        
        val measurePaint = Paint().apply {
            textSize = fontSp * ctx.resources.displayMetrics.scaledDensity
        }
        val fm = measurePaint.fontMetrics
        val textHeight = fm.bottom - fm.top
        val padH = (16 * density).toInt()
        val padV = (8 * density).toInt()
        val boxHeight = (textHeight + padV * 2).toInt()

        val lp = WindowManager.LayoutParams(
            widthPx, boxHeight,
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
        box.setPadding(padH, padV, padH, padV)

        tvCurrent = LyricTextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        
        tvNext = LyricTextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            translationY = boxHeight.toFloat()
            alpha = 0f
        }

        box.addView(tvCurrent)
        box.addView(tvNext)
        
        runCatching { wm.addView(box, lp) }.onFailure { return }
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
        var lastX = 0f
        var lastY = 0f
        box.setOnTouchListener { v, e ->
            // 完全固定：吃掉所有点击
            if (isFixed && fixMode == 1) return@setOnTouchListener true

            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val text = tvCurrent?.text?.toString() ?: ""
                    val paint = tvCurrent?.paint ?: return@setOnTouchListener false
                    
                    val textWidth = paint.measureText(text)
                    val fm = paint.fontMetrics
                    val textHeight = fm.bottom - fm.top
                    
                    // 计算文字在容器内的绝对矩形边界
                    val textLeft = (box.width - textWidth) / 2f
                    val textRight = textLeft + textWidth
                    val textTop = (box.height - textHeight) / 2f
                    val textBottom = textTop + textHeight
                    
                    // 像素级判定：不在文字范围内就穿透
                    if (e.x < textLeft || e.x > textRight || e.y < textTop || e.y > textBottom) {
                        return@setOnTouchListener false
                    }

                    lastX = e.rawX
                    lastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).roundToInt()
                    val dy = (e.rawY - lastY).roundToInt()

                    if (!isFixed) {
                        lp.x += dx
                        lp.y += dy
                    } else if (fixMode == 0) {
                        lp.x = (screenW() - lp.width) / 2
                        lp.y += dy
                    }

                    lastX = e.rawX
                    lastY = e.rawY
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isFixed) {
                        sp.edit().putInt(KEY_POS_X, lp.x).apply()
                    }
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
        tvCurrent = null
        tvNext = null
        params = null
        bgDrawable = null
        shown = false
        lastLineIdx = -1
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
                if (loadingPath == path) { lyricLines = lines; loadingPath = null; lastLineIdx = -1 }
            }
        }

        val lines = lyricLines
        if (lines.isEmpty()) { 
            val title = item.mediaMetadata.title?.toString() ?: ""
            if (tvCurrent?.text?.toString() != title) setText(title) 
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
            
            val (c1, c2) = getColors()
            val unsungColor = Color.parseColor("#66FFFFFF")

            if (lastLineIdx == -1) {
                lastLineIdx = idx
                tvCurrent?.text = line.text
                tvCurrent?.updateState(frac, c1, c2, unsungColor, isKaraoke)
            } else if (idx != lastLineIdx) {
                lastLineIdx = idx
                if (!isAnimating) {
                    startScrollAnim(line.text, frac, c1, c2, unsungColor)
                } else {
                    isAnimating = false
                    tvCurrent?.text = line.text
                    tvCurrent?.translationY = 0f
                    tvCurrent?.alpha = 1f
                    tvNext?.alpha = 0f
                    tvCurrent?.updateState(frac, c1, c2, unsungColor, isKaraoke)
                }
            } else {
                if (isAnimating) {
                    tvNext?.updateState(frac, c1, c2, unsungColor, isKaraoke)
                } else {
                    tvCurrent?.updateState(frac, c1, c2, unsungColor, isKaraoke)
                }
            }
        } else { setText("") }
    }

    private fun startScrollAnim(nextText: String, nextFrac: Float, c1: Int, c2: Int, unsungColor: Int) {
        val cur = tvCurrent ?: return
        val nxt = tvNext ?: return
        val box = container ?: return
        isAnimating = true

        nxt.text = nextText
        nxt.updateState(nextFrac, c1, c2, unsungColor, isKaraoke)
        nxt.translationY = box.height.toFloat()
        nxt.alpha = 1f
        
        cur.isKaraoke = false

        val curAnimY = ObjectAnimator.ofFloat(cur, "translationY", 0f, -box.height.toFloat())
        val curAnimAlpha = ObjectAnimator.ofFloat(cur, "alpha", 1f, 0f)
        val nxtAnimY = ObjectAnimator.ofFloat(nxt, "translationY", box.height.toFloat(), 0f)
        
        val set = AnimatorSet()
        set.playTogether(curAnimY, curAnimAlpha, nxtAnimY)
        set.duration = 450 
        set.interpolator = AccelerateDecelerateInterpolator()
        
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val temp = tvCurrent
                tvCurrent = tvNext
                tvNext = temp
                
                tvNext?.translationY = box.height.toFloat()
                tvNext?.alpha = 0f
                
                isAnimating = false
            }
        })
        set.start()
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
        lastLineIdx = -1
        isAnimating = false
        val cur = tvCurrent ?: return
        val nxt = tvNext ?: return
        val box = container ?: return
        
        cur.text = s
        cur.frac = 1f
        cur.isKaraoke = false
        cur.translationY = 0f
        cur.alpha = 1f
        
        nxt.alpha = 0f
        nxt.translationY = box.height.toFloat()
    }

    fun requestPermission(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
}

class LyricTextView(context: Context) : TextView(context) {
    var frac = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
        
    var c1 = Color.WHITE
    var c2 = Color.WHITE
    var unsungColor = Color.GRAY
    var isKaraoke = false

    init {
        maxLines = 1
        ellipsize = null 
        gravity = Gravity.CENTER 
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }
    
    fun updateState(frac: Float, c1: Int, c2: Int, unsungColor: Int, karaoke: Boolean) {
        this.c1 = c1
        this.c2 = c2
        this.unsungColor = unsungColor
        this.isKaraoke = karaoke
        this.frac = frac
    }

    override fun onDraw(canvas: Canvas) {
        if (!isKaraoke || frac >= 0.999f) {
            paint.shader = null
            if (c1 != c2) {
                val textW: Float = paint.measureText(text.toString())
                paint.shader = LinearGradient(0f, 0f, textW, 0f, intArrayOf(c1, c2), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            } else {
                setTextColor(c1)
            }
            super.onDraw(canvas)
            return
        }
        
        if (frac <= 0.001f) {
            paint.shader = null
            setTextColor(unsungColor)
            super.onDraw(canvas)
            return
        }

        paint.shader = null
        setTextColor(unsungColor)
        super.onDraw(canvas)

        val textW: Float = paint.measureText(text.toString())
        val progressW: Float = textW * frac
        
        if (progressW > 0) {
            canvas.save()
            val paddingX = (width - textW) / 2f
            val clipLeft = if (paddingX > 0) paddingX else 0f
            val clipRight = clipLeft + progressW
            canvas.clipRect(clipLeft, 0f, clipRight, height.toFloat())
            
            if (c1 != c2) {
                paint.shader = LinearGradient(0f, 0f, textW, 0f, intArrayOf(c1, c2), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            } else {
                paint.shader = null
                setTextColor(c1)
            }
            super.onDraw(canvas)
            canvas.restore()
        }
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
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, fadeRatio, 1f - fadeRatio, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restoreToCount(sc)
    }
}
