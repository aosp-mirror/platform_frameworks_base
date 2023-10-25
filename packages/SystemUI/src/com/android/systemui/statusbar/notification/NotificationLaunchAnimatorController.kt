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

package com.android.systemui.statusbar.notification

import android.util.Log
import android.view.ViewGroup
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.LaunchAnimator
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.HeadsUpUtil
import kotlin.math.ceil
import kotlin.math.max

private const val TAG = "NotificationLaunchAnimatorController"

/** A provider of [NotificationLaunchAnimatorController]. */
class NotificationLaunchAnimatorControllerProvider(
    private val notificationLaunchAnimationInteractor: NotificationLaunchAnimationInteractor,
    private val notificationListContainer: NotificationListContainer,
    private val headsUpManager: HeadsUpManager,
    private val jankMonitor: InteractionJankMonitor
) {
    @JvmOverloads
    fun getAnimatorController(
        notification: ExpandableNotificationRow,
        onFinishAnimationCallback: Runnable? = null
    ): NotificationLaunchAnimatorController {
        return NotificationLaunchAnimatorController(
            notificationLaunchAnimationInteractor,
            notificationListContainer,
            headsUpManager,
            notification,
            jankMonitor,
            onFinishAnimationCallback
        )
    }
}

/**
 * An [ActivityLaunchAnimator.Controller] that animates an [ExpandableNotificationRow]. An instance
 * of this class can be passed to [ActivityLaunchAnimator.startIntentWithAnimation] to animate a
 * notification expanding into an opening window.
 */
class NotificationLaunchAnimatorController(
    private val notificationLaunchAnimationInteractor: NotificationLaunchAnimationInteractor,
    private val notificationListContainer: NotificationListContainer,
    private val headsUpManager: HeadsUpManager,
    private val notification: ExpandableNotificationRow,
    private val jankMonitor: InteractionJankMonitor,
    private val onFinishAnimationCallback: Runnable?
) : ActivityLaunchAnimator.Controller {

    companion object {
        const val ANIMATION_DURATION_TOP_ROUNDING = 100L
    }

    private val notificationEntry = notification.entry
    private val notificationKey = notificationEntry.sbn.key

    override var launchContainer: ViewGroup
        get() = notification.rootView as ViewGroup
        set(ignored) {
            // Do nothing. Notifications are always animated inside their rootView.
        }

    override fun createAnimatorState(): LaunchAnimator.State {
        // If the notification panel is collapsed, the clip may be larger than the height.
        val height = max(0, notification.actualHeight - notification.clipBottomAmount)
        val location = notification.locationOnScreen

        val clipStartLocation = notificationListContainer.topClippingStartLocation
        val roundedTopClipping = (clipStartLocation - location[1]).coerceAtLeast(0)
        val windowTop = location[1] + roundedTopClipping
        val topCornerRadius =
            if (roundedTopClipping > 0) {
                // Because the rounded Rect clipping is complex, we start the top rounding at
                // 0, which is pretty close to matching the real clipping.
                // We'd have to clipOut the overlaid drawable too with the outer rounded rect in
                // case
                // if we'd like to have this perfect, but this is close enough.
                0f
            } else {
                notification.topCornerRadius
            }
        val params =
            LaunchAnimationParameters(
                top = windowTop,
                bottom = location[1] + height,
                left = location[0],
                right = location[0] + notification.width,
                topCornerRadius = topCornerRadius,
                bottomCornerRadius = notification.bottomCornerRadius
            )

        params.startTranslationZ = notification.translationZ
        params.startNotificationTop = location[1]
        params.notificationParentTop =
            notificationListContainer
                .getViewParentForNotification(notificationEntry)
                .locationOnScreen[1]
        params.startRoundedTopClipping = roundedTopClipping
        params.startClipTopAmount = notification.clipTopAmount
        if (notification.isChildInGroup) {
            val locationOnScreen = notification.notificationParent.locationOnScreen[1]
            val parentRoundedClip = (clipStartLocation - locationOnScreen).coerceAtLeast(0)
            params.parentStartRoundedTopClipping = parentRoundedClip

            val parentClip = notification.notificationParent.clipTopAmount
            params.parentStartClipTopAmount = parentClip

            // We need to calculate how much the child is clipped by the parent because children
            // always have 0 clipTopAmount
            if (parentClip != 0) {
                val childClip = parentClip - notification.translationY
                if (childClip > 0) {
                    params.startClipTopAmount = ceil(childClip.toDouble()).toInt()
                }
            }
        }

        return params
    }

    override fun onIntentStarted(willAnimate: Boolean) {
        if (ActivityLaunchAnimator.DEBUG_LAUNCH_ANIMATION) {
            Log.d(TAG, "onIntentStarted(willAnimate=$willAnimate)")
        }
        notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(willAnimate)
        notificationEntry.isExpandAnimationRunning = willAnimate

        if (!willAnimate) {
            removeHun(animate = true)
            onFinishAnimationCallback?.run()
        }
    }

    private val headsUpNotificationRow: ExpandableNotificationRow?
        get() {
            val summaryEntry = notificationEntry.parent?.summary

            return when {
                headsUpManager.isAlerting(notificationKey) -> notification
                summaryEntry == null -> null
                headsUpManager.isAlerting(summaryEntry.key) -> summaryEntry.row
                else -> null
            }
        }

    private fun removeHun(animate: Boolean) {
        val row = headsUpNotificationRow ?: return

        // TODO: b/297247841 - Call on the row we're removing, which may differ from notification.
        HeadsUpUtil.setNeedsHeadsUpDisappearAnimationAfterClick(notification, animate)

        headsUpManager.removeNotification(row.entry.key, true /* releaseImmediately */, animate)
    }

    override fun onLaunchAnimationCancelled(newKeyguardOccludedState: Boolean?) {
        if (ActivityLaunchAnimator.DEBUG_LAUNCH_ANIMATION) {
            Log.d(TAG, "onLaunchAnimationCancelled()")
        }

        // TODO(b/184121838): Should we call InteractionJankMonitor.cancel if the animation started
        // here?
        notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(false)
        notificationEntry.isExpandAnimationRunning = false
        removeHun(animate = true)
        onFinishAnimationCallback?.run()
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        notification.isExpandAnimationRunning = true
        notificationListContainer.setExpandingNotification(notification)

        jankMonitor.begin(notification, InteractionJankMonitor.CUJ_NOTIFICATION_APP_START)
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        if (ActivityLaunchAnimator.DEBUG_LAUNCH_ANIMATION) {
            Log.d(TAG, "onLaunchAnimationEnd()")
        }
        jankMonitor.end(InteractionJankMonitor.CUJ_NOTIFICATION_APP_START)

        notification.isExpandAnimationRunning = false
        notificationLaunchAnimationInteractor.setIsLaunchAnimationRunning(false)
        notificationEntry.isExpandAnimationRunning = false
        notificationListContainer.setExpandingNotification(null)
        applyParams(null)
        removeHun(animate = false)
        onFinishAnimationCallback?.run()
    }

    private fun applyParams(params: LaunchAnimationParameters?) {
        notification.applyLaunchAnimationParams(params)
        notificationListContainer.applyLaunchAnimationParams(params)
    }

    override fun onLaunchAnimationProgress(
        state: LaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        val params = state as LaunchAnimationParameters
        params.progress = progress
        params.linearProgress = linearProgress

        applyParams(params)
    }
}
