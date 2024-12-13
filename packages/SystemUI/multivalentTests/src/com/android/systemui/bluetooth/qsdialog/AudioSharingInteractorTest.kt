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
import android.content.ContentResolver
import android.content.applicationContext
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.BluetoothEventManager
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.settingslib.bluetooth.VolumeControlProfile
import com.android.settingslib.volume.shared.AudioSharingLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
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
    fun testIsAudioSharingOn_flagOff_false() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(false)
                val value by collectLastValue(underTest.isAudioSharingOn)
                runCurrent()

                assertThat(value).isFalse()
            }
        }

    @Test
    fun testIsAudioSharingOn_flagOn_notInAudioSharing_false() =
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
    fun testIsAudioSharingOn_flagOn_inAudioSharing_true() =
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
    fun testAudioSourceStateUpdate_notInAudioSharing_returnEmpty() =
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
    fun testAudioSourceStateUpdate_inAudioSharing_returnUnit() =
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
    fun testHandleAudioSourceWhenReady_flagOff_sourceNotAdded() =
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
    fun testHandleAudioSourceWhenReady_noProfile_sourceNotAdded() =
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
    fun testHandleAudioSourceWhenReady_hasProfileButAudioSharingOff_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(true)
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(false)
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun testHandleAudioSourceWhenReady_audioSharingOnButNoPlayback_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(false)
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(true)
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isFalse()
                job.cancel()
            }
        }

    @Test
    fun testHandleAudioSourceWhenReady_audioSharingOnAndPlaybackStarts_sourceAdded() =
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(false)
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                bluetoothTileDialogAudioSharingRepository.setLeAudioBroadcastProfile(
                    localBluetoothLeBroadcast
                )
                val job = launch { underTest.handleAudioSourceWhenReady() }
                runCurrent()
                bluetoothTileDialogAudioSharingRepository.setInAudioSharing(true)
                runCurrent()
                verify(localBluetoothLeBroadcast)
                    .registerServiceCallBack(any(), callbackCaptor.capture())
                runCurrent()
                callbackCaptor.value.onBroadcastMetadataChanged(0, bluetoothLeBroadcastMetadata)
                runCurrent()

                assertThat(bluetoothTileDialogAudioSharingRepository.sourceAdded).isTrue()
                job.cancel()
            }
        }

    @Test
    fun testHandleAudioSourceWhenReady_skipInitialValue_noAudioSharing_sourceNotAdded() =
        with(kosmos) {
            testScope.runTest {
                val (broadcast, repository) = setupRepositoryImpl()
                val interactor =
                    object :
                        AudioSharingInteractorImpl(
                            applicationContext,
                            localBluetoothManager,
                            repository,
                            testDispatcher,
                        ) {
                        override suspend fun audioSharingAvailable() = true
                    }
                val job = launch { interactor.handleAudioSourceWhenReady() }
                runCurrent()
                // Verify callback registered for onBroadcastStartedOrStopped
                verify(broadcast).registerServiceCallBack(any(), callbackCaptor.capture())
                runCurrent()
                // Verify source is not added
                verify(repository, never()).addSource()
                job.cancel()
            }
        }

    @Test
    fun testHandleAudioSourceWhenReady_skipInitialValue_newAudioSharing_sourceAdded() =
        with(kosmos) {
            testScope.runTest {
                val (broadcast, repository) = setupRepositoryImpl()
                val interactor =
                    object :
                        AudioSharingInteractorImpl(
                            applicationContext,
                            localBluetoothManager,
                            repository,
                            testDispatcher,
                        ) {
                        override suspend fun audioSharingAvailable() = true
                    }
                val job = launch { interactor.handleAudioSourceWhenReady() }
                runCurrent()
                // Verify callback registered for onBroadcastStartedOrStopped
                verify(broadcast).registerServiceCallBack(any(), callbackCaptor.capture())
                // Audio sharing started, trigger onBroadcastStarted
                whenever(broadcast.isEnabled(null)).thenReturn(true)
                callbackCaptor.value.onBroadcastStarted(0, 0)
                runCurrent()
                // Verify callback registered for onBroadcastMetadataChanged
                verify(broadcast, times(2)).registerServiceCallBack(any(), callbackCaptor.capture())
                runCurrent()
                // Trigger onBroadcastMetadataChanged (ready to add source)
                callbackCaptor.value.onBroadcastMetadataChanged(0, bluetoothLeBroadcastMetadata)
                runCurrent()
                // Verify source added
                verify(repository).addSource()
                job.cancel()
            }
        }

    private fun setupRepositoryImpl(): Pair<LocalBluetoothLeBroadcast, AudioSharingRepositoryImpl> {
        with(kosmos) {
            val broadcast =
                mock<LocalBluetoothLeBroadcast> {
                    on { isProfileReady } doReturn true
                    on { isEnabled(null) } doReturn false
                }
            val assistant =
                mock<LocalBluetoothLeBroadcastAssistant> { on { isProfileReady } doReturn true }
            val volumeControl = mock<VolumeControlProfile> { on { isProfileReady } doReturn true }
            val profileManager =
                mock<LocalBluetoothProfileManager> {
                    on { leAudioBroadcastProfile } doReturn broadcast
                    on { leAudioBroadcastAssistantProfile } doReturn assistant
                    on { volumeControlProfile } doReturn volumeControl
                }
            whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
            whenever(localBluetoothManager.eventManager).thenReturn(mock<BluetoothEventManager> {})

            val repository =
                AudioSharingRepositoryImpl(
                    localBluetoothManager,
                    com.android.settingslib.volume.data.repository.AudioSharingRepositoryImpl(
                        mock<ContentResolver> {},
                        localBluetoothManager,
                        testScope.backgroundScope,
                        testScope.testScheduler,
                        mock<AudioSharingLogger> {},
                    ),
                    testDispatcher,
                )
            return Pair(broadcast, spy(repository))
        }
    }
}
