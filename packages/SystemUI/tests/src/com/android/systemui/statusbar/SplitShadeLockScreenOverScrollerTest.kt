package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.intThat
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class SplitShadeLockScreenOverScrollerTest : SysuiTestCase() {

    private val configurationController = FakeConfigurationController()

    @Mock private lateinit var scrimController: ScrimController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    private var qS: QS? = null
    @Mock private lateinit var nsslController: NotificationStackScrollLayoutController
    @Mock private lateinit var dumpManager: DumpManager

    private lateinit var overScroller: SplitShadeLockScreenOverScroller

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        qS = mock()

        whenever(nsslController.height).thenReturn(1800)

        overScroller =
            SplitShadeLockScreenOverScroller(
                configurationController,
                dumpManager,
                context,
                scrimController,
                statusBarStateController,
                { qS },
                { nsslController })
    }

    @Test
    fun setDragDownAmount_onKeyguard_appliesOverScroll() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        setDragAmount(1000f)

        verifyOverScrollPerformed()
    }

    @Test
    fun setDragDownAmount_notOnKeyguard_doesNotApplyOverScroll() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)

        setDragAmount(1000f)

        verifyZeroInteractions(qS)
        verifyZeroInteractions(scrimController)
        verifyZeroInteractions(nsslController)
    }

    @Test
    fun setDragAmount_onKeyguard_thenNotOnKeyguard_resetsOverScrollToZero() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        setDragAmount(1000f)
        verifyOverScrollPerformed()
        reset(qS, scrimController, nsslController)

        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        setDragAmount(999f)
        verifyOverScrollResetToZero()
    }

    @Test
    fun setDragAmount_onKeyguard_thenNotOnKeyguard_multipleTimes_resetsOverScrollToZeroOnlyOnce() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        setDragAmount(1000f)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        setDragAmount(999f)
        reset(qS!!, scrimController, nsslController)

        setDragAmount(998f)
        setDragAmount(997f)
        setDragAmount(996f)
        verifyNoMoreOverScrollChanges()
    }

    @Test
    fun qsNull_applyOverscroll_doesNotCrash() {
        qS = null

        setDragAmount(100f)
    }

    private fun verifyOverScrollPerformed() {
        verify(qS!!).setOverScrollAmount(intThat { it > 0 })
        verify(scrimController).setNotificationsOverScrollAmount(intThat { it > 0 })
        verify(nsslController).setOverScrollAmount(intThat { it > 0 })
    }

    private fun verifyOverScrollResetToZero() {
        // Might be more than once as the animator might have multiple values close to zero that
        // round down to zero.
        verify(qS!!, atLeast(1)).setOverScrollAmount(0)
        verify(scrimController, atLeast(1)).setNotificationsOverScrollAmount(0)
        verify(nsslController, atLeast(1)).setOverScrollAmount(0)
    }

    private fun verifyNoMoreOverScrollChanges() {
        verifyNoMoreInteractions(qS)
        verifyNoMoreInteractions(scrimController)
        verifyNoMoreInteractions(nsslController)
    }

    private fun setDragAmount(dragDownAmount: Float) {
        overScroller.expansionDragDownAmount = dragDownAmount
        overScroller.finishAnimations()
    }
}
