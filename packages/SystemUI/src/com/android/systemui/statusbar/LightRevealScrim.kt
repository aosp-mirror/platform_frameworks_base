package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.android.systemui.animation.Interpolators
import java.util.function.Consumer

/**
 * Provides methods to modify the various properties of a [LightRevealScrim] to reveal between 0% to
 * 100% of the view(s) underneath the scrim.
 */
interface LightRevealEffect {
    fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim)

    companion object {

        /**
         * Returns the percent that the given value is past the threshold value. For example, 0.9 is
         * 50% of the way past 0.8.
         */
        fun getPercentPastThreshold(value: Float, threshold: Float): Float {
            return (value - threshold).coerceAtLeast(0f) * (1f / (1f - threshold))
        }
    }
}

/**
 * Light reveal effect that shows light entering the phone from the bottom of the screen. The light
 * enters from the bottom-middle as a narrow oval, and moves upward, eventually widening to fill the
 * screen.
 */
object LiftReveal : LightRevealEffect {

    /** Widen the oval of light after 35%, so it will eventually fill the screen. */
    private const val WIDEN_OVAL_THRESHOLD = 0.35f

    /** After 85%, fade out the black color at the end of the gradient. */
    private const val FADE_END_COLOR_OUT_THRESHOLD = 0.85f

    /** The initial width of the light oval, in percent of scrim width. */
    private const val OVAL_INITIAL_WIDTH_PERCENT = 0.5f

    /** The initial top value of the light oval, in percent of scrim height. */
    private const val OVAL_INITIAL_TOP_PERCENT = 1.1f

    /** The initial bottom value of the light oval, in percent of scrim height. */
    private const val OVAL_INITIAL_BOTTOM_PERCENT = 1.2f

    /** Interpolator to use for the reveal amount. */
    private val INTERPOLATOR = Interpolators.FAST_OUT_SLOW_IN_REVERSE

    override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {
        val interpolatedAmount = INTERPOLATOR.getInterpolation(amount)
        val ovalWidthIncreaseAmount =
                LightRevealEffect.getPercentPastThreshold(interpolatedAmount, WIDEN_OVAL_THRESHOLD)

        val initialWidthMultiplier = (1f - OVAL_INITIAL_WIDTH_PERCENT) / 2f

        with(scrim) {
            revealGradientEndColorAlpha = 1f - LightRevealEffect.getPercentPastThreshold(
                    amount, FADE_END_COLOR_OUT_THRESHOLD)
            setRevealGradientBounds(
                    scrim.width * initialWidthMultiplier +
                            -scrim.width * ovalWidthIncreaseAmount,
                    scrim.height * OVAL_INITIAL_TOP_PERCENT -
                            scrim.height * interpolatedAmount,
                    scrim.width * (1f - initialWidthMultiplier) +
                            scrim.width * ovalWidthIncreaseAmount,
                    scrim.height * OVAL_INITIAL_BOTTOM_PERCENT +
                            scrim.height * interpolatedAmount)
        }
    }
}

class CircleReveal(
    /** X-value of the circle center of the reveal. */
    val centerX: Float,
    /** Y-value of the circle center of the reveal. */
    val centerY: Float,
    /** Radius of initial state of circle reveal */
    val startRadius: Float,
    /** Radius of end state of circle reveal */
    val endRadius: Float
) : LightRevealEffect {
    override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {
        // reveal amount updates already have an interpolator, so we intentionally use the
        // non-interpolated amount
        val fadeAmount = LightRevealEffect.getPercentPastThreshold(amount, 0.5f)
        val radius = startRadius + ((endRadius - startRadius) * amount)
        scrim.revealGradientEndColorAlpha = 1f - fadeAmount
        scrim.setRevealGradientBounds(
            centerX - radius /* left */,
            centerY - radius /* top */,
            centerX + radius /* right */,
            centerY + radius /* bottom */
        )
    }
}

class PowerButtonReveal(
    /** Approximate Y-value of the center of the power button on the physical device. */
    val powerButtonY: Float
) : LightRevealEffect {

    /**
     * How far off the side of the screen to start the power button reveal, in terms of percent of
     * the screen width. This ensures that the initial part of the animation (where the reveal is
     * just a sliver) starts just off screen.
     */
    private val OFF_SCREEN_START_AMOUNT = 0.05f

    private val WIDTH_INCREASE_MULTIPLIER = 1.25f

    override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {
        val interpolatedAmount = Interpolators.FAST_OUT_SLOW_IN_REVERSE.getInterpolation(amount)
        val fadeAmount =
                LightRevealEffect.getPercentPastThreshold(interpolatedAmount, 0.5f)

        with(scrim) {
            revealGradientEndColorAlpha = 1f - fadeAmount
            setRevealGradientBounds(
                    width * (1f + OFF_SCREEN_START_AMOUNT) -
                            width * WIDTH_INCREASE_MULTIPLIER * interpolatedAmount,
                    powerButtonY -
                            height * interpolatedAmount,
                    width * (1f + OFF_SCREEN_START_AMOUNT) +
                            width * WIDTH_INCREASE_MULTIPLIER * interpolatedAmount,
                    powerButtonY +
                            height * interpolatedAmount)
        }
    }
}

/**
 * Scrim view that partially reveals the content underneath it using a [RadialGradient] with a
 * transparent center. The center position, size, and stops of the gradient can be manipulated to
 * reveal views below the scrim as if they are being 'lit up'.
 */
class LightRevealScrim(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    /**
     * Listener that is called if the scrim's opaqueness changes
     */
    lateinit var isScrimOpaqueChangedListener: Consumer<Boolean>

    /**
     * How much of the underlying views are revealed, in percent. 0 means they will be completely
     * obscured and 1 means they'll be fully visible.
     */
    var revealAmount: Float = 1f
        set(value) {
            if (field != value) {
                field = value

                revealEffect.setRevealAmountOnScrim(value, this)
                updateScrimOpaque()
                invalidate()
            }
        }

    /**
     * The [LightRevealEffect] used to manipulate the radial gradient whenever [revealAmount]
     * changes.
     */
    var revealEffect: LightRevealEffect = LiftReveal
        set(value) {
            if (field != value) {
                field = value

                revealEffect.setRevealAmountOnScrim(revealAmount, this)
                invalidate()
            }
        }

    var revealGradientCenter = PointF()
    var revealGradientWidth: Float = 0f
    var revealGradientHeight: Float = 0f

    var revealGradientEndColor: Int = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                setPaintColorFilter()
            }
        }

    var revealGradientEndColorAlpha = 0f
        set(value) {
            if (field != value) {
                field = value
                setPaintColorFilter()
            }
        }

    /**
     * Is the scrim currently fully opaque
     */
    var isScrimOpaque = false
        private set(value) {
            if (field != value) {
                field = value
                isScrimOpaqueChangedListener.accept(field)
            }
        }

    private fun updateScrimOpaque() {
        isScrimOpaque = revealAmount == 0.0f && alpha == 1.0f && visibility == VISIBLE
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        updateScrimOpaque()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        updateScrimOpaque()
    }

    /**
     * Paint used to draw a transparent-to-white radial gradient. This will be scaled and translated
     * via local matrix in [onDraw] so we never need to construct a new shader.
     */
    private val gradientPaint = Paint().apply {
        shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.TRANSPARENT, Color.WHITE), floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP)

        // SRC_OVER ensures that we draw the semitransparent pixels over other views in the same
        // window, rather than outright replacing them.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }

    /**
     * Matrix applied to [gradientPaint]'s RadialGradient shader to move the gradient to
     * [revealGradientCenter] and set its size to [revealGradientWidth]/[revealGradientHeight],
     * without needing to construct a new shader each time those properties change.
     */
    private val shaderGradientMatrix = Matrix()

    init {
        revealEffect.setRevealAmountOnScrim(revealAmount, this)
        setPaintColorFilter()
        invalidate()
    }

    /**
     * Sets bounds for the transparent oval gradient that reveals the views below the scrim. This is
     * simply a helper method that sets [revealGradientCenter], [revealGradientWidth], and
     * [revealGradientHeight] for you.
     *
     * This method does not call [invalidate] - you should do so once you're done changing
     * properties.
     */
    public fun setRevealGradientBounds(left: Float, top: Float, right: Float, bottom: Float) {
        revealGradientWidth = right - left
        revealGradientHeight = bottom - top

        revealGradientCenter.x = left + (revealGradientWidth / 2f)
        revealGradientCenter.y = top + (revealGradientHeight / 2f)
    }

    override fun onDraw(canvas: Canvas?) {
        if (canvas == null || revealGradientWidth <= 0 || revealGradientHeight <= 0) {
            if (revealAmount < 1f) {
                canvas?.drawColor(revealGradientEndColor)
            }
            return
        }

        with(shaderGradientMatrix) {
            setScale(revealGradientWidth, revealGradientHeight, 0f, 0f)
            postTranslate(revealGradientCenter.x, revealGradientCenter.y)

            gradientPaint.shader.setLocalMatrix(this)
        }

        // Draw the gradient over the screen, then multiply the end color by it.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
    }

    private fun setPaintColorFilter() {
        gradientPaint.colorFilter = PorterDuffColorFilter(
                Color.argb(
                        (revealGradientEndColorAlpha * 255).toInt(),
                        Color.red(revealGradientEndColor),
                        Color.green(revealGradientEndColor),
                        Color.blue(revealGradientEndColor)),
                PorterDuff.Mode.MULTIPLY)
    }
}