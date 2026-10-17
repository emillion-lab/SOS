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

/** Бутон в картата над приложенията. */
data class Btn(val label: String, val color: Int, val action: () -> Unit)

/**
 * Прозорци над всички приложения:
 *  - плаващ бутон SOS (влачи се; задържане 2 сек = тревога / спиране на моята тревога)
 *  - мигаща червена рамка по края — не приема докосвания, всичко отдолу работи
 *  - карта горе: или моята тревога (запис, точна локация, 112, спри), или чужда (чуй, карта, навигация)
 */
class Overlay(private val ctx: Context, private val onHold: () -> Unit) {
    companion object {
        const val GREEN = 0xFF2E7D32.toInt()
        const val BLUE = 0xFF1E88E5.toInt()
        const val WAZE = 0xFF33CCFF.toInt()
        const val GLASS = 0x55FFFFFF
        const val DARK = 0x33000000
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var button: TextView? = null
    private var border: BorderView? = null
    private var card: LinearLayout? = null
    private var cardTitle: TextView? = null
    private var cardInfo: TextView? = null
    private var cardSig: String? = null
    private var current: List<List<Btn>> = emptyList()
    private var alarmOn = false
    private var helpers = 0

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
        val lp = WindowManager.LayoutParams(size, size, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(12)
            y = px(320)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        // Броячът е в балонче НАД бутона — под пръста не се вижда. Точно 2 сек: „2" → „1" → тревога.
        val fire = Runnable { if (!dragging) { hideBubble(); v.text = "…"; onHold() } }
        val tick1 = Runnable { if (!dragging) showBubble(lp, if (alarmOn) "Спира след 1" else "1") }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    showBubble(lp, if (alarmOn) "Спира след 2" else "2")
                    v.postDelayed(tick1, 1000); v.postDelayed(fire, 2000)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > px(10) || abs(dy) > px(10))) {
                        dragging = true
                        v.removeCallbacks(tick1); v.removeCallbacks(fire)
                        hideBubble()
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(tick1); v.removeCallbacks(fire)
                    hideBubble()
                    restoreLabel(v)
                }
            }
            true
        }
        try { wm.addView(v, lp); button = v } catch (_: Exception) {}
    }

    // ---------- балонче с брояча над бутона ----------
    private var bubble: TextView? = null

    private fun showBubble(at: WindowManager.LayoutParams, text: String) {
        if (!allowed()) return
        val b = bubble ?: TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(18), px(6), px(18), px(6))
            background = GradientDrawable().apply { setColor(0xE6B71C1C.toInt()); cornerRadius = px(24).toFloat() }
        }
        b.text = text
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = at.x
            y = (at.y - px(70)).coerceAtLeast(0) // над пръста
        }
        try {
            if (bubble == null) { wm.addView(b, lp); bubble = b } else wm.updateViewLayout(b, lp)
        } catch (_: Exception) {}
    }

    private fun hideBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun paintButton(v: TextView, alarm: Boolean) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (alarm) 0xFFB71C1C.toInt() else 0xD9D32F2F.toInt())
            setStroke(px(if (alarm) 4 else 2), if (alarm) Color.YELLOW else Color.WHITE)
        }
    }

    private fun restoreLabel(v: TextView) {
        v.text = if (alarmOn) (if (helpers > 0) "СТОП\n$helpers↗" else "СТОП") else "SOS"
    }

    /** Моята тревога е пусната / спряна. helpers = колко идват. */
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { alpha = 0.8f } // Android 12+ пропуска докосванията само през ≤ 0.8 непрозрачен прозорец
        try { wm.addView(v, lp); border = v } catch (_: Exception) {}
    }

    fun hideBorder() {
        border?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        border = null
    }

    // ---------- карта ----------
    /**
     * Показва или обновява картата горе. rows = редове бутони. Ако наборът бутони е същият,
     * се сменя само текстът — без премигване.
     */
    fun showCard(title: String, info: String, rows: List<List<Btn>>, bg: Int = 0xF0B71C1C.toInt()) {
        if (!allowed()) return
        val sig = rows.joinToString("|") { r -> r.joinToString(",") { it.label } } + bg
        current = rows
        if (card != null && sig == cardSig) {
            cardTitle?.text = title; cardInfo?.text = info
            return
        }
        hideCard()
        current = rows
        val t = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; text = title }
        val i = TextView(ctx).apply { setTextColor(0xFFFFE0E0.toInt()); textSize = 13f; setPadding(0, px(4), 0, px(6)); text = info }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(10), px(10))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = px(14).toFloat() }
            addView(t); addView(i)
        }
        rows.forEachIndexed { ri, row ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(3), 0, px(3)) }
            row.forEachIndexed { bi, b -> line.addView(pill(b.label, b.color) { current.getOrNull(ri)?.getOrNull(bi)?.action?.invoke() }) }
            box.addView(line)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP; y = px(36); horizontalMargin = 0.03f }
        try { wm.addView(box, lp); card = box; cardTitle = t; cardInfo = i; cardSig = sig } catch (_: Exception) {}
    }

    fun hideCard() {
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null; cardTitle = null; cardInfo = null; cardSig = null
    }

    fun removeAll() {
        hideCard(); hideBorder(); hideBubble()
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        button = null
    }

    private fun pill(label: String, color: Int, click: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(px(8), px(10), px(8), px(10))
        background = GradientDrawable().apply { setColor(color); cornerRadius = px(20).toFloat() }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(3); rightMargin = px(3) }
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
