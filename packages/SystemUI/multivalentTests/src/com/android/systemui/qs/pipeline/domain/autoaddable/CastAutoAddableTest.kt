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
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.CastTile
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class CastAutoAddableTest : SysuiTestCase() {

    @Mock private lateinit var castController: CastController
    @Captor private lateinit var callbackCaptor: ArgumentCaptor<CastController.Callback>

    private lateinit var underTest: CastAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = CastAutoAddable(castController)
    }

    @Test
    fun onCastDevicesChanged_noDevices_noSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(castController).addCallback(capture(callbackCaptor))

        callbackCaptor.value.onCastDevicesChanged()

        assertThat(signal).isNull()
    }

    @Test
    fun onCastDevicesChanged_deviceNotConnectedOrConnecting_noSignal() = runTest {
        val device =
            CastController.CastDevice().apply {
                state = CastController.CastDevice.STATE_DISCONNECTED
            }
        whenever(castController.castDevices).thenReturn(listOf(device))

        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(castController).addCallback(capture(callbackCaptor))

        callbackCaptor.value.onCastDevicesChanged()

        assertThat(signal).isNull()
    }

    @Test
    fun onCastDevicesChanged_someDeviceConnecting_addSignal() = runTest {
        val disconnectedDevice =
            CastController.CastDevice().apply {
                state = CastController.CastDevice.STATE_DISCONNECTED
            }
        val connectingDevice =
            CastController.CastDevice().apply { state = CastController.CastDevice.STATE_CONNECTING }
        whenever(castController.castDevices)
            .thenReturn(listOf(disconnectedDevice, connectingDevice))

        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(castController).addCallback(capture(callbackCaptor))

        callbackCaptor.value.onCastDevicesChanged()

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun onCastDevicesChanged_someDeviceConnected_addSignal() = runTest {
        val disconnectedDevice =
            CastController.CastDevice().apply {
                state = CastController.CastDevice.STATE_DISCONNECTED
            }
        val connectedDevice =
            CastController.CastDevice().apply { state = CastController.CastDevice.STATE_CONNECTED }
        whenever(castController.castDevices).thenReturn(listOf(disconnectedDevice, connectedDevice))

        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(castController).addCallback(capture(callbackCaptor))

        callbackCaptor.value.onCastDevicesChanged()

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    companion object {
        private val SPEC by lazy { TileSpec.create(CastTile.TILE_SPEC) }
    }
}
