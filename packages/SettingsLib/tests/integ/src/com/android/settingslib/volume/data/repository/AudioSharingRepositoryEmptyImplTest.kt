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

package com.android.settingslib.volume.data.repository

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcastReceiveState
import android.content.Context
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.BluetoothEventManager
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.settingslib.bluetooth.VolumeControlProfile
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioSharingRepositoryEmptyImplTest {
    @get:Rule val mockito: MockitoRule = MockitoJUnit.rule()

    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Mock private lateinit var btManager: LocalBluetoothManager

    @Mock private lateinit var profileManager: LocalBluetoothProfileManager

    @Mock private lateinit var broadcast: LocalBluetoothLeBroadcast

    @Mock private lateinit var assistant: LocalBluetoothLeBroadcastAssistant

    @Mock private lateinit var volumeControl: VolumeControlProfile

    @Mock private lateinit var eventManager: BluetoothEventManager

    @Mock private lateinit var deviceManager: CachedBluetoothDeviceManager

    @Mock private lateinit var device1: BluetoothDevice

    @Mock private lateinit var device2: BluetoothDevice

    @Mock private lateinit var cachedDevice1: CachedBluetoothDevice

    @Mock private lateinit var cachedDevice2: CachedBluetoothDevice

    @Mock private lateinit var receiveState: BluetoothLeBroadcastReceiveState

    private val testScope = TestScope()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var underTest: AudioSharingRepository

    @Before
    fun setup() {
        `when`(btManager.profileManager).thenReturn(profileManager)
        `when`(profileManager.leAudioBroadcastProfile).thenReturn(broadcast)
        `when`(profileManager.leAudioBroadcastAssistantProfile).thenReturn(assistant)
        `when`(profileManager.volumeControlProfile).thenReturn(volumeControl)
        `when`(btManager.eventManager).thenReturn(eventManager)
        `when`(btManager.cachedDeviceManager).thenReturn(deviceManager)
        `when`(broadcast.isEnabled(null)).thenReturn(true)
        `when`(cachedDevice1.groupId).thenReturn(TEST_GROUP_ID1)
        `when`(cachedDevice1.device).thenReturn(device1)
        `when`(deviceManager.findDevice(device1)).thenReturn(cachedDevice1)
        `when`(cachedDevice2.groupId).thenReturn(TEST_GROUP_ID2)
        `when`(cachedDevice2.device).thenReturn(device2)
        `when`(deviceManager.findDevice(device2)).thenReturn(cachedDevice2)
        `when`(receiveState.bisSyncState).thenReturn(arrayListOf(TEST_RECEIVE_STATE_CONTENT))
        `when`(assistant.getAllSources(any())).thenReturn(listOf(receiveState))
        underTest = AudioSharingRepositoryEmptyImpl()
    }

    @Test
    fun inAudioSharing_returnFalse() {
        testScope.runTest {
            val states = mutableListOf<Boolean?>()
            underTest.inAudioSharing.onEach { states.add(it) }.launchIn(backgroundScope)
            runCurrent()

            Truth.assertThat(states).containsExactly(false)
            verify(broadcast, never()).registerServiceCallBack(any(), any())
            verify(broadcast, never()).isEnabled(any())
        }
    }

    @Test
    fun secondaryGroupIdChange_returnFalse() {
        testScope.runTest {
            val groupIds = mutableListOf<Int?>()
            underTest.secondaryGroupId.onEach { groupIds.add(it) }.launchIn(backgroundScope)
            runCurrent()

            Truth.assertThat(groupIds).containsExactly(TEST_GROUP_ID_INVALID)
            verify(assistant, never()).registerServiceCallBack(any(), any())
            verify(eventManager, never()).registerCallback(any())
        }
    }

    @Test
    fun volumeMapChange_returnFalse() {
        testScope.runTest {
            val volumeMaps = mutableListOf<GroupIdToVolumes?>()
            underTest.volumeMap.onEach { volumeMaps.add(it) }.launchIn(backgroundScope)
            runCurrent()

            Truth.assertThat(volumeMaps).containsExactly(emptyMap<Int, Int>())
            verify(broadcast, never()).registerServiceCallBack(any(), any())
            verify(volumeControl, never()).registerCallback(any(), any())
        }
    }

    @Test
    fun setSecondaryVolume_doNothing() {
        testScope.runTest {
            Settings.Secure.putInt(
                context.contentResolver,
                BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
                TEST_GROUP_ID2)
            `when`(assistant.allConnectedDevices).thenReturn(listOf(device1, device2))
            underTest.setSecondaryVolume(TEST_VOLUME1)

            runCurrent()
            verify(volumeControl, never()).setDeviceVolume(any(), anyInt(), anyBoolean())
        }
    }

    private companion object {
        const val TEST_GROUP_ID_INVALID = -1
        const val TEST_GROUP_ID1 = 1
        const val TEST_GROUP_ID2 = 2
        const val TEST_RECEIVE_STATE_CONTENT = 1L
        const val TEST_VOLUME1 = 10
    }
}
