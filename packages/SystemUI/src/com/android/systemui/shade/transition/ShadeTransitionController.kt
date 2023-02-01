package com.android.systemui.shade.transition

import android.content.Context
import android.content.res.Configuration
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionChangeEvent
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.statusbar.phone.panelstate.PanelState
import com.android.systemui.statusbar.phone.panelstate.panelStateToString
import com.android.systemui.statusbar.policy.ConfigurationController
import java.io.PrintWriter
import javax.inject.Inject

/** Controls the shade expansion transition on non-lockscreen. */
@SysUISingleton
class ShadeTransitionController
@Inject
constructor(
    configurationController: ConfigurationController,
    panelExpansionStateManager: PanelExpansionStateManager,
    dumpManager: DumpManager,
    private val context: Context,
    private val splitShadeOverScrollerFactory: SplitShadeOverScroller.Factory,
    private val noOpOverScroller: NoOpOverScroller,
    private val scrimShadeTransitionController: ScrimShadeTransitionController,
    private val statusBarStateController: SysuiStatusBarStateController,
) {

    lateinit var notificationPanelViewController: NotificationPanelViewController
    lateinit var notificationStackScrollLayoutController: NotificationStackScrollLayoutController
    lateinit var qs: QS

    private var inSplitShade = false
    private var currentPanelState: Int? = null
    private var lastPanelExpansionChangeEvent: PanelExpansionChangeEvent? = null

    private val splitShadeOverScroller by lazy {
        splitShadeOverScrollerFactory.create({ qs }, { notificationStackScrollLayoutController })
    }
    private val shadeOverScroller: ShadeOverScroller
        get() =
            if (inSplitShade && isScreenUnlocked() && propertiesInitialized()) {
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
        dumpManager.registerDumpable("ShadeTransitionController") { printWriter, _ ->
            dump(printWriter)
        }
    }

    private fun updateResources() {
        inSplitShade = context.resources.getBoolean(R.bool.config_use_split_notification_shade)
    }

    private fun onPanelStateChanged(@PanelState state: Int) {
        currentPanelState = state
        shadeOverScroller.onPanelStateChanged(state)
        scrimShadeTransitionController.onPanelStateChanged(state)
    }

    private fun onPanelExpansionChanged(event: PanelExpansionChangeEvent) {
        lastPanelExpansionChangeEvent = event
        shadeOverScroller.onDragDownAmountChanged(event.dragDownPxAmount)
        scrimShadeTransitionController.onPanelExpansionChanged(event)
    }

    private fun propertiesInitialized() =
        this::qs.isInitialized &&
            this::notificationPanelViewController.isInitialized &&
            this::notificationStackScrollLayoutController.isInitialized

    private fun dump(pw: PrintWriter) {
        pw.println(
            """
            ShadeTransitionController:
                inSplitShade: $inSplitShade
                isScreenUnlocked: ${isScreenUnlocked()}
                currentPanelState: ${currentPanelState?.panelStateToString()}
                lastPanelExpansionChangeEvent: $lastPanelExpansionChangeEvent
                qs.isInitialized: ${this::qs.isInitialized}
                npvc.isInitialized: ${this::notificationPanelViewController.isInitialized}
                nssl.isInitialized: ${this::notificationStackScrollLayoutController.isInitialized}
            """.trimIndent())
    }

    private fun isScreenUnlocked() =
        statusBarStateController.currentOrUpcomingState == StatusBarState.SHADE
}
