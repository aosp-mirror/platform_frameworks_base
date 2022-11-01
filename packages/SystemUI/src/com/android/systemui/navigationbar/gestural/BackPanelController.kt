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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.LatencyTracker
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.NavigationEdgeBackPlugin
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.ViewController
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
private const val FLING_MIN_APPEARANCE_DURATION = 235L

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

private val DECELERATE_INTERPOLATOR = DecelerateInterpolator()

private val DECELERATE_INTERPOLATOR_SLOW = DecelerateInterpolator(0.7f)

class BackPanelController private constructor(
    context: Context,
    private val windowManager: WindowManager,
    private val viewConfiguration: ViewConfiguration,
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
        private val viewConfiguration: ViewConfiguration,
        @Main private val mainHandler: Handler,
        private val vibratorHelper: VibratorHelper,
        private val configurationController: ConfigurationController,
        private val latencyTracker: LatencyTracker
    ) {
        /** Construct a [BackPanelController].  */
        fun create(context: Context): BackPanelController {
            val backPanelController = BackPanelController(
                context,
                windowManager,
                viewConfiguration,
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

    private var gestureStartTime = 0L

    // Whether the current gesture has moved a sufficiently large amount,
    // so that we can unambiguously start showing the ENTRY animation
    private var hasPassedDragSlop = false

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
        CANCELLED;

        /**
         * @return true if the current state responds to touch move events in some way (e.g. by
         * stretching the back indicator)
         */
        fun isInteractive(): Boolean {
            return when (this) {
                ENTRY, ACTIVE, INACTIVE -> true
                GONE, FLUNG, COMMITTED, CANCELLED -> false
            }
        }
    }

    /**
     * Wrapper around OnAnimationEndListener which runs the given runnable after a delay. The
     * runnable is not called if the animation is cancelled
     */
    inner class DelayedOnAnimationEndListener internal constructor(
        private val handler: Handler,
        private val runnable: Runnable,
        private val minDuration: Long
    ) : DynamicAnimation.OnAnimationEndListener {
        override fun onAnimationEnd(
            animation: DynamicAnimation<*>,
            canceled: Boolean,
            value: Float,
            velocity: Float
        ) {
            animation.removeEndListener(this)
            if (!canceled) {
                // Total elapsed time of the gesture and the animation
                val totalElapsedTime = SystemClock.uptimeMillis() - gestureStartTime
                // The delay between finishing this animation and starting the runnable
                val delay = max(0, minDuration - totalElapsedTime)
                handler.postDelayed(runnable, delay)
            }
        }

        internal fun runNow() {
            runnable.run()
        }
    }

    private val setCommittedEndListener =
        DelayedOnAnimationEndListener(
            mainHandler,
            { updateArrowState(GestureState.COMMITTED) },
            minDuration = FLING_MIN_APPEARANCE_DURATION
        )

    private val setGoneEndListener =
        DelayedOnAnimationEndListener(
            mainHandler,
            {
                cancelFailsafe()
                updateArrowState(GestureState.GONE)
            },
            minDuration = 0
        )

    // Vibration
    private var vibrationTime: Long = 0

    // Minimum of the screen's width or the predefined threshold
    private var fullyStretchedThreshold = 0f

    /**
     * Used for initialization and configuration changes
     */
    private fun updateConfiguration() {
        params.update(resources)
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
        velocityTracker!!.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetOnDown()
                startX = event.x
                startY = event.y
                gestureStartTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                // only go to the ENTRY state after some minimum motion has occurred
                if (dragSlopExceeded(event.x, startX)) {
                    handleMoveEvent(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (currentState == GestureState.ACTIVE) {
                    updateArrowState(if (isFlung()) GestureState.FLUNG else GestureState.COMMITTED)
                } else if (currentState != GestureState.GONE) { // if invisible, skip animation
                    updateArrowState(GestureState.CANCELLED)
                }
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                // Receiving a CANCEL implies that something else intercepted
                // the gesture, i.e., the user did not cancel their gesture.
                // Therefore, disappear immediately, with minimum fanfare.
                updateArrowState(GestureState.GONE)
                velocityTracker = null
            }
        }
    }

    /**
     * Returns false until the current gesture exceeds the touch slop threshold,
     * and returns true thereafter (we reset on the subsequent back gesture).
     * The moment it switches from false -> true is important,
     * because that's when we switch state, from GONE -> ENTRY.
     * @return whether the current gesture has moved past a minimum threshold.
     */
    private fun dragSlopExceeded(curX: Float, startX: Float): Boolean {
        if (hasPassedDragSlop) return true

        if (abs(curX - startX) > viewConfiguration.scaledTouchSlop) {
            // Reset the arrow to the side
            updateArrowState(GestureState.ENTRY)

            windowManager.updateViewLayout(mView, layoutParams)
            mView.startTrackingShowBackArrowLatency()

            hasPassedDragSlop = true
        }
        return hasPassedDragSlop
    }

    private fun updateArrowStateOnMove(yTranslation: Float, xTranslation: Float) {
        if (!currentState.isInteractive())
            return

        when (currentState) {
            // Check if we should transition from ENTRY to ACTIVE
            GestureState.ENTRY ->
                if (xTranslation > params.swipeTriggerThreshold) {
                    updateArrowState(GestureState.ACTIVE)
                }

            // Abort if we had continuous motion toward the edge for a while, OR the direction
            // in Y is bigger than X * 2
            GestureState.ACTIVE ->
                if ((totalTouchDelta < 0 && -totalTouchDelta > params.minDeltaForSwitch) ||
                    (yTranslation > xTranslation * 2)
                ) {
                    updateArrowState(GestureState.INACTIVE)
                }

            //  Re-activate if we had continuous motion away from the edge for a while
            GestureState.INACTIVE ->
                if (totalTouchDelta > 0 && totalTouchDelta > params.minDeltaForSwitch) {
                    updateArrowState(GestureState.ACTIVE)
                }

            // By default assume the current direction is kept
            else -> {}
        }
    }

    private fun handleMoveEvent(event: MotionEvent) {
        if (!currentState.isInteractive())
            return

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
            GestureState.ACTIVE ->
                stretchActiveBackIndicator(fullScreenStretchProgress(xTranslation))
            GestureState.ENTRY ->
                stretchEntryBackIndicator(preThresholdStretchProgress(xTranslation))
            GestureState.INACTIVE ->
                mView.resetStretch()
        }

        // set y translation
        setVerticalTranslation(yOffset)
    }

    private fun setVerticalTranslation(yOffset: Float) {
        val yTranslation = abs(yOffset)
        val maxYOffset = (mView.height - params.entryIndicator.backgroundDimens.height) / 2f
        val yProgress = saturate(yTranslation / (maxYOffset * RUBBER_BAND_AMOUNT))
        mView.animateVertically(
            RUBBER_BAND_INTERPOLATOR.getInterpolation(yProgress) * maxYOffset *
                sign(yOffset)
        )
    }

    /**
     * @return the relative position of the drag from the time after the arrow is activated until
     * the arrow is fully stretched (between 0.0 - 1.0f)
     */
    private fun fullScreenStretchProgress(xTranslation: Float): Float {
        return saturate(
            (xTranslation - params.swipeTriggerThreshold) /
                (fullyStretchedThreshold - params.swipeTriggerThreshold)
        )
    }

    /**
     * Tracks the relative position of the drag from the entry until the threshold where the arrow
     * activates (between 0.0 - 1.0f)
     */
    private fun preThresholdStretchProgress(xTranslation: Float): Float {
        return saturate(xTranslation / params.swipeTriggerThreshold)
    }

    private fun stretchActiveBackIndicator(progress: Float) {
        val rubberBandIterpolation = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
        mView.setStretch(
            horizontalTranslationStretchAmount = rubberBandIterpolation,
            arrowStretchAmount = rubberBandIterpolation,
            backgroundWidthStretchAmount = DECELERATE_INTERPOLATOR_SLOW.getInterpolation(progress),
            params.fullyStretchedIndicator
        )
    }

    private fun stretchEntryBackIndicator(progress: Float) {
        mView.setStretch(
            horizontalTranslationStretchAmount = 0f,
            arrowStretchAmount = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress),
            backgroundWidthStretchAmount = DECELERATE_INTERPOLATOR.getInterpolation(progress),
            params.preThresholdIndicator
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
            velocityTracker!!.computeCurrentVelocity(1000)
            val isSlow = abs(velocityTracker!!.xVelocity) < 500
            val hasNotVibratedRecently =
                SystemClock.uptimeMillis() - vibrationTime >= GESTURE_DURATION_FOR_CLICK_MS
            if (isSlow || hasNotVibratedRecently) {
                vibratorHelper.vibrate(VibrationEffect.EFFECT_CLICK)
            }
        }
        // Dispatch the actual back trigger
        if (DEBUG) Log.d(TAG, "playCommitBackAnimation() invoked triggerBack() on backCallback")
        backCallback.triggerBack()

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
        hasPassedDragSlop = false
        hasHapticPlayed = false
        totalTouchDelta = 0f
        vibrationTime = 0
        cancelFailsafe()
    }

    private fun updateYPosition(touchY: Float) {
        var yPosition = touchY - params.fingerOffset
        yPosition = max(yPosition, params.minArrowYPosition.toFloat())
        yPosition -= layoutParams.height / 2.0f
        layoutParams.y = constrain(yPosition.toInt(), 0, displaySize.y)
    }

    override fun setDisplaySize(displaySize: Point) {
        this.displaySize.set(displaySize.x, displaySize.y)
        fullyStretchedThreshold = min(displaySize.x.toFloat(), params.swipeProgressThreshold)
    }

    /**
     * Updates resting arrow and background size not accounting for stretch
     */
    private fun updateRestingArrowDimens(animated: Boolean, currentState: GestureState) {
        if (animated) {
            when (currentState) {
                GestureState.ENTRY, GestureState.ACTIVE, GestureState.FLUNG ->
                    mView.setArrowStiffness(ARROW_APPEAR_STIFFNESS, ARROW_APPEAR_DAMPING_RATIO)
                GestureState.CANCELLED -> mView.fadeOut()
                else ->
                    mView.setArrowStiffness(
                        ARROW_DISAPPEAR_STIFFNESS,
                        ARROW_DISAPPEAR_DAMPING_RATIO
                    )
            }
        }
        mView.setRestingDimens(
            restingParams = EdgePanelParams.BackIndicatorDimens(
                horizontalTranslation = when (currentState) {
                    GestureState.GONE -> -params.activeIndicator.backgroundDimens.width
                    // Position the committed arrow slightly further off the screen so we  do not
                    // see part of it bouncing
                    GestureState.COMMITTED ->
                        -params.activeIndicator.backgroundDimens.width * 1.5f
                    GestureState.FLUNG -> params.fullyStretchedIndicator.horizontalTranslation
                    GestureState.ACTIVE -> params.activeIndicator.horizontalTranslation
                    GestureState.ENTRY, GestureState.INACTIVE, GestureState.CANCELLED ->
                        params.entryIndicator.horizontalTranslation
                },
                arrowDimens = when (currentState) {
                    GestureState.ACTIVE, GestureState.INACTIVE,
                    GestureState.COMMITTED, GestureState.FLUNG -> params.activeIndicator.arrowDimens
                    GestureState.CANCELLED -> params.cancelledArrowDimens
                    GestureState.GONE, GestureState.ENTRY -> params.entryIndicator.arrowDimens
                },
                backgroundDimens = when (currentState) {
                    GestureState.GONE, GestureState.ENTRY -> params.entryIndicator.backgroundDimens
                    else ->
                        params.activeIndicator.backgroundDimens.copy(
                            edgeCornerRadius =
                            if (currentState == GestureState.INACTIVE ||
                                currentState == GestureState.CANCELLED
                            )
                                params.cancelledEdgeCornerRadius
                            else
                                params.activeIndicator.backgroundDimens.edgeCornerRadius
                        )
                }
            ),
            animate = animated
        )
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
        if (currentState == GestureState.GONE) {
            mView.cancelAlphaAnimations()
            mView.visibility = View.GONE
        } else {
            mView.visibility = View.VISIBLE
        }

        when (currentState) {
            // Transitioning to GONE never animates since the arrow is (presumably) already off the
            // screen
            GestureState.GONE -> updateRestingArrowDimens(animated = false, currentState)
            GestureState.ENTRY -> {
                updateYPosition(startY)
                updateRestingArrowDimens(animated = true, currentState)
            }
            GestureState.ACTIVE -> {
                updateRestingArrowDimens(animated = true, currentState)
                // Vibrate the first time we transition to ACTIVE
                if (!hasHapticPlayed) {
                    hasHapticPlayed = true
                    vibrationTime = SystemClock.uptimeMillis()
                    vibratorHelper.vibrate(VibrationEffect.EFFECT_TICK)
                }
            }
            GestureState.INACTIVE -> {
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
