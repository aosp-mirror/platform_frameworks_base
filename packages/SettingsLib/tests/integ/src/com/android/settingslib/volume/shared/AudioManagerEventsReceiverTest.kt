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

package com.android.settingslib.volume.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import com.android.settingslib.volume.shared.model.AudioStream
import com.google.common.truth.Expect
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
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@Suppress("UnspecifiedRegisterReceiverFlag")
@RunWith(AndroidJUnit4::class)
class AudioManagerEventsReceiverTest {

    @JvmField @Rule val expect = Expect.create()
    private val testScope = TestScope()

    @Mock private lateinit var context: Context
    @Captor private lateinit var receiverCaptor: ArgumentCaptor<BroadcastReceiver>

    private lateinit var underTest: AudioManagerEventsReceiver

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest = AudioManagerEventsReceiverImpl(context, testScope.backgroundScope)
    }

    @Test
    fun validIntent_translatedToEvent() {
        testScope.runTest {
            val events = mutableListOf<AudioManagerEvent>()
            underTest.events.onEach { events.add(it) }.launchIn(backgroundScope)
            runCurrent()

            triggerIntent(
                Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION).apply {
                    putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_SYSTEM)
                }
            )
            triggerIntent(
                Intent(AudioManager.VOLUME_CHANGED_ACTION).apply {
                    putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_SYSTEM)
                }
            )
            triggerIntent(Intent(AudioManager.MASTER_MUTE_CHANGED_ACTION))
            triggerIntent(Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION))
            triggerIntent(Intent(AudioManager.STREAM_DEVICES_CHANGED_ACTION))
            runCurrent()

            expect
                .that(events)
                .containsExactly(
                    AudioManagerEvent.StreamMuteChanged(
                        AudioStream(AudioManager.STREAM_SYSTEM),
                    ),
                    AudioManagerEvent.StreamVolumeChanged(
                        AudioStream(AudioManager.STREAM_SYSTEM),
                    ),
                    AudioManagerEvent.StreamMasterMuteChanged,
                    AudioManagerEvent.InternalRingerModeChanged,
                    AudioManagerEvent.StreamDevicesChanged,
                )
        }
    }

    @Test
    fun streamAudioManagerEvent_withoutAudioStream_areSkipped() {
        testScope.runTest {
            val events = mutableListOf<AudioManagerEvent>()
            underTest.events.onEach { events.add(it) }.launchIn(backgroundScope)
            runCurrent()

            triggerIntent(Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION))
            triggerIntent(Intent(AudioManager.VOLUME_CHANGED_ACTION))
            runCurrent()

            expect.that(events).isEmpty()
        }
    }

    @Test
    fun invalidIntents_areSkipped() {
        testScope.runTest {
            val events = mutableListOf<AudioManagerEvent>()
            underTest.events.onEach { events.add(it) }.launchIn(backgroundScope)
            runCurrent()

            triggerIntent(null)
            triggerIntent(Intent())
            triggerIntent(Intent("invalid_action"))
            runCurrent()

            expect.that(events).isEmpty()
        }
    }

    private fun triggerIntent(intent: Intent?) {
        verify(context).registerReceiver(receiverCaptor.capture(), any())
        receiverCaptor.value.onReceive(context, intent)
    }
}
