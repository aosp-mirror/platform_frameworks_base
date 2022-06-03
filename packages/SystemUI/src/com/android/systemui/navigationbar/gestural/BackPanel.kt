package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.LatencyTracker
import com.android.settingslib.Utils
import com.android.systemui.navigationbar.gestural.BackPanelController.DelayedOnAnimationEndListener

private const val TAG = "BackPanel"
private const val DEBUG = false

class BackPanel(context: Context, private val latencyTracker: LatencyTracker) : View(context) {

    var arrowsPointLeft = false
        set(value) {
            if (field != value) {
                invalidate()
                field = value
            }
        }

    // Arrow color and shape
    private val arrowPath = Path()
    private val arrowPaint = Paint()

    // Arrow background color and shape
    private var arrowBackgroundRect = RectF()
    private var arrowBackgroundPaint = Paint()

    // True if the panel is currently on the left of the screen
    var isLeftPanel = false

    /**
     * Used to track back arrow latency from [android.view.MotionEvent.ACTION_DOWN] to [onDraw]
     */
    private var trackingBackArrowLatency = false

    /**
     * The length of the arrow measured horizontally. Used for animating [arrowPath]
     */
    private var arrowLength = AnimatedFloat("arrowLength", SpringForce())

    /**
     * The height of the arrow measured vertically from its center to its top (i.e. half the total
     * height). Used for animating [arrowPath]
     */
    private var arrowHeight = AnimatedFloat("arrowHeight", SpringForce())

    private val backgroundWidth = AnimatedFloat(
        name = "backgroundWidth",
        SpringForce().apply {
            stiffness = 600f
            dampingRatio = 0.65f
        }
    )

    private val backgroundHeight = AnimatedFloat(
        name = "backgroundHeight",
        SpringForce().apply {
            stiffness = 600f
            dampingRatio = 0.65f
        }
    )

    /**
     * Corners of the background closer to the edge of the screen (where the arrow appeared from).
     * Used for animating [arrowBackgroundRect]
     */
    private val backgroundEdgeCornerRadius = AnimatedFloat(
        name = "backgroundEdgeCornerRadius",
        SpringForce().apply {
            stiffness = 400f
            dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
    )

    /**
     * Corners of the background further from the edge of the screens (toward the direction the
     * arrow is being dragged). Used for animating [arrowBackgroundRect]
     */
    private val backgroundFarCornerRadius = AnimatedFloat(
        name = "backgroundDragCornerRadius",
        SpringForce().apply {
            stiffness = 2200f
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
    )

    /**
     * Left/right position of the background relative to the canvas. Also corresponds with the
     * background's margin relative to the screen edge. The arrow will be centered within the
     * background.
     */
    private var horizontalTranslation = AnimatedFloat("horizontalTranslation", SpringForce())

    private val currentAlpha: FloatPropertyCompat<BackPanel> =
        object : FloatPropertyCompat<BackPanel>("currentAlpha") {
            override fun setValue(panel: BackPanel, value: Float) {
                panel.alpha = value
            }

            override fun getValue(panel: BackPanel): Float = panel.alpha
        }

    private val alphaAnimation = SpringAnimation(this, currentAlpha)
        .setSpring(
            SpringForce()
                .setStiffness(60f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
        )

    /**
     * Canvas vertical translation. How far up/down the arrow and background appear relative to the
     * canvas.
     */
    private var verticalTranslation: AnimatedFloat = AnimatedFloat(
        name = "verticalTranslation",
        SpringForce().apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
        }
    )

    /**
     * Use for drawing debug info. Can only be set if [DEBUG]=true
     */
    var drawDebugInfo: ((canvas: Canvas) -> Unit)? = null
        set(value) {
            if (DEBUG) field = value
        }

    internal fun updateArrowPaint(arrowThickness: Float) {
        // Arrow constants
        arrowPaint.strokeWidth = arrowThickness

        arrowPaint.color =
            Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.colorPrimary)
        arrowBackgroundPaint.color = Utils.getColorAccentDefaultColor(context)
    }

    private inner class AnimatedFloat(name: String, springForce: SpringForce) {
        // The resting position when not stretched by a touch drag
        private var restingPosition = 0f

        // The current position as updated by the SpringAnimation
        var pos = 0f
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        val animation: SpringAnimation

        init {
            val floatProp = object : FloatPropertyCompat<AnimatedFloat>(name) {
                override fun setValue(animatedFloat: AnimatedFloat, value: Float) {
                    animatedFloat.pos = value
                }

                override fun getValue(animatedFloat: AnimatedFloat): Float = animatedFloat.pos
            }
            animation = SpringAnimation(this, floatProp)
            animation.spring = springForce
        }

        fun snapTo(newPosition: Float) {
            animation.cancel()
            restingPosition = newPosition
            animation.spring.finalPosition = newPosition
            pos = newPosition
        }

        fun stretchTo(stretchAmount: Float) {
            animation.animateToFinalPosition(restingPosition + stretchAmount)
        }

        /**
         * Animates to a new position ([finalPosition]) that is the given fraction ([amount])
         * between the existing [restingPosition] and the new [finalPosition].
         *
         * The [restingPosition] will remain unchanged. Only the animation is updated.
         */
        fun stretchBy(finalPosition: Float, amount: Float) {
            val stretchedAmount = amount * (finalPosition - restingPosition)
            animation.animateToFinalPosition(restingPosition + stretchedAmount)
        }

        fun updateRestingPosition(pos: Float, animated: Boolean) {
            restingPosition = pos
            if (animated)
                animation.animateToFinalPosition(restingPosition)
            else
                snapTo(restingPosition)
        }
    }

    init {
        visibility = GONE
        arrowPaint.apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
        }
        arrowBackgroundPaint.apply {
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun calculateArrowPath(dx: Float, dy: Float): Path {
        arrowPath.reset()
        arrowPath.moveTo(dx, -dy)
        arrowPath.lineTo(0f, 0f)
        arrowPath.lineTo(dx, dy)
        arrowPath.moveTo(dx, -dy)
        return arrowPath
    }

    fun addEndListener(endListener: DelayedOnAnimationEndListener): Boolean {
        return if (alphaAnimation.isRunning) {
            alphaAnimation.addEndListener(endListener)
            true
        } else if (horizontalTranslation.animation.isRunning) {
            horizontalTranslation.animation.addEndListener(endListener)
            true
        } else {
            endListener.runNow()
            false
        }
    }

    fun setStretch(
        horizontalTranslationStretchAmount: Float,
        arrowStretchAmount: Float,
        backgroundWidthStretchAmount: Float,
        fullyStretchedDimens: EdgePanelParams.BackIndicatorDimens
    ) {
        horizontalTranslation.stretchBy(
            finalPosition = fullyStretchedDimens.horizontalTranslation,
            amount = horizontalTranslationStretchAmount
        )
        arrowLength.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.length,
            amount = arrowStretchAmount
        )
        arrowHeight.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.height,
            amount = arrowStretchAmount
        )
        backgroundWidth.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.width,
            amount = backgroundWidthStretchAmount
        )
    }

    fun resetStretch() {
        horizontalTranslation.stretchTo(0f)
        arrowLength.stretchTo(0f)
        arrowHeight.stretchTo(0f)
        backgroundWidth.stretchTo(0f)
        backgroundHeight.stretchTo(0f)
        backgroundEdgeCornerRadius.stretchTo(0f)
        backgroundFarCornerRadius.stretchTo(0f)
    }

    /**
     * Updates resting arrow and background size not accounting for stretch
     */
    internal fun setRestingDimens(
        restingParams: EdgePanelParams.BackIndicatorDimens,
        animate: Boolean
    ) {
        horizontalTranslation.updateRestingPosition(restingParams.horizontalTranslation, animate)
        arrowLength.updateRestingPosition(restingParams.arrowDimens.length, animate)
        arrowHeight.updateRestingPosition(restingParams.arrowDimens.height, animate)
        backgroundWidth.updateRestingPosition(restingParams.backgroundDimens.width, animate)
        backgroundHeight.updateRestingPosition(restingParams.backgroundDimens.height, animate)
        backgroundEdgeCornerRadius.updateRestingPosition(
            restingParams.backgroundDimens.edgeCornerRadius,
            animate
        )
        backgroundFarCornerRadius.updateRestingPosition(
            restingParams.backgroundDimens.farCornerRadius,
            animate
        )
    }

    fun animateVertically(yPos: Float) = verticalTranslation.stretchTo(yPos)

    fun setArrowStiffness(arrowStiffness: Float, arrowDampingRatio: Float) {
        arrowLength.animation.spring.apply {
            stiffness = arrowStiffness
            dampingRatio = arrowDampingRatio
        }
        arrowHeight.animation.spring.apply {
            stiffness = arrowStiffness
            dampingRatio = arrowDampingRatio
        }
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        var edgeCorner = backgroundEdgeCornerRadius.pos
        val farCorner = backgroundFarCornerRadius.pos
        val halfHeight = backgroundHeight.pos / 2

        canvas.save()

        if (!isLeftPanel) canvas.scale(-1f, 1f, width / 2.0f, 0f)

        canvas.translate(
            horizontalTranslation.pos,
            height * 0.5f + verticalTranslation.pos
        )

        val arrowBackground = arrowBackgroundRect.apply {
            left = 0f
            top = -halfHeight
            right = backgroundWidth.pos
            bottom = halfHeight
        }.toPathWithRoundCorners(
            topLeft = edgeCorner,
            bottomLeft = edgeCorner,
            topRight = farCorner,
            bottomRight = farCorner
        )
        canvas.drawPath(arrowBackground, arrowBackgroundPaint)

        val dx = arrowLength.pos
        val dy = arrowHeight.pos

        // How far the arrow bounding box should be from the edge of the screen. Measured from
        // either the tip or the back of the arrow, whichever is closer
        var arrowOffset = (backgroundWidth.pos - dx) / 2
        canvas.translate(
            /* dx= */ arrowOffset,
            /* dy= */ 0f /* pass 0 for the y position since the canvas was already translated */
        )

        val arrowPointsAwayFromEdge = !arrowsPointLeft.xor(isLeftPanel)
        if (arrowPointsAwayFromEdge) {
            canvas.apply {
                scale(-1f, 1f, 0f, 0f)
                translate(-dx, 0f)
            }
        }

        val arrowPath = calculateArrowPath(dx = dx, dy = dy)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()

        if (trackingBackArrowLatency) {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_SHOW_BACK_ARROW)
            trackingBackArrowLatency = false
        }

        if (DEBUG) drawDebugInfo?.invoke(canvas)
    }

    fun startTrackingShowBackArrowLatency() {
        latencyTracker.onActionStart(LatencyTracker.ACTION_SHOW_BACK_ARROW)
        trackingBackArrowLatency = true
    }

    private fun RectF.toPathWithRoundCorners(
        topLeft: Float = 0f,
        topRight: Float = 0f,
        bottomRight: Float = 0f,
        bottomLeft: Float = 0f
    ): Path = Path().apply {
        val corners = floatArrayOf(
            topLeft, topLeft,
            topRight, topRight,
            bottomRight, bottomRight,
            bottomLeft, bottomLeft
        )
        addRoundRect(this@toPathWithRoundCorners, corners, Path.Direction.CW)
    }

    fun cancelAlphaAnimations() {
        alphaAnimation.cancel()
        alpha = 1f
    }

    fun fadeOut() {
        alphaAnimation.animateToFinalPosition(0f)
    }
}
