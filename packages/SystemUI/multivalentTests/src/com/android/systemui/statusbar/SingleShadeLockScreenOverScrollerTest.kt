package com.android.systemui.statusbar

import org.mockito.Mockito.`when` as whenever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.intThat
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class SingleShadeLockScreenOverScrollerTest : SysuiTestCase() {

    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var nsslController: NotificationStackScrollLayoutController

    private lateinit var overScroller: SingleShadeLockScreenOverScroller

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(nsslController.height).thenReturn(1800)
        overScroller =
            SingleShadeLockScreenOverScroller(
                FakeConfigurationController(),
                context,
                statusBarStateController,
                nsslController
            )
    }

    @Test
    fun setDragDownAmount_onKeyguard_overScrolls() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        overScroller.expansionDragDownAmount = 10f

        verify(nsslController).setOverScrollAmount(intThat { it > 0 })
    }

    @Test
    fun setDragDownAmount_notOnKeyguard_doesNotOverScroll() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)

        overScroller.expansionDragDownAmount = 10f

        verify(nsslController).setOverScrollAmount(0)
    }
}
