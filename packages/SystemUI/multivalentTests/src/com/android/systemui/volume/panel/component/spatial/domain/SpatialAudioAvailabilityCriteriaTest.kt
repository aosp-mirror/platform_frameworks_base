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

package com.android.systemui.volume.panel.component.spatial.domain

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LeAudioProfile
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.spatializerRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.panel.component.spatial.spatialAudioComponentInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class SpatialAudioAvailabilityCriteriaTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private lateinit var underTest: SpatialAudioAvailabilityCriteria

    @Before
    fun setup() {
        with(kosmos) {
            val leAudioProfile =
                mock<LeAudioProfile> {
                    whenever(profileId).thenReturn(BluetoothProfile.LE_AUDIO)
                    whenever(isEnabled(any())).thenReturn(true)
                }
            val cachedBluetoothDevice: CachedBluetoothDevice = mock {
                whenever(address).thenReturn("test_address")
                whenever(profiles).thenReturn(listOf(leAudioProfile))
                whenever(device).thenReturn(mock<BluetoothDevice> {})
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

            underTest = SpatialAudioAvailabilityCriteria(spatialAudioComponentInteractor)
        }
    }

    @Test
    fun noSpatialAudio_noHeadTracking_unavailable() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(headset, false)
                spatializerRepository.setIsHeadTrackingAvailable(headset, false)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isFalse()
            }
        }
    }

    @Test
    fun spatialAudio_noHeadTracking_available() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(headset, true)
                spatializerRepository.setIsHeadTrackingAvailable(headset, false)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isTrue()
            }
        }
    }

    @Test
    fun spatialAudio_headTracking_available() {
        with(kosmos) {
            testScope.runTest {
                spatializerRepository.setIsSpatialAudioAvailable(headset, true)
                spatializerRepository.setIsHeadTrackingAvailable(headset, true)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isTrue()
            }
        }
    }

    @Test
    fun spatialAudio_headTracking_noDevice_unavailable() {
        with(kosmos) {
            testScope.runTest {
                localMediaRepository.updateCurrentConnectedDevice(null)
                spatializerRepository.setIsSpatialAudioAvailable(headset, false)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isFalse()
            }
        }
    }

    private companion object {
        val headset =
            AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                "test_address"
            )
    }
}
