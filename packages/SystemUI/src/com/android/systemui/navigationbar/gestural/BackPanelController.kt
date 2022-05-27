/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Handler
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.Log
import android.util.MathUtils.constrain
import android.util.MathUtils.saturate
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.window.BackEvent
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.LatencyTracker
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.NavigationEdgeBackPlugin
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.ViewController
import com.android.wm.shell.back.BackAnimation
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

private const val TAG = "BackPanelController"
private const val DEBUG = false

private const val ENABLE_FAILSAFE = true

private const val FAILSAFE_DELAY_MS: Long = 350

/**
 * The time required between the arrow-appears vibration effect and the back-committed vibration
 * effect. If the arrow is flung quickly, the phone only vibrates once. However, if the arrow is
 * held on the screen for a long time, it will vibrate a second time when the back gesture is
 * committed.
 */
private const val GESTURE_DURATION_FOR_CLICK_MS = 400

/**
 * The min duration arrow remains on screen during a fling event.
 */
private const val FLING_PAUSE_DURATION_MS = 50L

/**
 * The min duration arrow remains on screen during a fling event.
 */
private const val MIN_FLING_VELOCITY = 3000

/**
 * The amount of rubber banding we do for the vertical translation
 */
private const val RUBBER_BAND_AMOUNT = 15

private const val ARROW_APPEAR_STIFFNESS = 600f
private const val ARROW_APPEAR_DAMPING_RATIO = 0.4f
private const val ARROW_DISAPPEAR_STIFFNESS = 1200f
private const val ARROW_DISAPPEAR_DAMPING_RATIO = SpringForce.DAMPING_RATIO_NO_BOUNCY

/**
 * The interpolator used to rubber band
 */
private val RUBBER_BAND_INTERPOLATOR = PathInterpolator(1.0f / 5.0f, 1.0f, 1.0f, 1.0f)

private val ACCELERATE_INTERPOLATOR = AccelerateInterpolator(0.7f)

class BackPanelController private constructor(
    context: Context,
    private var backAnimation: BackAnimation?,
    private val windowManager: WindowManager,
    @Main private val mainHandler: Handler,
    private val vibratorHelper: VibratorHelper,
    private val configurationController: ConfigurationController,
    latencyTracker: LatencyTracker
) : ViewController<BackPanel>(BackPanel(context, latencyTracker)), NavigationEdgeBackPlugin {

    /**
     * Injectable instance to create a new BackPanelController.
     *
     * Necessary because EdgeBackGestureHandler sometimes needs to create new instances of
     * BackPanelController, and we need to match EdgeBackGestureHandler's context.
     */
    class Factory @Inject constructor(
        private val windowManager: WindowManager,
        @Main private val mainHandler: Handler,
        private val vibratorHelper: VibratorHelper,
        private val configurationController: ConfigurationController,
        private val latencyTracker: LatencyTracker
    ) {
        /** Construct a [BackPanelController].  */
        fun create(context: Context, backAnimation: BackAnimation?): BackPanelController {
            val backPanelController = BackPanelController(
                context,
                backAnimation,
                windowManager,
                mainHandler,
                vibratorHelper,
                configurationController,
                latencyTracker
            )
            backPanelController.init()
            return backPanelController
        }
    }

    private var params: EdgePanelParams = EdgePanelParams(resources)
    private var currentState: GestureState = GestureState.GONE
    private var previousState: GestureState = GestureState.GONE

    // Phone should only vibrate the first time the arrow is activated
    private var hasHapticPlayed = false

    // Screen attributes
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val displaySize = Point()

    private lateinit var backCallback: NavigationEdgeBackPlugin.BackCallback

    private var previousXTranslation = 0f
    private var totalTouchDelta = 0f
    private var velocityTracker: VelocityTracker? = null
        set(value) {
            if (field != value) field?.recycle()
            field = value
        }
        get() {
            if (field == null) field = VelocityTracker.obtain()
            return field
        }

    // The x,y position of the first touch event
    private var startX = 0f
    private var startY = 0f

    private val failsafeRunnable = Runnable { onFailsafe() }

    private enum class GestureState {
        /* Arrow is off the screen and invisible */
        GONE,

        /* Arrow is animating in */
        ENTRY,

        /* could be entry, neutral, or stretched, releasing will commit back */
        ACTIVE,

        /* releasing will cancel back */
        INACTIVE,

        /* like committed, but animation takes longer */
        FLUNG,

        /* back action currently occurring, arrow soon to be GONE */
        COMMITTED,

        /* back action currently cancelling, arrow soon to be GONE */
        CANCELLED
    }

    /**
     * Wrapper around OnAnimationEndListener which runs the given runnable after a delay. The
     * runnable is not called if the animation is cancelled
     */
    class DelayedOnAnimationEndListener(
        private val handler: Handler,
        private val runnable: Runnable,
        private val delay: Long
    ) : DynamicAnimation.OnAnimationEndListener {

        override fun onAnimationEnd(
            animation: DynamicAnimation<*>,
            canceled: Boolean,
            value: Float,
            velocity: Float
        ) {
            animation.removeEndListener(this)
            if (!canceled) {
                handler.postDelayed(runnable, delay)
            }
        }

        fun runNow() {
            runnable.run()
        }
    }

    private val setCommittedEndListener =
        DelayedOnAnimationEndListener(
            mainHandler,
            { updateArrowState(GestureState.COMMITTED) },
            delay = FLING_PAUSE_DURATION_MS
        )

    private val setGoneEndListener =
        DelayedOnAnimationEndListener(
            mainHandler,
            {
                cancelFailsafe()
                updateArrowState(GestureState.GONE)
            },
            delay = 0
        )

    // Vibration
    private var vibrationTime: Long = 0

    // Minimum size of the screen's width or height
    private var screenSize = 0

    /**
     * Used for initialization and configuration changes
     */
    private fun updateConfiguration() {
        params.update(resources)
        initializeBackAnimation()
        mView.updateArrowPaint(params.arrowThickness)
    }

    private val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onConfigChanged(newConfig: Configuration?) {
            updateConfiguration()
        }

        override fun onLayoutDirectionChanged(isLayoutRtl: Boolean) {
            updateArrowDirection(isLayoutRtl)
        }
    }

    override fun onViewAttached() {
        updateConfiguration()
        updateArrowDirection(configurationController.isLayoutRtl)
        updateArrowState(GestureState.GONE, force = true)
        updateRestingArrowDimens(animated = false, currentState)
        configurationController.addCallback(configurationListener)
    }

    /** Update the arrow direction. The arrow should point the same way for both panels. */
    private fun updateArrowDirection(isLayoutRtl: Boolean) {
        mView.arrowsPointLeft = isLayoutRtl
    }

    override fun onViewDetached() {
        configurationController.removeCallback(configurationListener)
    }

    override fun onMotionEvent(event: MotionEvent) {
        backAnimation?.onBackMotion(
            event,
            event.actionMasked,
            if (mView.isLeftPanel) BackEvent.EDGE_LEFT else BackEvent.EDGE_RIGHT
        )

        velocityTracker!!.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetOnDown()
                startX = event.x
                startY = event.y

                // Reset the arrow to the side
                updateArrowState(GestureState.ENTRY)

                windowManager.updateViewLayout(mView, layoutParams)
                mView.startTrackingShowBackArrowLatency()
            }
            MotionEvent.ACTION_MOVE -> handleMoveEvent(event)
            MotionEvent.ACTION_UP -> {
                if (currentState == GestureState.ACTIVE) {
                    updateArrowState(if (isFlung()) GestureState.FLUNG else GestureState.COMMITTED)
                } else {
                    updateArrowState(GestureState.CANCELLED)
                }
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                updateArrowState(GestureState.CANCELLED)
                velocityTracker = null
            }
        }
    }

    private fun updateArrowStateOnMove(yTranslation: Float, xTranslation: Float) {
        when (currentState) {
            GestureState.GONE, GestureState.FLUNG, GestureState.COMMITTED, GestureState.CANCELLED ->
                return
        }

        updateArrowState(
            when {
                // Check if we should transition from ENTRY to ACTIVE
                currentState == GestureState.ENTRY && xTranslation > params.swipeTriggerThreshold ->
                    GestureState.ACTIVE

                // Abort if we had continuous motion toward the edge for a while, OR the direction
                // in Y is bigger than X * 2
                currentState == GestureState.ACTIVE &&
                        ((totalTouchDelta < 0 && -totalTouchDelta > params.minDeltaForSwitch) ||
                                (yTranslation > xTranslation * 2)) ->
                    GestureState.INACTIVE

                //  Re-activate if we had continuous motion away from the edge for a while
                currentState == GestureState.INACTIVE &&
                        (totalTouchDelta > 0 && totalTouchDelta > params.minDeltaForSwitch) ->
                    GestureState.ACTIVE

                // By default assume the current direction is kept
                else -> currentState
            }
        )
    }

    private fun handleMoveEvent(event: MotionEvent) {
        when (currentState) {
            GestureState.GONE, GestureState.FLUNG, GestureState.COMMITTED,
            GestureState.CANCELLED -> return
        }

        val x = event.x
        val y = event.y

        val yOffset = y - startY

        // How far in the y direction we are from the original touch
        val yTranslation = abs(yOffset)

        // How far in the x direction we are from the original touch ignoring motion that
        // occurs between the screen edge and the touch start.
        val xTranslation = max(0f, if (mView.isLeftPanel) x - startX else startX - x)

        // Compared to last time, how far we moved in the x direction. If <0, we are moving closer
        // to the edge. If >0, we are moving further from the edge
        val xDelta = xTranslation - previousXTranslation
        previousXTranslation = xTranslation

        if (abs(xDelta) > 0) {
            if (sign(xDelta) == sign(totalTouchDelta)) {
                // Direction has NOT changed, so keep counting the delta
                totalTouchDelta += xDelta
            } else {
                // Direction has changed, so reset the delta
                totalTouchDelta = xDelta
            }
        }

        updateArrowStateOnMove(yTranslation, xTranslation)
        when (currentState) {
            GestureState.ACTIVE -> setActiveStretch(fullScreenStretchProgress(xTranslation))
            GestureState.ENTRY ->
                setEntryStretch(preThresholdStretchProgress(xTranslation))
            GestureState.INACTIVE -> mView.resetStretch()
        }

        // set y translation
        setVerticalTranslation(yOffset)
    }

    fun setVerticalTranslation(yOffset: Float) {
        val yTranslation = abs(yOffset)
        val maxYOffset = (mView.height / 2) - (params.entryBackgroundHeight / 2)
        val yProgress = saturate(yTranslation / (maxYOffset * RUBBER_BAND_AMOUNT))
        mView.animateVertically(
            RUBBER_BAND_INTERPOLATOR.getInterpolation(yProgress) * maxYOffset * sign(
                yOffset
            )
        )
    }

    /**
     * @return the relative position of the drag from the time after the arrow is activated until
     * the arrow is fully stretched (between 0.0 - 1.0f)
     */
    fun fullScreenStretchProgress(xTranslation: Float): Float {
        return saturate(
            (xTranslation - params.swipeTriggerThreshold) /
                    (min(
                        params.fullyStretchedThreshold,
                        screenSize.toFloat()
                    ) - params.swipeTriggerThreshold)
        )
    }

    /**
     * Tracks the relative position of the drag from the entry until the threshold where the arrow
     * activates (between 0.0 - 1.0f)
     */
    fun preThresholdStretchProgress(xTranslation: Float): Float {
        return saturate(xTranslation / params.swipeTriggerThreshold)
    }

    fun setActiveStretch(progress: Float) {
        val stretch = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
        mView.setStretch(
            arrowLengthStretch = stretch * (params.stretchedArrowLength - params.activeArrowLength),
            arrowHeightStretch = stretch * (params.stretchedArrowHeight - params.activeArrowHeight),
            backgroundWidthStretch =
                stretch * (params.stretchBackgroundWidth - params.activeBackgroundWidth),
            backgroundHeightStretch =
                stretch * (params.stretchBackgroundHeight - params.activeBackgroundHeight),
            backgroundEdgeCornerRadiusStretch =
                stretch * (params.stretchEdgeCorners - params.activeEdgeCorners),
            backgroundDragCornerRadiusStretch =
                stretch * (params.stretchFarCorners - params.activeFarCorners),
            horizontalTranslationStretch = stretch * (params.stretchMargin - params.activeMargin)
        )
    }

    fun setEntryStretch(progress: Float) {
        val bgStretch = ACCELERATE_INTERPOLATOR.getInterpolation(progress)
        val arrowStretch = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
        mView.setStretch(
            arrowLengthStretch =
                arrowStretch * (params.activeArrowLength - params.entryArrowLength),
            arrowHeightStretch =
                arrowStretch * (params.activeArrowHeight - params.entryArrowHeight),
            backgroundWidthStretch =
                bgStretch * (params.preThresholdBackgroundWidth - params.entryBackgroundWidth),
            backgroundHeightStretch =
                bgStretch * (params.preThresholdBackgroundHeight - params.entryBackgroundHeight),
            backgroundEdgeCornerRadiusStretch =
                bgStretch * (params.preThresholdEdgeCorners - params.entryEdgeCorners),
            backgroundDragCornerRadiusStretch =
                bgStretch * (params.preThresholdFarCorners - params.entryFarCorners),
            horizontalTranslationStretch =
                bgStretch * (params.preThresholdMargin - params.entryMargin)
        )
    }

    fun setBackAnimation(backAnimation: BackAnimation?) {
        this.backAnimation = backAnimation
        initializeBackAnimation()
    }

    private fun initializeBackAnimation() {
        backAnimation?.setSwipeThresholds(
            params.swipeTriggerThreshold,
            params.swipeProgressThreshold
        )
    }

    override fun onDestroy() {
        cancelFailsafe()
        windowManager.removeView(mView)
    }

    override fun setIsLeftPanel(isLeftPanel: Boolean) {
        mView.isLeftPanel = isLeftPanel
        layoutParams.gravity = if (isLeftPanel) {
            Gravity.LEFT or Gravity.TOP
        } else {
            Gravity.RIGHT or Gravity.TOP
        }
    }

    override fun setInsets(insetLeft: Int, insetRight: Int) {
    }

    override fun setBackCallback(callback: NavigationEdgeBackPlugin.BackCallback) {
        backCallback = callback
    }

    override fun setLayoutParams(layoutParams: WindowManager.LayoutParams) {
        this.layoutParams = layoutParams
        windowManager.addView(mView, layoutParams)
    }

    private fun isFlung() = velocityTracker!!.run {
        computeCurrentVelocity(1000)
        abs(xVelocity) > MIN_FLING_VELOCITY
    }

    private fun playFlingBackAnimation() {
        playAnimation(setCommittedEndListener)
    }

    private fun playCommitBackAnimation() {
        // Check if we should vibrate again
        if (previousState != GestureState.FLUNG) {
            backCallback.triggerBack()
            velocityTracker!!.computeCurrentVelocity(1000)
            val isSlow = abs(velocityTracker!!.xVelocity) < 500
            val hasNotVibratedRecently =
                SystemClock.uptimeMillis() - vibrationTime >= GESTURE_DURATION_FOR_CLICK_MS
            if (isSlow || hasNotVibratedRecently) {
                vibratorHelper.vibrate(VibrationEffect.EFFECT_CLICK)
            }
        }
        playAnimation(setGoneEndListener)
    }

    private fun playCancelBackAnimation() {
        backCallback.cancelBack()
        playAnimation(setGoneEndListener)
    }

    /**
     * @return true if the animation is running, false otherwise. Some transitions don't animate
     */
    private fun playAnimation(endListener: DelayedOnAnimationEndListener) {
        updateRestingArrowDimens(animated = true, currentState)

        if (!mView.addEndListener(endListener)) {
            scheduleFailsafe()
        }
    }

    private fun resetOnDown() {
        hasHapticPlayed = false
        totalTouchDelta = 0f
        vibrationTime = 0
        cancelFailsafe()
        backAnimation?.setTriggerBack(false)
    }

    private fun updateYPosition(touchY: Float) {
        var yPosition = touchY - params.fingerOffset
        yPosition = Math.max(yPosition, params.minArrowYPosition.toFloat())
        yPosition -= layoutParams.height / 2.0f
        layoutParams.y = constrain(yPosition.toInt(), 0, displaySize.y)
    }

    override fun setDisplaySize(displaySize: Point) {
        this.displaySize.set(displaySize.x, displaySize.y)
        screenSize = Math.min(displaySize.x, displaySize.y)
    }

    /**
     * Updates resting arrow and background size not accounting for stretch
     */
    private fun updateRestingArrowDimens(animated: Boolean, currentState: GestureState) {
        mView.updateRestingArrowDimens(
            backgroundWidth =
                when (currentState) {
                    GestureState.GONE, GestureState.ENTRY -> params.entryBackgroundWidth
                    else -> params.activeBackgroundWidth
                },
            backgroundHeight =
                when (currentState) {
                    GestureState.GONE, GestureState.ENTRY -> params.entryBackgroundHeight
                    else -> params.activeBackgroundHeight
                },
            backgroundEdgeCornerRadius =
                when (currentState) {
                    GestureState.GONE, GestureState.ENTRY, GestureState.INACTIVE ->
                        params.entryEdgeCorners
                    else ->
                        params.activeEdgeCorners
                },
            backgroundDragCornerRadius =
                when (currentState) {
                    GestureState.GONE, GestureState.ENTRY -> params.entryFarCorners
                    else -> params.activeFarCorners
                },
            arrowLength =
                when (currentState) {
                    GestureState.ACTIVE, GestureState.INACTIVE, GestureState.COMMITTED,
                    GestureState.FLUNG -> params.activeArrowLength
                    GestureState.CANCELLED -> params.cancelledArrowLength
                    GestureState.GONE, GestureState.ENTRY -> params.entryArrowLength
                },
            arrowHeight =
                when (currentState) {
                    GestureState.ACTIVE, GestureState.INACTIVE, GestureState.COMMITTED,
                    GestureState.FLUNG -> params.activeArrowHeight
                    GestureState.CANCELLED -> params.cancelledArrowHeight
                    GestureState.GONE, GestureState.ENTRY -> params.entryArrowHeight
                },
            horizontalTranslation =
                when (currentState) {
                    GestureState.GONE -> -params.activeBackgroundWidth
                    // Position the cancelled/committed arrow slightly further off the screen so we
                    // do not see part of it bouncing
                    GestureState.CANCELLED, GestureState.COMMITTED ->
                        -params.activeBackgroundWidth * 1.5f
                    GestureState.FLUNG -> params.stretchMargin
                    GestureState.ACTIVE -> params.activeMargin
                    GestureState.ENTRY, GestureState.INACTIVE -> params.entryMargin
                },
            animate = animated
        )
        if (animated) {
            when (currentState) {
                GestureState.ENTRY, GestureState.ACTIVE, GestureState.FLUNG ->
                    mView.setArrowStiffness(ARROW_APPEAR_STIFFNESS, ARROW_APPEAR_DAMPING_RATIO)
                else ->
                    mView.setArrowStiffness(
                        ARROW_DISAPPEAR_STIFFNESS, ARROW_DISAPPEAR_DAMPING_RATIO)
            }
        }
    }

    /**
     * Update arrow state. If state has not changed, this is a no-op.
     *
     * Transitioning to active/inactive will indicate whether or not releasing touch will trigger
     * the back action.
     */
    private fun updateArrowState(newState: GestureState, force: Boolean = false) {
        if (!force && currentState == newState) return

        if (DEBUG) Log.d(TAG, "updateArrowState $currentState -> $newState")
        previousState = currentState
        currentState = newState
        mView.visibility = if (currentState == GestureState.GONE) View.GONE else View.VISIBLE

        when (currentState) {
            // Transitioning to GONE never animates since the arrow is (presumably) already off the
            // screen
            GestureState.GONE -> updateRestingArrowDimens(animated = false, currentState)
            GestureState.ENTRY -> {
                updateYPosition(startY)
                updateRestingArrowDimens(animated = true, currentState)
            }
            GestureState.ACTIVE -> {
                backAnimation?.setTriggerBack(true)
                updateRestingArrowDimens(animated = true, currentState)
                // Vibrate the first time we transition to ACTIVE
                if (!hasHapticPlayed) {
                    hasHapticPlayed = true
                    vibrationTime = SystemClock.uptimeMillis()
                    vibratorHelper.vibrate(VibrationEffect.EFFECT_TICK)
                }
            }
            GestureState.INACTIVE -> {
                backAnimation?.setTriggerBack(false)
                updateRestingArrowDimens(animated = true, currentState)
            }
            GestureState.FLUNG -> playFlingBackAnimation()
            GestureState.COMMITTED -> playCommitBackAnimation()
            GestureState.CANCELLED -> playCancelBackAnimation()
        }
    }

    private fun scheduleFailsafe() {
        if (!ENABLE_FAILSAFE) return
        cancelFailsafe()
        if (DEBUG) Log.d(TAG, "scheduleFailsafe")
        mainHandler.postDelayed(failsafeRunnable, FAILSAFE_DELAY_MS)
    }

    private fun cancelFailsafe() {
        if (DEBUG) Log.d(TAG, "cancelFailsafe")
        mainHandler.removeCallbacks(failsafeRunnable)
    }

    private fun onFailsafe() {
        if (DEBUG) Log.d(TAG, "onFailsafe")
        updateArrowState(GestureState.GONE, force = true)
    }

    override fun dump(pw: PrintWriter) {
        pw.println("$TAG:")
        pw.println("  currentState=$currentState")
        pw.println("  isLeftPanel=$mView.isLeftPanel")
    }

    init {
        if (DEBUG) mView.drawDebugInfo = { canvas ->
            val debugStrings = listOf(
                "$currentState",
                "startX=$startX",
                "startY=$startY",
                "xDelta=${"%.1f".format(totalTouchDelta)}",
                "xTranslation=${"%.1f".format(previousXTranslation)}",
                "pre=${"%.0f".format(preThresholdStretchProgress(previousXTranslation) * 100)}%",
                "post=${"%.0f".format(fullScreenStretchProgress(previousXTranslation) * 100)}%"
            )
            val debugPaint = Paint().apply {
                color = Color.WHITE
            }
            val debugInfoBottom = debugStrings.size * 32f + 4f
            canvas.drawRect(
                4f,
                4f,
                canvas.width.toFloat(),
                debugStrings.size * 32f + 4f,
                debugPaint
            )
            debugPaint.apply {
                color = Color.BLACK
                textSize = 32f
            }
            var offset = 32f
            for (debugText in debugStrings) {
                canvas.drawText(debugText, 10f, offset, debugPaint)
                offset += 32f
            }
            debugPaint.apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val canvasWidth = canvas.width.toFloat()
            val canvasHeight = canvas.height.toFloat()
            canvas.drawRect(0f, 0f, canvasWidth, canvasHeight, debugPaint)

            fun drawVerticalLine(x: Float, color: Int) {
                debugPaint.color = color
                val x = if (mView.isLeftPanel) x else canvasWidth - x
                canvas.drawLine(x, debugInfoBottom, x, canvas.height.toFloat(), debugPaint)
            }

            drawVerticalLine(x = params.swipeTriggerThreshold, color = Color.BLUE)
            drawVerticalLine(x = startX, color = Color.GREEN)
            drawVerticalLine(x = previousXTranslation, color = Color.DKGRAY)
        }
    }
}
