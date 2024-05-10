/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import android.os.Process
import android.provider.DeviceConfig
import androidx.core.animation.Animator
import androidx.core.animation.AnimatorListenerAdapter
import androidx.core.animation.AnimatorSet
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.Assert
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Scheduler for system status events. Obeys the following principles:
 * ```
 *      - Waits 100 ms to schedule any event for debouncing/prioritization
 *      - Simple prioritization: Privacy > Battery > Connectivity (encoded in [StatusEvent])
 *      - Only schedules a single event, and throws away lowest priority events
 * ```
 *
 * There are 4 basic stages of animation at play here:
 * ```
 *      1. System chrome animation OUT
 *      2. Chip animation IN
 *      3. Chip animation OUT; potentially into a dot
 *      4. System chrome animation IN
 * ```
 *
 * Thus we can keep all animations synchronized with two separate ValueAnimators, one for system
 * chrome and the other for the chip. These can animate from 0,1 and listeners can parameterize
 * their respective views based on the progress of the animator.
 */
@OptIn(FlowPreview::class)
open class SystemStatusAnimationSchedulerImpl
@Inject
constructor(
    private val coordinator: SystemEventCoordinator,
    private val chipAnimationController: SystemEventChipAnimationController,
    private val statusBarWindowController: StatusBarWindowController,
    dumpManager: DumpManager,
    private val systemClock: SystemClock,
    @Application private val coroutineScope: CoroutineScope,
    private val logger: SystemStatusAnimationSchedulerLogger?
) : SystemStatusAnimationScheduler {

    companion object {
        private const val PROPERTY_ENABLE_IMMERSIVE_INDICATOR = "enable_immersive_indicator"
    }

    /** Contains the StatusEvent that is going to be displayed next. */
    private var scheduledEvent = MutableStateFlow<StatusEvent?>(null)

    /**
     * The currently displayed status event. (This is null in all states except ANIMATING_IN and
     * CHIP_ANIMATION_RUNNING)
     */
    private var currentlyDisplayedEvent: StatusEvent? = null

    /** StateFlow holding the current [SystemAnimationState] at any time. */
    private var animationState = MutableStateFlow(IDLE)

    /** True if the persistent privacy dot should be active */
    var hasPersistentDot = false
        protected set

    /** Set of currently registered listeners */
    protected val listeners = mutableSetOf<SystemStatusAnimationCallback>()

    /** The job that is controlling the animators of the currently displayed status event. */
    private var currentlyRunningAnimationJob: Job? = null

    /** The job that is controlling the animators when an event is cancelled. */
    private var eventCancellationJob: Job? = null

    init {
        coordinator.attachScheduler(this)
        dumpManager.registerCriticalDumpable(TAG, this)

        coroutineScope.launch {
            // Wait for animationState to become ANIMATION_QUEUED and scheduledEvent to be non null.
            // Once this combination is stable for at least DEBOUNCE_DELAY, then start a chip enter
            // animation
            animationState
                .combine(scheduledEvent) { animationState, scheduledEvent ->
                    Pair(animationState, scheduledEvent)
                }
                .debounce(DEBOUNCE_DELAY)
                .collect { (animationState, event) ->
                    if (animationState == ANIMATION_QUEUED && event != null) {
                        startAnimationLifecycle(event)
                        scheduledEvent.value = null
                    }
                }
        }

        coroutineScope.launch {
            animationState.collect { logger?.logAnimationStateUpdate(it) }
        }
    }

    @SystemAnimationState override fun getAnimationState(): Int = animationState.value

    override fun onStatusEvent(event: StatusEvent) {
        Assert.isMainThread()

        // Ignore any updates until the system is up and running. However, for important events that
        // request to be force visible (like privacy), ignore whether it's too early.
        if ((isTooEarly() && !event.forceVisible) || !isImmersiveIndicatorEnabled()) {
            return
        }

        if (
            (event.priority > (scheduledEvent.value?.priority ?: -1)) &&
                (event.priority > (currentlyDisplayedEvent?.priority ?: -1)) &&
                !hasPersistentDot
        ) {
            // a event can only be scheduled if no other event is in progress or it has a higher
            // priority. If a persistent dot is currently displayed, don't schedule the event.
            logger?.logScheduleEvent(event)
            scheduleEvent(event)
        } else if (currentlyDisplayedEvent?.shouldUpdateFromEvent(event) == true) {
            logger?.logUpdateEvent(event, animationState.value)
            currentlyDisplayedEvent?.updateFromEvent(event)
            if (event.forceVisible) hasPersistentDot = true
        } else if (scheduledEvent.value?.shouldUpdateFromEvent(event) == true) {
            logger?.logUpdateEvent(event, animationState.value)
            scheduledEvent.value?.updateFromEvent(event)
        } else {
            logger?.logIgnoreEvent(event)
        }
    }

    override fun removePersistentDot() {
        Assert.isMainThread()

        // If there is an event scheduled currently, set its forceVisible flag to false, such that
        // it will never transform into a persistent dot
        scheduledEvent.value?.forceVisible = false

        // Nothing else to do if hasPersistentDot is already false
        if (!hasPersistentDot) return
        // Set hasPersistentDot to false. If the animationState is anything before ANIMATING_OUT,
        // the disappear animation will not animate into a dot but remove the chip entirely
        hasPersistentDot = false

        if (animationState.value == SHOWING_PERSISTENT_DOT) {
            // if we are currently showing a persistent dot, hide it and update the animationState
            notifyHidePersistentDot()
            if (scheduledEvent.value != null) {
                animationState.value = ANIMATION_QUEUED
            } else {
                animationState.value = IDLE
            }
        } else if (animationState.value == ANIMATING_OUT) {
            // if we are currently animating out, hide the dot. The animationState will be updated
            // once the animation has ended in the onAnimationEnd callback
            notifyHidePersistentDot()
        }
    }

    protected fun isTooEarly(): Boolean {
        return systemClock.uptimeMillis() - Process.getStartUptimeMillis() < MIN_UPTIME
    }

    protected fun isImmersiveIndicatorEnabled(): Boolean {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            PROPERTY_ENABLE_IMMERSIVE_INDICATOR,
            true
        )
    }

    /** Clear the scheduled event (if any) and schedule a new one */
    private fun scheduleEvent(event: StatusEvent) {
        scheduledEvent.value = event
        if (currentlyDisplayedEvent != null && eventCancellationJob?.isActive != true) {
            // cancel the currently displayed event. As soon as the event is animated out, the
            // scheduled event will be displayed.
            cancelCurrentlyDisplayedEvent()
            return
        }
        if (animationState.value == IDLE) {
            // If we are in IDLE state, set it to ANIMATION_QUEUED now
            animationState.value = ANIMATION_QUEUED
        }
    }

    /**
     * Cancels the currently displayed event by animating it out. This function should only be
     * called if the animationState is ANIMATING_IN or RUNNING_CHIP_ANIM, or in other words whenever
     * currentlyRunningEvent is not null
     */
    private fun cancelCurrentlyDisplayedEvent() {
        eventCancellationJob =
            coroutineScope.launch {
                withTimeout(APPEAR_ANIMATION_DURATION) {
                    // wait for animationState to become RUNNING_CHIP_ANIM, then cancel the running
                    // animation job and run the disappear animation immediately
                    animationState.first { it == RUNNING_CHIP_ANIM }
                    currentlyRunningAnimationJob?.cancel()
                    runChipDisappearAnimation()
                }
            }
    }

    /**
     * Takes the currently scheduled Event and (using the coroutineScope) animates it in and out
     * again after displaying it for DISPLAY_LENGTH ms. This function should only be called if there
     * is an event scheduled (and currentlyDisplayedEvent is null)
     */
    private fun startAnimationLifecycle(event: StatusEvent) {
        Assert.isMainThread()
        hasPersistentDot = event.forceVisible

        if (!event.showAnimation && event.forceVisible) {
            // If animations are turned off, we'll transition directly to the dot
            animationState.value = SHOWING_PERSISTENT_DOT
            notifyTransitionToPersistentDot(event)
            return
        }

        currentlyDisplayedEvent = event

        chipAnimationController.prepareChipAnimation(event.viewCreator)
        currentlyRunningAnimationJob =
            coroutineScope.launch {
                runChipAppearAnimation()
                announceForAccessibilityIfNeeded(event)
                delay(APPEAR_ANIMATION_DURATION + DISPLAY_LENGTH)
                runChipDisappearAnimation()
            }
    }

    private fun announceForAccessibilityIfNeeded(event: StatusEvent) {
        val description = event.contentDescription ?: return
        if (!event.shouldAnnounceAccessibilityEvent)  return
        chipAnimationController.announceForAccessibility(description)
    }

    /**
     * 1. Define a total budget for the chip animation (1500ms)
     * 2. Send out callbacks to listeners so that they can generate animations locally
     * 3. Update the scheduler state so that clients know where we are
     * 4. Maybe: provide scaffolding such as: dot location, margins, etc
     * 5. Maybe: define a maximum animation length and enforce it. Probably only doable if we
     *    collect all of the animators and run them together.
     */
    private fun runChipAppearAnimation() {
        Assert.isMainThread()
        if (hasPersistentDot) {
            statusBarWindowController.setForceStatusBarVisible(true)
        }
        animationState.value = ANIMATING_IN

        val animSet = collectStartAnimations()
        if (animSet.totalDuration > 500) {
            throw IllegalStateException(
                "System animation total length exceeds budget. " +
                    "Expected: 500, actual: ${animSet.totalDuration}"
            )
        }
        animSet.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animationState.value = RUNNING_CHIP_ANIM
                }
            }
        )
        animSet.start()
    }

    private fun runChipDisappearAnimation() {
        Assert.isMainThread()
        val animSet2 = collectFinishAnimations()
        animationState.value = ANIMATING_OUT
        animSet2.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animationState.value =
                        when {
                            hasPersistentDot -> SHOWING_PERSISTENT_DOT
                            scheduledEvent.value != null -> ANIMATION_QUEUED
                            else -> IDLE
                        }
                    statusBarWindowController.setForceStatusBarVisible(false)
                }
            }
        )
        animSet2.start()

        // currentlyDisplayedEvent is set to null before the animation has ended such that new
        // events can be scheduled during the disappear animation. We don't want to miss e.g. a new
        // privacy event being scheduled during the disappear animation, otherwise we could end up
        // with e.g. an active microphone but no privacy dot being displayed.
        currentlyDisplayedEvent = null
    }

    private fun collectStartAnimations(): AnimatorSet {
        val animators = mutableListOf<Animator>()
        listeners.forEach { listener ->
            listener.onSystemEventAnimationBegin()?.let { anim -> animators.add(anim) }
        }
        animators.add(chipAnimationController.onSystemEventAnimationBegin())

        return AnimatorSet().also { it.playTogether(animators) }
    }

    private fun collectFinishAnimations(): AnimatorSet {
        val animators = mutableListOf<Animator>()
        listeners.forEach { listener ->
            listener.onSystemEventAnimationFinish(hasPersistentDot)?.let { anim ->
                animators.add(anim)
            }
        }
        animators.add(chipAnimationController.onSystemEventAnimationFinish(hasPersistentDot))
        if (hasPersistentDot) {
            val dotAnim = notifyTransitionToPersistentDot(currentlyDisplayedEvent)
            if (dotAnim != null) {
                animators.add(dotAnim)
            }
        }

        return AnimatorSet().also { it.playTogether(animators) }
    }

    private fun notifyTransitionToPersistentDot(event: StatusEvent?): Animator? {
        logger?.logTransitionToPersistentDotCallbackInvoked()
        val anims: List<Animator> =
            listeners.mapNotNull {
                it.onSystemStatusAnimationTransitionToPersistentDot(
                    event?.contentDescription
                )
            }
        if (anims.isNotEmpty()) {
            val aSet = AnimatorSet()
            aSet.playTogether(anims)
            return aSet
        }

        return null
    }

    private fun notifyHidePersistentDot(): Animator? {
        Assert.isMainThread()
        logger?.logHidePersistentDotCallbackInvoked()
        val anims: List<Animator> = listeners.mapNotNull { it.onHidePersistentDot() }

        if (anims.isNotEmpty()) {
            val aSet = AnimatorSet()
            aSet.playTogether(anims)
            return aSet
        }

        return null
    }

    override fun addCallback(listener: SystemStatusAnimationCallback) {
        Assert.isMainThread()

        if (listeners.isEmpty()) {
            coordinator.startObserving()
        }
        listeners.add(listener)
    }

    override fun removeCallback(listener: SystemStatusAnimationCallback) {
        Assert.isMainThread()

        listeners.remove(listener)
        if (listeners.isEmpty()) {
            coordinator.stopObserving()
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Scheduled event: ${scheduledEvent.value}")
        pw.println("Currently displayed event: $currentlyDisplayedEvent")
        pw.println("Has persistent privacy dot: $hasPersistentDot")
        pw.println("Animation state: ${animationState.value}")
        pw.println("Listeners:")
        if (listeners.isEmpty()) {
            pw.println("(none)")
        } else {
            listeners.forEach { pw.println("  $it") }
        }
    }
}

private const val TAG = "SystemStatusAnimationSchedulerImpl"
