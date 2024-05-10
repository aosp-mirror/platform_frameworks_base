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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class ConfigurationInteractorTest : SysuiTestCase() {
    private lateinit var testScope: TestScope
    private lateinit var underTest: ConfigurationInteractor
    private lateinit var configurationRepository: FakeConfigurationRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

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
}
