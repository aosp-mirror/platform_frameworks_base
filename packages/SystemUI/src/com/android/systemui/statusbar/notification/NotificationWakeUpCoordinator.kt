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
import android.content.Context
import android.util.FloatProperty
import com.android.systemui.Interpolators
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.AmbientPulseManager
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.DozeParameters

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationWakeUpCoordinator @Inject constructor(
        private val mContext: Context,
        private val mAmbientPulseManager: AmbientPulseManager,
        private val mStatusBarStateController: StatusBarStateController)
    : AmbientPulseManager.OnAmbientChangedListener, StatusBarStateController.StateListener {

    private val mNotificationVisibility
            = object : FloatProperty<NotificationWakeUpCoordinator>("notificationVisibility") {

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
    private var mDarkAnimator: ObjectAnimator? = null
    private var mVisibilityAmount = 0.0f
    private var mLinearVisibilityAmount = 0.0f
    private var mWakingUp = false
    private val mEntrySetToClearWhenFinished = mutableSetOf<NotificationEntry>()
    private val mDozeParameters: DozeParameters;

    init {
        mAmbientPulseManager.addListener(this)
        mStatusBarStateController.addCallback(this)
        mDozeParameters = DozeParameters.getInstance(mContext)
    }

    fun setStackScroller(stackScroller: NotificationStackScrollLayout) {
        mStackScroller = stackScroller
    }

    /**
     * @param visible should notifications be visible
     * @param animate should this change be animated
     * @param increaseSpeed should the speed be increased of the animation
     */
    fun setNotificationsVisibleForExpansion(visible: Boolean, animate: Boolean,
                                                    increaseSpeed: Boolean) {
        mNotificationsVisibleForExpansion = visible
        updateNotificationVisibility(animate, increaseSpeed)
        if (!visible && mNotificationsVisible) {
            // If we stopped expanding and we're still visible because we had a pulse that hasn't
            // times out, let's release them all to make sure were not stuck in a state where
            // notifications are visible
            mAmbientPulseManager.releaseAllImmediately()
        }
    }

    private fun updateNotificationVisibility(animate: Boolean, increaseSpeed: Boolean) {
        var visible = mNotificationsVisibleForExpansion || mAmbientPulseManager.hasNotifications()
        if (!visible && mNotificationsVisible && mWakingUp && mDozeAmount != 0.0f) {
            // let's not make notifications invisible while waking up, otherwise the animation
            // is strange
            return;
        }
        setNotificationsVisible(visible, animate, increaseSpeed)
    }

    private fun setNotificationsVisible(visible: Boolean, animate: Boolean,
                                        increaseSpeed: Boolean) {
        if (mNotificationsVisible == visible) {
            return
        }
        mNotificationsVisible = visible
        mDarkAnimator?.cancel();
        if (animate) {
            notifyAnimationStart(visible)
            startVisibilityAnimation(increaseSpeed)
        } else {
            setVisibilityAmount(if (visible) 1.0f else 0.0f)
        }
    }

    override fun onDozeAmountChanged(linear: Float, eased: Float) {
        if (linear != 1.0f && linear != 0.0f
                && (mLinearDozeAmount == 0.0f || mLinearDozeAmount == 1.0f)) {
            // Let's notify the scroller that an animation started
            notifyAnimationStart(mLinearDozeAmount == 1.0f)
        }
        mLinearDozeAmount = linear
        mDozeAmount = eased
        mStackScroller.setDozeAmount(mDozeAmount)
        updateDarkAmount()
        if (linear == 0.0f) {
            setNotificationsVisible(visible = false, animate = false, increaseSpeed = false);
            setNotificationsVisibleForExpansion(visible = false, animate = false,
                    increaseSpeed = false)
        }
    }

    private fun startVisibilityAnimation(increaseSpeed: Boolean) {
        if (mNotificationVisibleAmount == 0f || mNotificationVisibleAmount == 1f) {
            mVisibilityInterpolator = if (mNotificationsVisible)
                Interpolators.TOUCH_RESPONSE
            else
                Interpolators.FAST_OUT_SLOW_IN_REVERSE
        }
        val target = if (mNotificationsVisible) 1.0f else 0.0f
        val darkAnimator = ObjectAnimator.ofFloat(this, mNotificationVisibility, target)
        darkAnimator.setInterpolator(Interpolators.LINEAR)
        var duration = StackStateAnimator.ANIMATION_DURATION_WAKEUP.toLong()
        if (increaseSpeed) {
            duration = (duration.toFloat() / 1.5F).toLong();
        }
        darkAnimator.setDuration(duration)
        darkAnimator.start()
        mDarkAnimator = darkAnimator
    }

    private fun setVisibilityAmount(visibilityAmount: Float) {
        mLinearVisibilityAmount = visibilityAmount
        mVisibilityAmount = mVisibilityInterpolator.getInterpolation(
                visibilityAmount)
        handleAnimationFinished();
        updateDarkAmount()
    }

    private fun handleAnimationFinished() {
        if (mLinearDozeAmount == 0.0f || mLinearVisibilityAmount == 0.0f) {
            mEntrySetToClearWhenFinished.forEach { it.setAmbientGoingAway(false) }
        }
    }

    fun getWakeUpHeight() : Float {
        return mStackScroller.pulseHeight
    }

    private fun updateDarkAmount() {
        val linearAmount = Math.min(1.0f - mLinearVisibilityAmount, mLinearDozeAmount)
        val amount = Math.min(1.0f - mVisibilityAmount, mDozeAmount)
        mStackScroller.setDarkAmount(linearAmount, amount)
    }

    private fun notifyAnimationStart(awake: Boolean) {
        mStackScroller.notifyDarkAnimationStart(!awake)
    }

    override fun onDozingChanged(isDozing: Boolean) {
        if (isDozing) {
            setNotificationsVisible(visible = false, animate = false, increaseSpeed = false)
        }
    }

    fun setPulseHeight(height: Float): Float {
        return mStackScroller.setPulseHeight(height)
    }

    fun setWakingUp(wakingUp: Boolean) {
        mWakingUp = wakingUp
        if (wakingUp && mNotificationsVisible && !mNotificationsVisibleForExpansion) {
            // We're waking up while pulsing, let's make sure the animation looks nice
            mStackScroller.wakeUpFromPulse();
        }
    }

    override fun onAmbientStateChanged(entry: NotificationEntry, isPulsing: Boolean) {
        var animate = mDozeParameters.getAlwaysOn() && !mDozeParameters.getDisplayNeedsBlanking()
        if (!isPulsing) {
            if (mLinearDozeAmount != 0.0f) {
                if (entry.isRowDismissed) {
                    // if we animate, we see the shelf briefly visible. Instead we fully animate
                    // the notification and its background out
                    animate = false
                } else {
                    entry.setAmbientGoingAway(true)
                    mEntrySetToClearWhenFinished.add(entry)
                }
            }
        } else if (mEntrySetToClearWhenFinished.contains(entry)) {
            mEntrySetToClearWhenFinished.remove(entry)
            entry.setAmbientGoingAway(false)
        }
        updateNotificationVisibility(animate, increaseSpeed = false)
    }
}