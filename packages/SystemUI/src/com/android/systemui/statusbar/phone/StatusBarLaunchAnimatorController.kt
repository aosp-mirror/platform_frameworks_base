package com.android.systemui.statusbar.phone

import android.view.View
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.LaunchAnimator

/**
 * A [ActivityLaunchAnimator.Controller] that takes care of collapsing the status bar at the right
 * time.
 */
class StatusBarLaunchAnimatorController(
    private val delegate: ActivityLaunchAnimator.Controller,
    private val centralSurfaces: CentralSurfaces,
    private val isLaunchForActivity: Boolean = true
) : ActivityLaunchAnimator.Controller by delegate {
    // Always sync the opening window with the shade, given that we draw a hole punch in the shade
    // of the same size and position as the opening app to make it visible.
    override val openingWindowSyncView: View?
        get() = centralSurfaces.notificationShadeWindowView

    override fun onIntentStarted(willAnimate: Boolean) {
        delegate.onIntentStarted(willAnimate)
        if (willAnimate) {
            centralSurfaces.notificationPanelViewController.setIsLaunchAnimationRunning(true)
        } else {
            centralSurfaces.collapsePanelOnMainThread()
        }
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        delegate.onLaunchAnimationStart(isExpandingFullyAbove)
        centralSurfaces.notificationPanelViewController.setIsLaunchAnimationRunning(true)
        if (!isExpandingFullyAbove) {
            centralSurfaces.collapsePanelWithDuration(
                ActivityLaunchAnimator.TIMINGS.totalDuration.toInt())
        }
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        delegate.onLaunchAnimationEnd(isExpandingFullyAbove)
        centralSurfaces.notificationPanelViewController.setIsLaunchAnimationRunning(false)
        centralSurfaces.onLaunchAnimationEnd(isExpandingFullyAbove)
    }

    override fun onLaunchAnimationProgress(
        state: LaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        delegate.onLaunchAnimationProgress(state, progress, linearProgress)
        centralSurfaces.notificationPanelViewController.applyLaunchAnimationProgress(linearProgress)
    }

    override fun onLaunchAnimationCancelled() {
        delegate.onLaunchAnimationCancelled()
        centralSurfaces.notificationPanelViewController.setIsLaunchAnimationRunning(false)
        centralSurfaces.onLaunchAnimationCancelled(isLaunchForActivity)
    }
}