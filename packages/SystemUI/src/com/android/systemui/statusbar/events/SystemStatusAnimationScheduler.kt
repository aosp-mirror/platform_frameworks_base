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
import android.animation.ValueAnimator
import android.annotation.IntDef
import android.content.Context
import android.os.Process
import android.provider.DeviceConfig
import android.util.Log
import android.view.View
import com.android.systemui.Dumpable

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.StatusBarWindowController
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.util.Assert
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.io.FileDescriptor
import java.io.PrintWriter

import javax.inject.Inject

/**
 * Dead-simple scheduler for system status events. Obeys the following principles (all values TBD):
 *      - Avoiding log spam by only allowing 12 events per minute (1event/5s)
 *      - Waits 100ms to schedule any event for debouncing/prioritization
 *      - Simple prioritization: Privacy > Battery > connectivity (encoded in StatusEvent)
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
class SystemStatusAnimationScheduler @Inject constructor(
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
    private fun isImmersiveIndicatorEnabled(): Boolean {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_ENABLE_IMMERSIVE_INDICATOR, true)
    }

    @SystemAnimationState var animationState: Int = IDLE
        private set

    /** True if the persistent privacy dot should be active */
    var hasPersistentDot = false
        private set

    private var scheduledEvent: StatusEvent? = null
    private var cancelExecutionRunnable: Runnable? = null
    private val listeners = mutableSetOf<SystemStatusAnimationCallback>()

    init {
        coordinator.attachScheduler(this)
        dumpManager.registerDumpable(TAG, this)
    }

    fun onStatusEvent(event: StatusEvent) {
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
                Log.d(TAG, "updating current event from: $event")
            }
            scheduledEvent?.updateFromEvent(event)
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

    private fun isTooEarly(): Boolean {
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
            return
        }

        // Schedule the animation to start after a debounce period
        cancelExecutionRunnable = executor.executeDelayed({
            cancelExecutionRunnable = null
            animationState = ANIMATING_IN
            statusBarWindowController.setForceStatusBarVisible(true)

            val entranceAnimator = ValueAnimator.ofFloat(1f, 0f)
            entranceAnimator.duration = ENTRANCE_ANIM_LENGTH
            entranceAnimator.addListener(systemAnimatorAdapter)
            entranceAnimator.addUpdateListener(systemUpdateListener)

            val chipAnimator = ValueAnimator.ofFloat(0f, 1f)
            chipAnimator.duration = CHIP_ANIM_LENGTH
            chipAnimator.addListener(
                    ChipAnimatorAdapter(RUNNING_CHIP_ANIM, scheduledEvent!!.viewCreator))
            chipAnimator.addUpdateListener(chipUpdateListener)

            val aSet2 = AnimatorSet()
            aSet2.playSequentially(entranceAnimator, chipAnimator)
            aSet2.start()

            executor.executeDelayed({
                animationState = ANIMATING_OUT

                val systemAnimator = ValueAnimator.ofFloat(0f, 1f)
                systemAnimator.duration = ENTRANCE_ANIM_LENGTH
                systemAnimator.addListener(systemAnimatorAdapter)
                systemAnimator.addUpdateListener(systemUpdateListener)

                val chipAnimator = ValueAnimator.ofFloat(1f, 0f)
                chipAnimator.duration = CHIP_ANIM_LENGTH
                val endState = if (hasPersistentDot) {
                    SHOWING_PERSISTENT_DOT
                } else {
                    IDLE
                }
                chipAnimator.addListener(
                    ChipAnimatorAdapter(endState, scheduledEvent!!.viewCreator))
                chipAnimator.addUpdateListener(chipUpdateListener)

                val aSet2 = AnimatorSet()

                aSet2.play(chipAnimator).before(systemAnimator)
                if (hasPersistentDot) {
                    val dotAnim = notifyTransitionToPersistentDot()
                    if (dotAnim != null) aSet2.playTogether(systemAnimator, dotAnim)
                }

                aSet2.start()

                statusBarWindowController.setForceStatusBarVisible(false)
                scheduledEvent = null
            }, DISPLAY_LENGTH)
        }, DELAY)
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

    private fun notifySystemStart() {
        listeners.forEach { it.onSystemChromeAnimationStart() }
    }

    private fun notifySystemFinish() {
        listeners.forEach { it.onSystemChromeAnimationEnd() }
    }

    private fun notifySystemAnimationUpdate(anim: ValueAnimator) {
        listeners.forEach { it.onSystemChromeAnimationUpdate(anim) }
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

    private val systemUpdateListener = ValueAnimator.AnimatorUpdateListener {
        anim -> notifySystemAnimationUpdate(anim)
    }

    private val systemAnimatorAdapter = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(p0: Animator?) {
            notifySystemFinish()
        }

        override fun onAnimationStart(p0: Animator?) {
            notifySystemStart()
        }
    }

    private val chipUpdateListener = ValueAnimator.AnimatorUpdateListener {
        anim -> chipAnimationController.onChipAnimationUpdate(anim, animationState)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
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

    inner class ChipAnimatorAdapter(
        @SystemAnimationState val endState: Int,
        val viewCreator: (context: Context) -> View
    ) : AnimatorListenerAdapter() {
        override fun onAnimationEnd(p0: Animator?) {
            chipAnimationController.onChipAnimationEnd(animationState)
            animationState = if (endState == SHOWING_PERSISTENT_DOT && !hasPersistentDot) {
                IDLE
            } else {
                endState
            }
        }

        override fun onAnimationStart(p0: Animator?) {
            chipAnimationController.onChipAnimationStart(viewCreator, animationState)
        }
    }
}

/**
 * The general idea here is that this scheduler will run two value animators, and provide
 * animator-like callbacks for each kind of animation. The SystemChrome animation is expected to
 * create space for the chip animation to display. This means hiding the system elements in the
 * status bar and keyguard.
 *
 * TODO: the chip animation really only has one client, we can probably remove it from this
 * interface
 *
 * The value animators themselves are simple animators from 0.0 to 1.0. Listeners can apply any
 * interpolation they choose but realistically these are most likely to be simple alpha transitions
 */
interface SystemStatusAnimationCallback {
    @JvmDefault fun onSystemChromeAnimationUpdate(animator: ValueAnimator) {}
    @JvmDefault fun onSystemChromeAnimationStart() {}
    @JvmDefault fun onSystemChromeAnimationEnd() {}

    // Best method name, change my mind
    @JvmDefault
    fun onSystemStatusAnimationTransitionToPersistentDot(contentDescription: String?): Animator? {
        return null
    }
    @JvmDefault fun onHidePersistentDot(): Animator? { return null }
}

interface SystemStatusChipAnimationCallback {
    fun onChipAnimationUpdate(animator: ValueAnimator, @SystemAnimationState state: Int) {}

    fun onChipAnimationStart(
        viewCreator: (context: Context) -> View,
        @SystemAnimationState state: Int
    ) {}

    fun onChipAnimationEnd(@SystemAnimationState state: Int) {}
}

/**
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
        value = [
            IDLE, ANIMATING_IN, RUNNING_CHIP_ANIM, ANIMATING_OUT
        ]
)
annotation class SystemAnimationState

/** No animation is in progress */
const val IDLE = 0
/** System is animating out, and chip is animating in */
const val ANIMATING_IN = 1
/** Chip has animated in and is awaiting exit animation, and optionally playing its own animation */
const val RUNNING_CHIP_ANIM = 2
/** Chip is animating away and system is animating back */
const val ANIMATING_OUT = 3
/** Chip has animated away, and the persistent dot is showing */
const val SHOWING_PERSISTENT_DOT = 4

private const val TAG = "SystemStatusAnimationScheduler"
private const val DELAY = 0L

/**
 * The total time spent animation should be 1500ms. The entrance animation is how much time
 * we give to the system to animate system elements out of the way. Total chip animation length
 * will be equivalent to 2*chip_anim_length + display_length
 */
private const val ENTRANCE_ANIM_LENGTH = 250L
private const val CHIP_ANIM_LENGTH = 250L
// 1s + entrance time + chip anim_length
private const val DISPLAY_LENGTH = 1500L

private const val MIN_UPTIME: Long = 5 * 1000

private const val DEBUG = false
