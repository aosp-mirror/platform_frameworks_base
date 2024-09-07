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

import android.bluetooth.BluetoothLeBroadcast
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.settingslib.flags.Flags
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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioSharingRepositoryTest {
    @get:Rule val mockito: MockitoRule = MockitoJUnit.rule()
    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var localBluetoothProfileManager: LocalBluetoothProfileManager
    @Mock private lateinit var localBluetoothLeBroadcast: LocalBluetoothLeBroadcast

    @Captor
    private lateinit var leBroadcastCallbackCaptor: ArgumentCaptor<BluetoothLeBroadcast.Callback>
    private val testScope = TestScope()

    private lateinit var underTest: AudioSharingRepository

    @Before
    fun setup() {
        `when`(localBluetoothManager.profileManager).thenReturn(localBluetoothProfileManager)
        `when`(localBluetoothProfileManager.leAudioBroadcastProfile)
            .thenReturn(localBluetoothLeBroadcast)
        `when`(localBluetoothLeBroadcast.isEnabled(null)).thenReturn(true)
        underTest =
            AudioSharingRepositoryImpl(
                localBluetoothManager,
                testScope.testScheduler,
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING)
    fun audioSharingStateChange_emitValues() {
        testScope.runTest {
            val states = mutableListOf<Boolean?>()
            underTest.inAudioSharing.onEach { states.add(it) }.launchIn(backgroundScope)
            runCurrent()
            triggerAudioSharingStateChange(false)
            runCurrent()
            triggerAudioSharingStateChange(true)
            runCurrent()

            Truth.assertThat(states).containsExactly(true, false, true)
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING)
    fun audioSharingFlagOff_returnFalse() {
        testScope.runTest {
            val states = mutableListOf<Boolean?>()
            underTest.inAudioSharing.onEach { states.add(it) }.launchIn(backgroundScope)
            runCurrent()

            Truth.assertThat(states).containsExactly(false)
            verify(localBluetoothLeBroadcast, never()).registerServiceCallBack(any(), any())
            verify(localBluetoothLeBroadcast, never()).isEnabled(any())
        }
    }

    private fun triggerAudioSharingStateChange(inAudioSharing: Boolean) {
        verify(localBluetoothLeBroadcast)
            .registerServiceCallBack(any(), leBroadcastCallbackCaptor.capture())
        `when`(localBluetoothLeBroadcast.isEnabled(null)).thenReturn(inAudioSharing)
        if (inAudioSharing) {
            leBroadcastCallbackCaptor.value.onBroadcastStarted(0, 0)
        } else {
            leBroadcastCallbackCaptor.value.onBroadcastStopped(0, 0)
        }
    }
}
