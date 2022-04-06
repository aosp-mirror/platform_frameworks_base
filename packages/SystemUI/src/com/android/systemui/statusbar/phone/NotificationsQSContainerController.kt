package com.android.systemui.statusbar.phone

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.ViewController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.function.Consumer
import javax.inject.Inject

@VisibleForTesting
internal const val INSET_DEBOUNCE_MILLIS = 500L

class NotificationsQSContainerController @Inject constructor(
    view: NotificationsQuickSettingsContainer,
    private val navigationModeController: NavigationModeController,
    private val overviewProxyService: OverviewProxyService,
    private val featureFlags: FeatureFlags,
    @Main private val delayableExecutor: DelayableExecutor
) : ViewController<NotificationsQuickSettingsContainer>(view), QSContainerController {

    var qsExpanded = false
        set(value) {
            if (field != value) {
                field = value
                mView.invalidate()
            }
        }
    private var splitShadeEnabled = false
    private var isQSDetailShowing = false
    private var isQSCustomizing = false
    private var isQSCustomizerAnimating = false

    private var largeScreenShadeHeaderHeight = 0
    private var largeScreenShadeHeaderActive = false
    private var notificationsBottomMargin = 0
    private var scrimShadeBottomMargin = 0
    private var bottomStableInsets = 0
    private var bottomCutoutInsets = 0
    private var panelMarginHorizontal = 0
    private var topMargin = 0

    private val useCombinedQSHeaders = featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)

    private var isGestureNavigation = true
    private var taskbarVisible = false
    private val taskbarVisibilityListener: OverviewProxyListener = object : OverviewProxyListener {
        override fun onTaskbarStatusUpdated(visible: Boolean, stashed: Boolean) {
            taskbarVisible = visible
        }
    }

    // With certain configuration changes (like light/dark changes), the nav bar will disappear
    // for a bit, causing `bottomStableInsets` to be unstable for some time. Debounce the value
    // for 500ms.
    // All interactions with this object happen in the main thread.
    private val delayedInsetSetter = object : Runnable, Consumer<WindowInsets> {
        private var canceller: Runnable? = null
        private var stableInsets = 0
        private var cutoutInsets = 0

        override fun accept(insets: WindowInsets) {
            // when taskbar is visible, stableInsetBottom will include its height
            stableInsets = insets.stableInsetBottom
            cutoutInsets = insets.displayCutout?.safeInsetBottom ?: 0
            canceller?.run()
            canceller = delayableExecutor.executeDelayed(this, INSET_DEBOUNCE_MILLIS)
        }

        override fun run() {
            bottomStableInsets = stableInsets
            bottomCutoutInsets = cutoutInsets
            updateBottomSpacing()
        }
    }

    override fun onInit() {
        val currentMode: Int = navigationModeController.addListener { mode: Int ->
            isGestureNavigation = QuickStepContract.isGesturalMode(mode)
        }
        isGestureNavigation = QuickStepContract.isGesturalMode(currentMode)
    }

    public override fun onViewAttached() {
        updateResources()
        overviewProxyService.addCallback(taskbarVisibilityListener)
        mView.setInsetsChangedListener(delayedInsetSetter)
        mView.setQSFragmentAttachedListener { qs: QS -> qs.setContainerController(this) }
        mView.setConfigurationChangedListener { updateResources() }
    }

    override fun onViewDetached() {
        overviewProxyService.removeCallback(taskbarVisibilityListener)
        mView.removeOnInsetsChangedListener()
        mView.removeQSFragmentAttachedListener()
        mView.setConfigurationChangedListener(null)
    }

    fun updateResources() {
        val newSplitShadeEnabled = LargeScreenUtils.shouldUseSplitNotificationShade(resources)
        val splitShadeEnabledChanged = newSplitShadeEnabled != splitShadeEnabled
        splitShadeEnabled = newSplitShadeEnabled
        largeScreenShadeHeaderActive = LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources)
        notificationsBottomMargin = resources.getDimensionPixelSize(
                R.dimen.notification_panel_margin_bottom)
        largeScreenShadeHeaderHeight =
                resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height)
        panelMarginHorizontal = resources.getDimensionPixelSize(
                R.dimen.notification_panel_margin_horizontal)
        topMargin = if (largeScreenShadeHeaderActive) {
            largeScreenShadeHeaderHeight
        } else {
            resources.getDimensionPixelSize(R.dimen.notification_panel_margin_top)
        }
        updateConstraints()
        if (splitShadeEnabledChanged) {
            // Let's do it at the end when all margins/paddings were already applied.
            // We need to updateBottomSpacing() in case device configuration changed while showing
            // QS details/customizer
            updateBottomSpacing()
        }
        val previousScrimShadeBottomMargin = scrimShadeBottomMargin
        scrimShadeBottomMargin = resources.getDimensionPixelSize(
            R.dimen.split_shade_notifications_scrim_margin_bottom
        )

        if (previousScrimShadeBottomMargin != scrimShadeBottomMargin) {
            updateBottomSpacing()
        }
    }

    override fun setCustomizerAnimating(animating: Boolean) {
        if (isQSCustomizerAnimating != animating) {
            isQSCustomizerAnimating = animating
            mView.invalidate()
        }
    }

    override fun setCustomizerShowing(showing: Boolean) {
        isQSCustomizing = showing
        updateBottomSpacing()
    }

    override fun setDetailShowing(showing: Boolean) {
        isQSDetailShowing = showing
        updateBottomSpacing()
    }

    private fun updateBottomSpacing() {
        val (containerPadding, notificationsMargin) = calculateBottomSpacing()
        var qsScrollPaddingBottom = 0
        if (!(isQSCustomizing || isQSDetailShowing)) {
            // With the new footer, we also want this padding in the bottom in these cases
            qsScrollPaddingBottom = if (splitShadeEnabled) {
                notificationsMargin - scrimShadeBottomMargin
            } else {
                bottomStableInsets
            }
        }
        mView.setPadding(0, 0, 0, containerPadding)
        mView.setNotificationsMarginBottom(notificationsMargin)
        mView.setQSContainerPaddingBottom(qsScrollPaddingBottom)
    }

    private fun calculateBottomSpacing(): Pair<Int, Int> {
        val containerPadding: Int
        var stackScrollMargin = notificationsBottomMargin
        if (splitShadeEnabled) {
            if (isGestureNavigation) {
                // only default cutout padding, taskbar always hides
                containerPadding = bottomCutoutInsets
            } else if (taskbarVisible) {
                // navigation buttons + visible taskbar means we're NOT on homescreen
                containerPadding = bottomStableInsets
            } else {
                // navigation buttons + hidden taskbar means we're on homescreen
                containerPadding = 0
                // we need extra margin for notifications as navigation buttons are below them
                stackScrollMargin = bottomStableInsets + notificationsBottomMargin
            }
        } else {
            if (isQSCustomizing || isQSDetailShowing) {
                // Clear out bottom paddings/margins so the qs customization can be full height.
                containerPadding = 0
                stackScrollMargin = 0
            } else if (isGestureNavigation) {
                containerPadding = bottomCutoutInsets
            } else if (taskbarVisible) {
                containerPadding = bottomStableInsets
            } else {
                containerPadding = 0
            }
        }
        return containerPadding to stackScrollMargin
    }

    fun updateConstraints() {
        // To change the constraints at runtime, all children of the ConstraintLayout must have ids
        ensureAllViewsHaveIds(mView)
        val constraintSet = ConstraintSet()
        constraintSet.clone(mView)
        setKeyguardStatusViewConstraints(constraintSet)
        setQsConstraints(constraintSet)
        setNotificationsConstraints(constraintSet)
        setLargeScreenShadeHeaderConstraints(constraintSet)
        mView.applyConstraints(constraintSet)
    }

    private fun setLargeScreenShadeHeaderConstraints(constraintSet: ConstraintSet) {
        if (largeScreenShadeHeaderActive) {
            constraintSet.constrainHeight(R.id.split_shade_status_bar, largeScreenShadeHeaderHeight)
        } else {
            if (useCombinedQSHeaders) {
                constraintSet.constrainHeight(R.id.split_shade_status_bar, WRAP_CONTENT)
            }
        }
    }

    private fun setNotificationsConstraints(constraintSet: ConstraintSet) {
        val startConstraintId = if (splitShadeEnabled) R.id.qs_edge_guideline else PARENT_ID
        constraintSet.apply {
            connect(R.id.notification_stack_scroller, START, startConstraintId, START)
            setMargin(R.id.notification_stack_scroller, START,
                    if (splitShadeEnabled) 0 else panelMarginHorizontal)
            setMargin(R.id.notification_stack_scroller, END, panelMarginHorizontal)
            setMargin(R.id.notification_stack_scroller, TOP, topMargin)
            setMargin(R.id.notification_stack_scroller, BOTTOM, notificationsBottomMargin)
        }
    }

    private fun setQsConstraints(constraintSet: ConstraintSet) {
        val endConstraintId = if (splitShadeEnabled) R.id.qs_edge_guideline else PARENT_ID
        constraintSet.apply {
            connect(R.id.qs_frame, END, endConstraintId, END)
            setMargin(R.id.qs_frame, START, if (splitShadeEnabled) 0 else panelMarginHorizontal)
            setMargin(R.id.qs_frame, END, if (splitShadeEnabled) 0 else panelMarginHorizontal)
            setMargin(R.id.qs_frame, TOP, topMargin)
        }
    }

    private fun setKeyguardStatusViewConstraints(constraintSet: ConstraintSet) {
        val statusViewMarginHorizontal = resources.getDimensionPixelSize(
                R.dimen.status_view_margin_horizontal)
        constraintSet.apply {
            setMargin(R.id.keyguard_status_view, START, statusViewMarginHorizontal)
            setMargin(R.id.keyguard_status_view, END, statusViewMarginHorizontal)
        }
    }

    private fun ensureAllViewsHaveIds(parentView: ViewGroup) {
        for (i in 0 until parentView.childCount) {
            val childView = parentView.getChildAt(i)
            if (childView.id == View.NO_ID) {
                childView.id = View.generateViewId()
            }
        }
    }
}
