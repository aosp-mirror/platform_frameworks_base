/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.util.Log
import androidx.core.animation.Animator
import androidx.core.animation.AnimatorListenerAdapter
import androidx.core.animation.AnimatorSet
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.Assert
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Dead-simple scheduler for system status events. Obeys the following principles (all values TBD):
 * ```
 *      - Avoiding log spam by only allowing 12 events per minute (1event/5s)
 *      - Waits 100ms to schedule any event for debouncing/prioritization
 *      - Simple prioritization: Privacy > Battery > connectivity (encoded in [StatusEvent])
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
 * their respective views based on the progress of the animator. Interpolation differences TBD
 */
open class SystemStatusAnimationSchedulerLegacyImpl
@Inject
constructor(
    private val coordinator: SystemEventCoordinator,
    private val chipAnimationController: SystemEventChipAnimationController,
    private val statusBarWindowController: StatusBarWindowController,
    private val dumpManager: DumpManager,
    private val systemClock: SystemClock,
    @Main private val executor: DelayableExecutor
) : SystemStatusAnimationScheduler {

    companion object {
        private const val PROPERTY_ENABLE_IMMERSIVE_INDICATOR = "enable_immersive_indicator"
    }

    fun isImmersiveIndicatorEnabled(): Boolean {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            PROPERTY_ENABLE_IMMERSIVE_INDICATOR,
            true
        )
    }

    @SystemAnimationState private var animationState: Int = IDLE

    /** True if the persistent privacy dot should be active */
    var hasPersistentDot = false
        protected set

    private var scheduledEvent: StatusEvent? = null

    val listeners = mutableSetOf<SystemStatusAnimationCallback>()

    init {
        coordinator.attachScheduler(this)
        dumpManager.registerDumpable(TAG, this)
    }

    @SystemAnimationState override fun getAnimationState() = animationState

    override fun onStatusEvent(event: StatusEvent) {
        // Ignore any updates until the system is up and running. However, for important events that
        // request to be force visible (like privacy), ignore whether it's too early.
        if ((isTooEarly() && !event.forceVisible) || !isImmersiveIndicatorEnabled()) {
            return
        }

        // Don't deal with threading for now (no need let's be honest)
        Assert.isMainThread()
        if (
            (event.priority > (scheduledEvent?.priority ?: -1)) &&
                animationState != ANIMATING_OUT &&
                animationState != SHOWING_PERSISTENT_DOT
        ) {
            // events can only be scheduled if a higher priority or no other event is in progress
            if (DEBUG) {
                Log.d(TAG, "scheduling event $event")
            }

            scheduleEvent(event)
        } else if (scheduledEvent?.shouldUpdateFromEvent(event) == true) {
            if (DEBUG) {
                Log.d(TAG, "updating current event from: $event. animationState=$animationState")
            }
            scheduledEvent?.updateFromEvent(event)
            if (event.forceVisible) {
                hasPersistentDot = true
                // If we missed the chance to show the persistent dot, do it now
                if (animationState == IDLE) {
                    notifyTransitionToPersistentDot()
                }
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "ignoring event $event")
            }
        }
    }

    override fun removePersistentDot() {
        if (!hasPersistentDot || !isImmersiveIndicatorEnabled()) {
            return
        }

        hasPersistentDot = false
        notifyHidePersistentDot()
        return
    }

    fun isTooEarly(): Boolean {
        return systemClock.uptimeMillis() - Process.getStartUptimeMillis() < MIN_UPTIME
    }

    /** Clear the scheduled event (if any) and schedule a new one */
    private fun scheduleEvent(event: StatusEvent) {
        scheduledEvent = event

        if (event.forceVisible) {
            hasPersistentDot = true
        }

        // If animations are turned off, we'll transition directly to the dot
        if (!event.showAnimation && event.forceVisible) {
            notifyTransitionToPersistentDot()
            scheduledEvent = null
            return
        }

        chipAnimationController.prepareChipAnimation(scheduledEvent!!.viewCreator)
        animationState = ANIMATION_QUEUED
        executor.executeDelayed({ runChipAnimation() }, DEBOUNCE_DELAY)
    }

    /**
     * 1. Define a total budget for the chip animation (1500ms)
     * 2. Send out callbacks to listeners so that they can generate animations locally
     * 3. Update the scheduler state so that clients know where we are
     * 4. Maybe: provide scaffolding such as: dot location, margins, etc
     * 5. Maybe: define a maximum animation length and enforce it. Probably only doable if we
     *    collect all of the animators and run them together.
     */
    private fun runChipAnimation() {
        statusBarWindowController.setForceStatusBarVisible(true)
        animationState = ANIMATING_IN

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
                    animationState = RUNNING_CHIP_ANIM
                }
            }
        )
        animSet.start()

        executor.executeDelayed(
            {
                val animSet2 = collectFinishAnimations()
                animationState = ANIMATING_OUT
                animSet2.addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            animationState =
                                if (hasPersistentDot) {
                                    SHOWING_PERSISTENT_DOT
                                } else {
                                    IDLE
                                }

                            statusBarWindowController.setForceStatusBarVisible(false)
                        }
                    }
                )
                animSet2.start()
                scheduledEvent = null
            },
            DISPLAY_LENGTH
        )
    }

    private fun collectStartAnimations(): AnimatorSet {
        val animators = mutableListOf<Animator>()
        listeners.forEach { listener ->
            listener.onSystemEventAnimationBegin()?.let { anim -> animators.add(anim) }
        }
        animators.add(chipAnimationController.onSystemEventAnimationBegin())
        val animSet = AnimatorSet().also { it.playTogether(animators) }

        return animSet
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
            val dotAnim = notifyTransitionToPersistentDot()
            if (dotAnim != null) {
                animators.add(dotAnim)
            }
        }
        val animSet = AnimatorSet().also { it.playTogether(animators) }

        return animSet
    }

    private fun notifyTransitionToPersistentDot(): Animator? {
        val anims: List<Animator> =
            listeners.mapNotNull {
                it.onSystemStatusAnimationTransitionToPersistentDot(
                    scheduledEvent?.contentDescription
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
        val anims: List<Animator> = listeners.mapNotNull { it.onHidePersistentDot() }

        if (animationState == SHOWING_PERSISTENT_DOT) {
            animationState = IDLE
        }

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
        pw.println("Scheduled event: $scheduledEvent")
        pw.println("Has persistent privacy dot: $hasPersistentDot")
        pw.println("Animation state: $animationState")
        pw.println("Listeners:")
        if (listeners.isEmpty()) {
            pw.println("(none)")
        } else {
            listeners.forEach { pw.println("  $it") }
        }
    }
}

private const val DEBUG = false
private const val TAG = "SystemStatusAnimationSchedulerLegacyImpl"
