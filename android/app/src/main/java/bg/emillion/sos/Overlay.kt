package bg.emillion.sos

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Три прозореца над всички приложения:
 *  - плаващ бутон SOS (влачи се; задържане 2 сек = тревога / спиране на тревогата)
 *  - мигаща червена рамка по края на екрана — не приема докосвания, всичко отдолу работи
 *  - малка лента горе: кой е колегата, колко далеч, „Тръгвам" и „✕"
 */
class Overlay(
    private val ctx: Context,
    private val onHold: () -> Unit,
    private val onGo: (String) -> Unit,
    private val onDismiss: (String) -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var button: TextView? = null
    private var border: BorderView? = null
    private var banner: LinearLayout? = null
    private var bannerTitle: TextView? = null
    private var bannerInfo: TextView? = null
    private var bannerId: String? = null
    private var alarmOn = false

    private fun px(v: Int) = (v * d).toInt()
    fun allowed() = Settings.canDrawOverlays(ctx)

    // ---------- бутон ----------
    @SuppressLint("ClickableViewAccessibility")
    fun showButton() {
        if (!allowed() || button != null) return
        val size = px(64)
        val v = TextView(ctx).apply {
            text = "SOS"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        paintButton(v, false)
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(12)
            y = px(260)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        val hold2 = Runnable { if (!dragging) { v.text = "…"; onHold() } }
        val hold1 = Runnable { if (!dragging) v.text = "1" }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    v.text = "2"
                    v.postDelayed(hold1, 1000); v.postDelayed(hold2, 2000)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > px(10) || abs(dy) > px(10))) {
                        dragging = true
                        v.removeCallbacks(hold1); v.removeCallbacks(hold2)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold1); v.removeCallbacks(hold2)
                    restoreLabel(v)
                }
            }
            true
        }
        try { wm.addView(v, lp); button = v } catch (_: Exception) {}
    }

    private fun paintButton(v: TextView, alarm: Boolean) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (alarm) 0xFFB71C1C.toInt() else 0xD9D32F2F.toInt())
            setStroke(px(if (alarm) 4 else 2), if (alarm) Color.YELLOW else Color.WHITE)
        }
    }

    private var helpers = 0
    private fun restoreLabel(v: TextView) {
        v.text = if (alarmOn) (if (helpers > 0) "СТОП\n$helpers↗" else "СТОП") else "SOS"
    }

    /** Моята тревога е пусната / спряна. helpers = колко колеги са тръгнали. */
    fun setAlarm(on: Boolean, helpersNow: Int = 0) {
        alarmOn = on; helpers = helpersNow
        button?.let { paintButton(it, on); restoreLabel(it) }
    }

    // ---------- рамка ----------
    fun showBorder() {
        if (!allowed() || border != null) return
        val v = BorderView(ctx, px(10).toFloat())
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ пропуска докосванията през чужд прозорец само ако е ≤ 0.8 непрозрачен
            alpha = 0.8f
        }
        try { wm.addView(v, lp); border = v } catch (_: Exception) {}
    }

    fun hideBorder() {
        border?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        border = null
    }

    // ---------- лента ----------
    fun showBanner(id: String, title: String, info: String) {
        if (!allowed()) return
        bannerId = id
        if (banner == null) {
            val t = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
            val i = TextView(ctx).apply { setTextColor(0xFFFFCDD2.toInt()); textSize = 13f }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(t); addView(i)
            }
            val go = pill("ТРЪГВАМ", 0xFF2E7D32.toInt()) { bannerId?.let(onGo) }
            val x = pill("✕", 0x55FFFFFF) { bannerId?.let(onDismiss) }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), px(10), px(10), px(10))
                background = GradientDrawable().apply { setColor(0xF0B71C1C.toInt()); cornerRadius = px(14).toFloat() }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(go); addView(x)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                y = px(36)
                horizontalMargin = 0.03f
            }
            try { wm.addView(row, lp); banner = row; bannerTitle = t; bannerInfo = i } catch (_: Exception) { return }
        }
        bannerTitle?.text = title
        bannerInfo?.text = info
    }

    fun hideBanner() {
        banner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        banner = null; bannerTitle = null; bannerInfo = null; bannerId = null
    }

    fun removeAll() {
        hideBanner(); hideBorder()
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        button = null
    }

    private fun pill(label: String, color: Int, click: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(px(14), px(10), px(14), px(10))
        background = GradientDrawable().apply { setColor(color); cornerRadius = px(20).toFloat() }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = px(6) }
    }
}

/** Червена рамка, която пулсира. Нищо не рисува вътре — екранът отдолу остава видим. */
class BorderView(ctx: Context, stroke: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFF1744.toInt()
        strokeWidth = stroke
    }
    private val anim = ValueAnimator.ofInt(40, 255).apply {
        duration = 600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { paint.alpha = it.animatedValue as Int; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val h = paint.strokeWidth / 2
        c.drawRect(h, h, width - h, height - h, paint)
    }
}
