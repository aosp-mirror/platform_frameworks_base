package com.android.systemui.statusbar.phone.shade.transition

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionChangeEvent
import com.android.systemui.statusbar.policy.FakeConfigurationController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ScrimShadeTransitionControllerTest : SysuiTestCase() {

    @Mock private lateinit var scrimController: ScrimController
    @Mock private lateinit var dumpManager: DumpManager
    private val configurationController = FakeConfigurationController()

    private lateinit var controller: ScrimShadeTransitionController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.ensureTestableResources()
        controller =
            ScrimShadeTransitionController(
                configurationController, dumpManager, scrimController, context.resources
            )
    }
    @Test
    fun onPanelExpansionChanged_inSingleShade_setsFractionEqualToEventFraction() {
        setSplitShadeEnabled(false)

        controller.onPanelExpansionChanged(EXPANSION_EVENT)

        verify(scrimController).setRawPanelExpansionFraction(EXPANSION_EVENT.fraction)
    }

    @Test
    fun onPanelExpansionChanged_inSplitShade_setsFractionBasedOnDragDownAmount() {
        val scrimShadeTransitionDistance =
            context.resources.getDimensionPixelSize(R.dimen.split_shade_scrim_transition_distance)
        setSplitShadeEnabled(true)

        controller.onPanelExpansionChanged(EXPANSION_EVENT)

        val expectedFraction = EXPANSION_EVENT.dragDownPxAmount / scrimShadeTransitionDistance
        verify(scrimController).setRawPanelExpansionFraction(expectedFraction)
    }

    private fun setSplitShadeEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_split_notification_shade, enabled)
        configurationController.notifyConfigurationChanged()
    }

    companion object {
        val EXPANSION_EVENT =
            PanelExpansionChangeEvent(
                fraction = 0.5f, expanded = true, tracking = true, dragDownPxAmount = 10f
            )
    }
}
