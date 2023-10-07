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
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.DeviceControlsTile
import com.android.systemui.statusbar.policy.DeviceControlsController
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
class DeviceControlsAutoAddableTest : SysuiTestCase() {

    @Mock private lateinit var deviceControlsController: DeviceControlsController
    @Captor private lateinit var callbackCaptor: ArgumentCaptor<DeviceControlsController.Callback>

    private lateinit var underTest: DeviceControlsAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = DeviceControlsAutoAddable(deviceControlsController)
    }

    @Test
    fun strategyAlways() {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.Always)
    }

    @Test
    fun onControlsUpdate_position_addSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        val position = 5
        runCurrent()

        verify(deviceControlsController).setCallback(capture(callbackCaptor))
        callbackCaptor.value.onControlsUpdate(position)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC, position))
        verify(deviceControlsController).removeCallback()
    }

    @Test
    fun onControlsUpdate_nullPosition_noAddSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(deviceControlsController).setCallback(capture(callbackCaptor))
        callbackCaptor.value.onControlsUpdate(null)

        assertThat(signal).isNull()
        verify(deviceControlsController).removeCallback()
    }

    @Test
    fun onRemoveControlsAutoTracker_removeSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(deviceControlsController).setCallback(capture(callbackCaptor))
        callbackCaptor.value.removeControlsAutoTracker()

        assertThat(signal).isEqualTo(AutoAddSignal.Remove(SPEC))
    }

    @Test
    fun flowCancelled_removeCallback() = runTest {
        val job = launch { underTest.autoAddSignal(0).collect() }
        runCurrent()

        job.cancel()
        runCurrent()
        verify(deviceControlsController).removeCallback()
    }

    companion object {
        private val SPEC by lazy { TileSpec.create(DeviceControlsTile.TILE_SPEC) }
    }
}
