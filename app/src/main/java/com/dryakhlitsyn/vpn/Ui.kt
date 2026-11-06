package com.dryakhlitsyn.vpn

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radiusDp.toFloat())
        setColor(color)
        if (strokeColor != null) setStroke(dpF(strokeDp).toInt().coerceAtLeast(1), strokeColor)
    }

fun Context.text(
    value: String,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
    letterSpacing: Float = 0f,
    gravity: Int = Gravity.START,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    this.letterSpacing = letterSpacing
    this.gravity = gravity
    includeFontPadding = false
}

/** Applies the manual light/dark override on top of the system theme. */
object UiTheme {
    fun wrap(base: Context): Context {
        val mode = Prefs.themeMode(base)
        if (mode == Prefs.THEME_AUTO) return base
        val configuration = Configuration(base.resources.configuration)
        val night = if (mode == Prefs.THEME_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(configuration)
    }
}

/** Minimal iOS-like switch drawn by hand, so the APK needs no extra libraries. */
class SwitchView(context: Context, private val palette: Palette) : View(context) {
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            onCheckedChange?.invoke(isChecked)
        }
    }

    private fun animateTo(target: Float) {
        val start = progress
        animate().cancel()
        valueAnimator(start, target)
    }

    private fun valueAnimator(start: Float, target: Float) {
        val duration = 160L
        val begin = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val fraction = ((System.currentTimeMillis() - begin).toFloat() / duration).coerceIn(0f, 1f)
                val eased = 1f - (1f - fraction) * (1f - fraction)
                progress = start + (target - start) * eased
                invalidate()
                if (fraction < 1f) postOnAnimation(this)
            }
        }
        postOnAnimation(runnable)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(64), context.dp(38))
    }

    override fun onDraw(canvas: Canvas) {
        val height = height.toFloat()
        val width = width.toFloat()
        val radius = height / 2f
        paint.color = blend(palette.switchOff, palette.switchOn, progress)
        rect.set(0f, 0f, width, height)
        canvas.drawRoundRect(rect, radius, radius, paint)

        val knobRadius = radius - context.dpF(4f)
        val minX = radius
        val maxX = width - radius
        val centerX = minX + (maxX - minX) * progress
        paint.color = palette.switchKnob
        canvas.drawCircle(centerX, height / 2f, knobRadius, paint)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val inverse = 1f - fraction
        return Color.argb(
            (Color.alpha(from) * inverse + Color.alpha(to) * fraction).toInt(),
            (Color.red(from) * inverse + Color.red(to) * fraction).toInt(),
            (Color.green(from) * inverse + Color.green(to) * fraction).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * fraction).toInt(),
        )
    }
}
