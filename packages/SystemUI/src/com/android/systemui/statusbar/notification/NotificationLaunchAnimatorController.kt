package com.android.systemui.statusbar.notification

import android.view.ViewGroup
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.LaunchAnimator
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.NotificationShadeWindowViewController
import com.android.systemui.statusbar.policy.HeadsUpUtil
import kotlin.math.ceil
import kotlin.math.max

/** A provider of [NotificationLaunchAnimatorController]. */
class NotificationLaunchAnimatorControllerProvider(
    private val notificationShadeWindowViewController: NotificationShadeWindowViewController,
    private val notificationListContainer: NotificationListContainer,
    private val headsUpManager: HeadsUpManagerPhone
) {
    fun getAnimatorController(
        notification: ExpandableNotificationRow
    ): NotificationLaunchAnimatorController {
        return NotificationLaunchAnimatorController(
            notificationShadeWindowViewController,
            notificationListContainer,
            headsUpManager,
            notification
        )
    }
}

/**
 * An [ActivityLaunchAnimator.Controller] that animates an [ExpandableNotificationRow]. An instance
 * of this class can be passed to [ActivityLaunchAnimator.startIntentWithAnimation] to animate a
 * notification expanding into an opening window.
 */
class NotificationLaunchAnimatorController(
    private val notificationShadeWindowViewController: NotificationShadeWindowViewController,
    private val notificationListContainer: NotificationListContainer,
    private val headsUpManager: HeadsUpManagerPhone,
    private val notification: ExpandableNotificationRow
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

        val clipStartLocation = notificationListContainer.getTopClippingStartLocation()
        val roundedTopClipping = Math.max(clipStartLocation - location[1], 0)
        val windowTop = location[1] + roundedTopClipping
        val topCornerRadius = if (roundedTopClipping > 0) {
            // Because the rounded Rect clipping is complex, we start the top rounding at
            // 0, which is pretty close to matching the real clipping.
            // We'd have to clipOut the overlaid drawable too with the outer rounded rect in case
            // if we'd like to have this perfect, but this is close enough.
            0f
        } else {
            notification.currentBackgroundRadiusTop
        }
        val params = ExpandAnimationParameters(
            top = windowTop,
            bottom = location[1] + height,
            left = location[0],
            right = location[0] + notification.width,
            topCornerRadius = topCornerRadius,
            bottomCornerRadius = notification.currentBackgroundRadiusBottom
        )

        params.startTranslationZ = notification.translationZ
        params.startNotificationTop = notification.translationY
        params.startRoundedTopClipping = roundedTopClipping
        params.startClipTopAmount = notification.clipTopAmount
        if (notification.isChildInGroup) {
            params.startNotificationTop += notification.notificationParent.translationY
            val parentRoundedClip = Math.max(
                clipStartLocation - notification.notificationParent.locationOnScreen[1], 0)
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
        notificationShadeWindowViewController.setExpandAnimationRunning(willAnimate)
        notificationEntry.isExpandAnimationRunning = willAnimate

        if (!willAnimate) {
            removeHun(animate = true)
        }
    }

    private fun removeHun(animate: Boolean) {
        if (!headsUpManager.isAlerting(notificationKey)) {
            return
        }

        HeadsUpUtil.setNeedsHeadsUpDisappearAnimationAfterClick(notification, animate)
        headsUpManager.removeNotification(notificationKey, true /* releaseImmediately */, animate)
    }

    override fun onLaunchAnimationCancelled() {
        // TODO(b/184121838): Should we call InteractionJankMonitor.cancel if the animation started
        // here?
        notificationShadeWindowViewController.setExpandAnimationRunning(false)
        notificationEntry.isExpandAnimationRunning = false
        removeHun(animate = true)
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        notification.isExpandAnimationRunning = true
        notificationListContainer.setExpandingNotification(notification)

        InteractionJankMonitor.getInstance().begin(notification,
            InteractionJankMonitor.CUJ_NOTIFICATION_APP_START)
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        InteractionJankMonitor.getInstance().end(InteractionJankMonitor.CUJ_NOTIFICATION_APP_START)

        notification.isExpandAnimationRunning = false
        notificationShadeWindowViewController.setExpandAnimationRunning(false)
        notificationEntry.isExpandAnimationRunning = false
        notificationListContainer.setExpandingNotification(null)
        applyParams(null)
        removeHun(animate = false)
    }

    private fun applyParams(params: ExpandAnimationParameters?) {
        notification.applyExpandAnimationParams(params)
        notificationListContainer.applyExpandAnimationParams(params)
    }

    override fun onLaunchAnimationProgress(
        state: LaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        val params = state as ExpandAnimationParameters
        params.progress = progress
        params.linearProgress = linearProgress

        applyParams(params)
    }
}
