package com.android.systemui.statusbar

import android.content.Context
import android.content.res.Configuration
import android.util.MathUtils
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class SingleShadeLockScreenOverScroller
@AssistedInject
constructor(
    configurationController: ConfigurationController,
    private val context: Context,
    private val statusBarStateController: SysuiStatusBarStateController,
    @Assisted private val nsslController: NotificationStackScrollLayoutController
) : LockScreenShadeOverScroller {

    private var maxOverScrollAmount = 0
    private var totalDistanceForFullShadeTransition = 0

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
        totalDistanceForFullShadeTransition =
            resources.getDimensionPixelSize(R.dimen.lockscreen_shade_qs_transition_distance)
        maxOverScrollAmount =
            resources.getDimensionPixelSize(R.dimen.lockscreen_shade_max_over_scroll_amount)
    }

    override var expansionDragDownAmount: Float = 0f
        set(value) {
            if (value == field) {
                return
            }
            field = value
            overScroll()
        }

    private fun overScroll() {
        var extraTopInset = 0.0f
        if (statusBarStateController.state == StatusBarState.KEYGUARD) {
            val viewHeight = nsslController.height
            val overallProgress = MathUtils.saturate(expansionDragDownAmount / viewHeight)
            val transitionProgress =
                Interpolators.getOvershootInterpolation(
                    overallProgress,
                    0.6f,
                    totalDistanceForFullShadeTransition.toFloat() / viewHeight.toFloat())
            extraTopInset = transitionProgress * maxOverScrollAmount
        }
        nsslController.setOverScrollAmount(extraTopInset.toInt())
    }

    @AssistedFactory
    fun interface Factory {
        fun create(
            nsslController: NotificationStackScrollLayoutController
        ): SingleShadeLockScreenOverScroller
    }
}
