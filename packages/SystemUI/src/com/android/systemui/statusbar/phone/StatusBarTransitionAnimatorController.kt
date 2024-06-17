package com.android.systemui.statusbar.phone

import android.view.View
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.animation.TransitionAnimator.Companion.getProgress
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment

/**
 * A [ActivityTransitionAnimator.Controller] that takes care of collapsing the status bar at the
 * right time.
 */
class StatusBarTransitionAnimatorController(
    private val delegate: ActivityTransitionAnimator.Controller,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val shadeController: ShadeController,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val commandQueue: CommandQueue,
    @DisplayId private val displayId: Int,
    private val isLaunchForActivity: Boolean = true
) : ActivityTransitionAnimator.Controller by delegate {
    private var hideIconsDuringLaunchAnimation: Boolean = true

    // Always sync the opening window with the shade, given that we draw a hole punch in the shade
    // of the same size and position as the opening app to make it visible.
    override val openingWindowSyncView: View?
        get() = notificationShadeWindowController.windowRootView

    override fun onIntentStarted(willAnimate: Boolean) {
        delegate.onIntentStarted(willAnimate)
        if (willAnimate) {
            shadeAnimationInteractor.setIsLaunchingActivity(true)
        } else {
            shadeController.collapseOnMainThread()
        }
    }

    override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
        delegate.onTransitionAnimationStart(isExpandingFullyAbove)
        shadeAnimationInteractor.setIsLaunchingActivity(true)
        if (!isExpandingFullyAbove) {
            shadeController.collapseWithDuration(
                ActivityTransitionAnimator.TIMINGS.totalDuration.toInt()
            )
        }
    }

    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
        shadeAnimationInteractor.setIsLaunchingActivity(false)
        shadeController.onLaunchAnimationEnd(isExpandingFullyAbove)
    }

    override fun onTransitionAnimationProgress(
        state: TransitionAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        delegate.onTransitionAnimationProgress(state, progress, linearProgress)
        val hideIcons =
            getProgress(
                ActivityTransitionAnimator.TIMINGS,
                linearProgress,
                ANIMATION_DELAY_ICON_FADE_IN,
                100
            ) == 0.0f
        if (hideIcons != hideIconsDuringLaunchAnimation) {
            hideIconsDuringLaunchAnimation = hideIcons
            if (!hideIcons) {
                commandQueue.recomputeDisableFlags(displayId, true /* animate */)
            }
        }
    }

    override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
        delegate.onTransitionAnimationCancelled()
        shadeAnimationInteractor.setIsLaunchingActivity(false)
        shadeController.onLaunchAnimationCancelled(isLaunchForActivity)
    }

    companion object {
        val ANIMATION_DELAY_ICON_FADE_IN =
            (ActivityTransitionAnimator.TIMINGS.totalDuration -
                CollapsedStatusBarFragment.FADE_IN_DURATION -
                CollapsedStatusBarFragment.FADE_IN_DELAY -
                48)
    }
}
