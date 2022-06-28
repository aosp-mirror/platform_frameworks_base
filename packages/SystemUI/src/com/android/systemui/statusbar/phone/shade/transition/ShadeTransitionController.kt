package com.android.systemui.statusbar.phone.shade.transition

import android.content.Context
import android.content.res.Configuration
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.NotificationPanelViewController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionChangeEvent
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.statusbar.phone.panelstate.PanelState
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

/** Controls the shade expansion transition on non-lockscreen. */
@SysUISingleton
class ShadeTransitionController
@Inject
constructor(
    configurationController: ConfigurationController,
    panelExpansionStateManager: PanelExpansionStateManager,
    private val context: Context,
    private val splitShadeOverScrollerFactory: SplitShadeOverScroller.Factory,
    private val noOpOverScroller: NoOpOverScroller
) {

    lateinit var notificationPanelViewController: NotificationPanelViewController
    lateinit var notificationStackScrollLayoutController: NotificationStackScrollLayoutController
    lateinit var qs: QS

    private var inSplitShade = false

    private val splitShadeOverScroller by lazy {
        splitShadeOverScrollerFactory.create(qs, notificationStackScrollLayoutController)
    }
    private val shadeOverScroller: ShadeOverScroller
        get() =
            if (inSplitShade && propertiesInitialized()) {
                splitShadeOverScroller
            } else {
                noOpOverScroller
            }

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            })
        panelExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged)
        panelExpansionStateManager.addStateListener(this::onPanelStateChanged)
    }

    private fun updateResources() {
        inSplitShade = context.resources.getBoolean(R.bool.config_use_split_notification_shade)
    }

    private fun onPanelStateChanged(@PanelState state: Int) {
        shadeOverScroller.onPanelStateChanged(state)
    }

    private fun onPanelExpansionChanged(event: PanelExpansionChangeEvent) {
        shadeOverScroller.onDragDownAmountChanged(event.dragDownPxAmount)
    }

    private fun propertiesInitialized() =
        this::qs.isInitialized &&
            this::notificationPanelViewController.isInitialized &&
            this::notificationStackScrollLayoutController.isInitialized
}
