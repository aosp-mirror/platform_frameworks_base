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
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import android.os.PowerManager.WAKE_REASON_GESTURE
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.classifier.Classifier.NOTIFICATION_DRAG_DOWN
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlin.math.max

/**
 * A utility class to enable the downward swipe on when pulsing.
 */
@SysUISingleton
class PulseExpansionHandler @Inject
constructor(
    context: Context,
    private val wakeUpCoordinator: NotificationWakeUpCoordinator,
    private val bypassController: KeyguardBypassController,
    private val headsUpManager: HeadsUpManagerPhone,
    private val roundnessManager: NotificationRoundnessManager,
    private val configurationController: ConfigurationController,
    private val statusBarStateController: StatusBarStateController,
    private val falsingManager: FalsingManager,
    private val lockscreenShadeTransitionController: LockscreenShadeTransitionController,
    private val falsingCollector: FalsingCollector
) : Gefingerpoken {
    companion object {
        private val SPRING_BACK_ANIMATION_LENGTH_MS = 375
    }
    private val mPowerManager: PowerManager?

    private var mInitialTouchX: Float = 0.0f
    private var mInitialTouchY: Float = 0.0f
    var isExpanding: Boolean = false
        private set(value) {
            val changed = field != value
            field = value
            bypassController.isPulseExpanding = value
            if (changed) {
                if (value) {
                    val topEntry = headsUpManager.topEntry
                    topEntry?.let {
                        roundnessManager.setTrackingHeadsUp(it.row)
                    }
                    lockscreenShadeTransitionController.onPulseExpansionStarted()
                } else {
                    roundnessManager.setTrackingHeadsUp(null)
                    if (!leavingLockscreen) {
                        bypassController.maybePerformPendingUnlock()
                        pulseExpandAbortListener?.run()
                    }
                }
                headsUpManager.unpinAll(true /* userUnPinned */)
            }
        }
    var leavingLockscreen: Boolean = false
        private set
    private var touchSlop = 0f
    private var minDragDistance = 0
    private lateinit var stackScrollerController: NotificationStackScrollLayoutController
    private val mTemp2 = IntArray(2)
    private var mDraggedFarEnough: Boolean = false
    private var mStartingChild: ExpandableView? = null
    private var mPulsing: Boolean = false
    var isWakingToShadeLocked: Boolean = false
        private set

    private var velocityTracker: VelocityTracker? = null

    private val isFalseTouch: Boolean
        get() = falsingManager.isFalseTouch(NOTIFICATION_DRAG_DOWN)
    var qsExpanded: Boolean = false
    var pulseExpandAbortListener: Runnable? = null
    var bouncerShowing: Boolean = false

    init {
        initResources(context)
        configurationController.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                initResources(context)
            }
        })
        mPowerManager = context.getSystemService(PowerManager::class.java)
    }

    private fun initResources(context: Context) {
        minDragDistance = context.resources.getDimensionPixelSize(
            R.dimen.keyguard_drag_down_min_distance)
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return canHandleMotionEvent() && startExpansion(event)
    }

    private fun canHandleMotionEvent(): Boolean {
        return wakeUpCoordinator.canShowPulsingHuns && !qsExpanded && !bouncerShowing
    }

    private fun startExpansion(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDraggedFarEnough = false
                isExpanding = false
                leavingLockscreen = false
                mStartingChild = null
                mInitialTouchY = y
                mInitialTouchX = x
            }

            MotionEvent.ACTION_MOVE -> {
                val h = y - mInitialTouchY
                if (h > touchSlop && h > Math.abs(x - mInitialTouchX)) {
                    falsingCollector.onStartExpandingFromPulse()
                    isExpanding = true
                    captureStartingChild(mInitialTouchX, mInitialTouchY)
                    mInitialTouchY = y
                    mInitialTouchX = x
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                recycleVelocityTracker()
                isExpanding = false
            }

            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                isExpanding = false
            }
        }
        return false
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val finishExpanding = (event.action == MotionEvent.ACTION_CANCEL ||
            event.action == MotionEvent.ACTION_UP) && isExpanding
        if (!canHandleMotionEvent() && !finishExpanding) {
            // We allow cancellations/finishing to still go through here to clean up the state
            return false
        }

        if (velocityTracker == null || !isExpanding ||
                event.actionMasked == MotionEvent.ACTION_DOWN) {
            return startExpansion(event)
        }
        velocityTracker!!.addMovement(event)
        val y = event.y

        val moveDistance = y - mInitialTouchY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateExpansionHeight(moveDistance)
            MotionEvent.ACTION_UP -> {
                velocityTracker!!.computeCurrentVelocity(1000 /* units */)
                val canExpand = moveDistance > 0 && velocityTracker!!.getYVelocity() > -1000 &&
                        statusBarStateController.state != StatusBarState.SHADE
                if (!falsingManager.isUnlockingDisabled && !isFalseTouch && canExpand) {
                    finishExpansion()
                } else {
                    cancelExpansion()
                }
                recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelExpansion()
                recycleVelocityTracker()
            }
        }
        return isExpanding
    }

    private fun finishExpansion() {
        val startingChild = mStartingChild
        if (mStartingChild != null) {
            setUserLocked(mStartingChild!!, false)
            mStartingChild = null
        }
        if (statusBarStateController.isDozing) {
            isWakingToShadeLocked = true
            wakeUpCoordinator.willWakeUp = true
            mPowerManager!!.wakeUp(SystemClock.uptimeMillis(), WAKE_REASON_GESTURE,
                    "com.android.systemui:PULSEDRAG")
        }
        lockscreenShadeTransitionController.goToLockedShade(startingChild,
                needsQSAnimation = false)
        lockscreenShadeTransitionController.finishPulseAnimation(cancelled = false)
        leavingLockscreen = true
        isExpanding = false
        if (mStartingChild is ExpandableNotificationRow) {
            val row = mStartingChild as ExpandableNotificationRow?
            row!!.onExpandedByGesture(true /* userExpanded */)
        }
    }

    private fun updateExpansionHeight(height: Float) {
        var expansionHeight = max(height, 0.0f)
        if (mStartingChild != null) {
            val child = mStartingChild!!
            val newHeight = Math.min((child.collapsedHeight + expansionHeight).toInt(),
                    child.maxContentHeight)
            child.actualHeight = newHeight
        } else {
            wakeUpCoordinator.setNotificationsVisibleForExpansion(
                height
                    > lockscreenShadeTransitionController.distanceUntilShowingPulsingNotifications,
                true /* animate */,
                true /* increaseSpeed */)
        }
        lockscreenShadeTransitionController.setPulseHeight(expansionHeight, animate = false)
    }

    private fun captureStartingChild(x: Float, y: Float) {
        if (mStartingChild == null && !bypassController.bypassEnabled) {
            mStartingChild = findView(x, y)
            if (mStartingChild != null) {
                setUserLocked(mStartingChild!!, true)
            }
        }
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

    private fun cancelExpansion() {
        isExpanding = false
        falsingCollector.onExpansionFromPulseStopped()
        if (mStartingChild != null) {
            reset(mStartingChild!!)
            mStartingChild = null
        }
        lockscreenShadeTransitionController.finishPulseAnimation(cancelled = true)
        wakeUpCoordinator.setNotificationsVisibleForExpansion(false /* visible */,
                true /* animate */,
                false /* increaseSpeed */)
    }

    private fun findView(x: Float, y: Float): ExpandableView? {
        var totalX = x
        var totalY = y
        stackScrollerController.getLocationOnScreen(mTemp2)
        totalX += mTemp2[0].toFloat()
        totalY += mTemp2[1].toFloat()
        val childAtRawPosition = stackScrollerController.getChildAtRawPosition(totalX, totalY)
        return if (childAtRawPosition != null && childAtRawPosition.isContentExpandable) {
            childAtRawPosition
        } else null
    }

    fun setUp(stackScrollerController: NotificationStackScrollLayoutController) {
        this.stackScrollerController = stackScrollerController
    }

    fun setPulsing(pulsing: Boolean) {
        mPulsing = pulsing
    }

    fun onStartedWakingUp() {
        isWakingToShadeLocked = false
    }
}
