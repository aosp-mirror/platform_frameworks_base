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

package com.android.systemui.volume.panel.component.spatial.domain.interactor

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.A2dpProfile
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.HearingAidProfile
import com.android.settingslib.bluetooth.LeAudioProfile
import com.android.settingslib.flags.Flags
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.spatializerRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioAvailabilityModel
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioEnabledModel
import com.android.systemui.volume.panel.component.spatial.spatialAudioComponentInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SpatialAudioComponentInteractorTest : SysuiTestCase() {
    @get:Rule val setFlagsRule = SetFlagsRule()

    private val kosmos = testKosmos()
    private lateinit var underTest: SpatialAudioComponentInteractor
    private val a2dpProfile: A2dpProfile = mock {
        whenever(profileId).thenReturn(BluetoothProfile.A2DP)
    }
    private val leAudioProfile: LeAudioProfile = mock {
        whenever(profileId).thenReturn(BluetoothProfile.LE_AUDIO)
    }
    private val hearingAidProfile: HearingAidProfile = mock {
        whenever(profileId).thenReturn(BluetoothProfile.HEARING_AID)
    }
    private val bluetoothDevice: BluetoothDevice = mock {}

    @Before
    fun setup() {
        with(kosmos) {
            val cachedBluetoothDevice: CachedBluetoothDevice = mock {
                whenever(address).thenReturn("test_address")
                whenever(device).thenReturn(bluetoothDevice)
                whenever(profiles)
                    .thenReturn(listOf(a2dpProfile, leAudioProfile, hearingAidProfile))
            }
            localMediaRepository.updateCurrentConnectedDevice(
                mock<BluetoothMediaDevice> {
                    whenever(name).thenReturn("test_device")
                    whenever(cachedDevice).thenReturn(cachedBluetoothDevice)
                }
            )

            whenever(localMediaController.packageName).thenReturn("test.pkg")
            whenever(localMediaController.sessionToken).thenReturn(MediaSession.Token(0, mock {}))
            whenever(localMediaController.playbackState).thenReturn(PlaybackState.Builder().build())

            mediaControllerRepository.setActiveSessions(listOf(localMediaController))

            underTest = spatialAudioComponentInteractor
        }
    }

    @Test
    fun setEnabled_changesIsEnabled() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(bleHeadsetAttributes, true)
                val values by collectValues(underTest.isEnabled)

                underTest.setEnabled(SpatialAudioEnabledModel.Disabled)
                runCurrent()
                underTest.setEnabled(SpatialAudioEnabledModel.HeadTrackingEnabled)
                runCurrent()
                underTest.setEnabled(SpatialAudioEnabledModel.SpatialAudioEnabled)
                runCurrent()

                assertThat(values)
                    .containsExactly(
                        SpatialAudioEnabledModel.Unknown,
                        SpatialAudioEnabledModel.Disabled,
                        SpatialAudioEnabledModel.HeadTrackingEnabled,
                        SpatialAudioEnabledModel.SpatialAudioEnabled,
                    )
                    .inOrder()
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DETERMINING_SPATIAL_AUDIO_ATTRIBUTES_BY_PROFILE)
    fun setEnabled_determinedByBluetoothProfile_a2dpProfileEnabled() {
        with(kosmos) {
            testScope.runTest {
                whenever(a2dpProfile.isEnabled(bluetoothDevice)).thenReturn(true)
                whenever(leAudioProfile.isEnabled(bluetoothDevice)).thenReturn(false)
                whenever(hearingAidProfile.isEnabled(bluetoothDevice)).thenReturn(false)
                spatializerRepository.setIsSpatialAudioAvailable(a2dpAttributes, true)
                val values by collectValues(underTest.isEnabled)

                underTest.setEnabled(SpatialAudioEnabledModel.Disabled)
                runCurrent()
                underTest.setEnabled(SpatialAudioEnabledModel.SpatialAudioEnabled)
                runCurrent()

                assertThat(values)
                    .containsExactly(
                        SpatialAudioEnabledModel.Unknown,
                        SpatialAudioEnabledModel.Disabled,
                        SpatialAudioEnabledModel.SpatialAudioEnabled,
                    )
                    .inOrder()
                assertThat(spatializerRepository.getSpatialAudioCompatibleDevices())
                    .containsExactly(a2dpAttributes)
            }
        }
    }

    @Test
    fun connectedDeviceSupports_isAvailable_SpatialAudio() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(bleHeadsetAttributes, true)

                val isAvailable by collectLastValue(underTest.isAvailable)

                assertThat(isAvailable)
                    .isInstanceOf(SpatialAudioAvailabilityModel.SpatialAudio::class.java)
            }
        }
    }

    @Test
    fun connectedDeviceSupportsHeadTracking_isAvailable_HeadTracking() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(bleHeadsetAttributes, true)
                spatializerRepository.setIsHeadTrackingAvailable(bleHeadsetAttributes, true)

                val isAvailable by collectLastValue(underTest.isAvailable)

                assertThat(isAvailable)
                    .isInstanceOf(SpatialAudioAvailabilityModel.HeadTracking::class.java)
            }
        }
    }

    @Test
    fun connectedDeviceDoesntSupport_isAvailable_Unavailable() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(bleHeadsetAttributes, false)

                val isAvailable by collectLastValue(underTest.isAvailable)

                assertThat(isAvailable)
                    .isInstanceOf(SpatialAudioAvailabilityModel.Unavailable::class.java)
            }
        }
    }

    @Test
    fun noConnectedDeviceBuiltinSupports_isAvailable_SpatialAudio() {
        with(kosmos) {
            testScope.runTest {
                localMediaRepository.updateCurrentConnectedDevice(null)
                spatializerRepository.setIsSpatialAudioAvailable(builtinSpeaker, true)

                val isAvailable by collectLastValue(underTest.isAvailable)

                assertThat(isAvailable)
                    .isInstanceOf(SpatialAudioAvailabilityModel.SpatialAudio::class.java)
            }
        }
    }

    @Test
    fun noConnectedDeviceBuiltinDoesntSupport_isAvailable_Unavailable() {
        with(kosmos) {
            testScope.runTest {
                localMediaRepository.updateCurrentConnectedDevice(null)
                spatializerRepository.setIsSpatialAudioAvailable(builtinSpeaker, false)

                val isAvailable by collectLastValue(underTest.isAvailable)

                assertThat(isAvailable)
                    .isInstanceOf(SpatialAudioAvailabilityModel.Unavailable::class.java)
            }
        }
    }

    private companion object {
        val a2dpAttributes =
            AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                "test_address"
            )
        val bleHeadsetAttributes =
            AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                "test_address"
            )
        val builtinSpeaker =
            AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                ""
            )
    }
}
