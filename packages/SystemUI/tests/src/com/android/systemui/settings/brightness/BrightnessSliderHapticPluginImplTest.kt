/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.settings.brightness

import android.view.VelocityTracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.slider.SeekableSliderEventProducer
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessSliderHapticPluginImplTest : SysuiTestCase() {

    @Mock private lateinit var vibratorHelper: VibratorHelper
    @Mock private lateinit var velocityTracker: VelocityTracker
    @Mock private lateinit var mainDispatcher: CoroutineDispatcher

    private val systemClock = FakeSystemClock()
    private val sliderEventProducer = SeekableSliderEventProducer()

    private lateinit var plugin: BrightnessSliderHapticPluginImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(vibratorHelper.getPrimitiveDurations(anyInt())).thenReturn(intArrayOf(0))
    }

    @Test
    fun start_beginsTrackingSlider() = runTest {
        createPlugin(UnconfinedTestDispatcher(testScheduler))
        plugin.start()

        assertThat(plugin.isTracking).isTrue()
    }

    @Test
    fun stop_stopsTrackingSlider() = runTest {
        createPlugin(UnconfinedTestDispatcher(testScheduler))
        // GIVEN that the plugin started the tracking component
        plugin.start()

        // WHEN called to stop
        plugin.stop()

        // THEN the tracking component stops
        assertThat(plugin.isTracking).isFalse()
    }

    @Test
    fun start_afterStop_startsTheTrackingAgain() = runTest {
        createPlugin(UnconfinedTestDispatcher(testScheduler))
        // GIVEN that the plugin started the tracking component
        plugin.start()

        // WHEN the plugin is restarted
        plugin.stop()
        plugin.start()

        // THEN the tracking begins again
        assertThat(plugin.isTracking).isTrue()
    }

    private fun createPlugin(dispatcher: CoroutineDispatcher) {
        plugin =
            BrightnessSliderHapticPluginImpl(
                vibratorHelper,
                systemClock,
                dispatcher,
                velocityTracker,
                sliderEventProducer,
            )
    }
}
