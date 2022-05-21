/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

private const val KEY = "KEY"
private const val PACKAGE = "PKG"
private const val SESSION_KEY = "SESSION_KEY"
private const val SESSION_ARTIST = "SESSION_ARTIST"
private const val SESSION_TITLE = "SESSION_TITLE"

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWith(AndroidTestingRunner::class)
class MediaTimeoutListenerTest : SysuiTestCase() {

    @Mock private lateinit var mediaControllerFactory: MediaControllerFactory
    @Mock private lateinit var mediaController: MediaController
    @Mock private lateinit var logger: MediaTimeoutLogger
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    private lateinit var executor: FakeExecutor
    @Mock private lateinit var timeoutCallback: (String, Boolean) -> Unit
    @Mock private lateinit var stateCallback: (String, PlaybackState) -> Unit
    @Captor private lateinit var mediaCallbackCaptor: ArgumentCaptor<MediaController.Callback>
    @Captor private lateinit var dozingCallbackCaptor:
        ArgumentCaptor<StatusBarStateController.StateListener>
    @JvmField @Rule val mockito = MockitoJUnit.rule()
    private lateinit var metadataBuilder: MediaMetadata.Builder
    private lateinit var playbackBuilder: PlaybackState.Builder
    private lateinit var session: MediaSession
    private lateinit var mediaData: MediaData
    private lateinit var resumeData: MediaData
    private lateinit var mediaTimeoutListener: MediaTimeoutListener
    private var clock = FakeSystemClock()

    @Before
    fun setup() {
        `when`(mediaControllerFactory.create(any())).thenReturn(mediaController)
        executor = FakeExecutor(clock)
        mediaTimeoutListener = MediaTimeoutListener(
            mediaControllerFactory,
            executor,
            logger,
            statusBarStateController,
            clock
        )
        mediaTimeoutListener.timeoutCallback = timeoutCallback
        mediaTimeoutListener.stateCallback = stateCallback

        // Create a media session and notification for testing.
        metadataBuilder = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
            putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
        }
        playbackBuilder = PlaybackState.Builder().apply {
            setState(PlaybackState.STATE_PAUSED, 6000L, 1f)
            setActions(PlaybackState.ACTION_PLAY)
        }
        session = MediaSession(context, SESSION_KEY).apply {
            setMetadata(metadataBuilder.build())
            setPlaybackState(playbackBuilder.build())
        }
        session.setActive(true)

        mediaData = MediaTestUtils.emptyMediaData.copy(
            app = PACKAGE,
            packageName = PACKAGE,
            token = session.sessionToken
        )

        resumeData = mediaData.copy(token = null, active = false, resumption = true)
    }

    @Test
    fun testOnMediaDataLoaded_registersPlaybackListener() {
        val playingState = mock(android.media.session.PlaybackState::class.java)
        `when`(playingState.state).thenReturn(PlaybackState.STATE_PLAYING)

        `when`(mediaController.playbackState).thenReturn(playingState)
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        verify(logger).logPlaybackState(eq(KEY), eq(playingState))

        // Ignores if same key
        clearInvocations(mediaController)
        mediaTimeoutListener.onMediaDataLoaded(KEY, KEY, mediaData)
        verify(mediaController, never()).registerCallback(anyObject())
    }

    @Test
    fun testOnMediaDataLoaded_registersTimeout_whenPaused() {
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        assertThat(executor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        verify(logger).logScheduleTimeout(eq(KEY), eq(false), eq(false))
        assertThat(executor.advanceClockToNext()).isEqualTo(PAUSED_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataRemoved_unregistersPlaybackListener() {
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        mediaTimeoutListener.onMediaDataRemoved(KEY)
        verify(mediaController).unregisterCallback(anyObject())

        // Ignores duplicate requests
        clearInvocations(mediaController)
        mediaTimeoutListener.onMediaDataRemoved(KEY)
        verify(mediaController, never()).unregisterCallback(anyObject())
    }

    @Test
    fun testOnMediaDataRemoved_clearsTimeout() {
        // GIVEN media that is paused
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        assertThat(executor.numPending()).isEqualTo(1)
        // WHEN the media is removed
        mediaTimeoutListener.onMediaDataRemoved(KEY)
        // THEN the timeout runnable is cancelled
        assertThat(executor.numPending()).isEqualTo(0)
    }

    @Test
    fun testOnMediaDataLoaded_migratesKeys() {
        val newKey = "NEWKEY"
        // From not playing
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        clearInvocations(mediaController)

        // To playing
        val playingState = mock(android.media.session.PlaybackState::class.java)
        `when`(playingState.state).thenReturn(PlaybackState.STATE_PLAYING)
        `when`(mediaController.playbackState).thenReturn(playingState)
        mediaTimeoutListener.onMediaDataLoaded(newKey, KEY, mediaData)
        verify(mediaController).unregisterCallback(anyObject())
        verify(mediaController).registerCallback(anyObject())
        verify(logger).logMigrateListener(eq(KEY), eq(newKey), eq(true))

        // Enqueues callback
        assertThat(executor.numPending()).isEqualTo(1)
    }

    @Test
    fun testOnMediaDataLoaded_migratesKeys_noTimeoutExtension() {
        val newKey = "NEWKEY"
        // From not playing
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        clearInvocations(mediaController)

        // Migrate, still not playing
        val playingState = mock(android.media.session.PlaybackState::class.java)
        `when`(playingState.state).thenReturn(PlaybackState.STATE_PAUSED)
        `when`(mediaController.playbackState).thenReturn(playingState)
        mediaTimeoutListener.onMediaDataLoaded(newKey, KEY, mediaData)

        // The number of queued timeout tasks remains the same. The timeout task isn't cancelled nor
        // is another scheduled
        assertThat(executor.numPending()).isEqualTo(1)
        verify(logger).logUpdateListener(eq(newKey), eq(false))
    }

    @Test
    fun testOnPlaybackStateChanged_schedulesTimeout_whenPaused() {
        // Assuming we're registered
        testOnMediaDataLoaded_registersPlaybackListener()

        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
        assertThat(executor.numPending()).isEqualTo(1)
        assertThat(executor.advanceClockToNext()).isEqualTo(PAUSED_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnPlaybackStateChanged_cancelsTimeout_whenResumed() {
        // Assuming we have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 0f).build())
        assertThat(executor.numPending()).isEqualTo(0)
        verify(logger).logTimeoutCancelled(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_reusesTimeout_whenNotPlaying() {
        // Assuming we have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
        assertThat(executor.numPending()).isEqualTo(1)
    }

    @Test
    fun testTimeoutCallback_invokedIfTimeout() {
        // Assuming we're have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        with(executor) {
            advanceClockToNext()
            runAllReady()
        }
        verify(timeoutCallback).invoke(eq(KEY), eq(true))
    }

    @Test
    fun testIsTimedOut() {
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        assertThat(mediaTimeoutListener.isTimedOut(KEY)).isFalse()
    }

    @Test
    fun testOnSessionDestroyed_active_clearsTimeout() {
        // GIVEN media that is paused
        val mediaPaused = mediaData.copy(isPlaying = false)
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaPaused)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        assertThat(executor.numPending()).isEqualTo(1)

        // WHEN the session is destroyed
        mediaCallbackCaptor.value.onSessionDestroyed()

        // THEN the controller is unregistered and timeout run
        verify(mediaController).unregisterCallback(anyObject())
        assertThat(executor.numPending()).isEqualTo(0)
        verify(logger).logSessionDestroyed(eq(KEY))
    }

    @Test
    fun testSessionDestroyed_thenRestarts_resetsTimeout() {
        // Assuming we have previously destroyed the session
        testOnSessionDestroyed_active_clearsTimeout()

        // WHEN we get an update with media playing
        val playingState = mock(android.media.session.PlaybackState::class.java)
        `when`(playingState.state).thenReturn(PlaybackState.STATE_PLAYING)
        `when`(mediaController.playbackState).thenReturn(playingState)
        val mediaPlaying = mediaData.copy(isPlaying = true)
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaPlaying)

        // THEN the timeout runnable will update the state
        assertThat(executor.numPending()).isEqualTo(1)
        with(executor) {
            advanceClockToNext()
            runAllReady()
        }
        verify(timeoutCallback).invoke(eq(KEY), eq(false))
        verify(logger).logReuseListener(eq(KEY))
    }

    @Test
    fun testOnSessionDestroyed_resume_continuesTimeout() {
        // GIVEN resume media with session info
        val resumeWithSession = resumeData.copy(token = session.sessionToken)
        mediaTimeoutListener.onMediaDataLoaded(PACKAGE, null, resumeWithSession)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        assertThat(executor.numPending()).isEqualTo(1)

        // WHEN the session is destroyed
        mediaCallbackCaptor.value.onSessionDestroyed()

        // THEN the controller is unregistered, but the timeout is still scheduled
        verify(mediaController).unregisterCallback(anyObject())
        assertThat(executor.numPending()).isEqualTo(1)
    }

    @Test
    fun testOnMediaDataLoaded_activeToResume_registersTimeout() {
        // WHEN a regular media is loaded
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)

        // AND it turns into a resume control
        mediaTimeoutListener.onMediaDataLoaded(PACKAGE, KEY, resumeData)

        // THEN we register a timeout
        assertThat(executor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        assertThat(executor.advanceClockToNext()).isEqualTo(RESUME_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataLoaded_pausedToResume_updatesTimeout() {
        // WHEN regular media is paused
        val pausedState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build()
        `when`(mediaController.playbackState).thenReturn(pausedState)
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        assertThat(executor.numPending()).isEqualTo(1)

        // AND it turns into a resume control
        mediaTimeoutListener.onMediaDataLoaded(PACKAGE, KEY, resumeData)

        // THEN we update the timeout length
        assertThat(executor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        assertThat(executor.advanceClockToNext()).isEqualTo(RESUME_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataLoaded_resumption_registersTimeout() {
        // WHEN a resume media is loaded
        mediaTimeoutListener.onMediaDataLoaded(PACKAGE, null, resumeData)

        // THEN we register a timeout
        assertThat(executor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        assertThat(executor.advanceClockToNext()).isEqualTo(RESUME_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataLoaded_resumeToActive_updatesTimeout() {
        // WHEN we have a resume control
        mediaTimeoutListener.onMediaDataLoaded(PACKAGE, null, resumeData)

        // AND that media is resumed
        val playingState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build()
        `when`(mediaController.playbackState).thenReturn(playingState)
        mediaTimeoutListener.onMediaDataLoaded(KEY, PACKAGE, mediaData)

        // THEN the timeout length is changed to a regular media control
        assertThat(executor.advanceClockToNext()).isEqualTo(PAUSED_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataRemoved_resume_timeoutCancelled() {
        // WHEN we have a resume control
        testOnMediaDataLoaded_resumption_registersTimeout()
        // AND the media is removed
        mediaTimeoutListener.onMediaDataRemoved(PACKAGE)

        // THEN the timeout runnable is cancelled
        assertThat(executor.numPending()).isEqualTo(0)
    }

    @Test
    fun testOnMediaDataLoaded_playbackActionsChanged_noCallback() {
        // Load media data once
        val pausedState = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When media data is loaded again, with different actions
        val playingState = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY)
                .build()
        loadMediaDataWithPlaybackState(playingState)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_playbackActionsChanged_sendsCallback() {
        // Load media data once
        val pausedState = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state changes, and has different actions
        val playingState = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY)
                .build()
        mediaCallbackCaptor.value.onPlaybackStateChanged(playingState)

        // Then the callback is invoked
        verify(stateCallback).invoke(eq(KEY), eq(playingState!!))
    }

    @Test
    fun testOnPlaybackStateChanged_differentCustomActions_sendsCallback() {
        val customOne = PlaybackState.CustomAction.Builder(
                    "ACTION_1",
                    "custom action 1",
                    android.R.drawable.ic_media_ff)
                .build()
        val pausedState = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customOne)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state actions change
        val customTwo = PlaybackState.CustomAction.Builder(
                "ACTION_2",
                "custom action 2",
                android.R.drawable.ic_media_rew)
                .build()
        val pausedStateTwoActions = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customOne)
                .addCustomAction(customTwo)
                .build()
        mediaCallbackCaptor.value.onPlaybackStateChanged(pausedStateTwoActions)

        // Then the callback is invoked
        verify(stateCallback).invoke(eq(KEY), eq(pausedStateTwoActions!!))
    }

    @Test
    fun testOnPlaybackStateChanged_sameActions_noCallback() {
        val stateWithActions = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY)
                .build()
        loadMediaDataWithPlaybackState(stateWithActions)

        // When the playback state updates with the same actions
        mediaCallbackCaptor.value.onPlaybackStateChanged(stateWithActions)

        // Then the callback is not invoked again
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_sameCustomActions_noCallback() {
        val actionName = "custom action"
        val actionIcon = android.R.drawable.ic_media_ff
        val customOne = PlaybackState.CustomAction.Builder(actionName, actionName, actionIcon)
                .build()
        val stateOne = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customOne)
                .build()
        loadMediaDataWithPlaybackState(stateOne)

        // When the playback state is updated, but has the same actions
        val customTwo = PlaybackState.CustomAction.Builder(actionName, actionName, actionIcon)
                .build()
        val stateTwo = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customTwo)
                .build()
        mediaCallbackCaptor.value.onPlaybackStateChanged(stateTwo)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnMediaDataLoaded_isPlayingChanged_noCallback() {
        // Load media data in paused state
        val pausedState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When media data is loaded again but playing
        val playingState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        loadMediaDataWithPlaybackState(playingState)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_isPlayingChanged_sendsCallback() {
        // Load media data in paused state
        val pausedState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state changes to playing
        val playingState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        mediaCallbackCaptor.value.onPlaybackStateChanged(playingState)

        // Then the callback is invoked
        verify(stateCallback).invoke(eq(KEY), eq(playingState!!))
    }

    @Test
    fun testOnPlaybackStateChanged_isPlayingSame_noCallback() {
        // Load media data in paused state
        val pausedState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state is updated, but still not playing
        val playingState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                .build()
        mediaCallbackCaptor.value.onPlaybackStateChanged(playingState)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), eq(playingState!!))
    }

    @Test
    fun testTimeoutCallback_dozedPastTimeout_invokedOnWakeup() {
        // When paused media is loaded
        testOnMediaDataLoaded_registersPlaybackListener()
        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
        verify(statusBarStateController).addCallback(capture(dozingCallbackCaptor))

        // And we doze past the scheduled timeout
        val time = clock.currentTimeMillis()
        clock.setElapsedRealtime(time + PAUSED_MEDIA_TIMEOUT)
        assertThat(executor.numPending()).isEqualTo(1)

        // Then when no longer dozing, the timeout runs immediately
        dozingCallbackCaptor.value.onDozingChanged(false)
        verify(timeoutCallback).invoke(eq(KEY), eq(true))
        verify(logger).logTimeout(eq(KEY))

        // and cancel any later scheduled timeout
        verify(logger).logTimeoutCancelled(eq(KEY), any())
        assertThat(executor.numPending()).isEqualTo(0)
    }

    @Test
    fun testTimeoutCallback_dozeShortTime_notInvokedOnWakeup() {
        // When paused media is loaded
        val time = clock.currentTimeMillis()
        clock.setElapsedRealtime(time)
        testOnMediaDataLoaded_registersPlaybackListener()
        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
        verify(statusBarStateController).addCallback(capture(dozingCallbackCaptor))

        // And we doze, but not past the scheduled timeout
        clock.setElapsedRealtime(time + PAUSED_MEDIA_TIMEOUT / 2L)
        assertThat(executor.numPending()).isEqualTo(1)

        // Then when no longer dozing, the timeout remains scheduled
        dozingCallbackCaptor.value.onDozingChanged(false)
        verify(timeoutCallback, never()).invoke(eq(KEY), eq(true))
        assertThat(executor.numPending()).isEqualTo(1)
    }

    private fun loadMediaDataWithPlaybackState(state: PlaybackState) {
        `when`(mediaController.playbackState).thenReturn(state)
        mediaTimeoutListener.onMediaDataLoaded(KEY, null, mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
    }
}
