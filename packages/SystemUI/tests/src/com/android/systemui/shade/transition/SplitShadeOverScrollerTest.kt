package com.android.systemui.shade.transition

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.shade.STATE_CLOSED
import com.android.systemui.shade.STATE_OPEN
import com.android.systemui.shade.STATE_OPENING
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class SplitShadeOverScrollerTest : SysuiTestCase() {

    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var scrimController: ScrimController
    @Mock private lateinit var qs: QS
    @Mock private lateinit var nsslController: NotificationStackScrollLayoutController

    private val configurationController = FakeConfigurationController()
    private lateinit var overScroller: SplitShadeOverScroller

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(nsslController.height).thenReturn(1000)
        overScroller =
            SplitShadeOverScroller(
                configurationController,
                dumpManager,
                context,
                scrimController,
                { qs },
                { nsslController })
    }

    @Test
    fun onDragDownAmountChanged_panelOpening_overScrolls_basedOnHeightAndMaxAmount() {
        val maxOverScrollAmount = 50
        val dragDownAmount = 100f
        overrideResource(R.dimen.shade_max_over_scroll_amount, maxOverScrollAmount)
        configurationController.notifyConfigurationChanged()

        overScroller.onPanelStateChanged(STATE_OPENING)
        overScroller.onDragDownAmountChanged(dragDownAmount)

        val expectedOverScrollAmount =
            (dragDownAmount / nsslController.height * maxOverScrollAmount).toInt()
        verify(qs).setOverScrollAmount(expectedOverScrollAmount)
        verify(nsslController).setOverScrollAmount(expectedOverScrollAmount)
        verify(scrimController).setNotificationsOverScrollAmount(expectedOverScrollAmount)
    }

    @Test
    fun onDragDownAmountChanged_panelClosed_doesNotOverScroll() {
        overScroller.onPanelStateChanged(STATE_CLOSED)
        overScroller.onDragDownAmountChanged(100f)

        verifyZeroInteractions(qs, scrimController, nsslController)
    }

    @Test
    fun onDragDownAmountChanged_panelOpen_doesNotOverScroll() {
        overScroller.onPanelStateChanged(STATE_OPEN)
        overScroller.onDragDownAmountChanged(100f)

        verifyZeroInteractions(qs, scrimController, nsslController)
    }

    @Test
    fun onPanelStateChanged_opening_thenOpen_releasesOverScroll() {
        overScroller.onPanelStateChanged(STATE_OPENING)
        overScroller.onDragDownAmountChanged(100f)

        overScroller.onPanelStateChanged(STATE_OPEN)
        overScroller.finishAnimations()

        verify(qs, atLeastOnce()).setOverScrollAmount(0)
        verify(scrimController, atLeastOnce()).setNotificationsOverScrollAmount(0)
        verify(nsslController, atLeastOnce()).setOverScrollAmount(0)
    }

    @Test
    fun onPanelStateChanged_opening_thenClosed_releasesOverScroll() {
        overScroller.onPanelStateChanged(STATE_OPENING)
        overScroller.onDragDownAmountChanged(100f)

        overScroller.onPanelStateChanged(STATE_CLOSED)
        overScroller.finishAnimations()

        verify(qs, atLeastOnce()).setOverScrollAmount(0)
        verify(scrimController, atLeastOnce()).setNotificationsOverScrollAmount(0)
        verify(nsslController, atLeastOnce()).setOverScrollAmount(0)
    }
}
