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
import android.os.PowerManager
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
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
class TemporaryViewDisplayControllerTest : SysuiTestCase() {
    private lateinit var underTest: TestController

    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor

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

        underTest = TestController(
                context,
                logger,
                windowManager,
                fakeExecutor,
                accessibilityManager,
                configurationController,
                powerManager,
        )
    }

    @Test
    fun displayView_viewAdded() {
        underTest.displayView(getState())

        verify(windowManager).addView(any(), any())
    }

    @Test
    fun displayView_screenOff_screenWakes() {
        whenever(powerManager.isScreenOn).thenReturn(false)

        underTest.displayView(getState())

        verify(powerManager).wakeUp(any(), any(), any())
    }

    @Test
    fun displayView_screenAlreadyOn_screenNotWoken() {
        whenever(powerManager.isScreenOn).thenReturn(true)

        underTest.displayView(getState())

        verify(powerManager, never()).wakeUp(any(), any(), any())
    }

    @Test
    fun displayView_twice_viewNotAddedTwice() {
        underTest.displayView(getState())
        reset(windowManager)

        underTest.displayView(getState())
        verify(windowManager, never()).addView(any(), any())
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
        verify(logger).logChipRemoval(reason)
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
    ) : TemporaryViewDisplayController<ViewInfo, TemporaryViewLogger>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        powerManager,
        R.layout.media_ttt_chip,
        "Window Title",
        "WAKE_REASON",
    ) {
        var mostRecentViewInfo: ViewInfo? = null

        override val windowLayoutParams = commonWindowLayoutParams
        override fun updateView(newInfo: ViewInfo, currentView: ViewGroup) {
            super.updateView(newInfo, currentView)
            mostRecentViewInfo = newInfo
        }
    }

    inner class ViewInfo(val name: String) : TemporaryViewInfo {
        override fun getTimeoutMs() = 1L
    }
}

private const val TIMEOUT_MS = 10000L
