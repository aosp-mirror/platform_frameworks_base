package com.android.systemui.statusbar.phone.shade.transition

import android.content.res.Configuration
import android.content.res.Resources
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionChangeEvent
import com.android.systemui.statusbar.policy.ConfigurationController
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
    @Main private val resources: Resources
) {

    private var inSplitShade = false
    private var splitShadeScrimTransitionDistance = 0
    private var lastExpansionFraction: Float = 0f
    private var lastExpansionEvent: PanelExpansionChangeEvent? = null

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            })
        dumpManager
            .registerDumpable(ScrimShadeTransitionController::class.java.simpleName, this::dump)
    }

    private fun updateResources() {
        inSplitShade = LargeScreenUtils.shouldUseSplitNotificationShade(resources)
        splitShadeScrimTransitionDistance =
            resources.getDimensionPixelSize(R.dimen.split_shade_scrim_transition_distance)
    }

    fun onPanelExpansionChanged(panelExpansionChangeEvent: PanelExpansionChangeEvent) {
        val expansionFraction = calculateScrimExpansionFraction(panelExpansionChangeEvent)
        scrimController.setRawPanelExpansionFraction(expansionFraction)
        lastExpansionFraction = expansionFraction
        lastExpansionEvent = panelExpansionChangeEvent
    }

    private fun calculateScrimExpansionFraction(expansionEvent: PanelExpansionChangeEvent): Float {
        return if (inSplitShade) {
            expansionEvent.dragDownPxAmount / splitShadeScrimTransitionDistance
        } else {
            expansionEvent.fraction
        }
    }

    private fun dump(printWriter: PrintWriter, args: Array<String>) {
        printWriter.println(
            """
                ScrimShadeTransitionController:
                  Resources:
                    inSplitShade: $inSplitShade
                    splitShadeScrimTransitionDistance: $splitShadeScrimTransitionDistance
                  State:
                    lastExpansionFraction: $lastExpansionFraction
                    lastExpansionEvent: $lastExpansionEvent
            """.trimIndent()
        )
    }
}
