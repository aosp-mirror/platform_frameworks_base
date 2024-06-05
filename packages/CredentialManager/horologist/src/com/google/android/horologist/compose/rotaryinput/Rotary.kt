/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalHorologistApi::class)

package com.google.android.horologist.compose.rotaryinput

import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.fastSumBy
import androidx.compose.ui.util.lerp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.sign

private const val DEBUG = false

/**
 * Debug logging that can be enabled.
 */
private inline fun debugLog(generateMsg: () -> String) {
    if (DEBUG) {
        println("RotaryScroll: ${generateMsg()}")
    }
}

/**
 * A modifier which connects rotary events with scrollable.
 * This modifier supports fling.
 *
 *  Fling algorithm:
 * - A scroll with RSB/ Bezel happens.
 * - If this is a first rotary event after the threshold ( by default 200ms), a new scroll
 * session starts by resetting all necessary parameters
 * - A delta value is added into VelocityTracker and a new speed is calculated.
 * - If the current speed is bigger than the previous one,  this value is remembered as
 * a latest fling speed with a timestamp
 * - After each scroll event a fling countdown starts ( by default 70ms) which
 * resets if new scroll event is received
 * - If fling countdown is finished - it means that the finger was probably raised from RSB, there will be no other events and probably
 * this is the last event during this session. After it a fling is triggered.
 * - Fling is stopped when a new scroll event happens
 *
 * The screen containing the scrollable item should request the focus
 * by calling [requestFocus] method
 *
 * ```
 * LaunchedEffect(Unit) {
 *   focusRequester.requestFocus()
 * }
 * ```
 * @param focusRequester Requests the focus for rotary input
 * @param scrollableState Scrollable state which will be scrolled while receiving rotary events
 * @param flingBehavior Logic describing fling behavior.
 * @param rotaryHaptics Class which will handle haptic feedback
 * @param reverseDirection Reverse the direction of scrolling. Should be aligned with
 * Scrollable `reverseDirection` parameter
 */
@ExperimentalHorologistApi
@Suppress("ComposableModifierFactory")
@Deprecated(
        "Use rotaryWithScroll instead",
        ReplaceWith(
                "this.rotaryWithScroll(scrollableState, focusRequester, " +
                        "flingBehavior, rotaryHaptics, reverseDirection)",
        ),
)
@Composable
public fun Modifier.rotaryWithFling(
        focusRequester: FocusRequester,
        scrollableState: ScrollableState,
        flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
        rotaryHaptics: RotaryHapticHandler = rememberRotaryHapticHandler(scrollableState),
        reverseDirection: Boolean = false,
): Modifier = rotaryHandler(
        rotaryScrollHandler = RotaryDefaults.rememberFlingHandler(scrollableState, flingBehavior),
        reverseDirection = reverseDirection,
        rotaryHaptics = rotaryHaptics,
)
        .focusRequester(focusRequester)
        .focusable()

/**
 * A modifier which connects rotary events with scrollable.
 * This modifier supports scroll with fling.
 *
 * @param scrollableState Scrollable state which will be scrolled while receiving rotary events
 * @param focusRequester Requests the focus for rotary input.
 * By default comes from [rememberActiveFocusRequester],
 * which is used with [HierarchicalFocusCoordinator]
 * @param flingBehavior Logic describing fling behavior. If null fling will not happen.
 * @param rotaryHaptics Class which will handle haptic feedback
 * @param reverseDirection Reverse the direction of scrolling. Should be aligned with
 * Scrollable `reverseDirection` parameter
 */
@OptIn(ExperimentalWearFoundationApi::class)
@ExperimentalHorologistApi
@Suppress("ComposableModifierFactory")
@Composable
public fun Modifier.rotaryWithScroll(
        scrollableState: ScrollableState,
        focusRequester: FocusRequester = rememberActiveFocusRequester(),
        flingBehavior: FlingBehavior? = ScrollableDefaults.flingBehavior(),
        rotaryHaptics: RotaryHapticHandler = rememberRotaryHapticHandler(scrollableState),
        reverseDirection: Boolean = false,
): Modifier = rotaryHandler(
        rotaryScrollHandler = RotaryDefaults.rememberFlingHandler(scrollableState, flingBehavior),
        reverseDirection = reverseDirection,
        rotaryHaptics = rotaryHaptics,
)
        .focusRequester(focusRequester)
        .focusable()

/**
 * A modifier which connects rotary events with scrollable.
 * This modifier supports snap.
 *
 * @param focusRequester Requests the focus for rotary input.
 * By default comes from [rememberActiveFocusRequester],
 * which is used with [HierarchicalFocusCoordinator]
 * @param rotaryScrollAdapter A connection between scrollable objects and rotary events
 * @param rotaryHaptics Class which will handle haptic feedback
 * @param reverseDirection Reverse the direction of scrolling. Should be aligned with
 * Scrollable `reverseDirection` parameter
 */
@OptIn(ExperimentalWearFoundationApi::class)
@ExperimentalHorologistApi
@Suppress("ComposableModifierFactory")
@Composable
public fun Modifier.rotaryWithSnap(
        rotaryScrollAdapter: RotaryScrollAdapter,
        focusRequester: FocusRequester = rememberActiveFocusRequester(),
        snapParameters: SnapParameters = RotaryDefaults.snapParametersDefault(),
        rotaryHaptics: RotaryHapticHandler =
                rememberRotaryHapticHandler(rotaryScrollAdapter.scrollableState),
        reverseDirection: Boolean = false,
): Modifier = rotaryHandler(
        rotaryScrollHandler =
            RotaryDefaults.rememberSnapHandler(rotaryScrollAdapter, snapParameters),
        reverseDirection = reverseDirection,
        rotaryHaptics = rotaryHaptics,
)
        .focusRequester(focusRequester)
        .focusable()

/**
 * An extension function for creating [RotaryScrollAdapter] from [ScalingLazyListState]
 */
@ExperimentalHorologistApi
public fun ScalingLazyListState.toRotaryScrollAdapter(): RotaryScrollAdapter =
        ScalingLazyColumnRotaryScrollAdapter(this)

/**
 * An implementation of rotary scroll adapter for [ScalingLazyColumn]
 */
@ExperimentalHorologistApi
public class ScalingLazyColumnRotaryScrollAdapter(
        override val scrollableState: ScalingLazyListState,
) : RotaryScrollAdapter {

    /**
     * Calculates an average height of an item by taking an average from visible items height.
     */
    override fun averageItemSize(): Float {
        val visibleItems = scrollableState.layoutInfo.visibleItemsInfo
        return (visibleItems.fastSumBy { it.unadjustedSize } / visibleItems.size).toFloat()
    }

    /**
     * Current (centred) item index
     */
    override fun currentItemIndex(): Int = scrollableState.centerItemIndex

    /**
     * An offset from the item centre
     */
    override fun currentItemOffset(): Float = scrollableState.centerItemScrollOffset.toFloat()

    /**
     * The total count of items in ScalingLazyColumn
     */
    override fun totalItemsCount(): Int = scrollableState.layoutInfo.totalItemsCount
}

/**
 * An adapter which connects scrollableState to Rotary
 */
@ExperimentalHorologistApi
public interface RotaryScrollAdapter {

    /**
     * A scrollable state. Used for performing scroll when Rotary events received
     */
    @ExperimentalHorologistApi
    public val scrollableState: ScrollableState

    /**
     * Average size of an item. Used for estimating the scrollable distance
     */
    @ExperimentalHorologistApi
    public fun averageItemSize(): Float

    /**
     * A current item index. Used for scrolling
     */
    @ExperimentalHorologistApi
    public fun currentItemIndex(): Int

    /**
     * An offset from the centre or the border of the current item.
     */
    @ExperimentalHorologistApi
    public fun currentItemOffset(): Float

    /**
     * The total count of items in [scrollableState]
     */
    @ExperimentalHorologistApi
    public fun totalItemsCount(): Int
}

/**
 * Defaults for rotary modifiers
 */
@ExperimentalHorologistApi
public object RotaryDefaults {

    /**
     * Handles scroll with fling.
     * @param scrollableState Scrollable state which will be scrolled while receiving rotary events
     * @param flingBehavior Logic describing Fling behavior. If null - fling will not happen
     * @param isLowRes Whether the input is Low-res (a bezel) or high-res(a crown/rsb)
     */
    @ExperimentalHorologistApi
    @Composable
    public fun rememberFlingHandler(
            scrollableState: ScrollableState,
            flingBehavior: FlingBehavior? = null,
            isLowRes: Boolean = isLowResInput(),
    ): RotaryScrollHandler {
        val viewConfiguration = ViewConfiguration.get(LocalContext.current)

        return remember(scrollableState, flingBehavior, isLowRes) {
            debugLog { "isLowRes : $isLowRes" }
            fun rotaryFlingBehavior() = flingBehavior?.run {
                DefaultRotaryFlingBehavior(
                        scrollableState,
                        flingBehavior,
                        viewConfiguration,
                        flingTimeframe =
                            if (isLowRes) lowResFlingTimeframe else highResFlingTimeframe,
                )
            }

            fun scrollBehavior() = AnimationScrollBehavior(scrollableState)

            if (isLowRes) {
                LowResRotaryScrollHandler(
                        rotaryFlingBehaviorFactory = { rotaryFlingBehavior() },
                        scrollBehaviorFactory = { scrollBehavior() },
                )
            } else {
                HighResRotaryScrollHandler(
                        rotaryFlingBehaviorFactory = { rotaryFlingBehavior() },
                        scrollBehaviorFactory = { scrollBehavior() },
                )
            }
        }
    }

    /**
     * Handles scroll with snap
     * @param rotaryScrollAdapter A connection between scrollable objects and rotary events
     * @param snapParameters Snap parameters
     */
    @ExperimentalHorologistApi
    @Composable
    public fun rememberSnapHandler(
            rotaryScrollAdapter: RotaryScrollAdapter,
            snapParameters: SnapParameters = snapParametersDefault(),
            isLowRes: Boolean = isLowResInput(),
    ): RotaryScrollHandler {
        return remember(rotaryScrollAdapter, snapParameters) {
            if (isLowRes) {
                LowResSnapHandler(
                        snapBehaviourFactory = {
                            DefaultSnapBehavior(rotaryScrollAdapter, snapParameters)
                        },
                )
            } else {
                HighResSnapHandler(
                        resistanceFactor = snapParameters.resistanceFactor,
                        thresholdBehaviorFactory = {
                            ThresholdBehavior(
                                    rotaryScrollAdapter,
                                    snapParameters.thresholdDivider,
                            )
                        },
                        snapBehaviourFactory = {
                            DefaultSnapBehavior(rotaryScrollAdapter, snapParameters)
                        },
                        scrollBehaviourFactory = {
                            AnimationScrollBehavior(rotaryScrollAdapter.scrollableState)
                        },
                )
            }
        }
    }

    /**
     * Returns default [SnapParameters]
     */
    @ExperimentalHorologistApi
    public fun snapParametersDefault(): SnapParameters =
            SnapParameters(
                    snapOffset = 0,
                    thresholdDivider = 1.5f,
                    resistanceFactor = 3f,
            )

    /**
     * Returns whether the input is Low-res (a bezel) or high-res(a crown/rsb).
     */
    @ExperimentalHorologistApi
    @Composable
    public fun isLowResInput(): Boolean = LocalContext.current.packageManager
            .hasSystemFeature("android.hardware.rotaryencoder.lowres")

    private val lowResFlingTimeframe: Long = 100L
    private val highResFlingTimeframe: Long = 30L
}

/**
 * Parameters used for snapping
 *
 * @param snapOffset an optional offset to be applied when snapping the item. After the snap the
 * snapped items offset will be [snapOffset].
 */
public class SnapParameters(
        public val snapOffset: Int,
        public val thresholdDivider: Float,
        public val resistanceFactor: Float,
) {
    /**
     * Returns a snapping offset in [Dp]
     */
    @Composable
    public fun snapOffsetDp(): Dp {
        return with(LocalDensity.current) {
            snapOffset.toDp()
        }
    }
}

/**
 * An interface for handling scroll events
 */
@ExperimentalHorologistApi
public interface RotaryScrollHandler {
    /**
     * Handles scrolling events
     * @param coroutineScope A scope for performing async actions
     * @param event A scrollable event from rotary input, containing scrollable delta and timestamp
     * @param rotaryHaptics
     */
    @ExperimentalHorologistApi
    public suspend fun handleScrollEvent(
            coroutineScope: CoroutineScope,
            event: TimestampedDelta,
            rotaryHaptics: RotaryHapticHandler,
    )
}

/**
 * An interface for scrolling behavior
 */
@ExperimentalHorologistApi
public interface RotaryScrollBehavior {
    /**
     * Handles scroll event to [targetValue]
     */
    @ExperimentalHorologistApi
    public suspend fun handleEvent(targetValue: Float)
}

/**
 * Default implementation of [RotaryFlingBehavior]
 */
@ExperimentalHorologistApi
public class DefaultRotaryFlingBehavior(
        private val scrollableState: ScrollableState,
        private val flingBehavior: FlingBehavior,
        viewConfiguration: ViewConfiguration,
        private val flingTimeframe: Long,
) : RotaryFlingBehavior {

    // A time range during which the fling is valid.
    // For simplicity it's twice as long as [flingTimeframe]
    private val timeRangeToFling = flingTimeframe * 2

    //  A default fling factor for making fling slower
    private val flingScaleFactor = 0.7f

    private var previousVelocity = 0f

    private val rotaryVelocityTracker = RotaryVelocityTracker()

    private val minFlingSpeed = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maxFlingSpeed = viewConfiguration.scaledMaximumFlingVelocity.toFloat()
    private var latestEventTimestamp: Long = 0

    private var flingVelocity: Float = 0f
    private var flingTimestamp: Long = 0

    @ExperimentalHorologistApi
    override fun startFlingTracking(timestamp: Long) {
        rotaryVelocityTracker.start(timestamp)
        latestEventTimestamp = timestamp
        previousVelocity = 0f
    }

    @ExperimentalHorologistApi
    override fun observeEvent(timestamp: Long, delta: Float) {
        rotaryVelocityTracker.move(timestamp, delta)
        latestEventTimestamp = timestamp
    }

    @ExperimentalHorologistApi
    override suspend fun trackFling(beforeFling: () -> Unit) {
        val currentVelocity = rotaryVelocityTracker.velocity
        debugLog { "currentVelocity: $currentVelocity" }

        if (abs(currentVelocity) >= abs(previousVelocity)) {
            flingTimestamp = latestEventTimestamp
            flingVelocity = currentVelocity * flingScaleFactor
        }
        previousVelocity = currentVelocity

        // Waiting for a fixed amount of time before checking the fling
        delay(flingTimeframe)

        // For making a fling 2 criteria should be met:
        // 1) no more than
        // `rangeToFling` ms should pass between last fling detection
        // and the time of last motion event
        // 2) flingVelocity should exceed the minFlingSpeed
        debugLog {
            "Check fling:  flingVelocity: $flingVelocity " +
                    "minFlingSpeed: $minFlingSpeed, maxFlingSpeed: $maxFlingSpeed"
        }
        if (latestEventTimestamp - flingTimestamp < timeRangeToFling &&
                abs(flingVelocity) > minFlingSpeed
        ) {
            // Stops scrollAnimationCoroutine because a fling will be performed
            beforeFling()
            val velocity = flingVelocity.coerceIn(-maxFlingSpeed, maxFlingSpeed)
            scrollableState.scroll(MutatePriority.UserInput) {
                with(flingBehavior) {
                    debugLog { "Flinging with velocity $velocity" }
                    performFling(velocity)
                }
            }
        }
    }
}

/**
 * An interface for flinging with rotary
 */
@ExperimentalHorologistApi
public interface RotaryFlingBehavior {

    /**
     * Observing new event within a fling tracking session with new timestamp and delta
     */
    @ExperimentalHorologistApi
    public fun observeEvent(timestamp: Long, delta: Float)

    /**
     * Performing fling if necessary and calling [beforeFling] lambda before it is triggered
     */
    @ExperimentalHorologistApi
    public suspend fun trackFling(beforeFling: () -> Unit)

    /**
     * Starts a new fling tracking session
     * with specified timestamp
     */
    @ExperimentalHorologistApi
    public fun startFlingTracking(timestamp: Long)
}

/**
 * An interface for snapping with rotary
 */
@ExperimentalHorologistApi
public interface RotarySnapBehavior {

    /**
     * Preparing snapping. This method should be called before [snapToTargetItem] is called.
     *
     * Snapping is done for current + [moveForElements] items.
     *
     * If [sequentialSnap] is true, items are summed up together.
     * For example, if [prepareSnapForItems] is called with
     * [moveForElements] = 2, 3, 5 -> then the snapping will happen to current + 10 items
     *
     * If [sequentialSnap] is false, then [moveForElements] are not summed up together.
     */
    public fun prepareSnapForItems(moveForElements: Int, sequentialSnap: Boolean)

    /**
     * Performs snapping to the closest item.
     */
    public suspend fun snapToClosestItem()

    /**
     * Returns true if top edge was reached
     */
    public fun topEdgeReached(): Boolean

    /**
     * Returns true if bottom edge was reached
     */
    public fun bottomEdgeReached(): Boolean

    /**
     * Performs snapping to the specified in [prepareSnapForItems] element
     */
    public suspend fun snapToTargetItem()
}

/**
 * A rotary event object which contains a [timestamp] of the rotary event and a scrolled [delta].
 */
@ExperimentalHorologistApi
public data class TimestampedDelta(val timestamp: Long, val delta: Float)

/** Animation implementation of [RotaryScrollBehavior].
 * This class does a smooth animation when the scroll by N pixels is done.
 * This animation works well on Rsb(high-res) and Bezel(low-res) devices.
 */
@ExperimentalHorologistApi
public class AnimationScrollBehavior(
        private val scrollableState: ScrollableState,
) : RotaryScrollBehavior {
    private var sequentialAnimation = false
    private var scrollAnimation = AnimationState(0f)
    private var prevPosition = 0f

    @ExperimentalHorologistApi
    override suspend fun handleEvent(targetValue: Float) {
        scrollableState.scroll(MutatePriority.UserInput) {
            debugLog { "ScrollAnimation value before start: ${scrollAnimation.value}" }

            scrollAnimation.animateTo(
                    targetValue,
                    animationSpec = spring(),
                    sequentialAnimation = sequentialAnimation,
            ) {
                val delta = value - prevPosition
                debugLog { "Animated by $delta, value: $value" }
                scrollBy(delta)
                prevPosition = value
                sequentialAnimation = value != this.targetValue
            }
        }
    }
}

/**
 * An animated implementation of [RotarySnapBehavior]. Uses animateScrollToItem
 * method for snapping to the Nth item
 */
@ExperimentalHorologistApi
public class DefaultSnapBehavior(
        private val rotaryScrollAdapter: RotaryScrollAdapter,
        private val snapParameters: SnapParameters,
) : RotarySnapBehavior {
    private var snapTarget: Int = rotaryScrollAdapter.currentItemIndex()
    private var sequentialSnap: Boolean = false

    private var anim = AnimationState(0f)
    private var expectedDistance = 0f

    private val defaultStiffness = 200f
    private var snapTargetUpdated = true

    @ExperimentalHorologistApi
    override fun prepareSnapForItems(moveForElements: Int, sequentialSnap: Boolean) {
        this.sequentialSnap = sequentialSnap
        if (sequentialSnap) {
            snapTarget += moveForElements
        } else {
            snapTarget = rotaryScrollAdapter.currentItemIndex() + moveForElements
        }
        snapTargetUpdated = true
        snapTarget = snapTarget.coerceIn(0 until rotaryScrollAdapter.totalItemsCount())
    }

    override suspend fun snapToClosestItem() {
        // Snapping to the closest item by using performFling method with 0 speed
        rotaryScrollAdapter.scrollableState.scroll(MutatePriority.UserInput) {
            debugLog { "snap to closest item" }
            var prevPosition = 0f
            AnimationState(0f).animateTo(
                    targetValue = -rotaryScrollAdapter.currentItemOffset(),
                    animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
            ) {
                val animDelta = value - prevPosition
                scrollBy(animDelta)
                prevPosition = value
            }
            snapTarget = rotaryScrollAdapter.currentItemIndex()
        }
    }

    override fun topEdgeReached(): Boolean = snapTarget <= 0

    override fun bottomEdgeReached(): Boolean =
            snapTarget >= rotaryScrollAdapter.totalItemsCount() - 1

    override suspend fun snapToTargetItem() {
        if (sequentialSnap) {
            anim = anim.copy(0f)
        } else {
            anim = AnimationState(0f)
        }
        rotaryScrollAdapter.scrollableState.scroll(MutatePriority.UserInput) {
            // If snapTargetUpdated is true - then the target was updated so we
            // need to do snap again
            while (snapTargetUpdated) {
                snapTargetUpdated = false
                var latestCenterItem: Int
                var continueFirstScroll = true
                debugLog { "snapTarget $snapTarget" }
                while (continueFirstScroll) {
                    latestCenterItem = rotaryScrollAdapter.currentItemIndex()
                    anim = anim.copy(0f)
                    expectedDistance = expectedDistanceTo(snapTarget, snapParameters.snapOffset)
                    debugLog {
                        "expectedDistance = $expectedDistance, " +
                                "scrollableState.centerItemScrollOffset " +
                                "${rotaryScrollAdapter.currentItemOffset()}"
                    }
                    continueFirstScroll = false
                    var prevPosition = 0f

                    anim.animateTo(
                            expectedDistance,
                            animationSpec = SpringSpec(
                                    stiffness = defaultStiffness,
                                    visibilityThreshold = 0.1f,
                            ),
                            sequentialAnimation = (anim.velocity != 0f),
                    ) {
                        val animDelta = value - prevPosition
                        debugLog {
                            "First animation, value:$value, velocity:$velocity, " +
                                    "animDelta:$animDelta"
                        }

                        // Exit animation if snap target was updated
                        if (snapTargetUpdated) cancelAnimation()

                        scrollBy(animDelta)
                        prevPosition = value

                        if (latestCenterItem != rotaryScrollAdapter.currentItemIndex()) {
                            continueFirstScroll = true
                            cancelAnimation()
                            return@animateTo
                        }

                        debugLog { "centerItemIndex =  ${rotaryScrollAdapter.currentItemIndex()}" }
                        if (rotaryScrollAdapter.currentItemIndex() == snapTarget) {
                            debugLog { "Target is visible. Cancelling first animation" }
                            debugLog {
                                "scrollableState.centerItemScrollOffset " +
                                        "${rotaryScrollAdapter.currentItemOffset()}"
                            }
                            expectedDistance = -rotaryScrollAdapter.currentItemOffset()
                            continueFirstScroll = false
                            cancelAnimation()
                            return@animateTo
                        }
                    }
                }
                // Exit animation if snap target was updated
                if (snapTargetUpdated) continue

                anim = anim.copy(0f)
                var prevPosition = 0f
                anim.animateTo(
                        expectedDistance,
                        animationSpec = SpringSpec(
                                stiffness = defaultStiffness,
                                visibilityThreshold = 0.1f,
                        ),
                        sequentialAnimation = (anim.velocity != 0f),
                ) {
                    // Exit animation if snap target was updated
                    if (snapTargetUpdated) cancelAnimation()

                    val animDelta = value - prevPosition
                    debugLog { "Final animation. velocity:$velocity, animDelta:$animDelta" }
                    scrollBy(animDelta)
                    prevPosition = value
                }
            }
        }
    }

    private fun expectedDistanceTo(index: Int, targetScrollOffset: Int): Float {
        val averageSize = rotaryScrollAdapter.averageItemSize()
        val indexesDiff = index - rotaryScrollAdapter.currentItemIndex()
        debugLog { "Average size $averageSize" }
        return (averageSize * indexesDiff) +
                targetScrollOffset - rotaryScrollAdapter.currentItemOffset()
    }
}

/**
 * A modifier which handles rotary events.
 * It accepts ScrollHandler as the input - a class where main logic about how
 * scroll should be handled is lying
 */
@ExperimentalHorologistApi
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.rotaryHandler(
        rotaryScrollHandler: RotaryScrollHandler,
        // TODO: batching causes additional delays. Return once it's clear that
        //  we will use it
        /* batchTimeframe: Long = 0L,*/
        reverseDirection: Boolean,
        rotaryHaptics: RotaryHapticHandler,
): Modifier = composed {
    val channel = rememberTimestampChannel()
    val eventsFlow = remember(channel) { channel.receiveAsFlow() }

    composed {
        LaunchedEffect(eventsFlow) {
            eventsFlow
                    // TODO: batching causes additional delays. Return once it's clear that
                    //  we will use it
                    // Do we really need to do this on this level?
//                .batchRequestsWithinTimeframe(batchTimeframe)
                    .collectLatest {
                        debugLog {
                            "Scroll event received: " +
                                    "delta:${it.delta}, timestamp:${it.timestamp}"
                        }
                        rotaryScrollHandler.handleScrollEvent(this, it, rotaryHaptics)
                    }
        }
        this
                .onRotaryScrollEvent {
                    // Okay to ignore the ChannelResult returned from trySend because it is conflated
                    // (see rememberTimestampChannel()).
                    @Suppress("UNUSED_VARIABLE")
                    val unused = channel.trySend(
                            TimestampedDelta(
                                    it.uptimeMillis,
                                    it.verticalScrollPixels * if (reverseDirection) -1f else 1f,
                            ),
                    )
                    true
                }
    }
}

/**
 * Batching requests for scrolling events. This function combines all events together
 * (except first) within specified timeframe. Should help with performance on high-res devices.
 */
@ExperimentalHorologistApi
@OptIn(ExperimentalCoroutinesApi::class)
public fun Flow<TimestampedDelta>.batchRequestsWithinTimeframe(
        timeframe: Long
): Flow<TimestampedDelta> {
    var delta = 0f
    var lastTimestamp = -timeframe
    return if (timeframe == 0L) {
        this
    } else {
        this.transformLatest {
            delta += it.delta
            debugLog { "Batching requests. delta:$delta" }
            if (lastTimestamp + timeframe <= it.timestamp) {
                lastTimestamp = it.timestamp
                debugLog { "No events before, delta= $delta" }
                emit(TimestampedDelta(it.timestamp, delta))
            } else {
                delay(timeframe)
                debugLog { "After delay, delta= $delta" }
                if (delta > 0f) {
                    emit(TimestampedDelta(it.timestamp, delta))
                }
            }
            delta = 0f
        }
    }
}

/**
 * A scroll handler for RSB(high-res) without snapping and with or without fling
 * A list is scrolled by the number of pixels received from the rotary device.
 *
 * This class is a little bit different from LowResScrollHandler class - it has a filtering
 * for events which are coming with wrong sign ( this happens to rsb devices,
 * especially at the end of the scroll)
 *
 * This scroll handler supports fling. It can be set with [RotaryFlingBehavior].
 */
internal class HighResRotaryScrollHandler(
        private val rotaryFlingBehaviorFactory: () -> RotaryFlingBehavior?,
        private val scrollBehaviorFactory: () -> RotaryScrollBehavior,
        private val hapticsThreshold: Long = 50,
) : RotaryScrollHandler {

    // This constant is specific for high-res devices. Because that input values
    // can sometimes come with different sign, we have to filter them in this threshold
    private val gestureThresholdTime = 200L
    private var scrollJob: Job = CompletableDeferred<Unit>()
    private var flingJob: Job = CompletableDeferred<Unit>()

    private var previousScrollEventTime = 0L
    private var rotaryScrollDistance = 0f

    private var rotaryFlingBehavior: RotaryFlingBehavior? = rotaryFlingBehaviorFactory()
    private var scrollBehavior: RotaryScrollBehavior = scrollBehaviorFactory()

    override suspend fun handleScrollEvent(
            coroutineScope: CoroutineScope,
            event: TimestampedDelta,
            rotaryHaptics: RotaryHapticHandler,
    ) {
        val time = event.timestamp
        val isOppositeScrollValue = isOppositeValueAfterScroll(event.delta)

        if (isNewScrollEvent(time)) {
            debugLog { "New scroll event" }
            resetTracking(time)
            rotaryScrollDistance = event.delta
        } else {
            // Due to the physics of Rotary side button, some events might come
            // with an opposite axis value - either at the start or at the end of the motion.
            // We don't want to use these values for fling calculations.
            if (!isOppositeScrollValue) {
                rotaryFlingBehavior?.observeEvent(event.timestamp, event.delta)
            } else {
                debugLog { "Opposite value after scroll :${event.delta}" }
            }
            rotaryScrollDistance += event.delta
        }

        scrollJob.cancel()

        rotaryHaptics.handleScrollHaptic(event.delta)
        debugLog { "Rotary scroll distance: $rotaryScrollDistance" }

        previousScrollEventTime = time
        scrollJob = coroutineScope.async {
            scrollBehavior.handleEvent(rotaryScrollDistance)
        }

        if (rotaryFlingBehavior != null) {
            flingJob.cancel()
            flingJob = coroutineScope.async {
                rotaryFlingBehavior?.trackFling(beforeFling = {
                    debugLog { "Calling before fling section" }
                    scrollJob.cancel()
                    scrollBehavior = scrollBehaviorFactory()
                })
            }
        }
    }

    private fun isOppositeValueAfterScroll(delta: Float): Boolean =
            sign(rotaryScrollDistance) * sign(delta) == -1f &&
                    (abs(delta) < abs(rotaryScrollDistance))

    private fun isNewScrollEvent(timestamp: Long): Boolean {
        val timeDelta = timestamp - previousScrollEventTime
        return previousScrollEventTime == 0L || timeDelta > gestureThresholdTime
    }

    private fun resetTracking(timestamp: Long) {
        scrollBehavior = scrollBehaviorFactory()
        rotaryFlingBehavior = rotaryFlingBehaviorFactory()
        rotaryFlingBehavior?.startFlingTracking(timestamp)
    }
}

/**
 * A scroll handler for Bezel(low-res) without snapping.
 * This scroll handler supports fling. It can be set with RotaryFlingBehavior.
 */
internal class LowResRotaryScrollHandler(
        private val rotaryFlingBehaviorFactory: () -> RotaryFlingBehavior?,
        private val scrollBehaviorFactory: () -> RotaryScrollBehavior,
) : RotaryScrollHandler {

    private val gestureThresholdTime = 200L
    private var previousScrollEventTime = 0L
    private var rotaryScrollDistance = 0f

    private var scrollJob: Job = CompletableDeferred<Unit>()
    private var flingJob: Job = CompletableDeferred<Unit>()

    private var rotaryFlingBehavior: RotaryFlingBehavior? = rotaryFlingBehaviorFactory()
    private var scrollBehavior: RotaryScrollBehavior = scrollBehaviorFactory()

    override suspend fun handleScrollEvent(
            coroutineScope: CoroutineScope,
            event: TimestampedDelta,
            rotaryHaptics: RotaryHapticHandler,
    ) {
        val time = event.timestamp

        if (isNewScrollEvent(time)) {
            resetTracking(time)
            rotaryScrollDistance = event.delta
        } else {
            rotaryFlingBehavior?.observeEvent(event.timestamp, event.delta)
            rotaryScrollDistance += event.delta
        }

        scrollJob.cancel()
        flingJob.cancel()

        rotaryHaptics.handleScrollHaptic(event.delta)
        debugLog { "Rotary scroll distance: $rotaryScrollDistance" }

        previousScrollEventTime = time
        scrollJob = coroutineScope.async {
            scrollBehavior.handleEvent(rotaryScrollDistance)
        }

        flingJob = coroutineScope.async {
            rotaryFlingBehavior?.trackFling(
                    beforeFling = {
                        debugLog { "Calling before fling section" }
                        scrollJob.cancel()
                        scrollBehavior = scrollBehaviorFactory()
                    },
            )
        }
    }

    private fun isNewScrollEvent(timestamp: Long): Boolean {
        val timeDelta = timestamp - previousScrollEventTime
        return previousScrollEventTime == 0L || timeDelta > gestureThresholdTime
    }

    private fun resetTracking(timestamp: Long) {
        scrollBehavior = scrollBehaviorFactory()
        debugLog { "Velocity tracker reset" }
        rotaryFlingBehavior = rotaryFlingBehaviorFactory()
        rotaryFlingBehavior?.startFlingTracking(timestamp)
    }
}

/**
 * A scroll handler for RSB(high-res) with snapping and without fling
 * Snapping happens after a threshold is reached ( set in [RotarySnapBehavior])
 *
 * This scroll handler doesn't support fling.
 */
internal class HighResSnapHandler(
        private val resistanceFactor: Float,
        private val thresholdBehaviorFactory: () -> ThresholdBehavior,
        private val snapBehaviourFactory: () -> RotarySnapBehavior,
        private val scrollBehaviourFactory: () -> RotaryScrollBehavior,
) : RotaryScrollHandler {
    private val gestureThresholdTime = 200L
    private val snapDelay = 100L
    private val maxSnapsPerEvent = 2

    private var scrollJob: Job = CompletableDeferred<Unit>()
    private var snapJob: Job = CompletableDeferred<Unit>()

    private var previousScrollEventTime = 0L
    private var snapAccumulator = 0f
    private var rotaryScrollDistance = 0f
    private var scrollInProgress = false

    private var snapBehaviour = snapBehaviourFactory()
    private var scrollBehaviour = scrollBehaviourFactory()
    private var thresholdBehavior = thresholdBehaviorFactory()

    private val scrollEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.5f, 1.0f)

    override suspend fun handleScrollEvent(
            coroutineScope: CoroutineScope,
            event: TimestampedDelta,
            rotaryHaptics: RotaryHapticHandler,
    ) {
        val time = event.timestamp

        if (isNewScrollEvent(time)) {
            debugLog { "New scroll event" }
            resetTracking()
            snapJob.cancel()
            snapBehaviour = snapBehaviourFactory()
            scrollBehaviour = scrollBehaviourFactory()
            thresholdBehavior = thresholdBehaviorFactory()
            thresholdBehavior.startThresholdTracking(time)
            snapAccumulator = 0f
            rotaryScrollDistance = 0f
        }

        if (!isOppositeValueAfterScroll(event.delta)) {
            thresholdBehavior.observeEvent(event.timestamp, event.delta)
        } else {
            debugLog { "Opposite value after scroll :${event.delta}" }
        }

        thresholdBehavior.applySmoothing()
        val snapThreshold = thresholdBehavior.snapThreshold()

        snapAccumulator += event.delta
        if (!snapJob.isActive) {
            val resistanceCoeff =
                    1 - scrollEasing.transform(rotaryScrollDistance.absoluteValue / snapThreshold)
            rotaryScrollDistance += event.delta * resistanceCoeff
        }

        debugLog { "Snap accumulator: $snapAccumulator" }
        debugLog { "Rotary scroll distance: $rotaryScrollDistance" }

        debugLog { "snapThreshold: $snapThreshold" }
        previousScrollEventTime = time

        if (abs(snapAccumulator) > snapThreshold) {
            scrollInProgress = false
            scrollBehaviour = scrollBehaviourFactory()
            scrollJob.cancel()

            val snapDistance = (snapAccumulator / snapThreshold).toInt()
                    .coerceIn(-maxSnapsPerEvent..maxSnapsPerEvent)
            snapAccumulator -= snapThreshold * snapDistance
            val sequentialSnap = snapJob.isActive

            debugLog {
                "Snap threshold reached: snapDistance:$snapDistance, " +
                        "sequentialSnap: $sequentialSnap, " +
                        "snap accumulator remaining: $snapAccumulator"
            }
            if ((!snapBehaviour.topEdgeReached() && snapDistance < 0) ||
                    (!snapBehaviour.bottomEdgeReached() && snapDistance > 0)
            ) {
                rotaryHaptics.handleSnapHaptic(event.delta)
            }

            snapBehaviour.prepareSnapForItems(snapDistance, sequentialSnap)
            if (!snapJob.isActive) {
                snapJob.cancel()
                snapJob = coroutineScope.async {
                    debugLog { "Snap started" }
                    try {
                        snapBehaviour.snapToTargetItem()
                    } finally {
                        debugLog { "Snap called finally" }
                    }
                }
            }
            rotaryScrollDistance = 0f
        } else {
            if (!snapJob.isActive) {
                scrollJob.cancel()
                debugLog { "Scrolling for $rotaryScrollDistance/$resistanceFactor px" }
                scrollJob = coroutineScope.async {
                    scrollBehaviour.handleEvent(rotaryScrollDistance / resistanceFactor)
                }
                delay(snapDelay)
                scrollInProgress = false
                scrollBehaviour = scrollBehaviourFactory()
                rotaryScrollDistance = 0f
                snapAccumulator = 0f
                snapBehaviour.prepareSnapForItems(0, false)

                snapJob.cancel()
                snapJob = coroutineScope.async {
                    snapBehaviour.snapToClosestItem()
                }
            }
        }
    }

    private fun isOppositeValueAfterScroll(delta: Float): Boolean =
            sign(rotaryScrollDistance) * sign(delta) == -1f &&
                    (abs(delta) < abs(rotaryScrollDistance))

    private fun isNewScrollEvent(timestamp: Long): Boolean {
        val timeDelta = timestamp - previousScrollEventTime
        return previousScrollEventTime == 0L || timeDelta > gestureThresholdTime
    }

    private fun resetTracking() {
        scrollInProgress = true
    }
}

/**
 * A scroll handler for RSB(high-res) with snapping and without fling
 * Snapping happens after a threshold is reached ( set in [RotarySnapBehavior])
 *
 * This scroll handler doesn't support fling.
 */
internal class LowResSnapHandler(
        private val snapBehaviourFactory: () -> RotarySnapBehavior,
) : RotaryScrollHandler {
    private val gestureThresholdTime = 200L

    private var snapJob: Job = CompletableDeferred<Unit>()

    private var previousScrollEventTime = 0L
    private var snapAccumulator = 0f
    private var scrollInProgress = false

    private var snapBehaviour = snapBehaviourFactory()

    override suspend fun handleScrollEvent(
            coroutineScope: CoroutineScope,
            event: TimestampedDelta,
            rotaryHaptics: RotaryHapticHandler,
    ) {
        val time = event.timestamp

        if (isNewScrollEvent(time)) {
            debugLog { "New scroll event" }
            resetTracking()
            snapJob.cancel()
            snapBehaviour = snapBehaviourFactory()
            snapAccumulator = 0f
        }

        snapAccumulator += event.delta

        debugLog { "Snap accumulator: $snapAccumulator" }

        previousScrollEventTime = time

        if (abs(snapAccumulator) > 1f) {
            scrollInProgress = false

            val snapDistance = sign(snapAccumulator).toInt()
            rotaryHaptics.handleSnapHaptic(event.delta)
            val sequentialSnap = snapJob.isActive
            debugLog {
                "Snap threshold reached: snapDistance:$snapDistance, " +
                        "sequentialSnap: $sequentialSnap, " +
                        "snap accumulator: $snapAccumulator"
            }

            snapBehaviour.prepareSnapForItems(snapDistance, sequentialSnap)
            if (!snapJob.isActive) {
                snapJob.cancel()
                snapJob = coroutineScope.async {
                    debugLog { "Snap started" }
                    try {
                        snapBehaviour.snapToTargetItem()
                    } finally {
                        debugLog { "Snap called finally" }
                    }
                }
            }
            snapAccumulator = 0f
        }
    }

    private fun isNewScrollEvent(timestamp: Long): Boolean {
        val timeDelta = timestamp - previousScrollEventTime
        return previousScrollEventTime == 0L || timeDelta > gestureThresholdTime
    }

    private fun resetTracking() {
        scrollInProgress = true
    }
}

internal class ThresholdBehavior(
        private val rotaryScrollAdapter: RotaryScrollAdapter,
        private val thresholdDivider: Float,
        private val minVelocity: Float = 300f,
        private val maxVelocity: Float = 3000f,
        private val smoothingConstant: Float = 0.4f,
) {
    private val thresholdDividerEasing: Easing = CubicBezierEasing(0.5f, 0.0f, 0.5f, 1.0f)

    private val rotaryVelocityTracker = RotaryVelocityTracker()

    private var smoothedVelocity = 0f
    fun startThresholdTracking(time: Long) {
        rotaryVelocityTracker.start(time)
        smoothedVelocity = 0f
    }

    fun observeEvent(timestamp: Long, delta: Float) {
        rotaryVelocityTracker.move(timestamp, delta)
    }

    fun applySmoothing() {
        if (rotaryVelocityTracker.velocity != 0.0f) {
            // smooth the velocity
            smoothedVelocity = exponentialSmoothing(
                    currentVelocity = rotaryVelocityTracker.velocity.absoluteValue,
                    prevVelocity = smoothedVelocity,
                    smoothingConstant = smoothingConstant,
            )
        }
        debugLog { "rotaryVelocityTracker velocity: ${rotaryVelocityTracker.velocity}" }
        debugLog { "SmoothedVelocity: $smoothedVelocity" }
    }

    fun snapThreshold(): Float {
        val thresholdDividerFraction =
                thresholdDividerEasing.transform(
                        inverseLerp(
                                minVelocity,
                                maxVelocity,
                                smoothedVelocity,
                        ),
                )
        return rotaryScrollAdapter.averageItemSize() / lerp(
                1f,
                thresholdDivider,
                thresholdDividerFraction,
        )
    }

    private fun exponentialSmoothing(
            currentVelocity: Float,
            prevVelocity: Float,
            smoothingConstant: Float,
    ): Float =
            smoothingConstant * currentVelocity + (1 - smoothingConstant) * prevVelocity
}

@Composable
private fun rememberTimestampChannel() = remember {
    Channel<TimestampedDelta>(capacity = Channel.CONFLATED)
}

private fun inverseLerp(start: Float, stop: Float, value: Float): Float {
    return ((value - start) / (stop - start)).coerceIn(0f, 1f)
}
