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
import android.media.AudioManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.R
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.bluetooth.bluetoothAdapter
import com.android.systemui.bluetooth.cachedBluetoothDeviceManager
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.TestAudioDevicesFactory
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.data.repository.audioSharingRepository
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.TestMediaDevicesFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val builtInDeviceName = "This phone"

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
                addOverride(R.drawable.ic_media_tablet, testIcon)

                addOverride(com.android.internal.R.drawable.ic_bt_hearing_aid, testIcon)

                addOverride(R.string.media_transfer_this_device_name_tv, builtInDeviceName)
                addOverride(R.string.media_transfer_this_device_name_tablet, builtInDeviceName)
                addOverride(R.string.media_transfer_this_device_name, builtInDeviceName)
            }
        }
    }

    @Test
    fun inCall_builtIn_returnsCommunicationDevice() {
        with(kosmos) {
            testScope.runTest {
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(TestAudioDevicesFactory.builtInDevice())
                }

                val device by collectLastValue(underTest.currentAudioDevice)

                runCurrent()

                assertThat(device).isInstanceOf(AudioOutputDevice.BuiltIn::class.java)
                assertThat(device!!.icon).isEqualTo(testIcon)
                assertThat(device!!.name).isEqualTo(builtInDeviceName)
            }
        }
    }

    @Test
    fun inCall_wired_returnsCommunicationDevice() {
        with(kosmos) {
            testScope.runTest {
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(TestAudioDevicesFactory.wiredDevice())
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
                val btDevice = TestAudioDevicesFactory.bluetoothDevice()
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(btDevice)
                }
                val bluetoothDevice: BluetoothDevice = mock {
                    on { address }.thenReturn(btDevice.address)
                }
                val cachedBluetoothDevice: CachedBluetoothDevice = mock {
                    on { address }.thenReturn(btDevice.address)
                    on { name }.thenReturn(btDevice.productName.toString())
                    on { isHearingAidDevice }.thenReturn(true)
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
                localMediaRepository.updateCurrentConnectedDevice(
                    TestMediaDevicesFactory.builtInMediaDevice()
                )

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
                localMediaRepository.updateCurrentConnectedDevice(
                    TestMediaDevicesFactory.wiredMediaDevice()
                )

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
                localMediaRepository.updateCurrentConnectedDevice(
                    TestMediaDevicesFactory.bluetoothMediaDevice()
                )

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
