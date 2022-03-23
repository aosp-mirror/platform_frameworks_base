package com.android.systemui.statusbar.phone

import android.view.WindowInsets
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.util.ViewController
import java.util.function.Consumer
import javax.inject.Inject

class NotificationsQSContainerController @Inject constructor(
    view: NotificationsQuickSettingsContainer,
    private val navigationModeController: NavigationModeController,
    private val overviewProxyService: OverviewProxyService
) : ViewController<NotificationsQuickSettingsContainer>(view), QSContainerController {

    var qsExpanded = false
        set(value) {
            if (field != value) {
                field = value
                mView.invalidate()
            }
        }
    var splitShadeEnabled = false
        set(value) {
            if (field != value) {
                field = value
                // in case device configuration changed while showing QS details/customizer
                updateBottomSpacing()
            }
        }

    private var isQSDetailShowing = false
    private var isQSCustomizing = false
    private var isQSCustomizerAnimating = false

    private var notificationsBottomMargin = 0
    private var bottomStableInsets = 0
    private var bottomCutoutInsets = 0

    private var isGestureNavigation = true
    private var taskbarVisible = false
    private val taskbarVisibilityListener: OverviewProxyListener = object : OverviewProxyListener {
        override fun onTaskbarStatusUpdated(visible: Boolean, stashed: Boolean) {
            taskbarVisible = visible
        }
    }
    private val windowInsetsListener: Consumer<WindowInsets> = Consumer { insets ->
        // when taskbar is visible, stableInsetBottom will include its height
        bottomStableInsets = insets.stableInsetBottom
        bottomCutoutInsets = insets.displayCutout?.safeInsetBottom ?: 0
        updateBottomSpacing()
    }

    override fun onInit() {
        val currentMode: Int = navigationModeController.addListener { mode: Int ->
            isGestureNavigation = QuickStepContract.isGesturalMode(mode)
        }
        isGestureNavigation = QuickStepContract.isGesturalMode(currentMode)
    }

    public override fun onViewAttached() {
        notificationsBottomMargin = mView.defaultNotificationsMarginBottom
        overviewProxyService.addCallback(taskbarVisibilityListener)
        mView.setInsetsChangedListener(windowInsetsListener)
        mView.setQSFragmentAttachedListener { qs: QS -> qs.setContainerController(this) }
    }

    override fun onViewDetached() {
        overviewProxyService.removeCallback(taskbarVisibilityListener)
        mView.removeOnInsetsChangedListener()
        mView.removeQSFragmentAttachedListener()
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
        if (!(splitShadeEnabled || isQSCustomizing || isQSDetailShowing || isGestureNavigation ||
                        taskbarVisible)) {
            // no taskbar, portrait, navigation buttons enabled:
            // padding is needed so QS can scroll up over bottom insets - to reach the point when
            // the whole QS is above bottom insets
            qsScrollPaddingBottom = bottomStableInsets
        }
        mView.setPadding(0, 0, 0, containerPadding)
        mView.setNotificationsMarginBottom(notificationsMargin)
        mView.setQSScrollPaddingBottom(qsScrollPaddingBottom)
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
}