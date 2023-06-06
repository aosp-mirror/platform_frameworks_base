package com.android.systemui.shade.transition

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.shade.STATE_OPENING
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.statusbar.phone.ScrimController
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

    private lateinit var controller: ScrimShadeTransitionController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.ensureTestableResources()
        controller = ScrimShadeTransitionController(dumpManager, scrimController)

        controller.onPanelStateChanged(STATE_OPENING)
    }

    @Test
    fun onPanelExpansionChanged_setsFractionEqualToEventFraction() {
        controller.onPanelExpansionChanged(EXPANSION_EVENT)

        verify(scrimController).setRawPanelExpansionFraction(EXPANSION_EVENT.fraction)
    }

    companion object {
        val EXPANSION_EVENT =
            ShadeExpansionChangeEvent(
                fraction = 0.5f,
                expanded = true,
                tracking = true,
                dragDownPxAmount = 10f
            )
    }
}
