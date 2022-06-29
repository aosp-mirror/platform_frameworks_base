package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.util.MathUtils
import android.view.animation.PathInterpolator
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class SplitShadeLockScreenOverScroller
@AssistedInject
constructor(
    configurationController: ConfigurationController,
    private val context: Context,
    private val scrimController: ScrimController,
    private val statusBarStateController: SysuiStatusBarStateController,
    @Assisted private val qS: QS,
    @Assisted private val nsslController: NotificationStackScrollLayoutController
) : LockScreenShadeOverScroller {

    private var releaseOverScrollAnimator: Animator? = null
    private var transitionToFullShadeDistance = 0
    private var releaseOverScrollDuration = 0L
    private var maxOverScrollAmount = 0
    private var previousOverscrollAmount = 0

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            })
    }

    private fun updateResources() {
        val resources = context.resources
        transitionToFullShadeDistance =
            resources.getDimensionPixelSize(R.dimen.lockscreen_shade_full_transition_distance)
        maxOverScrollAmount =
            resources.getDimensionPixelSize(R.dimen.lockscreen_shade_max_over_scroll_amount)
        releaseOverScrollDuration =
            resources.getInteger(R.integer.lockscreen_shade_over_scroll_release_duration).toLong()
    }

    override var expansionDragDownAmount: Float = 0f
        set(dragDownAmount) {
            if (field == dragDownAmount) {
                return
            }
            field = dragDownAmount
            if (shouldOverscroll()) {
                overScroll(dragDownAmount)
            } else if (shouldReleaseOverscroll()) {
                releaseOverScroll()
            }
        }

    private fun shouldOverscroll() = statusBarStateController.state == StatusBarState.KEYGUARD

    private fun shouldReleaseOverscroll() = !shouldOverscroll() && previousOverscrollAmount != 0

    private fun overScroll(dragDownAmount: Float) {
        val overscrollAmount: Int = calculateOverscrollAmount(dragDownAmount)
        applyOverscroll(overscrollAmount)
        previousOverscrollAmount = overscrollAmount
    }

    private fun applyOverscroll(overscrollAmount: Int) {
        qS.setOverScrollAmount(overscrollAmount)
        scrimController.setNotificationsOverScrollAmount(overscrollAmount)
        nsslController.setOverScrollAmount(overscrollAmount)
    }

    private fun calculateOverscrollAmount(dragDownAmount: Float): Int {
        val fullHeight: Int = nsslController.height
        val fullHeightProgress: Float = MathUtils.saturate(dragDownAmount / fullHeight)
        val overshootStart: Float = transitionToFullShadeDistance / fullHeight.toFloat()
        val overShootTransitionProgress: Float =
            Interpolators.getOvershootInterpolation(
                fullHeightProgress, OVER_SHOOT_AMOUNT, overshootStart)
        return (overShootTransitionProgress * maxOverScrollAmount).toInt()
    }

    private fun releaseOverScroll() {
        val animator = ValueAnimator.ofInt(previousOverscrollAmount, 0)
        animator.addUpdateListener {
            val overScrollAmount = it.animatedValue as Int
            qS.setOverScrollAmount(overScrollAmount)
            scrimController.setNotificationsOverScrollAmount(overScrollAmount)
            nsslController.setOverScrollAmount(overScrollAmount)
        }
        animator.interpolator = RELEASE_OVER_SCROLL_INTERPOLATOR
        animator.duration = releaseOverScrollDuration
        animator.start()
        releaseOverScrollAnimator = animator
        previousOverscrollAmount = 0
    }

    @VisibleForTesting
    internal fun finishAnimations() {
        releaseOverScrollAnimator?.end()
        releaseOverScrollAnimator = null
    }

    @AssistedFactory
    fun interface Factory {
        fun create(
            qS: QS,
            nsslController: NotificationStackScrollLayoutController
        ): SplitShadeLockScreenOverScroller
    }

    companion object {
        private const val OVER_SHOOT_AMOUNT = 0.6f
        private val RELEASE_OVER_SCROLL_INTERPOLATOR = PathInterpolator(0.17f, 0f, 0f, 1f)
    }
}
