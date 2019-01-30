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
import android.view.animation.Interpolator
import com.android.systemui.Interpolators
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.StackStateAnimator

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationWakeUpCoordinator @Inject constructor() {

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
    private var mDarkAnimator: ObjectAnimator? = null
    private var mVisibilityAmount = 0.0f
    private var mLinearVisibilityAmount = 0.0f

    fun setStackScroller(stackScroller: NotificationStackScrollLayout) {
        mStackScroller = stackScroller
    }

    fun setNotificationsVisible(visible: Boolean, animate: Boolean) {
        if (mNotificationsVisible == visible) {
            return
        }
        mNotificationsVisible = visible
        mDarkAnimator?.cancel();
        if (animate) {
            startVisibilityAnimation()
            notifyAnimationStart(visible)
        } else {
            setVisibilityAmount(if (visible) 1.0f else 0.0f)
        }
    }

    fun setDozeAmount(linearAmount: Float, interpolatedAmount: Float) {
        mLinearDozeAmount = linearAmount;
        mDozeAmount = interpolatedAmount;
        updateDarkAmount()
    }

    private fun startVisibilityAnimation() {
        if (mNotificationVisibleAmount == 0f || mNotificationVisibleAmount == 1f) {
            mVisibilityInterpolator = if (mNotificationsVisible)
                Interpolators.TOUCH_RESPONSE_REVERSE
            else
                Interpolators.FAST_OUT_SLOW_IN
        }
        val target = if (mNotificationsVisible) 1.0f else 0.0f
        val darkAnimator = ObjectAnimator.ofFloat(this, mNotificationVisibility, target)
        darkAnimator.setInterpolator(Interpolators.LINEAR)
        darkAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP.toLong())
        darkAnimator.start()
        mDarkAnimator = darkAnimator
    }

    private fun setVisibilityAmount(visibilityAmount: Float) {
        mLinearVisibilityAmount = visibilityAmount
        mVisibilityAmount = mVisibilityInterpolator.getInterpolation(
                visibilityAmount)
        updateDarkAmount()
    }

    private fun updateDarkAmount() {
        val linearAmount = Math.min(1.0f - mLinearVisibilityAmount, mLinearDozeAmount)
        val amount = Math.min(1.0f - mVisibilityAmount, mDozeAmount)
        mStackScroller.setDarkAmount(linearAmount, amount)
    }

    fun notifyAnimationStart(awake: Boolean) {
        mStackScroller.notifyDarkAnimationStart(!awake)
    }
}