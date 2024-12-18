package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.MathUtils.min
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

    /** Used to track back arrow latency from [android.view.MotionEvent.ACTION_DOWN] to [onDraw] */
    private var trackingBackArrowLatency = false

    /** The length of the arrow measured horizontally. Used for animating [arrowPath] */
    private var arrowLength =
        AnimatedFloat(
            name = "arrowLength",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS
        )

    /**
     * The height of the arrow measured vertically from its center to its top (i.e. half the total
     * height). Used for animating [arrowPath]
     */
    var arrowHeight =
        AnimatedFloat(
            name = "arrowHeight",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES
        )

    val backgroundWidth =
        AnimatedFloat(
            name = "backgroundWidth",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = 0f,
        )

    val backgroundHeight =
        AnimatedFloat(
            name = "backgroundHeight",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = 0f,
        )

    /**
     * Corners of the background closer to the edge of the screen (where the arrow appeared from).
     * Used for animating [arrowBackgroundRect]
     */
    val backgroundEdgeCornerRadius = AnimatedFloat("backgroundEdgeCornerRadius")

    /**
     * Corners of the background further from the edge of the screens (toward the direction the
     * arrow is being dragged). Used for animating [arrowBackgroundRect]
     */
    val backgroundFarCornerRadius = AnimatedFloat("backgroundFarCornerRadius")

    var scale =
        AnimatedFloat(
            name = "scale",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_SCALE,
            minimumValue = 0f
        )

    val scalePivotX =
        AnimatedFloat(
            name = "scalePivotX",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = backgroundWidth.pos / 2,
        )

    /**
     * Left/right position of the background relative to the canvas. Also corresponds with the
     * background's margin relative to the screen edge. The arrow will be centered within the
     * background.
     */
    var horizontalTranslation = AnimatedFloat(name = "horizontalTranslation")

    var arrowAlpha =
        AnimatedFloat(
            name = "arrowAlpha",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA,
            minimumValue = 0f,
            maximumValue = 1f
        )

    val backgroundAlpha =
        AnimatedFloat(
            name = "backgroundAlpha",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA,
            minimumValue = 0f,
            maximumValue = 1f
        )

    private val allAnimatedFloat =
        setOf(
            arrowLength,
            arrowHeight,
            backgroundWidth,
            backgroundEdgeCornerRadius,
            backgroundFarCornerRadius,
            scalePivotX,
            scale,
            horizontalTranslation,
            arrowAlpha,
            backgroundAlpha
        )

    /**
     * Canvas vertical translation. How far up/down the arrow and background appear relative to the
     * canvas.
     */
    var verticalTranslation = AnimatedFloat("verticalTranslation")

    /** Use for drawing debug info. Can only be set if [DEBUG]=true */
    var drawDebugInfo: ((canvas: Canvas) -> Unit)? = null
        set(value) {
            if (DEBUG) field = value
        }

    internal fun updateArrowPaint(arrowThickness: Float) {
        arrowPaint.strokeWidth = arrowThickness

        val isDeviceInNightTheme =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        arrowPaint.color =
            Utils.getColorAttrDefaultColor(
                context,
                if (isDeviceInNightTheme) {
                    com.android.internal.R.attr.materialColorOnSecondaryContainer
                } else {
                    com.android.internal.R.attr.materialColorOnSecondaryFixed
                }
            )

        arrowBackgroundPaint.color =
            Utils.getColorAttrDefaultColor(
                context,
                if (isDeviceInNightTheme) {
                    com.android.internal.R.attr.materialColorSecondaryContainer
                } else {
                    com.android.internal.R.attr.materialColorSecondaryFixedDim
                }
            )
    }

    inner class AnimatedFloat(
        name: String,
        private val minimumVisibleChange: Float? = null,
        private val minimumValue: Float? = null,
        private val maximumValue: Float? = null,
    ) {

        // The resting position when not stretched by a touch drag
        private var restingPosition = 0f

        // The current position as updated by the SpringAnimation
        var pos = 0f
            private set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val animation: SpringAnimation
        var spring: SpringForce
            get() = animation.spring
            set(value) {
                animation.cancel()
                animation.spring = value
            }

        val isRunning: Boolean
            get() = animation.isRunning

        fun addEndListener(listener: DelayedOnAnimationEndListener) {
            animation.addEndListener(listener)
        }

        init {
            val floatProp =
                object : FloatPropertyCompat<AnimatedFloat>(name) {
                    override fun setValue(animatedFloat: AnimatedFloat, value: Float) {
                        animatedFloat.pos = value
                    }

                    override fun getValue(animatedFloat: AnimatedFloat): Float = animatedFloat.pos
                }
            animation =
                SpringAnimation(this, floatProp).apply {
                    spring = SpringForce()
                    this@AnimatedFloat.minimumValue?.let { setMinValue(it) }
                    this@AnimatedFloat.maximumValue?.let { setMaxValue(it) }
                    this@AnimatedFloat.minimumVisibleChange?.let { minimumVisibleChange = it }
                }
        }

        fun snapTo(newPosition: Float) {
            animation.cancel()
            restingPosition = newPosition
            animation.spring.finalPosition = newPosition
            pos = newPosition
        }

        fun snapToRestingPosition() {
            snapTo(restingPosition)
        }

        fun stretchTo(
            stretchAmount: Float,
            startingVelocity: Float? = null,
            springForce: SpringForce? = null
        ) {
            animation.apply {
                startingVelocity?.let {
                    cancel()
                    setStartVelocity(it)
                }
                springForce?.let { spring = springForce }
                animateToFinalPosition(restingPosition + stretchAmount)
            }
        }

        /**
         * Animates to a new position ([finalPosition]) that is the given fraction ([amount])
         * between the existing [restingPosition] and the new [finalPosition].
         *
         * The [restingPosition] will remain unchanged. Only the animation is updated.
         */
        fun stretchBy(finalPosition: Float?, amount: Float) {
            val stretchedAmount = amount * ((finalPosition ?: 0f) - restingPosition)
            animation.animateToFinalPosition(restingPosition + stretchedAmount)
        }

        fun updateRestingPosition(pos: Float?, animated: Boolean = true) {
            if (pos == null) return

            restingPosition = pos
            if (animated) {
                animation.animateToFinalPosition(restingPosition)
            } else {
                snapTo(restingPosition)
            }
        }

        fun cancel() = animation.cancel()
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

    fun addAnimationEndListener(
        animatedFloat: AnimatedFloat,
        endListener: DelayedOnAnimationEndListener
    ): Boolean {
        return if (animatedFloat.isRunning) {
            animatedFloat.addEndListener(endListener)
            true
        } else {
            endListener.run()
            false
        }
    }

    fun cancelAnimations() {
        allAnimatedFloat.forEach { it.cancel() }
    }

    fun setStretch(
        horizontalTranslationStretchAmount: Float,
        arrowStretchAmount: Float,
        arrowAlphaStretchAmount: Float,
        backgroundAlphaStretchAmount: Float,
        backgroundWidthStretchAmount: Float,
        backgroundHeightStretchAmount: Float,
        edgeCornerStretchAmount: Float,
        farCornerStretchAmount: Float,
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
        arrowAlpha.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.alpha,
            amount = arrowAlphaStretchAmount
        )
        backgroundAlpha.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.alpha,
            amount = backgroundAlphaStretchAmount
        )
        backgroundWidth.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.width,
            amount = backgroundWidthStretchAmount
        )
        backgroundHeight.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.height,
            amount = backgroundHeightStretchAmount
        )
        backgroundEdgeCornerRadius.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.edgeCornerRadius,
            amount = edgeCornerStretchAmount
        )
        backgroundFarCornerRadius.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.farCornerRadius,
            amount = farCornerStretchAmount
        )
    }

    fun popOffEdge(startingVelocity: Float) {
        scale.stretchTo(stretchAmount = 0f, startingVelocity = startingVelocity * -.8f)
        horizontalTranslation.stretchTo(stretchAmount = 0f, startingVelocity * 200f)
    }

    fun popScale(startingVelocity: Float) {
        scalePivotX.snapTo(backgroundWidth.pos / 2)
        scale.stretchTo(stretchAmount = 0f, startingVelocity = startingVelocity)
    }

    fun popArrowAlpha(startingVelocity: Float, springForce: SpringForce? = null) {
        arrowAlpha.stretchTo(
            stretchAmount = 0f,
            startingVelocity = startingVelocity,
            springForce = springForce
        )
    }

    fun resetStretch() {
        backgroundAlpha.snapTo(1f)
        verticalTranslation.snapTo(0f)
        scale.snapTo(1f)

        horizontalTranslation.snapToRestingPosition()
        arrowLength.snapToRestingPosition()
        arrowHeight.snapToRestingPosition()
        arrowAlpha.snapToRestingPosition()
        backgroundWidth.snapToRestingPosition()
        backgroundHeight.snapToRestingPosition()
        backgroundEdgeCornerRadius.snapToRestingPosition()
        backgroundFarCornerRadius.snapToRestingPosition()
    }

    /** Updates resting arrow and background size not accounting for stretch */
    internal fun setRestingDimens(
        restingParams: EdgePanelParams.BackIndicatorDimens,
        animate: Boolean = true
    ) {
        horizontalTranslation.updateRestingPosition(restingParams.horizontalTranslation)
        scale.updateRestingPosition(restingParams.scale)
        backgroundAlpha.updateRestingPosition(restingParams.backgroundDimens.alpha)

        arrowAlpha.updateRestingPosition(restingParams.arrowDimens.alpha, animate)
        arrowLength.updateRestingPosition(restingParams.arrowDimens.length, animate)
        arrowHeight.updateRestingPosition(restingParams.arrowDimens.height, animate)
        scalePivotX.updateRestingPosition(restingParams.scalePivotX, animate)
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

    fun setSpring(
        horizontalTranslation: SpringForce? = null,
        verticalTranslation: SpringForce? = null,
        scale: SpringForce? = null,
        arrowLength: SpringForce? = null,
        arrowHeight: SpringForce? = null,
        arrowAlpha: SpringForce? = null,
        backgroundAlpha: SpringForce? = null,
        backgroundFarCornerRadius: SpringForce? = null,
        backgroundEdgeCornerRadius: SpringForce? = null,
        backgroundWidth: SpringForce? = null,
        backgroundHeight: SpringForce? = null,
    ) {
        arrowLength?.let { this.arrowLength.spring = it }
        arrowHeight?.let { this.arrowHeight.spring = it }
        arrowAlpha?.let { this.arrowAlpha.spring = it }
        backgroundAlpha?.let { this.backgroundAlpha.spring = it }
        backgroundFarCornerRadius?.let { this.backgroundFarCornerRadius.spring = it }
        backgroundEdgeCornerRadius?.let { this.backgroundEdgeCornerRadius.spring = it }
        scale?.let { this.scale.spring = it }
        backgroundWidth?.let { this.backgroundWidth.spring = it }
        backgroundHeight?.let { this.backgroundHeight.spring = it }
        horizontalTranslation?.let { this.horizontalTranslation.spring = it }
        verticalTranslation?.let { this.verticalTranslation.spring = it }
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val edgeCorner = backgroundEdgeCornerRadius.pos
        val farCorner = backgroundFarCornerRadius.pos
        val halfHeight = backgroundHeight.pos / 2
        val canvasWidth = width
        val backgroundWidth = backgroundWidth.pos
        val scalePivotX = scalePivotX.pos

        canvas.save()

        if (!isLeftPanel) canvas.scale(-1f, 1f, canvasWidth / 2.0f, 0f)

        canvas.translate(horizontalTranslation.pos, height * 0.5f + verticalTranslation.pos)

        canvas.scale(scale.pos, scale.pos, scalePivotX, 0f)

        val arrowBackground =
            arrowBackgroundRect
                .apply {
                    left = 0f
                    top = -halfHeight
                    right = backgroundWidth
                    bottom = halfHeight
                }
                .toPathWithRoundCorners(
                    topLeft = edgeCorner,
                    bottomLeft = edgeCorner,
                    topRight = farCorner,
                    bottomRight = farCorner
                )
        canvas.drawPath(
            arrowBackground,
            arrowBackgroundPaint.apply { alpha = (255 * backgroundAlpha.pos).toInt() }
        )

        val dx = arrowLength.pos
        val dy = arrowHeight.pos

        // How far the arrow bounding box should be from the edge of the screen. Measured from
        // either the tip or the back of the arrow, whichever is closer
        val arrowOffset = (backgroundWidth - dx) / 2
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
        val arrowPaint =
            arrowPaint.apply { alpha = (255 * min(arrowAlpha.pos, backgroundAlpha.pos)).toInt() }
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
    ): Path =
        Path().apply {
            val corners =
                floatArrayOf(
                    topLeft,
                    topLeft,
                    topRight,
                    topRight,
                    bottomRight,
                    bottomRight,
                    bottomLeft,
                    bottomLeft
                )
            addRoundRect(this@toPathWithRoundCorners, corners, Path.Direction.CW)
        }
}
