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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastAssistant
import android.bluetooth.BluetoothLeBroadcastReceiveState
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothVolumeControl
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.BluetoothCallback
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioSharingRepositoryTest {
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

    @Captor
    private lateinit var broadcastCallbackCaptor: ArgumentCaptor<BluetoothLeBroadcast.Callback>

    @Captor
    private lateinit var assistantCallbackCaptor:
            ArgumentCaptor<BluetoothLeBroadcastAssistant.Callback>

    @Captor private lateinit var btCallbackCaptor: ArgumentCaptor<BluetoothCallback>

    @Captor private lateinit var contentObserverCaptor: ArgumentCaptor<ContentObserver>

    @Captor
    private lateinit var volumeCallbackCaptor: ArgumentCaptor<BluetoothVolumeControl.Callback>

    private val logger = FakeAudioSharingRepositoryLogger()
    private val testScope = TestScope()
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Spy private val contentResolver: ContentResolver = context.contentResolver
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
        Settings.Secure.putInt(
            contentResolver,
            BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
            TEST_GROUP_ID_INVALID
        )
        underTest =
            AudioSharingRepositoryImpl(
                contentResolver,
                btManager,
                testScope.backgroundScope,
                testScope.testScheduler,
                logger
            )
    }

    @After
    fun tearDown() {
        logger.reset()
    }

    @Test
    fun audioSharingStateChange_profileReady_emitValues() {
        testScope.runTest {
            `when`(broadcast.isProfileReady).thenReturn(true)
            `when`(assistant.isProfileReady).thenReturn(true)
            `when`(volumeControl.isProfileReady).thenReturn(true)
            val states = mutableListOf<Boolean?>()
            underTest.inAudioSharing.onEach { states.add(it) }.launchIn(backgroundScope)
            runCurrent()
            triggerAudioSharingStateChange(TriggerType.BROADCAST_STOP, broadcastStopped)
            runCurrent()
            triggerAudioSharingStateChange(TriggerType.BROADCAST_START, broadcastStarted)
            runCurrent()

            Truth.assertThat(states).containsExactly(false, true, false, true)
            Truth.assertThat(logger.logs)
                .containsAtLeastElementsIn(
                    listOf(
                        "onAudioSharingStateChanged state=true",
                        "onAudioSharingStateChanged state=false",
                    )
                ).inOrder()
        }
    }

    @Test
    fun audioSharingStateChange_profileNotReady_broadcastCallbackNotRegistered() {
        testScope.runTest {
            val states = mutableListOf<Boolean?>()
            underTest.inAudioSharing.onEach { states.add(it) }.launchIn(backgroundScope)
            runCurrent()
            verify(broadcast, never()).registerServiceCallBack(any(), any())

            Truth.assertThat(states).containsExactly(false)
        }
    }

    @Test
    fun primaryGroupIdChange_emitValues() {
        testScope.runTest {
            val groupIds = mutableListOf<Int?>()
            underTest.primaryGroupId.onEach { groupIds.add(it) }.launchIn(backgroundScope)
            runCurrent()
            triggerContentObserverChange()
            runCurrent()

            Truth.assertThat(groupIds)
                .containsExactly(
                    TEST_GROUP_ID_INVALID,
                    TEST_GROUP_ID2
                )
        }
    }

    @Test
    fun secondaryGroupIdChange_profileNotReady_assistantCallbackNotRegistered() {
        testScope.runTest {
            val groupIds = mutableListOf<Int?>()
            underTest.secondaryGroupId.onEach { groupIds.add(it) }.launchIn(backgroundScope)
            runCurrent()
            verify(assistant, never()).registerServiceCallBack(any(), any())
        }
    }

    @Test
    fun secondaryGroupIdChange_profileReady_emitValues() {
        testScope.runTest {
            `when`(broadcast.isProfileReady).thenReturn(true)
            `when`(assistant.isProfileReady).thenReturn(true)
            `when`(volumeControl.isProfileReady).thenReturn(true)
            val groupIds = mutableListOf<Int?>()
            underTest.secondaryGroupId.onEach { groupIds.add(it) }.launchIn(backgroundScope)
            runCurrent()
            triggerSourceAdded()
            runCurrent()
            triggerContentObserverChange()
            runCurrent()
            triggerSourceRemoved()
            runCurrent()
            triggerSourceAdded()
            runCurrent()
            triggerProfileConnectionChange(
                BluetoothAdapter.STATE_CONNECTING, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT
            )
            runCurrent()
            triggerProfileConnectionChange(
                BluetoothAdapter.STATE_DISCONNECTED, BluetoothProfile.LE_AUDIO
            )
            runCurrent()
            triggerProfileConnectionChange(
                BluetoothAdapter.STATE_DISCONNECTED, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT
            )
            runCurrent()

            Truth.assertThat(groupIds)
                .containsExactly(
                    TEST_GROUP_ID_INVALID,
                    TEST_GROUP_ID2,
                    TEST_GROUP_ID1,
                    TEST_GROUP_ID_INVALID,
                    TEST_GROUP_ID2,
                    TEST_GROUP_ID_INVALID
                )
            Truth.assertThat(logger.logs)
                .containsAtLeastElementsIn(
                    listOf(
                        "onSecondaryGroupIdChanged groupId=$TEST_GROUP_ID_INVALID",
                        "onSecondaryGroupIdChanged groupId=$TEST_GROUP_ID2",
                        "onSecondaryGroupIdChanged groupId=$TEST_GROUP_ID1",
                    )
                ).inOrder()
        }
    }

    @Test
    fun volumeMapChange_profileReady_emitValues() {
        testScope.runTest {
            `when`(broadcast.isProfileReady).thenReturn(true)
            `when`(assistant.isProfileReady).thenReturn(true)
            `when`(volumeControl.isProfileReady).thenReturn(true)
            val volumeMaps = mutableListOf<GroupIdToVolumes?>()
            underTest.volumeMap.onEach { volumeMaps.add(it) }.launchIn(backgroundScope)
            runCurrent()
            triggerVolumeMapChange(Pair(device1, TEST_VOLUME1))
            runCurrent()
            triggerVolumeMapChange(Pair(device1, TEST_VOLUME2))
            runCurrent()
            triggerAudioSharingStateChange(TriggerType.BROADCAST_STOP, broadcastStopped)
            runCurrent()
            verify(volumeControl).unregisterCallback(any())
            runCurrent()

            val expectedMap1 = mapOf(TEST_GROUP_ID1 to TEST_VOLUME1)
            val expectedMap2 = mapOf(TEST_GROUP_ID1 to TEST_VOLUME2)
            Truth.assertThat(volumeMaps)
                .containsExactly(
                    emptyMap<Int, Int>(),
                    expectedMap1,
                    expectedMap2
                )
            Truth.assertThat(logger.logs)
                .containsAtLeastElementsIn(
                    listOf(
                        "onVolumeMapChanged map={}",
                        "onVolumeMapChanged map=$expectedMap1",
                        "onVolumeMapChanged map=$expectedMap2",
                    )
                ).inOrder()
        }
    }

    @Test
    fun volumeMapChange_profileNotReady_volumeControlCallbackNotRegistered() {
        testScope.runTest {
            val volumeMaps = mutableListOf<GroupIdToVolumes?>()
            underTest.volumeMap.onEach { volumeMaps.add(it) }.launchIn(backgroundScope)
            runCurrent()
            verify(volumeControl, never()).registerCallback(any(), any())
        }
    }

    @Test
    fun setSecondaryVolume_setValue() {
        testScope.runTest {
            Settings.Secure.putInt(
                contentResolver,
                BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
                TEST_GROUP_ID2
            )
            `when`(assistant.allConnectedDevices).thenReturn(listOf(device1, device2))
            underTest.setSecondaryVolume(TEST_VOLUME1)

            runCurrent()
            verify(volumeControl).setDeviceVolume(device1, TEST_VOLUME1, true)
            Truth.assertThat(logger.logs)
                .isEqualTo(
                    listOf(
                        "onSetVolumeRequested volume=$TEST_VOLUME1",
                    )
                )
        }
    }

    private fun triggerAudioSharingStateChange(
        type: TriggerType,
        broadcastAction: BluetoothLeBroadcast.Callback.() -> Unit
    ) {
        verify(broadcast).registerServiceCallBack(any(), broadcastCallbackCaptor.capture())
        when (type) {
            TriggerType.BROADCAST_START -> {
                `when`(broadcast.isEnabled(null)).thenReturn(true)
                broadcastCallbackCaptor.value.broadcastAction()
            }

            TriggerType.BROADCAST_STOP -> {
                `when`(broadcast.isEnabled(null)).thenReturn(false)
                broadcastCallbackCaptor.value.broadcastAction()
            }
        }
    }

    private fun triggerSourceAdded() {
        verify(assistant).registerServiceCallBack(any(), assistantCallbackCaptor.capture())
        Settings.Secure.putInt(
            contentResolver,
            BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
            TEST_GROUP_ID1
        )
        `when`(assistant.allConnectedDevices).thenReturn(listOf(device1, device2))
        assistantCallbackCaptor.value.sourceAdded(device1, receiveState)
    }

    private fun triggerSourceRemoved() {
        verify(assistant).registerServiceCallBack(any(), assistantCallbackCaptor.capture())
        `when`(assistant.allConnectedDevices).thenReturn(listOf(device1))
        Settings.Secure.putInt(
            contentResolver,
            BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
            TEST_GROUP_ID1
        )
        assistantCallbackCaptor.value.sourceRemoved(device2)
    }

    private fun triggerProfileConnectionChange(state: Int, profile: Int) {
        verify(eventManager).registerCallback(btCallbackCaptor.capture())
        `when`(assistant.allConnectedDevices).thenReturn(listOf(device1))
        Settings.Secure.putInt(
            contentResolver,
            BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
            TEST_GROUP_ID1
        )
        btCallbackCaptor.value.onProfileConnectionStateChanged(cachedDevice2, state, profile)
    }

    private fun triggerContentObserverChange() {
        verify(contentResolver)
            .registerContentObserver(
                eq(Settings.Secure.getUriFor(BluetoothUtils.getPrimaryGroupIdUriForBroadcast())),
                eq(false),
                contentObserverCaptor.capture()
            )
        `when`(assistant.allConnectedDevices).thenReturn(listOf(device1, device2))
        Settings.Secure.putInt(
            contentResolver,
            BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
            TEST_GROUP_ID2
        )
        contentObserverCaptor.value.primaryChanged()
    }

    private fun triggerVolumeMapChange(change: Pair<BluetoothDevice, Int>) {
        verify(volumeControl).registerCallback(any(), volumeCallbackCaptor.capture())
        volumeCallbackCaptor.value.onDeviceVolumeChanged(change.first, change.second)
    }

    private enum class TriggerType {
        BROADCAST_START,
        BROADCAST_STOP
    }

    private companion object {
        const val TEST_GROUP_ID_INVALID = -1
        const val TEST_GROUP_ID1 = 1
        const val TEST_GROUP_ID2 = 2
        const val TEST_SOURCE_ID = 1
        const val TEST_BROADCAST_ID = 1
        const val TEST_REASON = 1
        const val TEST_RECEIVE_STATE_CONTENT = 1L
        const val TEST_VOLUME1 = 10
        const val TEST_VOLUME2 = 20

        val broadcastStarted: BluetoothLeBroadcast.Callback.() -> Unit = {
            onBroadcastStarted(TEST_REASON, TEST_BROADCAST_ID)
        }
        val broadcastStopped: BluetoothLeBroadcast.Callback.() -> Unit = {
            onBroadcastStopped(TEST_REASON, TEST_BROADCAST_ID)
        }
        val sourceAdded:
                BluetoothLeBroadcastAssistant.Callback.(
                    sink: BluetoothDevice, state: BluetoothLeBroadcastReceiveState
                ) -> Unit =
            { sink, state ->
                onReceiveStateChanged(sink, TEST_SOURCE_ID, state)
            }
        val sourceRemoved: BluetoothLeBroadcastAssistant.Callback.(sink: BluetoothDevice) -> Unit =
            { sink ->
                onSourceRemoved(sink, TEST_SOURCE_ID, TEST_REASON)
            }
        val primaryChanged: ContentObserver.() -> Unit = { onChange(false) }
    }
}
