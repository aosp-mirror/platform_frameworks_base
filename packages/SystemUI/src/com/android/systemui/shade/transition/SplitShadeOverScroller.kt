package com.android.systemui.shade.transition

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.util.MathUtils
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.panelstate.PanelState
import com.android.systemui.statusbar.phone.panelstate.STATE_CLOSED
import com.android.systemui.statusbar.phone.panelstate.STATE_OPENING
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter

class SplitShadeOverScroller
@AssistedInject
constructor(
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    private val context: Context,
    private val scrimController: ScrimController,
    @Assisted private val qSProvider: () -> QS,
    @Assisted private val nsslControllerProvider: () -> NotificationStackScrollLayoutController
) : ShadeOverScroller {

    private var releaseOverScrollDuration = 0L
    private var maxOverScrollAmount = 0
    private var previousOverscrollAmount = 0
    private var dragDownAmount: Float = 0f
    @PanelState private var panelState: Int = STATE_CLOSED
    private var releaseOverScrollAnimator: Animator? = null

    private val qS: QS
        get() = qSProvider()

    private val nsslController: NotificationStackScrollLayoutController
        get() = nsslControllerProvider()

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            })
        dumpManager.registerDumpable("SplitShadeOverScroller") { printWriter, _ ->
            dump(printWriter)
        }
    }

    private fun updateResources() {
        val resources = context.resources
        maxOverScrollAmount = resources.getDimensionPixelSize(R.dimen.shade_max_over_scroll_amount)
        releaseOverScrollDuration =
            resources.getInteger(R.integer.lockscreen_shade_over_scroll_release_duration).toLong()
    }

    override fun onPanelStateChanged(@PanelState newPanelState: Int) {
        if (shouldReleaseOverscroll(previousState = panelState, newState = newPanelState)) {
            releaseOverScroll()
        }
        panelState = newPanelState
    }

    override fun onDragDownAmountChanged(newDragDownAmount: Float) {
        if (dragDownAmount == newDragDownAmount) {
            return
        }
        dragDownAmount = newDragDownAmount
        if (shouldOverscroll()) {
            overScroll(newDragDownAmount)
        }
    }

    private fun shouldOverscroll() = panelState == STATE_OPENING

    private fun shouldReleaseOverscroll(@PanelState previousState: Int, @PanelState newState: Int) =
        previousState == STATE_OPENING && newState != STATE_OPENING

    private fun overScroll(dragDownAmount: Float) {
        val overscrollAmount: Int = calculateOverscrollAmount(dragDownAmount)
        applyOverscroll(overscrollAmount)
        previousOverscrollAmount = overscrollAmount
    }

    private fun calculateOverscrollAmount(dragDownAmount: Float): Int {
        val fullHeight: Int = nsslController.height
        val fullHeightProgress: Float = MathUtils.saturate(dragDownAmount / fullHeight)
        return (fullHeightProgress * maxOverScrollAmount).toInt()
    }

    private fun applyOverscroll(overscrollAmount: Int) {
        qS.setOverScrollAmount(overscrollAmount)
        scrimController.setNotificationsOverScrollAmount(overscrollAmount)
        nsslController.setOverScrollAmount(overscrollAmount)
    }

    private fun releaseOverScroll() {
        val animator = ValueAnimator.ofInt(previousOverscrollAmount, 0)
        animator.addUpdateListener {
            val overScrollAmount = it.animatedValue as Int
            qS.setOverScrollAmount(overScrollAmount)
            scrimController.setNotificationsOverScrollAmount(overScrollAmount)
            nsslController.setOverScrollAmount(overScrollAmount)
        }
        animator.interpolator = Interpolators.STANDARD
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

    private fun dump(pw: PrintWriter) {
        pw.println(
            """
            SplitShadeOverScroller:
                Resources:
                    releaseOverScrollDuration: $releaseOverScrollDuration
                    maxOverScrollAmount: $maxOverScrollAmount
                State:
                    previousOverscrollAmount: $previousOverscrollAmount
                    dragDownAmount: $dragDownAmount
                    panelState: $panelState
            """.trimIndent())
    }

    @AssistedFactory
    fun interface Factory {
        fun create(
            qSProvider: () -> QS,
            nsslControllerProvider: () -> NotificationStackScrollLayoutController
        ): SplitShadeOverScroller
    }
}
