/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.temporarydisplay

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.wakelock.WakeLock
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
class TemporaryViewDisplayControllerTest : SysuiTestCase() {
    private lateinit var underTest: TestController

    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor

    private lateinit var fakeWakeLockBuilder: WakeLockFake.Builder
    private lateinit var fakeWakeLock: WakeLockFake

    @Mock
    private lateinit var logger: TemporaryViewLogger
    @Mock
    private lateinit var accessibilityManager: AccessibilityManager
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any()))
            .thenReturn(TIMEOUT_MS.toInt())

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        fakeWakeLock = WakeLockFake()
        fakeWakeLockBuilder = WakeLockFake.Builder(context)
        fakeWakeLockBuilder.setWakeLock(fakeWakeLock)

        underTest = TestController(
                context,
                logger,
                windowManager,
                fakeExecutor,
                accessibilityManager,
                configurationController,
                powerManager,
                fakeWakeLockBuilder,
        )
        underTest.start()
    }

    @Test
    fun displayView_viewAddedWithCorrectTitle() {
        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "Fake Window Title",
            )
        )

        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(any(), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value!!.title).isEqualTo("Fake Window Title")
    }

    @Test
    fun displayView_logged() {
        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "Fake Window Title",
            )
        )

        verify(logger).logViewAddition("Fake Window Title")
    }

    @Test
    fun displayView_wakeLockAcquired() {
        underTest.displayView(getState())

        assertThat(fakeWakeLock.isHeld).isTrue()
    }

    @Test
    fun displayView_screenAlreadyOn_wakeLockAcquired() {
        whenever(powerManager.isScreenOn).thenReturn(true)

        underTest.displayView(getState())

        assertThat(fakeWakeLock.isHeld).isTrue()
    }

    @Test
    fun displayView_wakeLockCanBeReleasedAfterTimeOut() {
        underTest.displayView(getState())
        assertThat(fakeWakeLock.isHeld).isTrue()

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun displayView_removeView_wakeLockCanBeReleased() {
        underTest.displayView(getState())
        assertThat(fakeWakeLock.isHeld).isTrue()

        underTest.removeView("test reason")

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun displayView_twice_viewNotAddedTwice() {
        underTest.displayView(getState())
        reset(windowManager)

        underTest.displayView(getState())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun displayView_twiceWithDifferentWindowTitles_oldViewRemovedNewViewAdded() {
        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "First Fake Window Title",
            )
        )

        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "Second Fake Window Title",
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()

        verify(windowManager, times(2)).addView(capture(viewCaptor), capture(windowParamsCaptor))

        assertThat(windowParamsCaptor.allValues[0].title).isEqualTo("First Fake Window Title")
        assertThat(windowParamsCaptor.allValues[1].title).isEqualTo("Second Fake Window Title")
        verify(windowManager).removeView(viewCaptor.allValues[0])
    }

    @Test
    fun displayView_viewDoesNotDisappearsBeforeTimeout() {
        val state = getState()
        underTest.displayView(state)
        reset(windowManager)

        fakeClock.advanceTime(TIMEOUT_MS - 1)

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun displayView_viewDisappearsAfterTimeout() {
        val state = getState()
        underTest.displayView(state)
        reset(windowManager)

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun displayView_calledAgainBeforeTimeout_timeoutReset() {
        // First, display the view
        val state = getState()
        underTest.displayView(state)

        // After some time, re-display the view
        val waitTime = 1000L
        fakeClock.advanceTime(waitTime)
        underTest.displayView(getState())

        // Wait until the timeout for the first display would've happened
        fakeClock.advanceTime(TIMEOUT_MS - waitTime + 1)

        // Verify we didn't hide the view
        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun displayView_calledAgainBeforeTimeout_eventuallyTimesOut() {
        // First, display the view
        val state = getState()
        underTest.displayView(state)

        // After some time, re-display the view
        fakeClock.advanceTime(1000L)
        underTest.displayView(getState())

        // Ensure we still hide the view eventually
        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun displayScaleChange_viewReinflatedWithMostRecentState() {
        underTest.displayView(getState(name = "First name"))
        underTest.displayView(getState(name = "Second name"))
        reset(windowManager)

        getConfigurationListener().onDensityOrFontScaleChanged()

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("Second name")
    }

    @Test
    fun removeView_viewRemovedAndRemovalLogged() {
        // First, add the view
        underTest.displayView(getState())

        // Then, remove it
        val reason = "test reason"
        underTest.removeView(reason)

        verify(windowManager).removeView(any())
        verify(logger).logViewRemoval(reason)
    }

    @Test
    fun removeView_noAdd_viewNotRemoved() {
        underTest.removeView("reason")

        verify(windowManager, never()).removeView(any())
    }

    private fun getState(name: String = "name") = ViewInfo(name)

    private fun getConfigurationListener(): ConfigurationListener {
        val callbackCaptor = argumentCaptor<ConfigurationListener>()
        verify(configurationController).addCallback(capture(callbackCaptor))
        return callbackCaptor.value
    }

    inner class TestController(
        context: Context,
        logger: TemporaryViewLogger,
        windowManager: WindowManager,
        @Main mainExecutor: DelayableExecutor,
        accessibilityManager: AccessibilityManager,
        configurationController: ConfigurationController,
        powerManager: PowerManager,
        wakeLockBuilder: WakeLock.Builder,
    ) : TemporaryViewDisplayController<ViewInfo, TemporaryViewLogger>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        powerManager,
        R.layout.chipbar,
        wakeLockBuilder,
    ) {
        var mostRecentViewInfo: ViewInfo? = null

        override val windowLayoutParams = commonWindowLayoutParams

        override fun updateView(newInfo: ViewInfo, currentView: ViewGroup) {
            mostRecentViewInfo = newInfo
        }

        override fun getTouchableRegion(view: View, outRect: Rect) {
            outRect.setEmpty()
        }

        override fun start() {}
    }

    inner class ViewInfo(
        val name: String,
        override val windowTitle: String = "Window Title",
        override val wakeReason: String = "WAKE_REASON",
        override val timeoutMs: Int = 1
    ) : TemporaryViewInfo()
}

private const val TIMEOUT_MS = 10000L
