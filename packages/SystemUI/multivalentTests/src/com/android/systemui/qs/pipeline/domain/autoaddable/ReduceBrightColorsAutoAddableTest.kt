/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.ReduceBrightColorsController
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ReduceBrightColorsAutoAddableTest : SysuiTestCase() {

    @Mock private lateinit var reduceBrightColorsController: ReduceBrightColorsController
    @Captor
    private lateinit var reduceBrightColorsListenerCaptor:
        ArgumentCaptor<ReduceBrightColorsController.Listener>

    private lateinit var underTest: ReduceBrightColorsAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun notAvailable_strategyDisabled() =
        testWithFeatureAvailability(available = false) {
            assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Disabled)
        }

    @Test
    fun available_strategyIfNotAdded() =
        testWithFeatureAvailability(available = true) {
            assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.IfNotAdded(SPEC))
        }

    @Test
    fun activated_addSignal() = testWithFeatureAvailability {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(reduceBrightColorsController).addCallback(capture(reduceBrightColorsListenerCaptor))

        reduceBrightColorsListenerCaptor.value.onActivated(true)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun notActivated_noSignal() = testWithFeatureAvailability {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(reduceBrightColorsController).addCallback(capture(reduceBrightColorsListenerCaptor))

        reduceBrightColorsListenerCaptor.value.onActivated(false)

        assertThat(signal).isNull()
    }

    private fun testWithFeatureAvailability(
        available: Boolean = true,
        body: suspend TestScope.() -> TestResult
    ) = runTest {
        underTest = ReduceBrightColorsAutoAddable(reduceBrightColorsController, available)
        body()
    }

    companion object {
        private val SPEC by lazy { TileSpec.create(ReduceBrightColorsTile.TILE_SPEC) }
    }
}
