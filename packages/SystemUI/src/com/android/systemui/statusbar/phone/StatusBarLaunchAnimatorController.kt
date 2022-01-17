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
    private val statusBar: StatusBar,
    private val isLaunchForActivity: Boolean = true
) : ActivityLaunchAnimator.Controller by delegate {
    // Always sync the opening window with the shade, given that we draw a hole punch in the shade
    // of the same size and position as the opening app to make it visible.
    override val openingWindowSyncView: View?
        get() = statusBar.notificationShadeWindowView

    override fun onIntentStarted(willAnimate: Boolean) {
        delegate.onIntentStarted(willAnimate)
        if (!willAnimate) {
            statusBar.collapsePanelOnMainThread()
        }
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        delegate.onLaunchAnimationStart(isExpandingFullyAbove)
        statusBar.notificationPanelViewController.setIsLaunchAnimationRunning(true)
        if (!isExpandingFullyAbove) {
            statusBar.collapsePanelWithDuration(
                ActivityLaunchAnimator.TIMINGS.totalDuration.toInt())
        }
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        delegate.onLaunchAnimationEnd(isExpandingFullyAbove)
        statusBar.notificationPanelViewController.setIsLaunchAnimationRunning(false)
        statusBar.onLaunchAnimationEnd(isExpandingFullyAbove)
    }

    override fun onLaunchAnimationProgress(
        state: LaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        delegate.onLaunchAnimationProgress(state, progress, linearProgress)
        statusBar.notificationPanelViewController.applyLaunchAnimationProgress(linearProgress)
    }

    override fun onLaunchAnimationCancelled() {
        delegate.onLaunchAnimationCancelled()
        statusBar.onLaunchAnimationCancelled(isLaunchForActivity)
    }
}