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

import android.animation.ObjectAnimator
import android.util.FloatProperty
import com.android.systemui.Interpolators
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.phone.PanelExpansionListener
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationWakeUpCoordinator @Inject constructor(
    private val mHeadsUpManager: HeadsUpManager,
    private val statusBarStateController: StatusBarStateController,
    private val bypassController: KeyguardBypassController,
    private val dozeParameters: DozeParameters
) : OnHeadsUpChangedListener, StatusBarStateController.StateListener, PanelExpansionListener {

    private val mNotificationVisibility = object : FloatProperty<NotificationWakeUpCoordinator>(
        "notificationVisibility") {

        override fun setValue(coordinator: NotificationWakeUpCoordinator, value: Float) {
            coordinator.setVisibilityAmount(value)
        }

        override fun get(coordinator: NotificationWakeUpCoordinator): Float? {
            return coordinator.mLinearVisibilityAmount
        }
    }
    private lateinit var mStackScroller: NotificationStackScrollLayout
    private var mVisibilityInterpolator = Interpolators.FAST_OUT_SLOW_IN_REVERSE

    private var mLinearDozeAmount: Float = 0.0f
    private var mDozeAmount: Float = 0.0f
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
        set(value) {
            field = value
            willWakeUp = false
            if (value) {
                if (mNotificationsVisible && !mNotificationsVisibleForExpansion &&
                    !bypassController.bypassEnabled) {
                    // We're waking up while pulsing, let's make sure the animation looks nice
                    mStackScroller.wakeUpFromPulse()
                }
                if (bypassController.bypassEnabled && !mNotificationsVisible) {
                    // Let's make sure our huns become visible once we are waking up in case
                    // they were blocked by the proximity sensor
                    updateNotificationVisibility(animate = shouldAnimateVisibility(),
                            increaseSpeed = false)
                }
            }
        }

    var willWakeUp = false
        set(value) {
            if (!value || mDozeAmount != 0.0f) {
                field = value
            }
        }

    private var collapsedEnoughToHide: Boolean = false
    lateinit var iconAreaController: NotificationIconAreaController

    var pulsing: Boolean = false
        set(value) {
            field = value
            if (value) {
                // Only when setting pulsing to true we want an immediate update, since we get
                // this already when the doze service finishes which is usually before we get
                // the waking up callback
                updateNotificationVisibility(animate = shouldAnimateVisibility(),
                        increaseSpeed = false)
            }
        }

    var notificationsFullyHidden: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                for (listener in wakeUpListeners) {
                    listener.onFullyHiddenChanged(value)
                }
            }
        }
    /**
     * True if we can show pulsing heads up notifications
     */
    var canShowPulsingHuns: Boolean = false
        private set
        get() {
            var canShow = pulsing
            if (bypassController.bypassEnabled) {
                // We also allow pulsing on the lock screen!
                canShow = canShow || (wakingUp || willWakeUp || fullyAwake) &&
                    statusBarStateController.state == StatusBarState.KEYGUARD
                // We want to hide the notifications when collapsed too much
                if (collapsedEnoughToHide) {
                    canShow = false
                }
            }
            return canShow
        }

    init {
        mHeadsUpManager.addListener(this)
        statusBarStateController.addCallback(this)
        addListener(object : WakeUpListener {
            override fun onFullyHiddenChanged(isFullyHidden: Boolean) {
                if (isFullyHidden && mNotificationsVisibleForExpansion) {
                    // When the notification becomes fully invisible, let's make sure our expansion
                    // flag also changes. This can happen if the bouncer shows when dragging down
                    // and then the screen turning off, where we don't reset this state.
                    setNotificationsVisibleForExpansion(visible = false, animate = false,
                            increaseSpeed = false)
                }
            }
        })
    }

    fun setStackScroller(stackScroller: NotificationStackScrollLayout) {
        mStackScroller = stackScroller
        pulseExpanding = stackScroller.isPulseExpanding
        stackScroller.setOnPulseHeightChangedListener {
            val nowExpanding = isPulseExpanding()
            val changed = nowExpanding != pulseExpanding
            pulseExpanding = nowExpanding
            for (listener in wakeUpListeners) {
                listener.onPulseExpansionChanged(changed)
            }
        }
    }

    fun isPulseExpanding(): Boolean = mStackScroller.isPulseExpanding

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

    private fun updateNotificationVisibility(
        animate: Boolean,
        increaseSpeed: Boolean
    ) {
        // TODO: handle Lockscreen wakeup for bypass when we're not pulsing anymore
        var visible = mNotificationsVisibleForExpansion || mHeadsUpManager.hasNotifications()
        visible = visible && canShowPulsingHuns

        if (!visible && mNotificationsVisible && (wakingUp || willWakeUp) && mDozeAmount != 0.0f) {
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
        if (updateDozeAmountIfBypass()) {
            return
        }
        if (linear != 1.0f && linear != 0.0f &&
            (mLinearDozeAmount == 0.0f || mLinearDozeAmount == 1.0f)) {
            // Let's notify the scroller that an animation started
            notifyAnimationStart(mLinearDozeAmount == 1.0f)
        }
        setDozeAmount(linear, eased)
    }

    fun setDozeAmount(linear: Float, eased: Float) {
        val changed = linear != mLinearDozeAmount
        mLinearDozeAmount = linear
        mDozeAmount = eased
        mStackScroller.setDozeAmount(mDozeAmount)
        updateHideAmount()
        if (changed && linear == 0.0f) {
            setNotificationsVisible(visible = false, animate = false, increaseSpeed = false)
            setNotificationsVisibleForExpansion(visible = false, animate = false,
                    increaseSpeed = false)
        }
    }

    override fun onStateChanged(newState: Int) {
        updateDozeAmountIfBypass()
        if (bypassController.bypassEnabled &&
                newState == StatusBarState.KEYGUARD && state == StatusBarState.SHADE_LOCKED &&
            (!statusBarStateController.isDozing || shouldAnimateVisibility())) {
            // We're leaving shade locked. Let's animate the notifications away
            setNotificationsVisible(visible = true, increaseSpeed = false, animate = false)
            setNotificationsVisible(visible = false, increaseSpeed = false, animate = true)
        }
        this.state = newState
    }

    override fun onPanelExpansionChanged(expansion: Float, tracking: Boolean) {
        val collapsedEnough = expansion <= 0.9f
        if (collapsedEnough != this.collapsedEnoughToHide) {
            val couldShowPulsingHuns = canShowPulsingHuns
            this.collapsedEnoughToHide = collapsedEnough
            if (couldShowPulsingHuns && !canShowPulsingHuns) {
                updateNotificationVisibility(animate = true, increaseSpeed = true)
                mHeadsUpManager.releaseAllImmediately()
            }
        }
    }

    private fun updateDozeAmountIfBypass(): Boolean {
        if (bypassController.bypassEnabled) {
            var amount = 1.0f
            if (statusBarStateController.state == StatusBarState.SHADE ||
                statusBarStateController.state == StatusBarState.SHADE_LOCKED) {
                amount = 0.0f
            }
            setDozeAmount(amount, amount)
            return true
        }
        return false
    }

    private fun startVisibilityAnimation(increaseSpeed: Boolean) {
        if (mNotificationVisibleAmount == 0f || mNotificationVisibleAmount == 1f) {
            mVisibilityInterpolator = if (mNotificationsVisible)
                Interpolators.TOUCH_RESPONSE
            else
                Interpolators.FAST_OUT_SLOW_IN_REVERSE
        }
        val target = if (mNotificationsVisible) 1.0f else 0.0f
        val visibilityAnimator = ObjectAnimator.ofFloat(this, mNotificationVisibility, target)
        visibilityAnimator.setInterpolator(Interpolators.LINEAR)
        var duration = StackStateAnimator.ANIMATION_DURATION_WAKEUP.toLong()
        if (increaseSpeed) {
            duration = (duration.toFloat() / 1.5F).toLong()
        }
        visibilityAnimator.setDuration(duration)
        visibilityAnimator.start()
        mVisibilityAnimator = visibilityAnimator
    }

    private fun setVisibilityAmount(visibilityAmount: Float) {
        mLinearVisibilityAmount = visibilityAmount
        mVisibilityAmount = mVisibilityInterpolator.getInterpolation(
                visibilityAmount)
        handleAnimationFinished()
        updateHideAmount()
    }

    private fun handleAnimationFinished() {
        if (mLinearDozeAmount == 0.0f || mLinearVisibilityAmount == 0.0f) {
            mEntrySetToClearWhenFinished.forEach { it.setHeadsUpAnimatingAway(false) }
            mEntrySetToClearWhenFinished.clear()
        }
    }

    fun getWakeUpHeight(): Float {
        return mStackScroller.wakeUpHeight
    }

    private fun updateHideAmount() {
        val linearAmount = Math.min(1.0f - mLinearVisibilityAmount, mLinearDozeAmount)
        val amount = Math.min(1.0f - mVisibilityAmount, mDozeAmount)
        mStackScroller.setHideAmount(linearAmount, amount)
        notificationsFullyHidden = linearAmount == 1.0f
    }

    private fun notifyAnimationStart(awake: Boolean) {
        mStackScroller.notifyHideAnimationStart(!awake)
    }

    override fun onDozingChanged(isDozing: Boolean) {
        if (isDozing) {
            setNotificationsVisible(visible = false, animate = false, increaseSpeed = false)
        }
    }

    /**
     * Set the height how tall notifications are pulsing. This is only set whenever we are expanding
     * from a pulse and determines how much the notifications are expanded.
     */
    fun setPulseHeight(height: Float): Float {
        val overflow = mStackScroller.setPulseHeight(height)
        //  no overflow for the bypass experience
        return if (bypassController.bypassEnabled) 0.0f else overflow
    }

    override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
        var animate = shouldAnimateVisibility()
        if (!isHeadsUp) {
            if (mLinearDozeAmount != 0.0f && mLinearVisibilityAmount != 0.0f) {
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
            dozeParameters.getAlwaysOn() && !dozeParameters.getDisplayNeedsBlanking()

    interface WakeUpListener {
        /**
         * Called whenever the notifications are fully hidden or shown
         */
        @JvmDefault fun onFullyHiddenChanged(isFullyHidden: Boolean) {}

        /**
         * Called whenever the pulseExpansion changes
         * @param expandingChanged if the user has started or stopped expanding
         */
        @JvmDefault fun onPulseExpansionChanged(expandingChanged: Boolean) {}
    }
}