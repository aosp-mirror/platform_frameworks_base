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

package com.android.systemui.volume.domain.interactor

import android.bluetooth.BluetoothDevice
import android.graphics.drawable.TestStubDrawable
import android.media.AudioDeviceInfo
import android.media.AudioDevicePort
import android.media.AudioManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.R
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.PhoneMediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.bluetooth.bluetoothAdapter
import com.android.systemui.bluetooth.cachedBluetoothDeviceManager
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.data.repository.audioSharingRepository
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaControllerRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AudioOutputInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    lateinit var underTest: AudioOutputInteractor

    @Before
    fun setUp() {
        with(kosmos) {
            underTest = audioOutputInteractor

            with(context.orCreateTestableResources) {
                addOverride(R.drawable.ic_headphone, testIcon)
                addOverride(R.drawable.ic_smartphone, testIcon)
                addOverride(R.drawable.ic_media_speaker_device, testIcon)

                addOverride(com.android.internal.R.drawable.ic_bt_hearing_aid, testIcon)
            }
        }
    }

    @Test
    fun inCall_builtIn_returnsCommunicationDevice() {
        with(kosmos) {
            testScope.runTest {
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(builtInDevice)
                }

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.BuiltIn::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo("built_in")
            }
        }
    }

    @Test
    fun inCall_wired_returnsCommunicationDevice() {
        with(kosmos) {
            testScope.runTest {
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(wiredDevice)
                }

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.Wired::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo("wired")
            }
        }
    }

    @Test
    fun inCall_bluetooth_returnsCommunicationDevice() {
        with(kosmos) {
            testScope.runTest {
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(btDevice)
                }
                val bluetoothDevice: BluetoothDevice = mock {
                    whenever(address).thenReturn(btDevice.address)
                }
                val cachedBluetoothDevice: CachedBluetoothDevice = mock {
                    whenever(address).thenReturn(btDevice.address)
                    whenever(name).thenReturn(btDevice.productName.toString())
                    whenever(isHearingAidDevice).thenReturn(true)
                }
                whenever(bluetoothAdapter.getRemoteDevice(eq(btDevice.address)))
                    .thenReturn(bluetoothDevice)
                whenever(cachedBluetoothDeviceManager.findDevice(any()))
                    .thenReturn(cachedBluetoothDevice)

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.Bluetooth::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo("bt")
            }
        }
    }

    @Test
    fun notInCall_builtIn_returnsMediaDevice() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_NORMAL)
                mediaControllerRepository.setActiveSessions(listOf(localMediaController))
                localMediaRepository.updateCurrentConnectedDevice(builtInMediaDevice)

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.BuiltIn::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo("built_in_media")
            }
        }
    }

    @Test
    fun notInCall_wired_returnsMediaDevice() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_NORMAL)
                mediaControllerRepository.setActiveSessions(listOf(localMediaController))
                localMediaRepository.updateCurrentConnectedDevice(wiredMediaDevice)

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.Wired::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo("wired_media")
            }
        }
    }

    @Test
    fun notInCall_bluetooth_returnsMediaDevice() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_NORMAL)
                mediaControllerRepository.setActiveSessions(listOf(localMediaController))
                localMediaRepository.updateCurrentConnectedDevice(bluetoothMediaDevice)

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.Bluetooth::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo("bt_media")
            }
        }
    }

    private companion object {
        val testIcon = TestStubDrawable()
        val builtInDevice =
            AudioDeviceInfo(
                AudioDevicePort.createForTesting(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    "built_in",
                    ""
                )
            )
        val wiredDevice =
            AudioDeviceInfo(
                AudioDevicePort.createForTesting(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "wired", "")
            )
        val btDevice =
            AudioDeviceInfo(
                AudioDevicePort.createForTesting(
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    "bt",
                    "test_address"
                )
            )
        val builtInMediaDevice: MediaDevice =
            mock<PhoneMediaDevice> {
                whenever(name).thenReturn("built_in_media")
                whenever(icon).thenReturn(testIcon)
            }
        val wiredMediaDevice: MediaDevice =
            mock<PhoneMediaDevice> {
                whenever(deviceType)
                    .thenReturn(MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE)
                whenever(name).thenReturn("wired_media")
                whenever(icon).thenReturn(testIcon)
            }
        val bluetoothMediaDevice: MediaDevice =
            mock<BluetoothMediaDevice> {
                whenever(name).thenReturn("bt_media")
                whenever(icon).thenReturn(testIcon)
                val cachedBluetoothDevice: CachedBluetoothDevice = mock {
                    whenever(isHearingAidDevice).thenReturn(true)
                }
                whenever(cachedDevice).thenReturn(cachedBluetoothDevice)
            }
    }

    @Test
    fun inAudioSharing_returnTrue() {
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setInAudioSharing(true)

                val inAudioSharing by collectLastValue(underTest.isInAudioSharing)
                runCurrent()

                assertThat(inAudioSharing).isTrue()
            }
        }
    }

    @Test
    fun notInAudioSharing_returnFalse() {
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setInAudioSharing(false)

                val inAudioSharing by collectLastValue(underTest.isInAudioSharing)
                runCurrent()

                assertThat(inAudioSharing).isFalse()
            }
        }
    }
}
