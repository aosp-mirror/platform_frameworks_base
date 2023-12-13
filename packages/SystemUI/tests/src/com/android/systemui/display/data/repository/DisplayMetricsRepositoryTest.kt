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

package com.android.systemui.display.data.repository

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayMetricsRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: DisplayMetricsRepository

    private val testScope = TestScope(StandardTestDispatcher())
    private val configurationController = FakeConfigurationController()

    private val displayMetrics =
        DisplayMetrics().apply { this.heightPixels = INITIAL_HEIGHT_PIXELS }
    private val mockContext: Context = mock()
    private val mockDisplay: Display = mock()

    @Before
    fun setUp() {
        underTest =
            DisplayMetricsRepository(
                testScope.backgroundScope,
                configurationController,
                displayMetrics,
                mockContext,
                LogBuffer("TestBuffer", maxSize = 10, logcatEchoTracker = mock())
            )
        whenever(mockContext.display).thenReturn(mockDisplay)
    }

    @Test
    fun heightPixels_getsInitialValue() {
        assertThat(underTest.heightPixels).isEqualTo(INITIAL_HEIGHT_PIXELS)
    }

    @Test
    fun heightPixels_configChanged_heightUpdated() =
        testScope.runTest {
            runCurrent()

            updateDisplayMetrics(456)
            configurationController.notifyConfigurationChanged()
            runCurrent()

            assertThat(underTest.heightPixels).isEqualTo(456)

            updateDisplayMetrics(23)
            configurationController.notifyConfigurationChanged()
            runCurrent()

            assertThat(underTest.heightPixels).isEqualTo(23)
        }

    private fun updateDisplayMetrics(newHeight: Int) {
        whenever(mockDisplay.getMetrics(displayMetrics)).thenAnswer {
            it.getArgument<DisplayMetrics>(0).heightPixels = newHeight
            Unit
        }
    }

    private companion object {
        const val INITIAL_HEIGHT_PIXELS = 345
    }
}
