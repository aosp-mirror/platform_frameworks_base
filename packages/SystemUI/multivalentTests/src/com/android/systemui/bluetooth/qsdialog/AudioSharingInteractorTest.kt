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

import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSharingInteractorTest : SysuiTestCase() {
    @get:Rule val mockito: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos()

    @Mock private lateinit var localBluetoothLeBroadcast: LocalBluetoothLeBroadcast

    @Mock private lateinit var bluetoothLeBroadcastMetadata: BluetoothLeBroadcastMetadata

    @Captor private lateinit var callbackCaptor: ArgumentCaptor<BluetoothLeBroadcast.Callback>
    private lateinit var underTest: AudioSharingInteractor

    @Before
    fun setUp() {
        with(kosmos) { underTest = audioSharingInteractor }
    }

    @Test
    fun isAudioSharingOn_flagOff_false() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(false)
                val value by collectLastValue(underTest.isAudioSharingOn)
                runCurrent()

                assertThat(value).isFalse()
            }
        }

    @Test
    fun isAudioSharingOn_flagOn_notInAudioSharing_false() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(false)
                val value by collectLastValue(underTest.isAudioSharingOn)
                runCurrent()

                assertThat(value).isFalse()
            }
        }

    @Test
    fun isAudioSharingOn_flagOn_inAudioSharing_true() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(true)
                val value by collectLastValue(underTest.isAudioSharingOn)
                runCurrent()

                assertThat(value).isTrue()
            }
        }

    @Test
    fun audioSourceStateUpdate_notInAudioSharing_returnEmpty() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(false)
                val value by collectLastValue(underTest.audioSourceStateUpdate)
                runCurrent()

                assertThat(value).isNull()
            }
        }

    @Test
    fun audioSourceStateUpdate_inAudioSharing_returnUnit() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(true)
                val value by collectLastValue(underTest.audioSourceStateUpdate)
                runCurrent()
                bluetoothTileDialogAudioSharingRepository.emitAudioSourceStateUpdate()
                runCurrent()

                assertThat(value).isNull()
            }
        }

    @Test
    fun handleAudioSourceWhenReady_flagOff_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(false)
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun handleAudioSourceWhenReady_noProfile_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(null)
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun handleAudioSourceWhenReady_hasProfileButAudioSharingNeverTriggered_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()

                // Verify callback registered for onBroadcastStartedOrStopped
                verify(localBluetoothLeBroadcast).registerServiceCallBack(any(), any())
                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun handleAudioSourceWhenReady_audioSharingTriggeredButFailed_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()
                // Verify callback registered for onBroadcastStartedOrStopped
                verify(localBluetoothLeBroadcast)
                    .registerServiceCallBack(any(), callbackCaptor.capture())
                // Audio sharing started failed, trigger onBroadcastStartFailed
                whenever(localBluetoothLeBroadcast.isEnabled(null)).thenReturn(false)
                underTest.startAudioSharing()
                runCurrent()
                callbackCaptor.value.onBroadcastStartFailed(0)
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun handleAudioSourceWhenReady_audioSharingTriggeredButMetadataNotReady_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()
                // Verify callback registered for onBroadcastStartedOrStopped
                verify(localBluetoothLeBroadcast)
                    .registerServiceCallBack(any(), callbackCaptor.capture())
                runCurrent()
                underTest.startAudioSharing()
                runCurrent()
                // Verify callback registered for onBroadcastMetadataChanged
                verify(localBluetoothLeBroadcast, times(2))
                    .registerServiceCallBack(any(), callbackCaptor.capture())

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun handleAudioSourceWhenReady_audioSharingTriggeredAndMetadataReady_sourceAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()
                // Verify callback registered for onBroadcastStartedOrStopped
                verify(localBluetoothLeBroadcast)
                    .registerServiceCallBack(any(), callbackCaptor.capture())
                // Audio sharing started, trigger onBroadcastStarted
                whenever(localBluetoothLeBroadcast.isEnabled(null)).thenReturn(true)
                underTest.startAudioSharing()
                runCurrent()
                callbackCaptor.value.onBroadcastStarted(0, 0)
                runCurrent()
                // Verify callback registered for onBroadcastMetadataChanged
                verify(localBluetoothLeBroadcast, times(2))
                    .registerServiceCallBack(any(), callbackCaptor.capture())
                runCurrent()
                // Trigger onBroadcastMetadataChanged (ready to add source)
                callbackCaptor.value.onBroadcastMetadataChanged(0, bluetoothLeBroadcastMetadata)
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isTrue()
                job.cancel()
            }
        }
}
