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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioSharingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AudioSharingRepositoryTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var profileManager: LocalBluetoothProfileManager
    @Mock private lateinit var leAudioBroadcastProfile: LocalBluetoothLeBroadcast
    @Mock private lateinit var leAudioBroadcastAssistant: LocalBluetoothLeBroadcastAssistant
    @Mock private lateinit var metadata: BluetoothLeBroadcastMetadata
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    private val kosmos = testKosmos()
    private lateinit var underTest: AudioSharingRepository

    @Before
    fun setUp() {
        underTest =
            AudioSharingRepositoryImpl(
                kosmos.localBluetoothManager,
                kosmos.audioSharingRepository,
                kosmos.testDispatcher,
            )
    }

    @Test
    fun testSwitchActive() =
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setAudioSharingAvailable(true)
                underTest.setActive(cachedBluetoothDevice)
                verify(cachedBluetoothDevice).setActive()
            }
        }

    @Test
    fun testSwitchActive_flagOff_doNothing() =
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setAudioSharingAvailable(false)
                underTest.setActive(cachedBluetoothDevice)
                verify(cachedBluetoothDevice, never()).setActive()
            }
        }

    @Test
    fun testStartAudioSharing() =
        with(kosmos) {
            testScope.runTest {
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioBroadcastProfile).thenReturn(leAudioBroadcastProfile)
                audioSharingRepository.setAudioSharingAvailable(true)
                underTest.startAudioSharing()
                verify(leAudioBroadcastProfile).startPrivateBroadcast()
            }
        }

    @Test
    fun testStartAudioSharing_flagOff_doNothing() =
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setAudioSharingAvailable(false)
                underTest.startAudioSharing()
                verify(leAudioBroadcastProfile, never()).startPrivateBroadcast()
            }
        }

    @Test
    fun testAddSource_flagOff_doesNothing() =
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setAudioSharingAvailable(false)

                underTest.addSource()
                runCurrent()

                verify(leAudioBroadcastAssistant, never()).allConnectedDevices
            }
        }

    @Test
    fun testAddSource_noMetadata_doesNothing() =
        with(kosmos) {
            testScope.runTest {
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioBroadcastProfile).thenReturn(leAudioBroadcastProfile)
                audioSharingRepository.setAudioSharingAvailable(true)
                whenever(leAudioBroadcastProfile.latestBluetoothLeBroadcastMetadata)
                    .thenReturn(null)

                underTest.addSource()
                runCurrent()

                verify(leAudioBroadcastAssistant, never()).allConnectedDevices
            }
        }

    @Test
    fun testAddSource_noConnectedDevice_doesNothing() =
        with(kosmos) {
            testScope.runTest {
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioBroadcastProfile).thenReturn(leAudioBroadcastProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(leAudioBroadcastAssistant)
                audioSharingRepository.setAudioSharingAvailable(true)
                whenever(leAudioBroadcastProfile.latestBluetoothLeBroadcastMetadata)
                    .thenReturn(metadata)
                whenever(leAudioBroadcastAssistant.allConnectedDevices).thenReturn(emptyList())

                underTest.addSource()
                runCurrent()

                verify(leAudioBroadcastAssistant, never()).addSource(any(), any(), anyBoolean())
            }
        }

    @Test
    fun testAddSource_hasConnectedDeviceAndMetadata_addSource() =
        with(kosmos) {
            testScope.runTest {
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioBroadcastProfile).thenReturn(leAudioBroadcastProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(leAudioBroadcastAssistant)
                audioSharingRepository.setAudioSharingAvailable(true)
                whenever(leAudioBroadcastProfile.latestBluetoothLeBroadcastMetadata)
                    .thenReturn(metadata)
                whenever(leAudioBroadcastAssistant.allConnectedDevices)
                    .thenReturn(listOf(bluetoothDevice))

                underTest.addSource()
                runCurrent()

                verify(leAudioBroadcastAssistant).addSource(bluetoothDevice, metadata, false)
            }
        }
}
