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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.annotation.IntDef
import android.os.Process
import android.provider.DeviceConfig
import android.util.Log
import android.view.animation.PathInterpolator
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.Assert
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Dead-simple scheduler for system status events. Obeys the following principles (all values TBD):
 *      - Avoiding log spam by only allowing 12 events per minute (1event/5s)
 *      - Waits 100ms to schedule any event for debouncing/prioritization
 *      - Simple prioritization: Privacy > Battery > connectivity (encoded in [StatusEvent])
 *      - Only schedules a single event, and throws away lowest priority events
 *
 * There are 4 basic stages of animation at play here:
 *      1. System chrome animation OUT
 *      2. Chip animation IN
 *      3. Chip animation OUT; potentially into a dot
 *      4. System chrome animation IN
 *
 * Thus we can keep all animations synchronized with two separate ValueAnimators, one for system
 * chrome and the other for the chip. These can animate from 0,1 and listeners can parameterize
 * their respective views based on the progress of the animator. Interpolation differences TBD
 */
@SysUISingleton
open class SystemStatusAnimationScheduler @Inject constructor(
    private val coordinator: SystemEventCoordinator,
    private val chipAnimationController: SystemEventChipAnimationController,
    private val statusBarWindowController: StatusBarWindowController,
    private val dumpManager: DumpManager,
    private val systemClock: SystemClock,
    @Main private val executor: DelayableExecutor
) : CallbackController<SystemStatusAnimationCallback>, Dumpable {

    companion object {
        private const val PROPERTY_ENABLE_IMMERSIVE_INDICATOR = "enable_immersive_indicator"
    }
    public fun isImmersiveIndicatorEnabled(): Boolean {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_ENABLE_IMMERSIVE_INDICATOR, true)
    }

    @SystemAnimationState var animationState: Int = IDLE
        private set

    /** True if the persistent privacy dot should be active */
    var hasPersistentDot = false
        protected set

    private var scheduledEvent: StatusEvent? = null
    private var cancelExecutionRunnable: Runnable? = null
    private val listeners = mutableSetOf<SystemStatusAnimationCallback>()

    fun getListeners(): MutableSet<SystemStatusAnimationCallback> {
        return listeners
    }

    init {
        coordinator.attachScheduler(this)
        dumpManager.registerDumpable(TAG, this)
    }

    open fun onStatusEvent(event: StatusEvent) {
        // Ignore any updates until the system is up and running
        if (isTooEarly() || !isImmersiveIndicatorEnabled()) {
            return
        }

        // Don't deal with threading for now (no need let's be honest)
        Assert.isMainThread()
        if ((event.priority > scheduledEvent?.priority ?: -1) &&
                animationState != ANIMATING_OUT &&
                (animationState != SHOWING_PERSISTENT_DOT && event.forceVisible)) {
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

    private fun clearDotIfVisible() {
        notifyHidePersistentDot()
    }

    fun setShouldShowPersistentPrivacyIndicator(should: Boolean) {
        if (hasPersistentDot == should || !isImmersiveIndicatorEnabled()) {
            return
        }

        hasPersistentDot = should

        if (!hasPersistentDot) {
            clearDotIfVisible()
        }
    }

    public fun isTooEarly(): Boolean {
        return systemClock.uptimeMillis() - Process.getStartUptimeMillis() < MIN_UPTIME
    }

    /**
     * Clear the scheduled event (if any) and schedule a new one
     */
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
        executor.executeDelayed({
            runChipAnimation()
        }, DEBOUNCE_DELAY)
    }

    /**
     * 1. Define a total budget for the chip animation (1500ms)
     * 2. Send out callbacks to listeners so that they can generate animations locally
     * 3. Update the scheduler state so that clients know where we are
     * 4. Maybe: provide scaffolding such as: dot location, margins, etc
     * 5. Maybe: define a maximum animation length and enforce it. Probably only doable if we
     * collect all of the animators and run them together.
     */
    private fun runChipAnimation() {
        statusBarWindowController.setForceStatusBarVisible(true)
        animationState = ANIMATING_IN

        val animSet = collectStartAnimations()
        if (animSet.totalDuration > 500) {
            throw IllegalStateException("System animation total length exceeds budget. " +
                    "Expected: 500, actual: ${animSet.totalDuration}")
        }
        animSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                animationState = RUNNING_CHIP_ANIM
            }
        })
        animSet.start()

        executor.executeDelayed({
            val animSet2 = collectFinishAnimations()
            animationState = ANIMATING_OUT
            animSet2.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    animationState = if (hasPersistentDot) {
                        SHOWING_PERSISTENT_DOT
                    } else {
                        IDLE
                    }

                    statusBarWindowController.setForceStatusBarVisible(false)
                }
            })
            animSet2.start()
            scheduledEvent = null
        }, DISPLAY_LENGTH)
    }

    private fun collectStartAnimations(): AnimatorSet {
        val animators = mutableListOf<Animator>()
        listeners.forEach { listener ->
            listener.onSystemEventAnimationBegin()?.let { anim ->
                animators.add(anim)
            }
        }
        animators.add(chipAnimationController.onSystemEventAnimationBegin())
        val animSet = AnimatorSet().also {
            it.playTogether(animators)
        }

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
        val animSet = AnimatorSet().also {
            it.playTogether(animators)
        }

        return animSet
    }

    private fun notifyTransitionToPersistentDot(): Animator? {
        val anims: List<Animator> = listeners.mapNotNull {
            it.onSystemStatusAnimationTransitionToPersistentDot(scheduledEvent?.contentDescription)
        }
        if (anims.isNotEmpty()) {
            val aSet = AnimatorSet()
            aSet.playTogether(anims)
            return aSet
        }

        return null
    }

    private fun notifyHidePersistentDot(): Animator? {
        val anims: List<Animator> = listeners.mapNotNull {
            it.onHidePersistentDot()
        }

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
            listeners.forEach {
                pw.println("  $it")
            }
        }
    }
}

/**
 * The general idea here is that this scheduler will run two value animators, and provide
 * animator-like callbacks for each kind of animation. The SystemChrome animation is expected to
 * create space for the chip animation to display. This means hiding the system elements in the
 * status bar and keyguard.
 *
 * The value animators themselves are simple animators from 0.0 to 1.0. Listeners can apply any
 * interpolation they choose but realistically these are most likely to be simple alpha transitions
 */
interface SystemStatusAnimationCallback {
    /** Implement this method to return an [Animator] or [AnimatorSet] that presents the chip */
    fun onSystemEventAnimationBegin(): Animator? { return null }
    /** Implement this method to return an [Animator] or [AnimatorSet] that hides the chip */
    fun onSystemEventAnimationFinish(hasPersistentDot: Boolean): Animator? { return null }

    // Best method name, change my mind
    @JvmDefault
    fun onSystemStatusAnimationTransitionToPersistentDot(contentDescription: String?): Animator? {
        return null
    }
    @JvmDefault fun onHidePersistentDot(): Animator? { return null }
}

/**
 * Animation state IntDef
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
        value = [
            IDLE,
            ANIMATION_QUEUED,
            ANIMATING_IN,
            RUNNING_CHIP_ANIM,
            ANIMATING_OUT,
            SHOWING_PERSISTENT_DOT
        ]
)
annotation class SystemAnimationState

/** No animation is in progress */
const val IDLE = 0
/** An animation is queued, and awaiting the debounce period */
const val ANIMATION_QUEUED = 1
/** System is animating out, and chip is animating in */
const val ANIMATING_IN = 2
/** Chip has animated in and is awaiting exit animation, and optionally playing its own animation */
const val RUNNING_CHIP_ANIM = 3
/** Chip is animating away and system is animating back */
const val ANIMATING_OUT = 4
/** Chip has animated away, and the persistent dot is showing */
const val SHOWING_PERSISTENT_DOT = 5

/** Commonly-needed interpolators can go here */
@JvmField val STATUS_BAR_X_MOVE_OUT = PathInterpolator(0.33f, 0f, 0f, 1f)
@JvmField val STATUS_BAR_X_MOVE_IN = PathInterpolator(0f, 0f, 0f, 1f)
/**
 * Status chip animation to dot have multiple stages of motion, the _1 and _2 interpolators should
 * be used in succession
 */
val STATUS_CHIP_WIDTH_TO_DOT_KEYFRAME_1 = PathInterpolator(0.44f, 0f, 0.25f, 1f)
val STATUS_CHIP_WIDTH_TO_DOT_KEYFRAME_2 = PathInterpolator(0.3f, 0f, 0.26f, 1f)
val STATUS_CHIP_HEIGHT_TO_DOT_KEYFRAME_1 = PathInterpolator(0.4f, 0f, 0.17f, 1f)
val STATUS_CHIP_HEIGHT_TO_DOT_KEYFRAME_2 = PathInterpolator(0.3f, 0f, 0f, 1f)
val STATUS_CHIP_MOVE_TO_DOT = PathInterpolator(0f, 0f, 0.05f, 1f)

private const val TAG = "SystemStatusAnimationScheduler"
private const val DEBOUNCE_DELAY = 100L

/**
 * The total time spent on the chip animation is 1500ms, broken up into 3 sections:
 *   - 500ms to animate the chip in (including animating system icons away)
 *   - 500ms holding the chip on screen
 *   - 500ms to animate the chip away (and system icons back)
 *
 *   So DISPLAY_LENGTH should be the sum of the first 2 phases, while the final 500ms accounts for
 *   the actual animation
 */
private const val DISPLAY_LENGTH = 1000L

private const val MIN_UPTIME: Long = 5 * 1000

private const val DEBUG = false
