/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.volume.data.repository

import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.BluetoothCallback
import com.android.settingslib.bluetooth.BluetoothEventManager
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.volume.shared.FakeAudioManagerEventsReceiver
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class MediaControllerRepositoryImplTest {

    @Captor private lateinit var callbackCaptor: ArgumentCaptor<BluetoothCallback>

    @Mock private lateinit var mediaSessionManager: MediaSessionManager
    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager
    @Mock private lateinit var eventManager: BluetoothEventManager

    @Mock private lateinit var stoppedMediaController: MediaController
    @Mock private lateinit var statelessMediaController: MediaController
    @Mock private lateinit var errorMediaController: MediaController
    @Mock private lateinit var remoteMediaController: MediaController
    @Mock private lateinit var localMediaController: MediaController

    @Mock private lateinit var remotePlaybackInfo: PlaybackInfo
    @Mock private lateinit var localPlaybackInfo: PlaybackInfo

    private val testScope = TestScope()
    private val eventsReceiver = FakeAudioManagerEventsReceiver()

    private lateinit var underTest: MediaControllerRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        `when`(localBluetoothManager.eventManager).thenReturn(eventManager)

        `when`(stoppedMediaController.playbackState).thenReturn(stateStopped)
        `when`(stoppedMediaController.packageName).thenReturn("test.pkg.stopped")
        `when`(statelessMediaController.playbackState).thenReturn(stateNone)
        `when`(statelessMediaController.packageName).thenReturn("test.pkg.stateless")
        `when`(errorMediaController.playbackState).thenReturn(stateError)
        `when`(errorMediaController.packageName).thenReturn("test.pkg.error")
        `when`(remoteMediaController.playbackState).thenReturn(statePlaying)
        `when`(remoteMediaController.playbackInfo).thenReturn(remotePlaybackInfo)
        `when`(remoteMediaController.packageName).thenReturn("test.pkg.remote")
        `when`(localMediaController.playbackState).thenReturn(statePlaying)
        `when`(localMediaController.playbackInfo).thenReturn(localPlaybackInfo)
        `when`(localMediaController.packageName).thenReturn("test.pkg.local")

        `when`(remotePlaybackInfo.playbackType).thenReturn(PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        `when`(localPlaybackInfo.playbackType).thenReturn(PlaybackInfo.PLAYBACK_TYPE_LOCAL)

        underTest =
            MediaControllerRepositoryImpl(
                eventsReceiver,
                mediaSessionManager,
                localBluetoothManager,
                testScope.backgroundScope,
                testScope.testScheduler,
            )
    }

    @Test
    fun playingMediaDevicesAvailable_sessionIsActive() {
        testScope.runTest {
            `when`(mediaSessionManager.getActiveSessions(any()))
                .thenReturn(
                    listOf(
                        stoppedMediaController,
                        statelessMediaController,
                        errorMediaController,
                        remoteMediaController,
                        localMediaController
                    )
                )
            var mediaController: MediaController? = null
            underTest.activeLocalMediaController
                .onEach { mediaController = it }
                .launchIn(backgroundScope)
            runCurrent()

            eventsReceiver.triggerEvent(AudioManagerEvent.StreamDevicesChanged)
            triggerOnAudioModeChanged()
            runCurrent()

            assertThat(mediaController).isSameInstanceAs(localMediaController)
        }
    }

    @Test
    fun noPlayingMediaDevicesAvailable_sessionIsInactive() {
        testScope.runTest {
            `when`(mediaSessionManager.getActiveSessions(any()))
                .thenReturn(
                    listOf(
                        stoppedMediaController,
                        statelessMediaController,
                        errorMediaController,
                    )
                )
            var mediaController: MediaController? = null
            underTest.activeLocalMediaController
                .onEach { mediaController = it }
                .launchIn(backgroundScope)
            runCurrent()

            eventsReceiver.triggerEvent(AudioManagerEvent.StreamDevicesChanged)
            triggerOnAudioModeChanged()
            runCurrent()

            assertThat(mediaController).isNull()
        }
    }

    private fun triggerOnAudioModeChanged() {
        verify(eventManager).registerCallback(callbackCaptor.capture())
        callbackCaptor.value.onAudioModeChanged()
    }

    private companion object {
        val statePlaying: PlaybackState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 0f).build()
        val stateError: PlaybackState =
            PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 0f).build()
        val stateStopped: PlaybackState =
            PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build()
        val stateNone: PlaybackState =
            PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build()
    }
}
