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

import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.spatializerInteractor
import com.android.systemui.media.spatializerRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaController
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.mediaOutputInteractor
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioEnabledModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SpatialAudioComponentInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private lateinit var underTest: SpatialAudioComponentInteractor

    @Before
    fun setup() {
        with(kosmos) {
            val cachedBluetoothDevice: CachedBluetoothDevice = mock {
                whenever(address).thenReturn("test_address")
            }
            localMediaRepository.updateCurrentConnectedDevice(
                mock<BluetoothMediaDevice> {
                    whenever(name).thenReturn("test_device")
                    whenever(cachedDevice).thenReturn(cachedBluetoothDevice)
                }
            )

            whenever(mediaController.packageName).thenReturn("test.pkg")
            whenever(mediaController.sessionToken).thenReturn(MediaSession.Token(0, mock {}))
            whenever(mediaController.playbackState).thenReturn(PlaybackState.Builder().build())

            mediaControllerRepository.setActiveLocalMediaController(mediaController)

            spatializerRepository.setIsSpatialAudioAvailable(
                AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    "test_address"
                ),
                true
            )
            spatializerRepository.setIsHeadTrackingAvailable(true)

            underTest =
                SpatialAudioComponentInteractor(
                    mediaOutputInteractor,
                    spatializerInteractor,
                    testScope.backgroundScope,
                )
        }
    }

    @Test
    fun setEnabled_changesIsEnabled() {
        with(kosmos) {
            testScope.runTest {
                val values by collectValues(underTest.isEnabled)

                underTest.setEnabled(SpatialAudioEnabledModel.Disabled)
                runCurrent()
                underTest.setEnabled(SpatialAudioEnabledModel.HeadTrackingEnabled)
                runCurrent()
                underTest.setEnabled(SpatialAudioEnabledModel.SpatialAudioEnabled)
                runCurrent()

                assertThat(values)
                    .containsExactly(
                        SpatialAudioEnabledModel.Disabled,
                        SpatialAudioEnabledModel.HeadTrackingEnabled,
                        SpatialAudioEnabledModel.SpatialAudioEnabled,
                    )
                    .inOrder()
            }
        }
    }
}
