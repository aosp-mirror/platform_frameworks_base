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

import android.hardware.display.NightDisplayListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dagger.NightDisplayListenerModule
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.NightDisplayTile
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NightDisplayAutoAddableTest : SysuiTestCase() {

    @Mock(answer = Answers.RETURNS_SELF)
    private lateinit var nightDisplayListenerBuilder: NightDisplayListenerModule.Builder
    @Mock private lateinit var nightDisplayListener: NightDisplayListener
    @Captor private lateinit var callbackCaptor: ArgumentCaptor<NightDisplayListener.Callback>

    private lateinit var underTest: NightDisplayAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(nightDisplayListenerBuilder.build()).thenReturn(nightDisplayListener)
    }

    @Test
    fun disabled_strategyDisabled() =
        testWithFeatureAvailability(enabled = false) {
            assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Disabled)
        }

    @Test
    fun enabled_strategyIfNotAdded() = testWithFeatureAvailability {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.IfNotAdded(SPEC))
    }

    @Test
    fun listenerCreatedForCorrectUser() = testWithFeatureAvailability {
        val user = 42
        backgroundScope.launch { underTest.autoAddSignal(user).collect() }
        runCurrent()

        val inOrder = inOrder(nightDisplayListenerBuilder)
        inOrder.verify(nightDisplayListenerBuilder).setUser(user)
        inOrder.verify(nightDisplayListenerBuilder).build()
    }

    @Test
    fun onCancelFlow_removeCallback() = testWithFeatureAvailability {
        val job = launch { underTest.autoAddSignal(0).collect() }
        runCurrent()
        job.cancel()
        runCurrent()
        verify(nightDisplayListener).setCallback(null)
    }

    @Test
    fun onActivatedTrue_addSignal() = testWithFeatureAvailability {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(nightDisplayListener).setCallback(capture(callbackCaptor))
        callbackCaptor.value.onActivated(true)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    private fun testWithFeatureAvailability(
        enabled: Boolean = true,
        body: suspend TestScope.() -> TestResult
    ) = runTest {
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_nightDisplayAvailable,
            enabled
        )
        underTest = NightDisplayAutoAddable(nightDisplayListenerBuilder, context)
        body()
    }

    companion object {
        private val SPEC by lazy { TileSpec.create(NightDisplayTile.TILE_SPEC) }
    }
}
