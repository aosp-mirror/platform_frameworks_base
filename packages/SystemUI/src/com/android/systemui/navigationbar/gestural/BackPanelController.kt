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
import android.util.MathUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import com.android.internal.util.LatencyTracker
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.ONE_WAY_HAPTICS_API_MIGRATION
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
private const val ENABLE_FAILSAFE = true
private const val FAILSAFE_DELAY_MS = 350L

private const val PX_PER_SEC = 1000
private const val PX_PER_MS = 1

internal const val MIN_DURATION_ACTIVE_BEFORE_INACTIVE_ANIMATION = 300L
private const val MIN_DURATION_ACTIVE_AFTER_INACTIVE_ANIMATION = 130L
private const val MIN_DURATION_CANCELLED_ANIMATION = 200L
private const val MIN_DURATION_COMMITTED_ANIMATION = 80L
private const val MIN_DURATION_COMMITTED_AFTER_FLING_ANIMATION = 120L
private const val MIN_DURATION_INACTIVE_BEFORE_FLUNG_ANIMATION = 50L
private const val MIN_DURATION_INACTIVE_BEFORE_ACTIVE_ANIMATION = 160F
private const val MIN_DURATION_ENTRY_BEFORE_ACTIVE_ANIMATION = 10F
internal const val MAX_DURATION_ENTRY_BEFORE_ACTIVE_ANIMATION = 100F
private const val MIN_DURATION_FLING_ANIMATION = 160L

private const val MIN_DURATION_ENTRY_TO_ACTIVE_CONSIDERED_AS_FLING = 100L
private const val MIN_DURATION_INACTIVE_TO_ACTIVE_CONSIDERED_AS_FLING = 400L

private const val POP_ON_FLING_DELAY = 60L
private const val POP_ON_FLING_VELOCITY = 2f
private const val POP_ON_COMMITTED_VELOCITY = 3f
private const val POP_ON_ENTRY_TO_ACTIVE_VELOCITY = 4.5f
private const val POP_ON_INACTIVE_TO_ACTIVE_VELOCITY = 4.7f
private const val POP_ON_INACTIVE_VELOCITY = -1.5f

internal val VIBRATE_ACTIVATED_EFFECT =
    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

internal val VIBRATE_DEACTIVATED_EFFECT =
    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

private const val DEBUG = false

class BackPanelController
internal constructor(
    context: Context,
    private val windowManager: WindowManager,
    private val viewConfiguration: ViewConfiguration,
    @Main private val mainHandler: Handler,
    private val vibratorHelper: VibratorHelper,
    private val configurationController: ConfigurationController,
    private val latencyTracker: LatencyTracker,
    private val featureFlags: FeatureFlags
) : ViewController<BackPanel>(BackPanel(context, latencyTracker)), NavigationEdgeBackPlugin {

    /**
     * Injectable instance to create a new BackPanelController.
     *
     * Necessary because EdgeBackGestureHandler sometimes needs to create new instances of
     * BackPanelController, and we need to match EdgeBackGestureHandler's context.
     */
    class Factory
    @Inject
    constructor(
        private val windowManager: WindowManager,
        private val viewConfiguration: ViewConfiguration,
        @Main private val mainHandler: Handler,
        private val vibratorHelper: VibratorHelper,
        private val configurationController: ConfigurationController,
        private val latencyTracker: LatencyTracker,
        private val featureFlags: FeatureFlags
    ) {
        /** Construct a [BackPanelController]. */
        fun create(context: Context): BackPanelController {
            val backPanelController =
                BackPanelController(
                    context,
                    windowManager,
                    viewConfiguration,
                    mainHandler,
                    vibratorHelper,
                    configurationController,
                    latencyTracker,
                    featureFlags
                )
            backPanelController.init()
            return backPanelController
        }
    }

    @VisibleForTesting internal var params: EdgePanelParams = EdgePanelParams(resources)
    @VisibleForTesting internal var currentState: GestureState = GestureState.GONE
    private var previousState: GestureState = GestureState.GONE

    // Screen attributes
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val displaySize = Point()

    private lateinit var backCallback: NavigationEdgeBackPlugin.BackCallback
    private var previousXTranslationOnActiveOffset = 0f
    private var previousXTranslation = 0f
    private var totalTouchDeltaActive = 0f
    private var totalTouchDeltaInactive = 0f
    private var touchDeltaStartX = 0f
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
    private var startIsLeft: Boolean? = null

    private var gestureEntryTime = 0L
    private var gestureInactiveTime = 0L

    private val elapsedTimeSinceInactive
        get() = SystemClock.uptimeMillis() - gestureInactiveTime
    private val elapsedTimeSinceEntry
        get() = SystemClock.uptimeMillis() - gestureEntryTime

    private var pastThresholdWhileEntryOrInactiveTime = 0L
    private var entryToActiveDelay = 0F
    private val entryToActiveDelayCalculation = {
        convertVelocityToAnimationFactor(
            valueOnFastVelocity = MIN_DURATION_ENTRY_BEFORE_ACTIVE_ANIMATION,
            valueOnSlowVelocity = MAX_DURATION_ENTRY_BEFORE_ACTIVE_ANIMATION,
        )
    }

    // Whether the current gesture has moved a sufficiently large amount,
    // so that we can unambiguously start showing the ENTRY animation
    private var hasPassedDragSlop = false

    // Distance in pixels a drag can be considered for a fling event
    private var minFlingDistance = 0

    private val failsafeRunnable = Runnable { onFailsafe() }

    internal enum class GestureState {
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
    inner class DelayedOnAnimationEndListener
    internal constructor(
        private val handler: Handler,
        private val runnableDelay: Long,
        val runnable: Runnable,
    ) : DynamicAnimation.OnAnimationEndListener {

        override fun onAnimationEnd(
            animation: DynamicAnimation<*>,
            canceled: Boolean,
            value: Float,
            velocity: Float
        ) {
            animation.removeEndListener(this)

            if (!canceled) {
                // The delay between finishing this animation and starting the runnable
                val delay = max(0, runnableDelay - elapsedTimeSinceEntry)

                handler.postDelayed(runnable, delay)
            }
        }

        internal fun run() = runnable.run()
    }

    private val onEndSetCommittedStateListener =
        DelayedOnAnimationEndListener(mainHandler, 0L) { updateArrowState(GestureState.COMMITTED) }

    private val onEndSetGoneStateListener =
        DelayedOnAnimationEndListener(mainHandler, runnableDelay = 0L) {
            cancelFailsafe()
            updateArrowState(GestureState.GONE)
        }

    private val onAlphaEndSetGoneStateListener =
        DelayedOnAnimationEndListener(mainHandler, 0L) {
            updateRestingArrowDimens()
            if (!mView.addAnimationEndListener(mView.backgroundAlpha, onEndSetGoneStateListener)) {
                scheduleFailsafe()
            }
        }

    // Minimum of the screen's width or the predefined threshold
    private var fullyStretchedThreshold = 0f

    /** Used for initialization and configuration changes */
    private fun updateConfiguration() {
        params.update(resources)
        mView.updateArrowPaint(params.arrowThickness)
        minFlingDistance = viewConfiguration.scaledTouchSlop * 3
    }

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
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
        updateRestingArrowDimens()
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
                cancelAllPendingAnimations()
                startX = event.x
                startY = event.y

                updateArrowState(GestureState.GONE)
                updateYStartPosition(startY)

                // reset animation properties
                startIsLeft = mView.isLeftPanel
                hasPassedDragSlop = false
                mView.resetStretch()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragSlopExceeded(event.x, startX)) {
                    handleMoveEvent(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                when (currentState) {
                    GestureState.ENTRY -> {
                        if (
                            isFlungAwayFromEdge(endX = event.x) ||
                                previousXTranslation > params.staticTriggerThreshold
                        ) {
                            updateArrowState(GestureState.FLUNG)
                        } else {
                            updateArrowState(GestureState.CANCELLED)
                        }
                    }
                    GestureState.INACTIVE -> {
                        if (isFlungAwayFromEdge(endX = event.x)) {
                            // This is called outside of updateArrowState so that
                            // BackAnimationController can immediately evaluate state
                            // instead of after the flung delay
                            backCallback.setTriggerBack(true)
                            mainHandler.postDelayed(MIN_DURATION_INACTIVE_BEFORE_FLUNG_ANIMATION) {
                                updateArrowState(GestureState.FLUNG)
                            }
                        } else {
                            updateArrowState(GestureState.CANCELLED)
                        }
                    }
                    GestureState.ACTIVE -> {
                        if (
                            previousState == GestureState.ENTRY &&
                                elapsedTimeSinceEntry <
                                    MIN_DURATION_ENTRY_TO_ACTIVE_CONSIDERED_AS_FLING
                        ) {
                            updateArrowState(GestureState.FLUNG)
                        } else if (
                            previousState == GestureState.INACTIVE &&
                                elapsedTimeSinceInactive <
                                    MIN_DURATION_INACTIVE_TO_ACTIVE_CONSIDERED_AS_FLING
                        ) {
                            // A delay is added to allow the background to transition back to ACTIVE
                            // since it was briefly in INACTIVE. Without this delay, setting it
                            // immediately to COMMITTED would result in the committed animation
                            // appearing like it was playing in INACTIVE.
                            mainHandler.postDelayed(MIN_DURATION_ACTIVE_AFTER_INACTIVE_ANIMATION) {
                                updateArrowState(GestureState.COMMITTED)
                            }
                        } else {
                            updateArrowState(GestureState.COMMITTED)
                        }
                    }
                    GestureState.GONE,
                    GestureState.FLUNG,
                    GestureState.COMMITTED,
                    GestureState.CANCELLED -> {
                        updateArrowState(GestureState.CANCELLED)
                    }
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

    private fun cancelAllPendingAnimations() {
        cancelFailsafe()
        mView.cancelAnimations()
        mainHandler.removeCallbacks(onEndSetCommittedStateListener.runnable)
        mainHandler.removeCallbacks(onEndSetGoneStateListener.runnable)
        mainHandler.removeCallbacks(onAlphaEndSetGoneStateListener.runnable)
    }

    /**
     * Returns false until the current gesture exceeds the touch slop threshold, and returns true
     * thereafter (we reset on the subsequent back gesture). The moment it switches from false ->
     * true is important, because that's when we switch state, from GONE -> ENTRY.
     *
     * @return whether the current gesture has moved past a minimum threshold.
     */
    private fun dragSlopExceeded(curX: Float, startX: Float): Boolean {
        if (hasPassedDragSlop) return true

        if (abs(curX - startX) > viewConfiguration.scaledEdgeSlop) {
            // Reset the arrow to the side
            updateArrowState(GestureState.ENTRY)

            windowManager.updateViewLayout(mView, layoutParams)
            mView.startTrackingShowBackArrowLatency()

            hasPassedDragSlop = true
        }
        return hasPassedDragSlop
    }

    private fun updateArrowStateOnMove(yTranslation: Float, xTranslation: Float) {
        val isWithinYActivationThreshold = xTranslation * 2 >= yTranslation
        val isPastStaticThreshold = xTranslation > params.staticTriggerThreshold
        when (currentState) {
            GestureState.ENTRY -> {
                if (
                    isPastThresholdToActive(
                        isPastThreshold = isPastStaticThreshold,
                        dynamicDelay = entryToActiveDelayCalculation
                    )
                ) {
                    updateArrowState(GestureState.ACTIVE)
                }
            }
            GestureState.INACTIVE -> {
                val isPastDynamicReactivationThreshold =
                    totalTouchDeltaInactive >= params.reactivationTriggerThreshold

                if (
                    isPastThresholdToActive(
                        isPastThreshold =
                            isPastStaticThreshold &&
                                isPastDynamicReactivationThreshold &&
                                isWithinYActivationThreshold,
                        delay = MIN_DURATION_INACTIVE_BEFORE_ACTIVE_ANIMATION
                    )
                ) {
                    updateArrowState(GestureState.ACTIVE)
                }
            }
            GestureState.ACTIVE -> {
                val isPastDynamicDeactivationThreshold =
                    totalTouchDeltaActive <= params.deactivationTriggerThreshold
                val isMinDurationElapsed =
                    elapsedTimeSinceEntry > MIN_DURATION_ACTIVE_BEFORE_INACTIVE_ANIMATION
                val isPastAllThresholds =
                    !isWithinYActivationThreshold || isPastDynamicDeactivationThreshold
                if (isPastAllThresholds && isMinDurationElapsed) {
                    updateArrowState(GestureState.INACTIVE)
                }
            }
            else -> {}
        }
    }

    private fun handleMoveEvent(event: MotionEvent) {
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
            val isInSameDirection = sign(xDelta) == sign(totalTouchDeltaActive)
            val isInDynamicRange = totalTouchDeltaActive in params.dynamicTriggerThresholdRange
            val isTouchInContinuousDirection = isInSameDirection || isInDynamicRange

            if (isTouchInContinuousDirection) {
                // Direction has NOT changed, so keep counting the delta
                totalTouchDeltaActive += xDelta
            } else {
                // Direction has changed, so reset the delta
                totalTouchDeltaActive = xDelta
                touchDeltaStartX = x
            }

            // Add a slop to to prevent small jitters when arrow is at edge in
            // emitting small values that cause the arrow to poke out slightly
            val minimumDelta = -viewConfiguration.scaledTouchSlop.toFloat()
            totalTouchDeltaInactive =
                totalTouchDeltaInactive.plus(xDelta).coerceAtLeast(minimumDelta)
        }

        updateArrowStateOnMove(yTranslation, xTranslation)

        val gestureProgress =
            when (currentState) {
                GestureState.ACTIVE -> fullScreenProgress(xTranslation)
                GestureState.ENTRY -> staticThresholdProgress(xTranslation)
                GestureState.INACTIVE -> reactivationThresholdProgress(totalTouchDeltaInactive)
                else -> null
            }

        gestureProgress?.let {
            when (currentState) {
                GestureState.ACTIVE -> stretchActiveBackIndicator(gestureProgress)
                GestureState.ENTRY -> stretchEntryBackIndicator(gestureProgress)
                GestureState.INACTIVE -> stretchInactiveBackIndicator(gestureProgress)
                else -> {}
            }
        }

        setArrowStrokeAlpha(gestureProgress)
        setVerticalTranslation(yOffset)
    }

    private fun setArrowStrokeAlpha(gestureProgress: Float?) {
        val strokeAlphaProgress =
            when (currentState) {
                GestureState.ENTRY -> gestureProgress
                GestureState.INACTIVE -> gestureProgress
                GestureState.ACTIVE,
                GestureState.FLUNG,
                GestureState.COMMITTED -> 1f
                GestureState.CANCELLED,
                GestureState.GONE -> 0f
            }

        val indicator =
            when (currentState) {
                GestureState.ENTRY -> params.entryIndicator
                GestureState.INACTIVE -> params.preThresholdIndicator
                GestureState.ACTIVE -> params.activeIndicator
                else -> params.preThresholdIndicator
            }

        strokeAlphaProgress?.let { progress ->
            indicator.arrowDimens.alphaSpring
                ?.get(progress)
                ?.takeIf { it.isNewState }
                ?.let { mView.popArrowAlpha(0f, it.value) }
        }
    }

    private fun setVerticalTranslation(yOffset: Float) {
        val yTranslation = abs(yOffset)
        val maxYOffset = (mView.height - params.entryIndicator.backgroundDimens.height) / 2f
        val rubberbandAmount = 15f
        val yProgress = MathUtils.saturate(yTranslation / (maxYOffset * rubberbandAmount))
        val yPosition =
            params.verticalTranslationInterpolator.getInterpolation(yProgress) *
                maxYOffset *
                sign(yOffset)
        mView.animateVertically(yPosition)
    }

    /**
     * Tracks the relative position of the drag from the time after the arrow is activated until the
     * arrow is fully stretched (between 0.0 - 1.0f)
     */
    private fun fullScreenProgress(xTranslation: Float): Float {
        val progress = (xTranslation - previousXTranslationOnActiveOffset) / fullyStretchedThreshold
        return MathUtils.saturate(progress)
    }

    /**
     * Tracks the relative position of the drag from the entry until the threshold where the arrow
     * activates (between 0.0 - 1.0f)
     */
    private fun staticThresholdProgress(xTranslation: Float): Float {
        return MathUtils.saturate(xTranslation / params.staticTriggerThreshold)
    }

    private fun reactivationThresholdProgress(totalTouchDelta: Float): Float {
        return MathUtils.saturate(totalTouchDelta / params.reactivationTriggerThreshold)
    }

    private fun stretchActiveBackIndicator(progress: Float) {
        mView.setStretch(
            horizontalTranslationStretchAmount =
                params.horizontalTranslationInterpolator.getInterpolation(progress),
            arrowStretchAmount = params.arrowAngleInterpolator.getInterpolation(progress),
            backgroundWidthStretchAmount =
                params.activeWidthInterpolator.getInterpolation(progress),
            backgroundAlphaStretchAmount = 1f,
            backgroundHeightStretchAmount = 1f,
            arrowAlphaStretchAmount = 1f,
            edgeCornerStretchAmount = 1f,
            farCornerStretchAmount = 1f,
            fullyStretchedDimens = params.fullyStretchedIndicator
        )
    }

    private fun stretchEntryBackIndicator(progress: Float) {
        mView.setStretch(
            horizontalTranslationStretchAmount = 0f,
            arrowStretchAmount = params.arrowAngleInterpolator.getInterpolation(progress),
            backgroundWidthStretchAmount = params.entryWidthInterpolator.getInterpolation(progress),
            backgroundHeightStretchAmount = params.heightInterpolator.getInterpolation(progress),
            backgroundAlphaStretchAmount = 1f,
            arrowAlphaStretchAmount =
                params.entryIndicator.arrowDimens.alphaInterpolator?.get(progress)?.value ?: 0f,
            edgeCornerStretchAmount = params.edgeCornerInterpolator.getInterpolation(progress),
            farCornerStretchAmount = params.farCornerInterpolator.getInterpolation(progress),
            fullyStretchedDimens = params.preThresholdIndicator
        )
    }

    private var previousPreThresholdWidthInterpolator = params.entryWidthInterpolator
    private fun preThresholdWidthStretchAmount(progress: Float): Float {
        val interpolator = run {
            val isPastSlop = totalTouchDeltaInactive > viewConfiguration.scaledTouchSlop
            if (isPastSlop) {
                    if (totalTouchDeltaInactive > 0) {
                        params.entryWidthInterpolator
                    } else {
                        params.entryWidthTowardsEdgeInterpolator
                    }
                } else {
                    previousPreThresholdWidthInterpolator
                }
                .also { previousPreThresholdWidthInterpolator = it }
        }
        return interpolator.getInterpolation(progress).coerceAtLeast(0f)
    }

    private fun stretchInactiveBackIndicator(progress: Float) {
        mView.setStretch(
            horizontalTranslationStretchAmount = 0f,
            arrowStretchAmount = params.arrowAngleInterpolator.getInterpolation(progress),
            backgroundWidthStretchAmount = preThresholdWidthStretchAmount(progress),
            backgroundHeightStretchAmount = params.heightInterpolator.getInterpolation(progress),
            backgroundAlphaStretchAmount = 1f,
            arrowAlphaStretchAmount =
                params.preThresholdIndicator.arrowDimens.alphaInterpolator?.get(progress)?.value
                    ?: 0f,
            edgeCornerStretchAmount = params.edgeCornerInterpolator.getInterpolation(progress),
            farCornerStretchAmount = params.farCornerInterpolator.getInterpolation(progress),
            fullyStretchedDimens = params.preThresholdIndicator
        )
    }

    override fun onDestroy() {
        cancelFailsafe()
        windowManager.removeView(mView)
    }

    override fun setIsLeftPanel(isLeftPanel: Boolean) {
        mView.isLeftPanel = isLeftPanel
        layoutParams.gravity =
            if (isLeftPanel) {
                Gravity.LEFT or Gravity.TOP
            } else {
                Gravity.RIGHT or Gravity.TOP
            }
    }

    override fun setInsets(insetLeft: Int, insetRight: Int) = Unit

    override fun setBackCallback(callback: NavigationEdgeBackPlugin.BackCallback) {
        backCallback = callback
    }

    override fun setLayoutParams(layoutParams: WindowManager.LayoutParams) {
        this.layoutParams = layoutParams
        windowManager.addView(mView, layoutParams)
    }

    private fun isFlungAwayFromEdge(endX: Float, startX: Float = touchDeltaStartX): Boolean {
        val flingDistance = if (mView.isLeftPanel) endX - startX else startX - endX
        val flingVelocity =
            velocityTracker?.run {
                computeCurrentVelocity(PX_PER_SEC)
                xVelocity.takeIf { mView.isLeftPanel } ?: (xVelocity * -1)
            }
                ?: 0f
        val isPastFlingVelocityThreshold =
            flingVelocity > viewConfiguration.scaledMinimumFlingVelocity
        return flingDistance > minFlingDistance && isPastFlingVelocityThreshold
    }

    private fun isPastThresholdToActive(
        isPastThreshold: Boolean,
        delay: Float? = null,
        dynamicDelay: () -> Float = { delay ?: 0F }
    ): Boolean {
        val resetValue = 0L
        val isPastThresholdForFirstTime = pastThresholdWhileEntryOrInactiveTime == resetValue

        if (!isPastThreshold) {
            pastThresholdWhileEntryOrInactiveTime = resetValue
            return false
        }

        if (isPastThresholdForFirstTime) {
            pastThresholdWhileEntryOrInactiveTime = SystemClock.uptimeMillis()
            entryToActiveDelay = dynamicDelay()
        }
        val timePastThreshold = SystemClock.uptimeMillis() - pastThresholdWhileEntryOrInactiveTime

        return timePastThreshold > entryToActiveDelay
    }

    private fun playWithBackgroundWidthAnimation(
        onEnd: DelayedOnAnimationEndListener,
        delay: Long = 0L
    ) {
        if (delay == 0L) {
            updateRestingArrowDimens()
            if (!mView.addAnimationEndListener(mView.backgroundWidth, onEnd)) {
                scheduleFailsafe()
            }
        } else {
            mainHandler.postDelayed(delay) { playWithBackgroundWidthAnimation(onEnd, delay = 0L) }
        }
    }

    private fun updateYStartPosition(touchY: Float) {
        var yPosition = touchY - params.fingerOffset
        yPosition = max(yPosition, params.minArrowYPosition.toFloat())
        yPosition -= layoutParams.height / 2.0f
        layoutParams.y = MathUtils.constrain(yPosition.toInt(), 0, displaySize.y)
    }

    override fun setDisplaySize(displaySize: Point) {
        this.displaySize.set(displaySize.x, displaySize.y)
        fullyStretchedThreshold = min(displaySize.x.toFloat(), params.swipeProgressThreshold)
    }

    /** Updates resting arrow and background size not accounting for stretch */
    private fun updateRestingArrowDimens() {
        when (currentState) {
            GestureState.GONE,
            GestureState.ENTRY -> {
                mView.setSpring(
                    arrowLength = params.entryIndicator.arrowDimens.lengthSpring,
                    arrowHeight = params.entryIndicator.arrowDimens.heightSpring,
                    scale = params.entryIndicator.scaleSpring,
                    verticalTranslation = params.entryIndicator.verticalTranslationSpring,
                    horizontalTranslation = params.entryIndicator.horizontalTranslationSpring,
                    backgroundAlpha = params.entryIndicator.backgroundDimens.alphaSpring,
                    backgroundWidth = params.entryIndicator.backgroundDimens.widthSpring,
                    backgroundHeight = params.entryIndicator.backgroundDimens.heightSpring,
                    backgroundEdgeCornerRadius =
                        params.entryIndicator.backgroundDimens.edgeCornerRadiusSpring,
                    backgroundFarCornerRadius =
                        params.entryIndicator.backgroundDimens.farCornerRadiusSpring,
                )
            }
            GestureState.INACTIVE -> {
                mView.setSpring(
                    arrowLength = params.preThresholdIndicator.arrowDimens.lengthSpring,
                    arrowHeight = params.preThresholdIndicator.arrowDimens.heightSpring,
                    horizontalTranslation =
                        params.preThresholdIndicator.horizontalTranslationSpring,
                    scale = params.preThresholdIndicator.scaleSpring,
                    backgroundWidth = params.preThresholdIndicator.backgroundDimens.widthSpring,
                    backgroundHeight = params.preThresholdIndicator.backgroundDimens.heightSpring,
                    backgroundEdgeCornerRadius =
                        params.preThresholdIndicator.backgroundDimens.edgeCornerRadiusSpring,
                    backgroundFarCornerRadius =
                        params.preThresholdIndicator.backgroundDimens.farCornerRadiusSpring,
                )
            }
            GestureState.ACTIVE -> {
                mView.setSpring(
                    arrowLength = params.activeIndicator.arrowDimens.lengthSpring,
                    arrowHeight = params.activeIndicator.arrowDimens.heightSpring,
                    scale = params.activeIndicator.scaleSpring,
                    horizontalTranslation = params.activeIndicator.horizontalTranslationSpring,
                    backgroundWidth = params.activeIndicator.backgroundDimens.widthSpring,
                    backgroundHeight = params.activeIndicator.backgroundDimens.heightSpring,
                    backgroundEdgeCornerRadius =
                        params.activeIndicator.backgroundDimens.edgeCornerRadiusSpring,
                    backgroundFarCornerRadius =
                        params.activeIndicator.backgroundDimens.farCornerRadiusSpring,
                )
            }
            GestureState.FLUNG -> {
                mView.setSpring(
                    arrowLength = params.flungIndicator.arrowDimens.lengthSpring,
                    arrowHeight = params.flungIndicator.arrowDimens.heightSpring,
                    backgroundWidth = params.flungIndicator.backgroundDimens.widthSpring,
                    backgroundHeight = params.flungIndicator.backgroundDimens.heightSpring,
                    backgroundEdgeCornerRadius =
                        params.flungIndicator.backgroundDimens.edgeCornerRadiusSpring,
                    backgroundFarCornerRadius =
                        params.flungIndicator.backgroundDimens.farCornerRadiusSpring,
                )
            }
            GestureState.COMMITTED -> {
                mView.setSpring(
                    arrowLength = params.committedIndicator.arrowDimens.lengthSpring,
                    arrowHeight = params.committedIndicator.arrowDimens.heightSpring,
                    scale = params.committedIndicator.scaleSpring,
                    backgroundAlpha = params.committedIndicator.backgroundDimens.alphaSpring,
                    backgroundWidth = params.committedIndicator.backgroundDimens.widthSpring,
                    backgroundHeight = params.committedIndicator.backgroundDimens.heightSpring,
                    backgroundEdgeCornerRadius =
                        params.committedIndicator.backgroundDimens.edgeCornerRadiusSpring,
                    backgroundFarCornerRadius =
                        params.committedIndicator.backgroundDimens.farCornerRadiusSpring,
                )
            }
            GestureState.CANCELLED -> {
                mView.setSpring(
                    backgroundAlpha = params.cancelledIndicator.backgroundDimens.alphaSpring
                )
            }
            else -> {}
        }

        mView.setRestingDimens(
            animate =
                !(currentState == GestureState.FLUNG || currentState == GestureState.COMMITTED),
            restingParams =
                EdgePanelParams.BackIndicatorDimens(
                    scale =
                        when (currentState) {
                            GestureState.ACTIVE,
                            GestureState.FLUNG, -> params.activeIndicator.scale
                            GestureState.COMMITTED -> params.committedIndicator.scale
                            else -> params.preThresholdIndicator.scale
                        },
                    scalePivotX =
                        when (currentState) {
                            GestureState.GONE,
                            GestureState.ENTRY,
                            GestureState.INACTIVE,
                            GestureState.CANCELLED -> params.preThresholdIndicator.scalePivotX
                            GestureState.ACTIVE -> params.activeIndicator.scalePivotX
                            GestureState.FLUNG,
                            GestureState.COMMITTED -> params.committedIndicator.scalePivotX
                        },
                    horizontalTranslation =
                        when (currentState) {
                            GestureState.GONE -> {
                                params.activeIndicator.backgroundDimens.width?.times(-1)
                            }
                            GestureState.ENTRY,
                            GestureState.INACTIVE -> params.entryIndicator.horizontalTranslation
                            GestureState.FLUNG -> params.activeIndicator.horizontalTranslation
                            GestureState.ACTIVE -> params.activeIndicator.horizontalTranslation
                            GestureState.CANCELLED -> {
                                params.cancelledIndicator.horizontalTranslation
                            }
                            else -> null
                        },
                    arrowDimens =
                        when (currentState) {
                            GestureState.GONE,
                            GestureState.ENTRY,
                            GestureState.INACTIVE -> params.entryIndicator.arrowDimens
                            GestureState.ACTIVE -> params.activeIndicator.arrowDimens
                            GestureState.FLUNG -> params.flungIndicator.arrowDimens
                            GestureState.COMMITTED -> params.committedIndicator.arrowDimens
                            GestureState.CANCELLED -> params.cancelledIndicator.arrowDimens
                        },
                    backgroundDimens =
                        when (currentState) {
                            GestureState.GONE,
                            GestureState.ENTRY,
                            GestureState.INACTIVE -> params.entryIndicator.backgroundDimens
                            GestureState.ACTIVE -> params.activeIndicator.backgroundDimens
                            GestureState.FLUNG -> params.activeIndicator.backgroundDimens
                            GestureState.COMMITTED -> params.committedIndicator.backgroundDimens
                            GestureState.CANCELLED -> params.cancelledIndicator.backgroundDimens
                        }
                )
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

        previousState = currentState
        currentState = newState

        when (currentState) {
            GestureState.CANCELLED -> {
                backCallback.cancelBack()
            }
            GestureState.FLUNG,
            GestureState.COMMITTED -> {
                // When flung, trigger back immediately but don't fire again
                // once state resolves to committed.
                if (previousState != GestureState.FLUNG) backCallback.triggerBack()
            }
            GestureState.ENTRY,
            GestureState.INACTIVE -> {
                backCallback.setTriggerBack(false)
            }
            GestureState.ACTIVE -> {
                backCallback.setTriggerBack(true)
            }
            GestureState.GONE -> {}
        }

        when (currentState) {
            // Transitioning to GONE never animates since the arrow is (presumably) already off the
            // screen
            GestureState.GONE -> {
                updateRestingArrowDimens()
                mView.isVisible = false
            }
            GestureState.ENTRY -> {
                mView.isVisible = true

                updateRestingArrowDimens()
                gestureEntryTime = SystemClock.uptimeMillis()
            }
            GestureState.ACTIVE -> {
                previousXTranslationOnActiveOffset = previousXTranslation
                updateRestingArrowDimens()
                performActivatedHapticFeedback()
                val popVelocity =
                    if (previousState == GestureState.INACTIVE) {
                        POP_ON_INACTIVE_TO_ACTIVE_VELOCITY
                    } else {
                        POP_ON_ENTRY_TO_ACTIVE_VELOCITY
                    }
                mView.popOffEdge(popVelocity)
            }
            GestureState.INACTIVE -> {
                gestureInactiveTime = SystemClock.uptimeMillis()

                // Typically entering INACTIVE means
                // totalTouchDelta <= deactivationSwipeTriggerThreshold
                // but because we can also independently enter this state
                // if touch Y >> touch X, we force it to deactivationSwipeTriggerThreshold
                // so that gesture progress in this state is consistent regardless of entry
                totalTouchDeltaInactive = params.deactivationTriggerThreshold

                mView.popOffEdge(POP_ON_INACTIVE_VELOCITY)

                performDeactivatedHapticFeedback()
                updateRestingArrowDimens()
            }
            GestureState.FLUNG -> {
                // Typically a vibration is only played while transitioning to ACTIVE. However there
                // are instances where a fling to trigger back occurs while not in that state.
                // (e.g. A fling is detected before crossing the trigger threshold.)
                if (previousState != GestureState.ACTIVE) {
                    performActivatedHapticFeedback()
                }
                mainHandler.postDelayed(POP_ON_FLING_DELAY) {
                    mView.popScale(POP_ON_FLING_VELOCITY)
                }
                mainHandler.postDelayed(
                    onEndSetCommittedStateListener.runnable,
                    MIN_DURATION_FLING_ANIMATION
                )
                updateRestingArrowDimens()
            }
            GestureState.COMMITTED -> {
                // In most cases, animating between states is handled via `updateRestingArrowDimens`
                // which plays an animation immediately upon state change. Some animations however
                // occur after a delay upon state change and these animations may be independent
                // or non-sequential from the state change animation. `postDelayed` is used to
                // manually play these kinds of animations in parallel.
                if (previousState == GestureState.FLUNG) {
                    updateRestingArrowDimens()
                    mainHandler.postDelayed(
                        onEndSetGoneStateListener.runnable,
                        MIN_DURATION_COMMITTED_AFTER_FLING_ANIMATION
                    )
                } else {
                    mView.popScale(POP_ON_COMMITTED_VELOCITY)
                    mainHandler.postDelayed(
                        onAlphaEndSetGoneStateListener.runnable,
                        MIN_DURATION_COMMITTED_ANIMATION
                    )
                }
            }
            GestureState.CANCELLED -> {
                val delay = max(0, MIN_DURATION_CANCELLED_ANIMATION - elapsedTimeSinceEntry)
                playWithBackgroundWidthAnimation(onEndSetGoneStateListener, delay)

                val springForceOnCancelled =
                    params.cancelledIndicator.arrowDimens.alphaSpring?.get(0f)?.value
                mView.popArrowAlpha(0f, springForceOnCancelled)
                if (!featureFlags.isEnabled(ONE_WAY_HAPTICS_API_MIGRATION))
                    mainHandler.postDelayed(10L) { vibratorHelper.cancel() }
            }
        }
    }

    private fun performDeactivatedHapticFeedback() {
        if (featureFlags.isEnabled(ONE_WAY_HAPTICS_API_MIGRATION)) {
            vibratorHelper.performHapticFeedback(
                    mView,
                    HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            )
        } else {
            vibratorHelper.vibrate(VIBRATE_DEACTIVATED_EFFECT)
        }
    }

    private fun performActivatedHapticFeedback() {
        if (featureFlags.isEnabled(ONE_WAY_HAPTICS_API_MIGRATION)) {
            vibratorHelper.performHapticFeedback(
                    mView,
                    HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            )
        } else {
            vibratorHelper.cancel()
            mainHandler.postDelayed(10L) {
                vibratorHelper.vibrate(VIBRATE_ACTIVATED_EFFECT)
            }
        }
    }

    private fun convertVelocityToAnimationFactor(
        valueOnFastVelocity: Float,
        valueOnSlowVelocity: Float,
        fastVelocityBound: Float = 1f,
        slowVelocityBound: Float = 0.5f,
    ): Float {
        val factor =
            velocityTracker?.run {
                computeCurrentVelocity(PX_PER_MS)
                MathUtils.smoothStep(slowVelocityBound, fastVelocityBound, abs(xVelocity))
            }
                ?: valueOnFastVelocity

        return MathUtils.lerp(valueOnFastVelocity, valueOnSlowVelocity, 1 - factor)
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
        if (DEBUG)
            mView.drawDebugInfo = { canvas ->
                val preProgress = staticThresholdProgress(previousXTranslation) * 100
                val postProgress = fullScreenProgress(previousXTranslation) * 100
                val debugStrings =
                    listOf(
                        "$currentState",
                        "startX=$startX",
                        "startY=$startY",
                        "xDelta=${"%.1f".format(totalTouchDeltaActive)}",
                        "xTranslation=${"%.1f".format(previousXTranslation)}",
                        "pre=${"%.0f".format(preProgress)}%",
                        "post=${"%.0f".format(postProgress)}%"
                    )
                val debugPaint = Paint().apply { color = Color.WHITE }
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

                drawVerticalLine(x = params.staticTriggerThreshold, color = Color.BLUE)
                drawVerticalLine(x = params.deactivationTriggerThreshold, color = Color.BLUE)
                drawVerticalLine(x = startX, color = Color.GREEN)
                drawVerticalLine(x = previousXTranslation, color = Color.DKGRAY)
            }
    }
}

/**
 * In addition to a typical step function which returns one or two values based on a threshold,
 * `Step` also gracefully handles quick changes in input near the threshold value that would
 * typically result in the output rapidly changing.
 *
 * In the context of Back arrow, the arrow's stroke opacity should always appear transparent or
 * opaque. Using a typical Step function, this would resulting in a flickering appearance as the
 * output would change rapidly. `Step` addresses this by moving the threshold after it is crossed so
 * it cannot be easily crossed again with small changes in touch events.
 */
class Step<T>(
    private val threshold: Float,
    private val factor: Float = 1.1f,
    private val postThreshold: T,
    private val preThreshold: T
) {

    data class Value<T>(val value: T, val isNewState: Boolean)

    private val lowerFactor = 2 - factor

    private lateinit var startValue: Value<T>
    private lateinit var previousValue: Value<T>
    private var hasCrossedUpperBoundAtLeastOnce = false
    private var progress: Float = 0f

    init {
        reset()
    }

    fun reset() {
        hasCrossedUpperBoundAtLeastOnce = false
        progress = 0f
        startValue = Value(preThreshold, false)
        previousValue = startValue
    }

    fun get(progress: Float): Value<T> {
        this.progress = progress

        val hasCrossedUpperBound = progress > threshold * factor
        val hasCrossedLowerBound = progress > threshold * lowerFactor

        return when {
            hasCrossedUpperBound && !hasCrossedUpperBoundAtLeastOnce -> {
                hasCrossedUpperBoundAtLeastOnce = true
                Value(postThreshold, true)
            }
            hasCrossedLowerBound -> previousValue.copy(isNewState = false)
            hasCrossedUpperBoundAtLeastOnce -> {
                hasCrossedUpperBoundAtLeastOnce = false
                Value(preThreshold, true)
            }
            else -> startValue
        }.also { previousValue = it }
    }
}
