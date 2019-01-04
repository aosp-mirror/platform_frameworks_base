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

package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import android.os.PowerManager.WAKE_REASON_GESTURE
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration

import com.android.systemui.Gefingerpoken
import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.classifier.FalsingManager
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.phone.ShadeController

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * A utility class to enable the downward swipe on when pulsing.
 */
@Singleton
class PulseExpansionHandler @Inject
constructor(context: Context,
            private val mWakeUpCoordinator: NotificationWakeUpCoordinator,
            private val mAmbientPulseManager: AmbientPulseManager) : Gefingerpoken {
    private val mPowerManager: PowerManager?
    private var mShadeController: ShadeController? = null

    private val mMinDragDistance: Int
    private var mInitialTouchX: Float = 0.0f
    private var mInitialTouchY: Float = 0.0f
    var isExpanding: Boolean = false
        private set
    private val mTouchSlop: Float
    private var mExpansionCallback: ExpansionCallback? = null
    private lateinit var mStackScroller: NotificationStackScrollLayout
    private val mTemp2 = IntArray(2)
    private var mDraggedFarEnough: Boolean = false
    private var mStartingChild: ExpandableView? = null
    private val mFalsingManager: FalsingManager
    private var mPulsing: Boolean = false
    var isWakingToShadeLocked: Boolean = false
        private set
    private var mEmptyDragAmount: Float = 0.0f
    private var mWakeUpHeight: Float = 0.0f
    private var mReachedWakeUpHeight: Boolean = false

    private val isFalseTouch: Boolean
        get() = mFalsingManager.isFalseTouch

    init {
        mMinDragDistance = context.resources.getDimensionPixelSize(
                R.dimen.keyguard_drag_down_min_distance)
        mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        mFalsingManager = FalsingManager.getInstance(context)
        mPowerManager = context.getSystemService(PowerManager::class.java)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return maybeStartExpansion(event)
    }

    private fun maybeStartExpansion(event: MotionEvent): Boolean {
        if (!mPulsing) {
            return false
        }
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDraggedFarEnough = false
                isExpanding = false
                mStartingChild = null
                mInitialTouchY = y
                mInitialTouchX = x
            }

            MotionEvent.ACTION_MOVE -> {
                val h = y - mInitialTouchY
                if (h > mTouchSlop && h > Math.abs(x - mInitialTouchX)) {
                    mFalsingManager.onStartExpandingFromPulse()
                    isExpanding = true
                    captureStartingChild(mInitialTouchX, mInitialTouchY)
                    mInitialTouchY = y
                    mInitialTouchX = x
                    mWakeUpHeight = mWakeUpCoordinator.getWakeUpHeight()
                    mReachedWakeUpHeight = false
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isExpanding) {
            return maybeStartExpansion(event)
        }
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateExpansionHeight(y - mInitialTouchY)
            MotionEvent.ACTION_UP -> if (!mFalsingManager.isUnlockingDisabled && !isFalseTouch) {
                finishExpansion()
            } else {
                cancelExpansion()
            }
            MotionEvent.ACTION_CANCEL -> cancelExpansion()
        }
        return isExpanding
    }

    private fun finishExpansion() {
        resetClock()
        if (mStartingChild != null) {
            setUserLocked(mStartingChild!!, false)
            mStartingChild = null
        }
        isExpanding = false
        isWakingToShadeLocked = true
        mPowerManager!!.wakeUp(SystemClock.uptimeMillis(), WAKE_REASON_GESTURE,
                "com.android.systemui:PULSEDRAG")
        mShadeController!!.goToLockedShade(mStartingChild)
        if (mStartingChild is ExpandableNotificationRow) {
            val row = mStartingChild as ExpandableNotificationRow?
            row!!.onExpandedByGesture(true /* userExpanded */)
        }
    }

    private fun updateExpansionHeight(height: Float) {
        var expansionHeight = max(height, 0.0f)
        if (!mReachedWakeUpHeight && height > mWakeUpHeight) {
            mReachedWakeUpHeight = true;
        }
        if (mStartingChild != null) {
            val child = mStartingChild!!
            val newHeight = Math.min((child.collapsedHeight + expansionHeight).toInt(),
                    child.maxContentHeight)
            child.actualHeight = newHeight
            expansionHeight = max(newHeight.toFloat(), expansionHeight)
        } else {
            if (!mAmbientPulseManager.hasNotifications()) {
                // No pulsing notifications, we need to make notifications visible
                val target = if (mReachedWakeUpHeight) (mWakeUpHeight / 2.0f) else 0.0f

                mWakeUpCoordinator.setNotificationsVisible(height > target, true /* animate */,
                        true /* increaseSpeed */)
            }
            expansionHeight = max(mWakeUpHeight, expansionHeight)
        }
        val emptyDragAmount = mWakeUpCoordinator.setPulseWakeUpHeight(expansionHeight)
        setEmptyDragAmount(emptyDragAmount * RUBBERBAND_FACTOR_STATIC)
    }

    private fun captureStartingChild(x: Float, y: Float) {
        if (mStartingChild == null) {
            mStartingChild = findView(x, y)
            if (mStartingChild != null) {
                setUserLocked(mStartingChild!!, true)
            }
        }
    }

    private fun setEmptyDragAmount(amount: Float) {
        mEmptyDragAmount = amount
        mExpansionCallback!!.setEmptyDragAmount(amount)
    }

    private fun reset(child: ExpandableView) {
        if (child.actualHeight == child.collapsedHeight) {
            setUserLocked(child, false)
            return
        }
        val anim = ObjectAnimator.ofInt(child, "actualHeight",
                child.actualHeight, child.collapsedHeight)
        anim.interpolator = Interpolators.FAST_OUT_SLOW_IN
        anim.duration = SPRING_BACK_ANIMATION_LENGTH_MS.toLong()
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                setUserLocked(child, false)
            }
        })
        anim.start()
    }

    private fun setUserLocked(child: ExpandableView, userLocked: Boolean) {
        if (child is ExpandableNotificationRow) {
            child.isUserLocked = userLocked
        }
    }

    private fun resetClock() {
        val anim = ValueAnimator.ofFloat(mEmptyDragAmount, 0f)
        anim.interpolator = Interpolators.FAST_OUT_SLOW_IN
        anim.duration = SPRING_BACK_ANIMATION_LENGTH_MS.toLong()
        anim.addUpdateListener { animation -> setEmptyDragAmount(animation.animatedValue as Float) }
        anim.start()
    }

    private fun cancelExpansion() {
        mFalsingManager.onExpansionFromPulseStopped()
        if (mStartingChild != null) {
            reset(mStartingChild!!)
            mStartingChild = null
        } else {
            resetClock()
        }
        if (!mAmbientPulseManager.hasNotifications()) {
            mWakeUpCoordinator.setNotificationsVisible(false /* visible */, true /* animate */,
                    false /* increaseSpeed */)
        }
        isExpanding = false
    }

    private fun findView(x: Float, y: Float): ExpandableView? {
        var totalX = x
        var totalY = y
        mStackScroller.getLocationOnScreen(mTemp2)
        totalX += mTemp2[0].toFloat()
        totalY += mTemp2[1].toFloat()
        val childAtRawPosition = mStackScroller.getChildAtRawPosition(totalX, totalY)
        return if (childAtRawPosition != null && childAtRawPosition.isContentExpandable) {
            childAtRawPosition
        } else null
    }

    fun setUp(notificationStackScroller: NotificationStackScrollLayout,
             expansionCallback: ExpansionCallback,
             shadeController: ShadeController) {
        mExpansionCallback = expansionCallback
        mShadeController = shadeController
        mStackScroller = notificationStackScroller
    }

    fun setPulsing(pulsing: Boolean) {
        mPulsing = pulsing
        val hasAmbientNotifications = mAmbientPulseManager.hasNotifications();
        if (pulsing && hasAmbientNotifications) {
            mWakeUpCoordinator.setNotificationsVisible(true /* visible */, true /* animate */,
                    false /* increaseSpeed */)
        } else if (!pulsing && !hasAmbientNotifications) {
            // TODO: figure out the optimal UX for this, should we extend the pulse instead?
            mWakeUpCoordinator.setNotificationsVisible(false /* visible */, true /* animate */,
                    false /* increaseSpeed */)
        }
    }

    fun onStartedWakingUp() {
        isWakingToShadeLocked = false
    }

    interface ExpansionCallback {
        fun setEmptyDragAmount(amount: Float)
    }

    companion object {
        private val RUBBERBAND_FACTOR_STATIC = 0.25f

        private val SPRING_BACK_ANIMATION_LENGTH_MS = 375
    }
}
