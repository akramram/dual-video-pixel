package com.akram.dualvideo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).toInt()

/** Colours: black chrome, white ink, one red accent that only marks recording. */
object Palette {
    val WHITE = Color.WHITE
    val BLACK = Color.BLACK
    /** White 20% over black: 'unselected' fill, icons on it stay >= 3:1. */
    const val CHIP = 0x33FFFFFF
    /** Black 60%: backing for text laid directly over the viewfinder (white text >= 5.7:1 worst case). */
    const val SCRIM = 0x99000000.toInt()
    /** #E5322D on black = 4.8:1. */
    const val RED = 0xFFE5322D.toInt()
}

fun pill(color: Int, radiusDp: Float, ctx: Context) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.dp(radiusDp)
}

enum class Glyph { SWAP, PAUSE, PLAY, SPLIT, STACK, PIP }

private val glyphPath = Path()

/** Draws [g] inside [r]; [p] supplies colour and stroke width. */
fun drawGlyph(c: Canvas, g: Glyph, r: RectF, p: Paint, stroke: Float) {
    val w = r.width()
    val h = r.height()
    val corner = w * 0.14f
    p.style = Paint.Style.STROKE
    p.strokeWidth = stroke
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    val inset = stroke / 2
    when (g) {
        Glyph.SPLIT -> {
            c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, corner, corner, p)
            c.drawLine(r.left + inset, r.centerY(), r.right - inset, r.centerY(), p)
        }
        Glyph.STACK -> {
            val x0 = r.left + w * 0.2f
            val x1 = r.right - w * 0.2f
            c.drawRoundRect(x0, r.top + inset, x1, r.centerY() - stroke, corner, corner, p)
            c.drawRoundRect(x0, r.centerY() + stroke, x1, r.bottom - inset, corner, corner, p)
        }
        Glyph.PIP -> {
            c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, corner, corner, p)
            p.style = Paint.Style.FILL
            c.drawRoundRect(r.left + w * 0.5f, r.top + h * 0.16f, r.right - w * 0.14f, r.top + h * 0.5f, corner / 2, corner / 2, p)
        }
        Glyph.SWAP -> {
            val ax = r.left + w * 0.28f
            val bx = r.right - w * 0.28f
            val head = w * 0.17f
            c.drawLine(ax, r.bottom - inset, ax, r.top + inset, p)
            c.drawLine(ax, r.top + inset, ax - head, r.top + inset + head, p)
            c.drawLine(ax, r.top + inset, ax + head, r.top + inset + head, p)
            c.drawLine(bx, r.top + inset, bx, r.bottom - inset, p)
            c.drawLine(bx, r.bottom - inset, bx - head, r.bottom - inset - head, p)
            c.drawLine(bx, r.bottom - inset, bx + head, r.bottom - inset - head, p)
        }
        Glyph.PAUSE -> {
            p.style = Paint.Style.FILL
            val bw = w * 0.22f
            c.drawRoundRect(r.left + w * 0.18f, r.top, r.left + w * 0.18f + bw, r.bottom, bw / 3, bw / 3, p)
            c.drawRoundRect(r.right - w * 0.18f - bw, r.top, r.right - w * 0.18f, r.bottom, bw / 3, bw / 3, p)
        }
        Glyph.PLAY -> {
            p.style = Paint.Style.FILL
            glyphPath.rewind()
            glyphPath.moveTo(r.left + w * 0.2f, r.top)
            glyphPath.lineTo(r.right, r.centerY())
            glyphPath.lineTo(r.left + w * 0.2f, r.bottom)
            glyphPath.close()
            c.drawPath(glyphPath, p)
        }
    }
}

/** Round 48dp button with a drawn glyph. Draws a focus ring when focused (keyboard / d-pad). */
class GlyphButton(context: Context, glyph: Glyph, description: String) : View(context) {
    var glyph: Glyph = glyph
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CHIP }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.WHITE; strokeWidth = context.dp(2f) }
    private val box = RectF()

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
        minimumWidth = context.dpi(52f)
        minimumHeight = context.dpi(52f)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dpi(52f)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, min(cx, cy), bg)
        if (isFocused) canvas.drawCircle(cx, cy, min(cx, cy) - ring.strokeWidth / 2, ring)
        val half = context.dp(11f)
        box.set(cx - half, cy - half, cx + half, cy + half)
        paint.color = if (isEnabled) Palette.WHITE else 0x66FFFFFF
        drawGlyph(canvas, glyph, box, paint, context.dp(2f))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}

/** Pill with a glyph and a label; selected = white fill, black ink. */
class GlyphChip(context: Context, private val glyph: Glyph, private val label: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(14f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.WHITE; strokeWidth = context.dp(2f) }
    private val box = RectF()

    init {
        contentDescription = label
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = context.dp(14f) + context.dp(18f) + context.dp(6f) + text.measureText(label) + context.dp(16f)
        setMeasuredDimension(width.toInt(), context.dpi(44f))
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        fill.color = if (isSelected) Palette.WHITE else Palette.CHIP
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, fill)
        if (isFocused) canvas.drawRoundRect(context.dp(1f), context.dp(1f), width - context.dp(1f), height - context.dp(1f), r, r, ring.apply { color = if (isSelected) Palette.BLACK else Palette.WHITE })
        val ink = if (isSelected) Palette.BLACK else Palette.WHITE
        paint.color = ink
        val gs = context.dp(18f)
        val gx = context.dp(14f)
        box.set(gx, (height - gs) / 2, gx + gs, (height + gs) / 2)
        drawGlyph(canvas, glyph, box, paint, context.dp(1.8f))
        text.color = ink
        val baseline = height / 2f - (text.ascent() + text.descent()) / 2
        canvas.drawText(label, gx + gs + context.dp(6f), baseline, text)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}

/** Video shutter: red disc that morphs into a rounded square while recording. */
class ShutterView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.WHITE; strokeWidth = context.dp(4f) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.RED }
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.WHITE; strokeWidth = context.dp(2f) }
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val box = RectF()

    var recording = false
        set(value) {
            if (field == value) return
            field = value
            animator?.cancel()
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
            contentDescription = if (value) "Berhenti merekam" else "Mulai merekam"
        }

    init {
        contentDescription = "Mulai merekam"
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dpi(80f)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy) - ring.strokeWidth / 2
        canvas.drawCircle(cx, cy, outer, ring)
        val idleHalf = outer - context.dp(7f)
        val recHalf = context.dp(15f)
        val half = idleHalf + (recHalf - idleHalf) * progress
        val corner = half + (context.dp(6f) - half) * progress
        core.alpha = if (isEnabled) 255 else 100
        box.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(box, corner, corner, core)
        if (isFocused) canvas.drawCircle(cx, cy, outer - context.dp(5f), focus)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}

/** Two-option switch (e.g. "1 file" / "2 file"); the selected option is white with black text. */
class SegmentedToggle(context: Context, private val labels: List<String>, descriptions: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val views = labels.mapIndexed { i, label ->
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            contentDescription = descriptions[i]
            isClickable = true
            isFocusable = true
            minWidth = context.dpi(64f)
            minHeight = context.dpi(44f)
            setPadding(context.dpi(14f), 0, context.dpi(14f), 0)
            setOnClickListener { select(i, notify = true) }
        }
    }
    var selectedIndex = 0
        private set

    init {
        orientation = HORIZONTAL
        background = pill(Palette.SCRIM, 22f, context)
        setPadding(context.dpi(2f), context.dpi(2f), context.dpi(2f), context.dpi(2f))
        views.forEach { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, context.dpi(44f))) }
        select(0, notify = false)
    }

    fun select(index: Int, notify: Boolean) {
        selectedIndex = index
        views.forEachIndexed { i, v ->
            val on = i == index
            v.isSelected = on
            v.setTextColor(if (on) Palette.BLACK else Palette.WHITE)
            v.background = if (on) pill(Palette.WHITE, 20f, context) else null
        }
        if (notify) onSelect(index)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        views.forEach { it.isEnabled = enabled }
    }
}

/** Rounded last-video thumbnail. */
class ThumbnailView(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        background = pill(Palette.CHIP, 12f, context)
        contentDescription = "Buka video terakhir"
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dpi(52f)
        setMeasuredDimension(s, s)
    }
}
