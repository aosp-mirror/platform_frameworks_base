package com.android.systemui.shade.transition

import android.content.res.Configuration
import android.content.res.Resources
import android.util.MathUtils.constrain
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionChangeEvent
import com.android.systemui.statusbar.phone.panelstate.PanelState
import com.android.systemui.statusbar.phone.panelstate.STATE_OPENING
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.LargeScreenUtils
import java.io.PrintWriter
import javax.inject.Inject

/** Controls the scrim properties during the shade expansion transition on non-lockscreen. */
@SysUISingleton
class ScrimShadeTransitionController
@Inject
constructor(
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    private val scrimController: ScrimController,
    @Main private val resources: Resources,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val headsUpManager: HeadsUpManager
) {

    private var inSplitShade = false
    private var splitShadeScrimTransitionDistance = 0
    private var lastExpansionFraction: Float? = null
    private var lastExpansionEvent: PanelExpansionChangeEvent? = null
    private var currentPanelState: Int? = null

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            })
        dumpManager.registerDumpable(
            ScrimShadeTransitionController::class.java.simpleName, this::dump)
    }

    private fun updateResources() {
        inSplitShade = LargeScreenUtils.shouldUseSplitNotificationShade(resources)
        splitShadeScrimTransitionDistance =
            resources.getDimensionPixelSize(R.dimen.split_shade_scrim_transition_distance)
    }

    fun onPanelStateChanged(@PanelState state: Int) {
        currentPanelState = state
        onStateChanged()
    }

    fun onPanelExpansionChanged(panelExpansionChangeEvent: PanelExpansionChangeEvent) {
        lastExpansionEvent = panelExpansionChangeEvent
        onStateChanged()
    }

    private fun onStateChanged() {
        val expansionEvent = lastExpansionEvent ?: return
        val panelState = currentPanelState
        val expansionFraction = calculateScrimExpansionFraction(expansionEvent, panelState)
        scrimController.setRawPanelExpansionFraction(expansionFraction)
        lastExpansionFraction = expansionFraction
    }

    private fun calculateScrimExpansionFraction(
        expansionEvent: PanelExpansionChangeEvent,
        @PanelState panelState: Int?
    ): Float {
        return if (canUseCustomFraction(panelState)) {
            constrain(expansionEvent.dragDownPxAmount / splitShadeScrimTransitionDistance, 0f, 1f)
        } else {
            expansionEvent.fraction
        }
    }

    private fun canUseCustomFraction(panelState: Int?) =
        inSplitShade && isScreenUnlocked() && panelState == STATE_OPENING &&
                // in case of HUN we can't always use predefined distances to manage scrim
                // transition because dragDownPxAmount can start from value bigger than
                // splitShadeScrimTransitionDistance
                !headsUpManager.isTrackingHeadsUp

    private fun isScreenUnlocked() =
        statusBarStateController.currentOrUpcomingState == StatusBarState.SHADE

    private fun dump(printWriter: PrintWriter, args: Array<String>) {
        printWriter.println(
            """
                ScrimShadeTransitionController:
                  Resources:
                    inSplitShade: $inSplitShade
                    isScreenUnlocked: ${isScreenUnlocked()}
                    splitShadeScrimTransitionDistance: $splitShadeScrimTransitionDistance
                  State:
                    currentPanelState: $currentPanelState
                    lastExpansionFraction: $lastExpansionFraction
                    lastExpansionEvent: $lastExpansionEvent
            """.trimIndent())
    }
}
