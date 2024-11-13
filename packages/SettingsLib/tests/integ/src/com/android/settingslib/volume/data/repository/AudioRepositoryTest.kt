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

import android.content.ContentResolver
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.shared.FakeAudioManagerEventsReceiver
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.AudioStreamModel
import com.android.settingslib.volume.shared.model.RingerMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioRepositoryTest {

    @Captor
    private lateinit var modeListenerCaptor: ArgumentCaptor<AudioManager.OnModeChangedListener>
    @Captor
    private lateinit var communicationDeviceListenerCaptor:
        ArgumentCaptor<AudioManager.OnCommunicationDeviceChangedListener>
    @Captor private lateinit var contentObserver: ArgumentCaptor<ContentObserver>

    @Mock private lateinit var audioManager: AudioManager
    @Mock private lateinit var communicationDevice: AudioDeviceInfo
    @Mock private lateinit var contentResolver: ContentResolver

    private val logger = FakeAudioRepositoryLogger()
    private val eventsReceiver = FakeAudioManagerEventsReceiver()
    private val volumeByStream: MutableMap<Int, Int> = mutableMapOf()
    private val isAffectedByRingerModeByStream: MutableMap<Int, Boolean> = mutableMapOf()
    private val isMuteByStream: MutableMap<Int, Boolean> = mutableMapOf()
    private val testScope = TestScope()

    private lateinit var underTest: AudioRepository

    private var ringerModeInternal: RingerMode = RingerMode(AudioManager.RINGER_MODE_NORMAL)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        `when`(audioManager.mode).thenReturn(AudioManager.MODE_RINGTONE)
        `when`(audioManager.communicationDevice).thenReturn(communicationDevice)
        `when`(audioManager.getStreamMinVolume(anyInt())).thenReturn(MIN_VOLUME)
        `when`(audioManager.getStreamMaxVolume(anyInt())).thenReturn(MAX_VOLUME)
        `when`(audioManager.ringerModeInternal).then { ringerModeInternal.value }
        `when`(audioManager.setStreamVolume(anyInt(), anyInt(), anyInt())).then {
            val streamType = it.arguments[0] as Int
            volumeByStream[it.arguments[0] as Int] = it.arguments[1] as Int
            triggerEvent(AudioManagerEvent.StreamVolumeChanged(AudioStream(streamType)))
            triggerSettingChange()
        }
        `when`(audioManager.adjustStreamVolume(anyInt(), anyInt(), anyInt())).then {
            val streamType = it.arguments[0] as Int
            isMuteByStream[streamType] = it.arguments[1] == AudioManager.ADJUST_MUTE
            triggerEvent(AudioManagerEvent.StreamMuteChanged(AudioStream(streamType)))
        }
        `when`(audioManager.getStreamVolume(anyInt())).thenAnswer {
            volumeByStream.getOrDefault(it.arguments[0] as Int, 0)
        }
        `when`(audioManager.isStreamAffectedByRingerMode(anyInt())).thenAnswer {
            isAffectedByRingerModeByStream.getOrDefault(it.arguments[0] as Int, false)
        }
        `when`(audioManager.isStreamMute(anyInt())).thenAnswer {
            isMuteByStream.getOrDefault(it.arguments[0] as Int, false)
        }
        `when`(audioManager.setRingerModeInternal(anyInt())).then {
            ringerModeInternal = RingerMode(it.arguments[0] as Int)
            Unit
        }

        underTest =
            AudioRepositoryImpl(
                eventsReceiver,
                audioManager,
                contentResolver,
                testScope.testScheduler,
                testScope.backgroundScope,
                logger,
                true,
            )
    }

    @Test
    fun audioModeChanges_repositoryEmits() {
        testScope.runTest {
            val modes = mutableListOf<Int>()
            underTest.mode.onEach { modes.add(it) }.launchIn(backgroundScope)
            runCurrent()

            triggerModeChange(AudioManager.MODE_IN_CALL)
            runCurrent()

            assertThat(modes).containsExactly(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL)
        }
    }

    @Test
    fun ringerModeChanges_repositoryEmits() {
        testScope.runTest {
            val modes = mutableListOf<RingerMode>()
            underTest.ringerMode.onEach { modes.add(it) }.launchIn(backgroundScope)
            runCurrent()

            ringerModeInternal = RingerMode(AudioManager.RINGER_MODE_SILENT)
            triggerEvent(AudioManagerEvent.InternalRingerModeChanged)
            runCurrent()

            assertThat(modes)
                .containsExactly(
                    RingerMode(AudioManager.RINGER_MODE_NORMAL),
                    RingerMode(AudioManager.RINGER_MODE_SILENT),
                )
        }
    }

    @Test
    fun changingRingerMode_changesRingerModeInternal() {
        testScope.runTest {
            underTest.setRingerModeInternal(
                AudioStream(AudioManager.STREAM_SYSTEM),
                RingerMode(AudioManager.RINGER_MODE_SILENT),
            )
            runCurrent()

            assertThat(ringerModeInternal).isEqualTo(RingerMode(AudioManager.RINGER_MODE_SILENT))
        }
    }

    @Test
    fun communicationDeviceChanges_repositoryEmits() {
        testScope.runTest {
            var device: AudioDeviceInfo? = null
            underTest.communicationDevice.onEach { device = it }.launchIn(backgroundScope)
            runCurrent()

            triggerConnectedDeviceChange(communicationDevice)
            runCurrent()

            assertThat(device).isSameInstanceAs(communicationDevice)
        }
    }

    @Test
    fun adjustingVolume_changesTheStream() {
        testScope.runTest {
            val audioStream = AudioStream(AudioManager.STREAM_SYSTEM)
            var streamModel: AudioStreamModel? = null
            underTest
                .getAudioStream(audioStream)
                .onEach { streamModel = it }
                .launchIn(backgroundScope)
            runCurrent()

            underTest.setVolume(audioStream, 50)
            runCurrent()

            assertThat(logger.logs)
                .isEqualTo(
                    listOf(
                        "onVolumeUpdateReceived audioStream=STREAM_SYSTEM",
                        "onSetVolumeRequested audioStream=STREAM_SYSTEM",
                        "onVolumeUpdateReceived audioStream=STREAM_SYSTEM",
                        "onVolumeUpdateReceived audioStream=STREAM_SYSTEM",
                    )
                )
            assertThat(streamModel)
                .isEqualTo(
                    AudioStreamModel(
                        audioStream = audioStream,
                        volume = 50,
                        minVolume = MIN_VOLUME,
                        maxVolume = MAX_VOLUME,
                        isAffectedByMute = false,
                        isAffectedByRingerMode = false,
                        isMuted = false,
                    )
                )
        }
    }

    @Test
    fun muteStream_mutesTheStream() {
        testScope.runTest {
            val audioStream = AudioStream(AudioManager.STREAM_SYSTEM)
            var streamModel: AudioStreamModel? = null
            underTest
                .getAudioStream(audioStream)
                .onEach { streamModel = it }
                .launchIn(backgroundScope)
            runCurrent()

            underTest.setMuted(audioStream, true)
            runCurrent()

            assertThat(streamModel)
                .isEqualTo(
                    AudioStreamModel(
                        audioStream = audioStream,
                        volume = 0,
                        minVolume = MIN_VOLUME,
                        maxVolume = MAX_VOLUME,
                        isAffectedByMute = false,
                        isAffectedByRingerMode = false,
                        isMuted = true,
                    )
                )
        }
    }

    @Test
    fun unmuteStream_unmutesTheStream() {
        testScope.runTest {
            val audioStream = AudioStream(AudioManager.STREAM_SYSTEM)
            isMuteByStream[audioStream.value] = true
            var streamModel: AudioStreamModel? = null
            underTest
                .getAudioStream(audioStream)
                .onEach { streamModel = it }
                .launchIn(backgroundScope)
            runCurrent()

            underTest.setMuted(audioStream, false)
            runCurrent()

            assertThat(streamModel)
                .isEqualTo(
                    AudioStreamModel(
                        audioStream = audioStream,
                        volume = 0,
                        minVolume = MIN_VOLUME,
                        maxVolume = MAX_VOLUME,
                        isAffectedByMute = false,
                        isAffectedByRingerMode = false,
                        isMuted = false,
                    )
                )
        }
    }

    @Test
    fun getBluetoothAudioDeviceCategory() {
        testScope.runTest {
            `when`(audioManager.getBluetoothAudioDeviceCategory("12:34:56:78"))
                .thenReturn(AudioManager.AUDIO_DEVICE_CATEGORY_HEADPHONES)

            val category = underTest.getBluetoothAudioDeviceCategory("12:34:56:78")
            runCurrent()

            assertThat(category).isEqualTo(AudioManager.AUDIO_DEVICE_CATEGORY_HEADPHONES)
        }
    }

    @Test
    fun useVolumeControllerDisabled_setVolumeController_notCalled() {
        testScope.runTest {
            underTest =
                AudioRepositoryImpl(
                    eventsReceiver,
                    audioManager,
                    contentResolver,
                    testScope.testScheduler,
                    testScope.backgroundScope,
                    logger,
                    false,
                )

            underTest.volumeControllerEvents.launchIn(backgroundScope)
            runCurrent()

            verify(audioManager, never()).volumeController = any()
        }
    }

    private fun triggerConnectedDeviceChange(communicationDevice: AudioDeviceInfo?) {
        verify(audioManager)
            .addOnCommunicationDeviceChangedListener(
                any(),
                communicationDeviceListenerCaptor.capture(),
            )
        communicationDeviceListenerCaptor.value.onCommunicationDeviceChanged(communicationDevice)
    }

    private fun triggerModeChange(mode: Int) {
        verify(audioManager).addOnModeChangedListener(any(), modeListenerCaptor.capture())
        modeListenerCaptor.value.onModeChanged(mode)
    }

    private fun triggerSettingChange(selfChange: Boolean = false) {
        verify(contentResolver)
            .registerContentObserver(any(), anyBoolean(), contentObserver.capture())
        contentObserver.value.onChange(selfChange)
    }

    private fun triggerEvent(event: AudioManagerEvent) {
        testScope.launch { eventsReceiver.triggerEvent(event) }
    }

    private companion object {
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 100
    }
}
