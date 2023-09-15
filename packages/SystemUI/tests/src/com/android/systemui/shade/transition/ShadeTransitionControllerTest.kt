package com.android.systemui.shade.transition

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.shade.STATE_OPENING
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ShadeTransitionControllerTest : SysuiTestCase() {

    @Mock private lateinit var scrimShadeTransitionController: ScrimShadeTransitionController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController

    private lateinit var controller: ShadeTransitionController

    private val configurationController = FakeConfigurationController()
    private val shadeExpansionStateManager = ShadeExpansionStateManager()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controller =
            ShadeTransitionController(
                configurationController,
                shadeExpansionStateManager,
                dumpManager,
                context,
                scrimShadeTransitionController,
                statusBarStateController,
                    ResourcesSplitShadeStateController()
            )
    }

    @Test
    fun onPanelStateChanged_forwardsToScrimTransitionController() {
        startPanelExpansion()

        verify(scrimShadeTransitionController).onPanelStateChanged(STATE_OPENING)
        verify(scrimShadeTransitionController).onPanelExpansionChanged(DEFAULT_EXPANSION_EVENT)
    }

    private fun startPanelExpansion() {
        shadeExpansionStateManager.onPanelExpansionChanged(
            DEFAULT_EXPANSION_EVENT.fraction,
            DEFAULT_EXPANSION_EVENT.expanded,
            DEFAULT_EXPANSION_EVENT.tracking,
            DEFAULT_EXPANSION_EVENT.dragDownPxAmount,
        )
    }

    companion object {
        private const val DEFAULT_DRAG_DOWN_AMOUNT = 123f
        private val DEFAULT_EXPANSION_EVENT =
            ShadeExpansionChangeEvent(
                fraction = 0.5f,
                expanded = true,
                tracking = true,
                dragDownPxAmount = DEFAULT_DRAG_DOWN_AMOUNT)
    }
}
