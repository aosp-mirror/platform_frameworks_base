/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.haptics.slider

import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SeekbarHapticPluginTest : SysuiTestCase() {

    private val kosmos = Kosmos()

    @Rule @JvmField val mMockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var vibratorHelper: VibratorHelper
    private val seekBar = SeekBar(mContext)
    private lateinit var plugin: SeekbarHapticPlugin

    @Before
    fun setup() {
        whenever(vibratorHelper.getPrimitiveDurations(anyInt())).thenReturn(intArrayOf(0))
    }

    @Test
    fun start_beginsTrackingSlider() = runOnStartedPlugin { assertThat(plugin.isTracking).isTrue() }

    @Test
    fun stop_stopsTrackingSlider() = runOnStartedPlugin {
        // WHEN called to stop
        plugin.stop()

        // THEN stops tracking
        assertThat(plugin.isTracking).isFalse()
    }

    @Test
    fun start_afterStop_startsTheTrackingAgain() = runOnStartedPlugin {
        // WHEN the plugin is restarted
        plugin.stop()
        plugin.startInScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // THEN the tracking begins again
        assertThat(plugin.isTracking).isTrue()
    }

    @Test
    fun onKeyDown_startsWaiting() = runOnStartedPlugin {
        // WHEN a keyDown event is recorded
        plugin.onKeyDown()

        // THEN the timer starts waiting
        assertThat(plugin.isKeyUpTimerWaiting).isTrue()
    }

    @Test
    fun keyUpWaitComplete_triggersOnArrowUp() = runOnStartedPlugin {
        // GIVEN an onKeyDown that starts the wait and a program progress change that advances the
        // slider state to ARROW_HANDLE_MOVED_ONCE
        plugin.onKeyDown()
        plugin.onProgressChanged(seekBar, 50, false)
        testScheduler.runCurrent()
        assertThat(plugin.trackerState).isEqualTo(SliderState.ARROW_HANDLE_MOVED_ONCE)

        // WHEN the key-up wait completes after the timeout plus a small buffer
        advanceTimeBy(KEY_UP_TIMEOUT + 10L)

        // THEN the onArrowUp event is delivered causing the slider tracker to move to IDLE
        assertThat(plugin.trackerState).isEqualTo(SliderState.IDLE)
        assertThat(plugin.isKeyUpTimerWaiting).isFalse()
    }

    @Test
    fun onKeyDown_whileWaiting_restartsWait() = runOnStartedPlugin {
        // GIVEN an onKeyDown that starts the wait and a program progress change that advances the
        // slider state to ARROW_HANDLE_MOVED_ONCE
        plugin.onKeyDown()
        plugin.onProgressChanged(seekBar, 50, false)
        testScheduler.runCurrent()
        assertThat(plugin.trackerState).isEqualTo(SliderState.ARROW_HANDLE_MOVED_ONCE)

        // WHEN half the timeout period has elapsed and a new keyDown event occurs
        advanceTimeBy(KEY_UP_TIMEOUT / 2)
        plugin.onKeyDown()

        // AFTER advancing by a period of time that should have complete the original wait
        advanceTimeBy(KEY_UP_TIMEOUT / 2 + 10L)

        // THEN the timer is still waiting and the slider tracker remains on ARROW_HANDLE_MOVED_ONCE
        assertThat(plugin.isKeyUpTimerWaiting).isTrue()
        assertThat(plugin.trackerState).isEqualTo(SliderState.ARROW_HANDLE_MOVED_ONCE)
    }

    private fun runOnStartedPlugin(test: suspend TestScope.() -> Unit) =
        with(kosmos) {
            testScope.runTest {
                val pluginScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                createPlugin()
                // GIVEN that the plugin is started in a test scope
                plugin.startInScope(pluginScope)

                // THEN run the test
                test()
            }
        }

    private fun createPlugin() {
        plugin =
            SeekbarHapticPlugin(
                vibratorHelper,
                kosmos.fakeSystemClock,
            )
    }

    companion object {
        private const val KEY_UP_TIMEOUT = 100L
    }
}
