/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bluetooth.qsdialog

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.res.R
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AudioSharingInteractorTest : SysuiTestCase() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val bluetoothState = MutableStateFlow(false)
    private val deviceItemUpdate: MutableSharedFlow<List<DeviceItem>> = MutableSharedFlow()
    @Mock private lateinit var cachedBluetoothDevice: CachedBluetoothDevice
    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var bluetoothStateInteractor: BluetoothStateInteractor
    @Mock private lateinit var deviceItemInteractor: DeviceItemInteractor
    @Mock private lateinit var deviceItem: DeviceItem
    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var audioSharingInteractor: AudioSharingInteractor

    @Before
    fun setUp() {
        mockitoSession =
            mockitoSession().initMocks(this).mockStatic(BluetoothUtils::class.java).startMocking()
        whenever(bluetoothStateInteractor.bluetoothStateUpdate).thenReturn(bluetoothState)
        whenever(deviceItemInteractor.deviceItemUpdate).thenReturn(deviceItemUpdate)
        audioSharingInteractor =
            AudioSharingInteractor(
                localBluetoothManager,
                bluetoothStateInteractor,
                deviceItemInteractor,
                testScope.backgroundScope,
                testDispatcher,
            )
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun testButtonStateUpdate_bluetoothOff_returnGone() {
        testScope.runTest {
            val actual by collectLastValue(audioSharingInteractor.audioSharingButtonStateUpdate)

            assertThat(actual).isEqualTo(AudioSharingButtonState.Gone)
        }
    }

    @Test
    fun testButtonStateUpdate_noDevice_returnGone() {
        testScope.runTest {
            val actual by collectLastValue(audioSharingInteractor.audioSharingButtonStateUpdate)
            bluetoothState.value = true
            runCurrent()

            assertThat(actual).isEqualTo(AudioSharingButtonState.Gone)
        }
    }

    @Test
    fun testButtonStateUpdate_isBroadcasting_returnSharingAudio() {
        testScope.runTest {
            whenever(BluetoothUtils.isBroadcasting(localBluetoothManager)).thenReturn(true)

            val actual by collectLastValue(audioSharingInteractor.audioSharingButtonStateUpdate)
            bluetoothState.value = true
            deviceItemUpdate.emit(listOf())
            runCurrent()

            assertThat(actual)
                .isEqualTo(
                    AudioSharingButtonState.Visible(
                        R.string.quick_settings_bluetooth_audio_sharing_button_sharing,
                        isActive = true
                    )
                )
        }
    }

    @Test
    fun testButtonStateUpdate_hasSource_returnGone() {
        testScope.runTest {
            whenever(BluetoothUtils.isBroadcasting(localBluetoothManager)).thenReturn(false)
            whenever(deviceItem.cachedBluetoothDevice).thenReturn(cachedBluetoothDevice)
            whenever(
                    BluetoothUtils.hasConnectedBroadcastSource(
                        cachedBluetoothDevice,
                        localBluetoothManager
                    )
                )
                .thenReturn(true)

            val actual by collectLastValue(audioSharingInteractor.audioSharingButtonStateUpdate)
            bluetoothState.value = true
            deviceItemUpdate.emit(listOf(deviceItem))
            runCurrent()

            assertThat(actual).isEqualTo(AudioSharingButtonState.Gone)
        }
    }

    @Test
    fun testButtonStateUpdate_hasActiveDevice_returnAudioSharing() {
        testScope.runTest {
            whenever(BluetoothUtils.isBroadcasting(localBluetoothManager)).thenReturn(false)
            whenever(deviceItem.cachedBluetoothDevice).thenReturn(cachedBluetoothDevice)
            whenever(
                    BluetoothUtils.hasConnectedBroadcastSource(
                        cachedBluetoothDevice,
                        localBluetoothManager
                    )
                )
                .thenReturn(false)
            whenever(BluetoothUtils.isActiveLeAudioDevice(cachedBluetoothDevice)).thenReturn(true)

            val actual by collectLastValue(audioSharingInteractor.audioSharingButtonStateUpdate)
            bluetoothState.value = true
            deviceItemUpdate.emit(listOf(deviceItem))
            runCurrent()

            assertThat(actual)
                .isEqualTo(
                    AudioSharingButtonState.Visible(
                        R.string.quick_settings_bluetooth_audio_sharing_button,
                        isActive = false
                    )
                )
        }
    }
}
