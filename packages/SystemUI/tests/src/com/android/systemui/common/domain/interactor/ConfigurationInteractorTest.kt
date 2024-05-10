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
package com.android.systemui.common.domain.interactor

import android.content.res.Configuration
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.Surface.ROTATION_0
import android.view.Surface.ROTATION_90
import android.view.Surface.Rotation
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.ConfigurationRepositoryImpl
import com.android.systemui.coroutines.collectValues
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
open class ConfigurationInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()

    private val configurationController = FakeConfigurationController()
    private val configurationRepository =
        ConfigurationRepositoryImpl(
            configurationController,
            context,
            testScope.backgroundScope,
            mock()
        )

    private lateinit var configuration: Configuration
    private lateinit var underTest: ConfigurationInteractor

    @Before
    fun setUp() {
        configuration = context.resources.configuration

        val testableResources = context.getOrCreateTestableResources()
        testableResources.overrideConfiguration(configuration)

        underTest = ConfigurationInteractorImpl(configurationRepository)
    }

    @Test
    fun maxBoundsChange_emitsMaxBoundsChange() =
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
    fun maxBoundsSameOnConfigChange_doesNotEmitMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    @Test
    fun firstMaxBoundsChange_emitsMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    @Test
    fun displayRotatedButMaxBoundsTheSame_doesNotEmitNewMaxBoundsChange() =
        testScope.runTest {
            val values by collectValues(underTest.naturalMaxBounds)

            updateDisplay(width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
            runCurrent()
            updateDisplay(width = DISPLAY_HEIGHT, height = DISPLAY_WIDTH, rotation = ROTATION_90)
            runCurrent()

            assertThat(values).containsExactly(Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT))
        }

    private fun updateDisplay(
        width: Int = DISPLAY_WIDTH,
        height: Int = DISPLAY_HEIGHT,
        @Rotation rotation: Int = ROTATION_0
    ) {
        configuration.windowConfiguration.maxBounds.set(Rect(0, 0, width, height))
        configuration.windowConfiguration.displayRotation = rotation

        configurationController.onConfigurationChanged(configuration)
    }

    private companion object {
        private const val DISPLAY_WIDTH = 100
        private const val DISPLAY_HEIGHT = 200
    }
}
