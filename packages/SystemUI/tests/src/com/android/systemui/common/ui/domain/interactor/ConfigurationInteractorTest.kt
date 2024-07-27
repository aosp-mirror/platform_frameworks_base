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

package com.android.systemui.common.ui.domain.interactor

import android.content.res.Configuration
import android.graphics.Rect
import android.util.LayoutDirection
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class ConfigurationInteractorTest : SysuiTestCase() {
    private lateinit var testScope: TestScope
    private lateinit var configuration: Configuration
    private lateinit var underTest: ConfigurationInteractor
    private lateinit var configurationRepository: FakeConfigurationRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        configuration = context.resources.configuration
        val testableResources = context.getOrCreateTestableResources()
        testableResources.overrideConfiguration(configuration)
        configurationRepository = FakeConfigurationRepository()
        testScope = TestScope()
        underTest = ConfigurationInteractor(configurationRepository)
    }

    @Test
    fun dimensionPixelSize() =
        testScope.runTest {
            val resourceId = 1001
            val pixelSize = 501
            configurationRepository.setDimensionPixelSize(resourceId, pixelSize)

            val dimensionPixelSize by collectLastValue(underTest.dimensionPixelSize(resourceId))

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimensionPixelSize).isEqualTo(pixelSize)
        }

    @Test
    fun directionalDimensionPixelSize() =
        testScope.runTest {
            val resourceId = 1001
            val pixelSize = 501
            configurationRepository.setDimensionPixelSize(resourceId, pixelSize)

            val config: Configuration = mock()
            val dimensionPixelSize by
                collectLastValue(
                    underTest.directionalDimensionPixelSize(LayoutDirection.LTR, resourceId)
                )

            whenever(config.layoutDirection).thenReturn(LayoutDirection.LTR)
            configurationRepository.onConfigurationChange(config)
            assertThat(dimensionPixelSize).isEqualTo(pixelSize)

            whenever(config.layoutDirection).thenReturn(LayoutDirection.RTL)
            configurationRepository.onConfigurationChange(config)
            assertThat(dimensionPixelSize).isEqualTo(-pixelSize)
        }

    @Test
    fun dimensionPixelSizes() =
        testScope.runTest {
            val resourceId1 = 1001
            val pixelSize1 = 501
            val resourceId2 = 1002
            val pixelSize2 = 502
            configurationRepository.setDimensionPixelSize(resourceId1, pixelSize1)
            configurationRepository.setDimensionPixelSize(resourceId2, pixelSize2)

            val dimensionPixelSizes by
                collectLastValue(underTest.dimensionPixelSize(setOf(resourceId1, resourceId2)))

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimensionPixelSizes!![resourceId1]).isEqualTo(pixelSize1)
            assertThat(dimensionPixelSizes!![resourceId2]).isEqualTo(pixelSize2)
        }

    @Test
    fun maxBoundsChange_emitsMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.maxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(width = DISPLAY_WIDTH * 2, height = DISPLAY_HEIGHT * 3)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT),
                    Rect(0, 0, DISPLAY_WIDTH * 2, DISPLAY_HEIGHT * 3),
                )
                .inOrder()
        }

    @Test
    fun maxBoundsSameOnConfigChange_doesNotEmitMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.maxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    @Test
    fun firstMaxBoundsChange_emitsMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.maxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    @Test
    fun maxBoundsChange_emitsNaturalMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(width = DISPLAY_WIDTH * 2, height = DISPLAY_HEIGHT * 3)
            runCurrent()

            assertThat(values)
                .containsExactly(
                    Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT),
                    Rect(0, 0, DISPLAY_WIDTH * 2, DISPLAY_HEIGHT * 3),
                )
                .inOrder()
        }

    @Test
    fun maxBoundsSameOnConfigChange_doesNotEmitNaturalMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    @Test
    fun firstMaxBoundsChange_emitsNaturalMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    @Test
    fun displayRotatedButMaxBoundsTheSame_doesNotEmitNewNaturalMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(
                width = DISPLAY_HEIGHT,
                height = DISPLAY_WIDTH,
                rotation = Surface.ROTATION_90
            )
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    private fun updateDisplay(
        width: Int = DISPLAY_WIDTH,
        height: Int = DISPLAY_HEIGHT,
        @Surface.Rotation rotation: Int = Surface.ROTATION_0
    ) {
        configuration.windowConfiguration.maxBounds.set(Rect(0, 0, width, height))
        configuration.windowConfiguration.displayRotation = rotation

        configurationRepository.onConfigurationChange(configuration)
    }

    private companion object {
        private const val DISPLAY_WIDTH = 100
        private const val DISPLAY_HEIGHT = 200
    }
}
