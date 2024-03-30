/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification

import android.util.FloatProperty
import android.view.animation.Interpolator
import androidx.annotation.VisibleForTesting
import androidx.core.animation.ObjectAnimator
import com.android.app.animation.Interpolators
import com.android.app.animation.InterpolatorsAndroidX
import com.android.systemui.Dumpable
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.KeyguardBypassController.OnBypassStateChangedListener
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.doOnEnd
import com.android.systemui.util.doOnStart
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class NotificationWakeUpCoordinator
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    dumpManager: DumpManager,
    private val mHeadsUpManager: HeadsUpManager,
    private val statusBarStateController: StatusBarStateController,
    private val bypassController: KeyguardBypassController,
    private val dozeParameters: DozeParameters,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val logger: NotificationWakeUpCoordinatorLogger,
    private val notifsKeyguardInteractor: NotificationsKeyguardInteractor,
    private val communalInteractor: CommunalInteractor,
) :
    OnHeadsUpChangedListener,
    StatusBarStateController.StateListener,
    ShadeExpansionListener,
    Dumpable {
    private lateinit var mStackScrollerController: NotificationStackScrollLayoutController
    private var mVisibilityInterpolator = Interpolators.FAST_OUT_SLOW_IN_REVERSE

    private var inputLinearDozeAmount: Float = 0.0f
    private var inputEasedDozeAmount: Float = 0.0f
    private var delayedDozeAmountOverride: Float = 0.0f
    private var delayedDozeAmountAnimator: ObjectAnimator? = null
    /** Valid values: {1f, 0f, null} null => use input */
    private var hardDozeAmountOverride: Float? = null
    private var hardDozeAmountOverrideSource: String = "n/a"
    private var outputLinearDozeAmount: Float = 0.0f
    private var outputEasedDozeAmount: Float = 0.0f
    @VisibleForTesting val dozeAmountInterpolator: Interpolator = Interpolators.FAST_OUT_SLOW_IN

    private var mNotificationVisibleAmount = 0.0f
    private var mNotificationsVisible = false
    private var mNotificationsVisibleForExpansion = false
    private var mVisibilityAnimator: ObjectAnimator? = null
    private var mVisibilityAmount = 0.0f
    private var mLinearVisibilityAmount = 0.0f
    private val mEntrySetToClearWhenFinished = mutableSetOf<NotificationEntry>()
    private var pulseExpanding: Boolean = false
    private val wakeUpListeners = arrayListOf<WakeUpListener>()
    private var state: Int = StatusBarState.KEYGUARD

    var fullyAwake: Boolean = false

    var wakingUp = false
        private set(value) {
            field = value
            willWakeUp = false
            if (value) {
                if (
                    mNotificationsVisible &&
                        !mNotificationsVisibleForExpansion &&
                        !bypassController.bypassEnabled
                ) {
                    // We're waking up while pulsing, let's make sure the animation looks nice
                    mStackScrollerController.wakeUpFromPulse()
                }
                if (bypassController.bypassEnabled && !mNotificationsVisible) {
                    // Let's make sure our huns become visible once we are waking up in case
                    // they were blocked by the proximity sensor
                    updateNotificationVisibility(
                        animate = shouldAnimateVisibility(),
                        increaseSpeed = false
                    )
                }
            }
        }

    var willWakeUp = false
        set(value) {
            if (!value || outputLinearDozeAmount != 0.0f) {
                field = value
            }
        }

    private var collapsedEnoughToHide: Boolean = false

    var pulsing: Boolean = false
        set(value) {
            field = value
            if (value) {
                // Only when setting pulsing to true we want an immediate update, since we get
                // this already when the doze service finishes which is usually before we get
                // the waking up callback
                updateNotificationVisibility(
                    animate = shouldAnimateVisibility(),
                    increaseSpeed = false
                )
            }
        }

    var notificationsFullyHidden: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                for (listener in wakeUpListeners) {
                    listener.onFullyHiddenChanged(value)
                }
                notifsKeyguardInteractor.setNotificationsFullyHidden(value)
            }
        }

    /** True if we can show pulsing heads up notifications */
    var canShowPulsingHuns: Boolean = false
        private set
        get() {
            var canShow = pulsing
            if (bypassController.bypassEnabled) {
                // We also allow pulsing on the lock screen!
                canShow =
                    canShow ||
                        (wakingUp || willWakeUp || fullyAwake) &&
                            statusBarStateController.state == StatusBarState.KEYGUARD
                // We want to hide the notifications when collapsed too much
                if (collapsedEnoughToHide) {
                    canShow = false
                }
            }
            return canShow
        }

    private val bypassStateChangedListener =
        object : OnBypassStateChangedListener {
            override fun onBypassStateChanged(isEnabled: Boolean) {
                // When the bypass state changes, we have to check whether we should re-show the
                // notifications by clearing the doze amount override which hides them.
                maybeClearHardDozeAmountOverrideHidingNotifs()
            }
        }

    init {
        dumpManager.registerDumpable(this)
        mHeadsUpManager.addListener(this)
        statusBarStateController.addCallback(this)
        bypassController.registerOnBypassStateChangedListener(bypassStateChangedListener)
        addListener(
            object : WakeUpListener {
                override fun onFullyHiddenChanged(isFullyHidden: Boolean) {
                    if (isFullyHidden && mNotificationsVisibleForExpansion) {
                        // When the notification becomes fully invisible, let's make sure our
                        // expansion
                        // flag also changes. This can happen if the bouncer shows when dragging
                        // down
                        // and then the screen turning off, where we don't reset this state.
                        setNotificationsVisibleForExpansion(
                            visible = false,
                            animate = false,
                            increaseSpeed = false
                        )
                    }
                }
            }
        )
        applicationScope.launch {
            communalInteractor.isIdleOnCommunal.collect {
                if (!overrideDozeAmountIfCommunalShowing()) {
                    maybeClearHardDozeAmountOverrideHidingNotifs()
                }
            }
        }
    }

    fun setStackScroller(stackScrollerController: NotificationStackScrollLayoutController) {
        mStackScrollerController = stackScrollerController
        pulseExpanding = stackScrollerController.isPulseExpanding
        stackScrollerController.setOnPulseHeightChangedListener {
            val nowExpanding = isPulseExpanding()
            val changed = nowExpanding != pulseExpanding
            pulseExpanding = nowExpanding
            if (!NotificationIconContainerRefactor.isEnabled) {
                for (listener in wakeUpListeners) {
                    listener.onPulseExpansionAmountChanged(changed)
                }
            }
            if (changed) {
                for (listener in wakeUpListeners) {
                    listener.onPulseExpandingChanged(pulseExpanding)
                }
                notifsKeyguardInteractor.setPulseExpanding(pulseExpanding)
            }
        }
    }

    fun isPulseExpanding(): Boolean = mStackScrollerController.isPulseExpanding

    /**
     * @param visible should notifications be visible
     * @param animate should this change be animated
     * @param increaseSpeed should the speed be increased of the animation
     */
    fun setNotificationsVisibleForExpansion(
        visible: Boolean,
        animate: Boolean,
        increaseSpeed: Boolean
    ) {
        mNotificationsVisibleForExpansion = visible
        updateNotificationVisibility(animate, increaseSpeed)
        if (!visible && mNotificationsVisible) {
            // If we stopped expanding and we're still visible because we had a pulse that hasn't
            // times out, let's release them all to make sure were not stuck in a state where
            // notifications are visible
            mHeadsUpManager.releaseAllImmediately()
        }
    }

    fun addListener(listener: WakeUpListener) {
        wakeUpListeners.add(listener)
    }

    fun removeListener(listener: WakeUpListener) {
        wakeUpListeners.remove(listener)
    }

    private fun updateNotificationVisibility(animate: Boolean, increaseSpeed: Boolean) {
        // TODO: handle Lockscreen wakeup for bypass when we're not pulsing anymore
        var visible = mNotificationsVisibleForExpansion || mHeadsUpManager.hasNotifications()
        visible = visible && canShowPulsingHuns

        if (
            !visible &&
                mNotificationsVisible &&
                (wakingUp || willWakeUp) &&
                outputLinearDozeAmount != 0.0f
        ) {
            // let's not make notifications invisible while waking up, otherwise the animation
            // is strange
            return
        }
        setNotificationsVisible(visible, animate, increaseSpeed)
    }

    private fun setNotificationsVisible(
        visible: Boolean,
        animate: Boolean,
        increaseSpeed: Boolean
    ) {
        if (mNotificationsVisible == visible) {
            return
        }
        mNotificationsVisible = visible
        mVisibilityAnimator?.cancel()
        if (animate) {
            notifyAnimationStart(visible)
            startVisibilityAnimation(increaseSpeed)
        } else {
            setVisibilityAmount(if (visible) 1.0f else 0.0f)
        }
    }

    override fun onDozeAmountChanged(linear: Float, eased: Float) {
        logger.logOnDozeAmountChanged(linear = linear, eased = eased)
        inputLinearDozeAmount = linear
        inputEasedDozeAmount = eased
        if (overrideDozeAmountIfAnimatingScreenOff()) {
            return
        }

        if (overrideDozeAmountIfBypass()) {
            return
        }

        if (overrideDozeAmountIfCommunalShowing()) {
            return
        }

        if (clearHardDozeAmountOverride()) {
            return
        }

        updateDozeAmount()
    }

    private fun setHardDozeAmountOverride(dozing: Boolean, source: String) {
        logger.logSetDozeAmountOverride(dozing = dozing, source = source)
        val previousOverride = hardDozeAmountOverride
        hardDozeAmountOverride = if (dozing) 1f else 0f
        hardDozeAmountOverrideSource = source
        if (previousOverride != hardDozeAmountOverride) {
            updateDozeAmount()
        }
    }

    private fun clearHardDozeAmountOverride(): Boolean {
        if (hardDozeAmountOverride == null) return false
        hardDozeAmountOverride = null
        hardDozeAmountOverrideSource = "Cleared: $hardDozeAmountOverrideSource"
        updateDozeAmount()
        return true
    }

    private fun updateDozeAmount() {
        // Calculate new doze amount (linear)
        val newOutputLinearDozeAmount =
            hardDozeAmountOverride ?: max(inputLinearDozeAmount, delayedDozeAmountOverride)
        val changed = outputLinearDozeAmount != newOutputLinearDozeAmount

        // notify when the animation is starting
        if (
            newOutputLinearDozeAmount != 1.0f &&
                newOutputLinearDozeAmount != 0.0f &&
                (outputLinearDozeAmount == 0.0f || outputLinearDozeAmount == 1.0f)
        ) {
            // Let's notify the scroller that an animation started
            notifyAnimationStart(outputLinearDozeAmount == 1.0f)
        }

        // Update output doze amount
        outputLinearDozeAmount = newOutputLinearDozeAmount
        outputEasedDozeAmount = dozeAmountInterpolator.getInterpolation(outputLinearDozeAmount)
        logger.logUpdateDozeAmount(
            inputLinear = inputLinearDozeAmount,
            delayLinear = delayedDozeAmountOverride,
            hardOverride = hardDozeAmountOverride,
            outputLinear = outputLinearDozeAmount,
            state = statusBarStateController.state,
            changed = changed
        )
        mStackScrollerController.setDozeAmount(outputEasedDozeAmount)
        updateHideAmount()
        if (changed && outputLinearDozeAmount == 0.0f) {
            setNotificationsVisible(visible = false, animate = false, increaseSpeed = false)
            setNotificationsVisibleForExpansion(
                visible = false,
                animate = false,
                increaseSpeed = false
            )
        }
    }

    /**
     * Notifies the wakeup coordinator that we're waking up.
     *
     * [requestDelayedAnimation] is used to request that we delay the start of the wakeup animation
     * in order to wait for a potential fingerprint authentication to arrive, since unlocking during
     * the wakeup animation looks chaotic.
     *
     * If called with [wakingUp] and [requestDelayedAnimation] both `true`, the [WakeUpListener]s
     * are guaranteed to receive at least one [WakeUpListener.onDelayedDozeAmountAnimationRunning]
     * call with `false` at some point in the near future. A call with `true` before that will
     * happen if the animation is not already running.
     */
    fun setWakingUp(
        wakingUp: Boolean,
        requestDelayedAnimation: Boolean,
    ) {
        logger.logSetWakingUp(wakingUp, requestDelayedAnimation)
        this.wakingUp = wakingUp
        if (wakingUp && requestDelayedAnimation) {
            scheduleDelayedDozeAmountAnimation()
        }
    }

    @Deprecated("As part of b/301915812")
    private fun scheduleDelayedDozeAmountAnimation() {
        val alreadyRunning = delayedDozeAmountAnimator != null
        logger.logStartDelayedDozeAmountAnimation(alreadyRunning)
        if (alreadyRunning) return
        delayedDozeAmount.setValue(this, 1.0f)
        delayedDozeAmountAnimator =
            ObjectAnimator.ofFloat(this, delayedDozeAmount, 0.0f).apply {
                interpolator = InterpolatorsAndroidX.LINEAR
                duration = StackStateAnimator.ANIMATION_DURATION_WAKEUP.toLong()
                startDelay = ShadeViewController.WAKEUP_ANIMATION_DELAY_MS.toLong()
                doOnStart {
                    wakeUpListeners.forEach { it.onDelayedDozeAmountAnimationRunning(true) }
                }
                doOnEnd {
                    delayedDozeAmountAnimator = null
                    wakeUpListeners.forEach { it.onDelayedDozeAmountAnimationRunning(false) }
                }
                start()
            }
    }

    override fun onStateChanged(newState: Int) {
        logger.logOnStateChanged(newState = newState, storedState = state)
        if (state == StatusBarState.SHADE && newState == StatusBarState.SHADE) {
            // The SHADE -> SHADE transition is only possible as part of cancelling the screen-off
            // animation (e.g. by fingerprint unlock).  This is done because the system is in an
            // undefined state, so it's an indication that we should do state cleanup. We override
            // the doze amount to 0f (not dozing) so that the notifications are no longer hidden.
            // See: UnlockedScreenOffAnimationController.onFinishedWakingUp()
            setHardDozeAmountOverride(
                dozing = false,
                source = "Override: Shade->Shade (lock cancelled by unlock)"
            )
            this.state = newState
            return
        }

        if (overrideDozeAmountIfAnimatingScreenOff()) {
            this.state = newState
            return
        }

        if (overrideDozeAmountIfBypass()) {
            this.state = newState
            return
        }

        if (overrideDozeAmountIfCommunalShowing()) {
            this.state = newState
            return
        }

        maybeClearHardDozeAmountOverrideHidingNotifs()

        this.state = newState
    }

    @VisibleForTesting
    val statusBarState: Int
        get() = state

    override fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
        val collapsedEnough = event.fraction <= 0.9f
        if (collapsedEnough != this.collapsedEnoughToHide) {
            val couldShowPulsingHuns = canShowPulsingHuns
            this.collapsedEnoughToHide = collapsedEnough
            if (couldShowPulsingHuns && !canShowPulsingHuns) {
                updateNotificationVisibility(animate = true, increaseSpeed = true)
                mHeadsUpManager.releaseAllImmediately()
            }
        }
    }

    /**
     * @return Whether the doze amount was overridden because bypass is enabled. If true, the
     *   original doze amount should be ignored.
     */
    private fun overrideDozeAmountIfBypass(): Boolean {
        if (bypassController.bypassEnabled) {
            if (statusBarStateController.state == StatusBarState.KEYGUARD) {
                setHardDozeAmountOverride(dozing = true, source = "Override: bypass (keyguard)")
            } else {
                setHardDozeAmountOverride(dozing = false, source = "Override: bypass (shade)")
            }
            return true
        }
        return false
    }

    private fun overrideDozeAmountIfCommunalShowing(): Boolean {
        if (communalInteractor.isIdleOnCommunal.value) {
            if (statusBarStateController.state == StatusBarState.KEYGUARD) {
                setHardDozeAmountOverride(dozing = true, source = "Override: communal (keyguard)")
            } else {
                setHardDozeAmountOverride(dozing = false, source = "Override: communal (shade)")
            }
            return true
        }
        return false
    }

    /**
     * If the last [setDozeAmount] call was an override to hide notifications, then this call will
     * check for the set of states that may have caused that override, and if none of them still
     * apply, and the device is awake or not on the keyguard, then dozeAmount will be reset to 0.
     * This fixes bugs where the bypass state changing could result in stale overrides, hiding
     * notifications either on the inside screen or even after unlock.
     */
    private fun maybeClearHardDozeAmountOverrideHidingNotifs() {
        if (hardDozeAmountOverride == 1f) {
            val onKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
            val dozing = statusBarStateController.isDozing
            val bypass = bypassController.bypassEnabled
            val idleOnCommunal = communalInteractor.isIdleOnCommunal.value
            val animating =
                screenOffAnimationController.overrideNotificationsFullyDozingOnKeyguard()
            // Overrides are set by [overrideDozeAmountIfAnimatingScreenOff],
            // [overrideDozeAmountIfBypass] and [overrideDozeAmountIfCommunalShowing] based on
            // 'animating', 'bypass' and 'idleOnCommunal' respectively, so only clear the override
            // if all of those conditions are cleared.  But also require either
            // !dozing or !onKeyguard because those conditions should indicate that we intend
            // notifications to be visible, and thus it is safe to unhide them.
            val willRemove = (!onKeyguard || !dozing) && !bypass && !animating && !idleOnCommunal
            logger.logMaybeClearHardDozeAmountOverrideHidingNotifs(
                willRemove = willRemove,
                onKeyguard = onKeyguard,
                dozing = dozing,
                bypass = bypass,
                animating = animating,
                idleOnCommunal = idleOnCommunal,
            )
            if (willRemove) {
                clearHardDozeAmountOverride()
            }
        }
    }

    /**
     * If we're playing the screen off animation, force the notification doze amount to be 1f (fully
     * dozing). This is needed so that the notifications aren't briefly visible as the screen turns
     * off and dozeAmount goes from 1f to 0f.
     *
     * @return Whether the doze amount was overridden because we are playing the screen off
     *   animation. If true, the original doze amount should be ignored.
     */
    private fun overrideDozeAmountIfAnimatingScreenOff(): Boolean {
        if (screenOffAnimationController.overrideNotificationsFullyDozingOnKeyguard()) {
            setHardDozeAmountOverride(dozing = true, source = "Override: animating screen off")
            return true
        }

        return false
    }

    private fun startVisibilityAnimation(increaseSpeed: Boolean) {
        if (mNotificationVisibleAmount == 0f || mNotificationVisibleAmount == 1f) {
            mVisibilityInterpolator =
                if (mNotificationsVisible) Interpolators.TOUCH_RESPONSE
                else Interpolators.FAST_OUT_SLOW_IN_REVERSE
        }
        val target = if (mNotificationsVisible) 1.0f else 0.0f
        val visibilityAnimator = ObjectAnimator.ofFloat(this, notificationVisibility, target)
        visibilityAnimator.interpolator = InterpolatorsAndroidX.LINEAR
        var duration = StackStateAnimator.ANIMATION_DURATION_WAKEUP.toLong()
        if (increaseSpeed) {
            duration = (duration.toFloat() / 1.5F).toLong()
        }
        visibilityAnimator.duration = duration
        visibilityAnimator.start()
        mVisibilityAnimator = visibilityAnimator
    }

    private fun setVisibilityAmount(visibilityAmount: Float) {
        logger.logSetVisibilityAmount(visibilityAmount)
        mLinearVisibilityAmount = visibilityAmount
        mVisibilityAmount = mVisibilityInterpolator.getInterpolation(visibilityAmount)
        handleAnimationFinished()
        updateHideAmount()
    }

    private fun handleAnimationFinished() {
        if (outputLinearDozeAmount == 0.0f || mLinearVisibilityAmount == 0.0f) {
            mEntrySetToClearWhenFinished.forEach { it.setHeadsUpAnimatingAway(false) }
            mEntrySetToClearWhenFinished.clear()
        }
    }

    private fun updateHideAmount() {
        val linearAmount = min(1.0f - mLinearVisibilityAmount, outputLinearDozeAmount)
        val amount = min(1.0f - mVisibilityAmount, outputEasedDozeAmount)
        logger.logSetHideAmount(linearAmount)
        mStackScrollerController.setHideAmount(linearAmount, amount)
        notificationsFullyHidden = linearAmount == 1.0f
    }

    private fun notifyAnimationStart(awake: Boolean) {
        mStackScrollerController.notifyHideAnimationStart(!awake)
    }

    override fun onDozingChanged(isDozing: Boolean) {
        if (isDozing) {
            setNotificationsVisible(visible = false, animate = false, increaseSpeed = false)
        }
    }

    override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
        var animate = shouldAnimateVisibility()
        if (!isHeadsUp) {
            if (outputLinearDozeAmount != 0.0f && mLinearVisibilityAmount != 0.0f) {
                if (entry.isRowDismissed) {
                    // if we animate, we see the shelf briefly visible. Instead we fully animate
                    // the notification and its background out
                    animate = false
                } else if (!wakingUp && !willWakeUp) {
                    // TODO: look that this is done properly and not by anyone else
                    entry.setHeadsUpAnimatingAway(true)
                    mEntrySetToClearWhenFinished.add(entry)
                }
            }
        } else if (mEntrySetToClearWhenFinished.contains(entry)) {
            mEntrySetToClearWhenFinished.remove(entry)
            entry.setHeadsUpAnimatingAway(false)
        }
        updateNotificationVisibility(animate, increaseSpeed = false)
    }

    private fun shouldAnimateVisibility() =
        dozeParameters.alwaysOn && !dozeParameters.displayNeedsBlanking

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("inputLinearDozeAmount: $inputLinearDozeAmount")
        pw.println("inputEasedDozeAmount: $inputEasedDozeAmount")
        pw.println("delayedDozeAmountOverride: $delayedDozeAmountOverride")
        pw.println("hardDozeAmountOverride: $hardDozeAmountOverride")
        pw.println("hardDozeAmountOverrideSource: $hardDozeAmountOverrideSource")
        pw.println("outputLinearDozeAmount: $outputLinearDozeAmount")
        pw.println("outputEasedDozeAmount: $outputEasedDozeAmount")
        pw.println("mNotificationVisibleAmount: $mNotificationVisibleAmount")
        pw.println("mNotificationsVisible: $mNotificationsVisible")
        pw.println("mNotificationsVisibleForExpansion: $mNotificationsVisibleForExpansion")
        pw.println("mVisibilityAmount: $mVisibilityAmount")
        pw.println("mLinearVisibilityAmount: $mLinearVisibilityAmount")
        pw.println("pulseExpanding: $pulseExpanding")
        pw.println("state: ${StatusBarState.toString(state)}")
        pw.println("fullyAwake: $fullyAwake")
        pw.println("wakingUp: $wakingUp")
        pw.println("willWakeUp: $willWakeUp")
        pw.println("collapsedEnoughToHide: $collapsedEnoughToHide")
        pw.println("pulsing: $pulsing")
        pw.println("notificationsFullyHidden: $notificationsFullyHidden")
        pw.println("canShowPulsingHuns: $canShowPulsingHuns")
    }

    fun logDelayingClockWakeUpAnimation(delayingAnimation: Boolean) {
        logger.logDelayingClockWakeUpAnimation(delayingAnimation)
    }

    interface WakeUpListener {
        /** Called whenever the notifications are fully hidden or shown */
        fun onFullyHiddenChanged(isFullyHidden: Boolean) {}

        /**
         * Called whenever the pulseExpansion changes
         *
         * @param expandingChanged if the user has started or stopped expanding
         */
        @Deprecated(
            message = "Use onPulseExpandedChanged instead.",
            replaceWith = ReplaceWith("onPulseExpandedChanged"),
        )
        fun onPulseExpansionAmountChanged(expandingChanged: Boolean) {}

        /**
         * Called when the animator started by [scheduleDelayedDozeAmountAnimation] begins running
         * after the start delay, or after it ends/is cancelled.
         */
        fun onDelayedDozeAmountAnimationRunning(running: Boolean) {}

        /** Called whenever a pulse has started or stopped expanding. */
        fun onPulseExpandingChanged(isPulseExpanding: Boolean) {}
    }

    companion object {
        private val notificationVisibility =
            object : FloatProperty<NotificationWakeUpCoordinator>("notificationVisibility") {

                override fun setValue(coordinator: NotificationWakeUpCoordinator, value: Float) {
                    coordinator.setVisibilityAmount(value)
                }

                override fun get(coordinator: NotificationWakeUpCoordinator): Float {
                    return coordinator.mLinearVisibilityAmount
                }
            }

        private val delayedDozeAmount =
            object : FloatProperty<NotificationWakeUpCoordinator>("delayedDozeAmount") {

                override fun setValue(coordinator: NotificationWakeUpCoordinator, value: Float) {
                    coordinator.delayedDozeAmountOverride = value
                    coordinator.logger.logSetDelayDozeAmountOverride(value)
                    coordinator.updateDozeAmount()
                }

                override fun get(coordinator: NotificationWakeUpCoordinator): Float {
                    return coordinator.delayedDozeAmountOverride
                }
            }
    }
}
