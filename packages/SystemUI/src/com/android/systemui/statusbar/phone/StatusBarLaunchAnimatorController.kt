package com.android.systemui.statusbar.phone

import com.android.systemui.animation.ActivityLaunchAnimator

/**
 * A [ActivityLaunchAnimator.Controller] that takes care of collapsing the status bar at the right
 * time.
 */
class StatusBarLaunchAnimatorController(
    private val delegate: ActivityLaunchAnimator.Controller,
    private val statusBar: StatusBar,
    private val isLaunchForActivity: Boolean = true
) : ActivityLaunchAnimator.Controller by delegate {
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
            statusBar.collapsePanelWithDuration(ActivityLaunchAnimator.ANIMATION_DURATION.toInt())
        }
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        delegate.onLaunchAnimationEnd(isExpandingFullyAbove)
        statusBar.notificationPanelViewController.setIsLaunchAnimationRunning(false)
        statusBar.onLaunchAnimationEnd(isExpandingFullyAbove)
    }

    override fun onLaunchAnimationProgress(
        state: ActivityLaunchAnimator.State,
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