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
 * limitations under the License
 */

package com.android.systemui.common.ui.data.repository

import android.content.res.Configuration
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.wrapper.DisplayUtilsWrapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class ConfigurationRepositoryImplTest : SysuiTestCase() {
    private var displaySizeRatio = 0f
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var displayUtils: DisplayUtilsWrapper

    private lateinit var testScope: TestScope
    private lateinit var underTest: ConfigurationRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        setPhysicalPixelDisplaySizeRatio(displaySizeRatio)

        testScope = TestScope()
        underTest =
            ConfigurationRepositoryImpl(
                configurationController,
                context,
                testScope.backgroundScope,
                displayUtils,
            )
    }

    @Test
    fun onAnyConfigurationChange_updatesOnUiModeChanged() =
        testScope.runTest {
            val lastAnyConfigurationChange by collectLastValue(underTest.onAnyConfigurationChange)
            assertThat(lastAnyConfigurationChange).isNull()

            val configurationCallback = withArgCaptor {
                verify(configurationController).addCallback(capture())
            }

            configurationCallback.onUiModeChanged()
            runCurrent()
            assertThat(lastAnyConfigurationChange).isNotNull()
        }

    @Test
    fun onAnyConfigurationChange_updatesOnThemeChanged() =
        testScope.runTest {
            val lastAnyConfigurationChange by collectLastValue(underTest.onAnyConfigurationChange)
            assertThat(lastAnyConfigurationChange).isNull()

            val configurationCallback = withArgCaptor {
                verify(configurationController).addCallback(capture())
            }

            configurationCallback.onThemeChanged()
            runCurrent()
            assertThat(lastAnyConfigurationChange).isNotNull()
        }

    @Test
    fun onAnyConfigurationChange_updatesOnConfigChanged() =
        testScope.runTest {
            val lastAnyConfigurationChange by collectLastValue(underTest.onAnyConfigurationChange)
            assertThat(lastAnyConfigurationChange).isNull()

            val configurationCallback = withArgCaptor {
                verify(configurationController).addCallback(capture())
            }

            configurationCallback.onConfigChanged(mock(Configuration::class.java))
            runCurrent()
            assertThat(lastAnyConfigurationChange).isNotNull()
        }

    @Test
    fun onResolutionScale_updatesOnConfigurationChange() =
        testScope.runTest {
            val scaleForResolution by collectLastValue(underTest.scaleForResolution)
            assertThat(scaleForResolution).isEqualTo(displaySizeRatio)

            val configurationCallback = withArgCaptor {
                verify(configurationController).addCallback(capture())
            }

            setPhysicalPixelDisplaySizeRatio(2f)
            configurationCallback.onConfigChanged(mock(Configuration::class.java))
            assertThat(scaleForResolution).isEqualTo(displaySizeRatio)

            setPhysicalPixelDisplaySizeRatio(.21f)
            configurationCallback.onConfigChanged(mock(Configuration::class.java))
            assertThat(scaleForResolution).isEqualTo(displaySizeRatio)
        }

    @Test
    fun onResolutionScale_nullMaxResolution() =
        testScope.runTest {
            val scaleForResolution by collectLastValue(underTest.scaleForResolution)
            runCurrent()

            givenNullMaxResolutionDisplayMode()
            val configurationCallback = withArgCaptor {
                verify(configurationController).addCallback(capture())
            }
            configurationCallback.onConfigChanged(mock(Configuration::class.java))
            assertThat(scaleForResolution).isEqualTo(1f)
        }

    @Test
    fun getResolutionScale_nullMaxResolutionDisplayMode() {
        givenNullMaxResolutionDisplayMode()
        assertThat(underTest.getResolutionScale()).isEqualTo(1f)
    }

    @Test
    fun getResolutionScale_infiniteDisplayRatios() {
        setPhysicalPixelDisplaySizeRatio(Float.POSITIVE_INFINITY)
        assertThat(underTest.getResolutionScale()).isEqualTo(1f)
    }

    @Test
    fun getResolutionScale_differentDisplayRatios() {
        setPhysicalPixelDisplaySizeRatio(.5f)
        assertThat(underTest.getResolutionScale()).isEqualTo(displaySizeRatio)

        setPhysicalPixelDisplaySizeRatio(.283f)
        assertThat(underTest.getResolutionScale()).isEqualTo(displaySizeRatio)

        setPhysicalPixelDisplaySizeRatio(3.58f)
        assertThat(underTest.getResolutionScale()).isEqualTo(displaySizeRatio)

        setPhysicalPixelDisplaySizeRatio(0f)
        assertThat(underTest.getResolutionScale()).isEqualTo(displaySizeRatio)

        setPhysicalPixelDisplaySizeRatio(1f)
        assertThat(underTest.getResolutionScale()).isEqualTo(displaySizeRatio)
    }

    private fun givenNullMaxResolutionDisplayMode() {
        whenever(displayUtils.getMaximumResolutionDisplayMode(any())).thenReturn(null)
    }

    private fun setPhysicalPixelDisplaySizeRatio(ratio: Float) {
        displaySizeRatio = ratio
        whenever(displayUtils.getMaximumResolutionDisplayMode(any()))
            .thenReturn(Display.Mode(0, 0, 0, 90f))
        whenever(
                displayUtils.getPhysicalPixelDisplaySizeRatio(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyInt()
                )
            )
            .thenReturn(ratio)
    }
}
