package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.MathUtils
import android.util.MathUtils.lerp
import android.view.MotionEvent
import android.view.View
import androidx.annotation.Keep
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.ColorUtils.blendARGB
import com.android.systemui.Interpolators
import com.android.systemui.R
import org.xmlpull.v1.XmlPullParser

private const val BACKGROUND_ANIM_DURATION = 370L
private const val RIPPLE_ANIM_DURATION = 800L
private const val RIPPLE_DOWN_PROGRESS = 0.05f
private const val RIPPLE_CANCEL_DURATION = 200L
private val GRADIENT_STOPS = floatArrayOf(0.2f, 1f)

private data class RippleData(
    var x: Float,
    var y: Float,
    var alpha: Float,
    var progress: Float,
    var minSize: Float,
    var maxSize: Float,
    var highlight: Float
)

/**
 * Drawable that can draw an animated gradient when tapped.
 */
@Keep
class IlluminationDrawable : Drawable() {

    private var cornerRadius = 0f
    private var highlightColor = Color.TRANSPARENT
    private val rippleData = RippleData(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private var tmpHsl = floatArrayOf(0f, 0f, 0f)
    private var paint = Paint()

    private var backgroundColor = Color.TRANSPARENT
    set(value) {
        if (value == field) {
            return
        }
        field = value
        animateBackground()
    }

    /**
     * Draw a small highlight under the finger before expanding (or cancelling) it.
     */
    private var pressed: Boolean = false
        set(value) {
            if (value == field) {
                return
            }
            field = value

            if (value) {
                rippleAnimation?.cancel()
                rippleData.alpha = 1f
                rippleData.progress = RIPPLE_DOWN_PROGRESS
            } else {
                rippleAnimation?.cancel()
                rippleAnimation = ValueAnimator.ofFloat(rippleData.alpha, 0f).apply {
                    duration = RIPPLE_CANCEL_DURATION
                    interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                    addUpdateListener {
                        rippleData.alpha = it.animatedValue as Float
                        invalidateSelf()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        var cancelled = false
                        override fun onAnimationCancel(animation: Animator?) {
                            cancelled = true;
                        }

                        override fun onAnimationEnd(animation: Animator?) {
                            if (cancelled) {
                                return
                            }
                            rippleData.progress = 0f
                            rippleData.alpha = 0f
                            rippleAnimation = null
                            invalidateSelf()
                        }
                    })
                    start()
                }
            }
            invalidateSelf()
        }

    private var rippleAnimation: Animator? = null
    private var backgroundAnimation: ValueAnimator? = null

    /**
     * Draw background and gradient.
     */
    override fun draw(canvas: Canvas) {
        paint.shader = if (rippleData.progress > 0) {
            val radius = lerp(rippleData.minSize, rippleData.maxSize, rippleData.progress)
            val centerColor = blendARGB(paint.color, highlightColor, rippleData.alpha)
            RadialGradient(rippleData.x, rippleData.y, radius, intArrayOf(centerColor, paint.color),
                    GRADIENT_STOPS, Shader.TileMode.CLAMP)
        } else {
            null
        }
        canvas.drawRoundRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
                cornerRadius, cornerRadius, paint)
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        val a = obtainAttributes(r, theme, attrs, R.styleable.IlluminationDrawable)
        cornerRadius = a.getDimension(R.styleable.IlluminationDrawable_cornerRadius, cornerRadius)
        rippleData.minSize = a.getDimension(R.styleable.IlluminationDrawable_rippleMinSize, 0f)
        rippleData.maxSize = a.getDimension(R.styleable.IlluminationDrawable_rippleMaxSize, 0f)
        rippleData.highlight = a.getInteger(R.styleable.IlluminationDrawable_highlight, 0) / 100f
        a.recycle()
    }

    override fun setColorFilter(p0: ColorFilter?) {
        throw UnsupportedOperationException("Color filters are not supported")
    }

    override fun setAlpha(value: Int) {
        throw UnsupportedOperationException("Alpha is not supported")
    }

    /**
     * Cross fade background.
     * @see setTintList
     * @see backgroundColor
     */
    private fun animateBackground() {
        ColorUtils.colorToHSL(backgroundColor, tmpHsl)
        val L = tmpHsl[2]
        tmpHsl[2] = MathUtils.constrain(if (L < 1f - rippleData.highlight) {
            L + rippleData.highlight
        } else {
            L - rippleData.highlight
        }, 0f, 1f)

        val initialBackground = paint.color
        val initialHighlight = highlightColor
        val finalHighlight = ColorUtils.HSLToColor(tmpHsl)

        backgroundAnimation?.cancel()
        backgroundAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BACKGROUND_ANIM_DURATION
            interpolator = Interpolators.FAST_OUT_LINEAR_IN
            addUpdateListener {
                val progress = it.animatedValue as Float
                paint.color = blendARGB(initialBackground, backgroundColor, progress)
                highlightColor = blendARGB(initialHighlight, finalHighlight, progress)
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    backgroundAnimation = null
                }
            })
            start()
        }
    }

    override fun setTintList(tint: ColorStateList?) {
        super.setTintList(tint)
        backgroundColor = tint!!.defaultColor
    }

    /**
     * Draws an animated ripple that expands fading away.
     */
    private fun illuminate() {
        rippleData.alpha = 1f
        invalidateSelf()

        rippleAnimation?.cancel()
        rippleAnimation = AnimatorSet().apply {
            playTogether(ValueAnimator.ofFloat(1f, 0f).apply {
                startDelay = 133
                duration = RIPPLE_ANIM_DURATION - startDelay
                interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                addUpdateListener {
                    rippleData.alpha = it.animatedValue as Float
                    invalidateSelf()
                }
            }, ValueAnimator.ofFloat(rippleData.progress, 1f).apply {
                duration = RIPPLE_ANIM_DURATION
                interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                addUpdateListener {
                    rippleData.progress = it.animatedValue as Float
                    invalidateSelf()
                }
            })
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    rippleData.progress = 0f
                    rippleAnimation = null
                    invalidateSelf()
                }
            })
            start()
        }
    }

    /**
     * Setup touch events on a view such as tapping it would trigger effects on this drawable.
     * @param target View receiving touched.
     * @param container View that holds this drawable.
     */
    fun setupTouch(target: View, container: View) {
        val containerRect = Rect()
        target.setOnTouchListener { view: View, event: MotionEvent ->
            container.getGlobalVisibleRect(containerRect)
            rippleData.x = event.rawX - containerRect.left
            rippleData.y = event.rawY - containerRect.top

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    invalidateSelf()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    if (event.action == MotionEvent.ACTION_UP) {
                        illuminate()
                    }
                }
            }
            false
        }
    }
}