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

package com.android.systemui.media.controls.domain.pipeline

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.util.concurrency.FakeExecutor
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
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

private const val KEY = "KEY"
private const val PACKAGE = "PKG"
private const val SESSION_KEY = "SESSION_KEY"
private const val SESSION_ARTIST = "SESSION_ARTIST"
private const val SESSION_TITLE = "SESSION_TITLE"
private const val SMARTSPACE_KEY = "SMARTSPACE_KEY"

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaTimeoutListenerTest : SysuiTestCase() {

    @Mock private lateinit var mediaControllerFactory: MediaControllerFactory
    @Mock private lateinit var mediaController: MediaController
    @Mock private lateinit var logger: MediaTimeoutLogger
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var timeoutCallback: (String, Boolean) -> Unit
    @Mock private lateinit var stateCallback: (String, PlaybackState) -> Unit
    @Mock private lateinit var sessionCallback: (String) -> Unit
    @Captor private lateinit var mediaCallbackCaptor: ArgumentCaptor<MediaController.Callback>
    @Captor
    private lateinit var dozingCallbackCaptor:
        ArgumentCaptor<StatusBarStateController.StateListener>
    @JvmField @Rule val mockito = MockitoJUnit.rule()
    private lateinit var metadataBuilder: MediaMetadata.Builder
    private lateinit var playbackBuilder: PlaybackState.Builder
    private lateinit var session: MediaSession
    private lateinit var mediaData: MediaData
    private lateinit var resumeData: MediaData
    private lateinit var mediaTimeoutListener: MediaTimeoutListener
    private var clock = FakeSystemClock()
    private lateinit var mainExecutor: FakeExecutor
    private lateinit var bgExecutor: FakeExecutor
    private lateinit var uiExecutor: FakeExecutor
    @Mock private lateinit var mediaFlags: MediaFlags
    @Mock private lateinit var smartspaceData: SmartspaceMediaData

    @Before
    fun setup() {
        whenever(mediaControllerFactory.create(any())).thenReturn(mediaController)
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(false)
        mainExecutor = FakeExecutor(clock)
        bgExecutor = FakeExecutor(clock)
        uiExecutor = FakeExecutor(clock)
        mediaTimeoutListener =
            MediaTimeoutListener(
                mediaControllerFactory,
                bgExecutor,
                uiExecutor,
                mainExecutor,
                logger,
                statusBarStateController,
                clock,
                mediaFlags,
            )
        mediaTimeoutListener.timeoutCallback = timeoutCallback
        mediaTimeoutListener.stateCallback = stateCallback
        mediaTimeoutListener.sessionCallback = sessionCallback

        // Create a media session and notification for testing.
        metadataBuilder =
            MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
                putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
            }
        playbackBuilder =
            PlaybackState.Builder().apply {
                setState(PlaybackState.STATE_PAUSED, 6000L, 1f)
                setActions(PlaybackState.ACTION_PLAY)
            }
        session =
            MediaSession(context, SESSION_KEY).apply {
                setMetadata(metadataBuilder.build())
                setPlaybackState(playbackBuilder.build())
            }
        session.setActive(true)

        mediaData =
            MediaTestUtils.emptyMediaData.copy(
                app = PACKAGE,
                packageName = PACKAGE,
                token = session.sessionToken,
            )

        resumeData = mediaData.copy(token = null, active = false, resumption = true)
    }

    @Test
    fun testOnMediaDataLoaded_registersPlaybackListener() {
        val playingState = mock(android.media.session.PlaybackState::class.java)
        whenever(playingState.state).thenReturn(PlaybackState.STATE_PLAYING)

        whenever(mediaController.playbackState).thenReturn(playingState)
        loadMediaData(KEY, null, mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        verify(logger).logPlaybackState(eq(KEY), eq(playingState))

        // Ignores if same key
        clearInvocations(mediaController)
        loadMediaData(KEY, KEY, mediaData)
        verify(mediaController, never()).registerCallback(anyObject())
    }

    @Test
    fun testOnMediaDataLoaded_registersTimeout_whenPaused() {
        loadMediaData(KEY, null, mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        verify(logger).logScheduleTimeout(eq(KEY), eq(false), eq(false))
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(PAUSED_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataRemoved_unregistersPlaybackListener() {
        loadMediaData(KEY, null, mediaData)
        mediaTimeoutListener.onMediaDataRemoved(KEY, false)
        assertThat(bgExecutor.runAllReady()).isEqualTo(1)
        verify(mediaController).unregisterCallback(anyObject())

        // Ignores duplicate requests
        clearInvocations(mediaController)
        mediaTimeoutListener.onMediaDataRemoved(KEY, false)
        verify(mediaController, never()).unregisterCallback(anyObject())
    }

    @Test
    fun testOnMediaDataRemoved_clearsTimeout() {
        // GIVEN media that is paused
        loadMediaData(KEY, null, mediaData)
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        // WHEN the media is removed
        mediaTimeoutListener.onMediaDataRemoved(KEY, false)
        // THEN the timeout runnable is cancelled
        assertThat(mainExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun testOnMediaDataLoaded_migratesKeys() {
        val newKey = "NEWKEY"
        // From not playing
        loadMediaData(KEY, null, mediaData)
        clearInvocations(mediaController)

        // To playing
        val playingState = mock(android.media.session.PlaybackState::class.java)
        whenever(playingState.state).thenReturn(PlaybackState.STATE_PLAYING)
        whenever(mediaController.playbackState).thenReturn(playingState)
        loadMediaData(newKey, KEY, mediaData)
        verify(mediaController).unregisterCallback(anyObject())
        verify(mediaController).registerCallback(anyObject())
        verify(logger).logMigrateListener(eq(KEY), eq(newKey), eq(true))

        // Enqueues callback
        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun testOnMediaDataLoaded_migratesKeys_noTimeoutExtension() {
        val newKey = "NEWKEY"
        // From not playing
        loadMediaData(KEY, null, mediaData)
        clearInvocations(mediaController)

        // Migrate, still not playing
        val playingState = mock(android.media.session.PlaybackState::class.java)
        whenever(playingState.state).thenReturn(PlaybackState.STATE_PAUSED)
        whenever(mediaController.playbackState).thenReturn(playingState)
        loadMediaData(newKey, KEY, mediaData)

        // The number of queued timeout tasks remains the same. The timeout task isn't cancelled nor
        // is another scheduled
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        verify(logger).logUpdateListener(eq(newKey), eq(false))
    }

    @Test
    fun testOnPlaybackStateChanged_schedulesTimeout_whenPaused() {
        // Assuming we're registered
        testOnMediaDataLoaded_registersPlaybackListener()

        onPlaybackStateChanged(
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        )
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(PAUSED_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnPlaybackStateChanged_cancelsTimeout_whenResumed() {
        // Assuming we have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        onPlaybackStateChanged(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 0f).build()
        )
        assertThat(mainExecutor.numPending()).isEqualTo(0)
        verify(logger).logTimeoutCancelled(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_reusesTimeout_whenNotPlaying() {
        // Assuming we have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        onPlaybackStateChanged(
            PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build()
        )
        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun testTimeoutCallback_invokedIfTimeout() {
        // Assuming we're have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        with(mainExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        verify(timeoutCallback).invoke(eq(KEY), eq(true))
    }

    @Test
    fun testIsTimedOut() {
        loadMediaData(KEY, null, mediaData)
        assertThat(mediaTimeoutListener.isTimedOut(KEY)).isFalse()
    }

    @Test
    fun testOnSessionDestroyed_active_clearsTimeout() {
        // GIVEN media that is paused
        val mediaPaused = mediaData.copy(isPlaying = false)
        loadMediaData(KEY, null, mediaPaused)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // WHEN the session is destroyed
        mediaCallbackCaptor.value.onSessionDestroyed()

        // THEN the controller is unregistered and timeout run
        assertThat(bgExecutor.runAllReady()).isEqualTo(1)
        verify(mediaController).unregisterCallback(anyObject())
        assertThat(mainExecutor.numPending()).isEqualTo(0)
        verify(logger).logSessionDestroyed(eq(KEY))
        verify(sessionCallback).invoke(eq(KEY))
    }

    @Test
    fun testSessionDestroyed_thenRestarts_resetsTimeout() {
        // Assuming we have previously destroyed the session
        testOnSessionDestroyed_active_clearsTimeout()

        // WHEN we get an update with media playing
        val playingState = mock(android.media.session.PlaybackState::class.java)
        whenever(playingState.state).thenReturn(PlaybackState.STATE_PLAYING)
        whenever(mediaController.playbackState).thenReturn(playingState)
        val mediaPlaying = mediaData.copy(isPlaying = true)
        loadMediaData(KEY, null, mediaPlaying)

        // THEN the timeout runnable will update the state
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        with(mainExecutor) {
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
        loadMediaData(PACKAGE, null, resumeWithSession)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // WHEN the session is destroyed
        mediaCallbackCaptor.value.onSessionDestroyed()

        // THEN the controller is unregistered, but the timeout is still scheduled
        assertThat(bgExecutor.runAllReady()).isEqualTo(1)
        verify(mediaController).unregisterCallback(anyObject())
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        verify(sessionCallback, never()).invoke(eq(KEY))
    }

    @Test
    fun testOnMediaDataLoaded_activeToResume_registersTimeout() {
        // WHEN a regular media is loaded
        loadMediaData(KEY, null, mediaData)

        // AND it turns into a resume control
        loadMediaData(PACKAGE, KEY, resumeData)

        // THEN we register a timeout
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(RESUME_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataLoaded_pausedToResume_updatesTimeout() {
        // WHEN regular media is paused
        val pausedState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        whenever(mediaController.playbackState).thenReturn(pausedState)
        loadMediaData(KEY, null, mediaData)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // AND it turns into a resume control
        loadMediaData(PACKAGE, KEY, resumeData)

        // THEN we update the timeout length
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(RESUME_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataLoaded_resumption_registersTimeout() {
        // WHEN a resume media is loaded
        loadMediaData(PACKAGE, null, resumeData)

        // THEN we register a timeout
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        verify(timeoutCallback, never()).invoke(anyString(), anyBoolean())
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(RESUME_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataLoaded_resumeToActive_updatesTimeout() {
        // WHEN we have a resume control
        loadMediaData(PACKAGE, null, resumeData)

        // AND that media is resumed
        val playingState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        whenever(mediaController.playbackState).thenReturn(playingState)
        loadMediaData(oldKey = PACKAGE, data = mediaData)

        // THEN the timeout length is changed to a regular media control
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(PAUSED_MEDIA_TIMEOUT)
    }

    @Test
    fun testOnMediaDataRemoved_resume_timeoutCancelled() {
        // WHEN we have a resume control
        testOnMediaDataLoaded_resumption_registersTimeout()
        // AND the media is removed
        mediaTimeoutListener.onMediaDataRemoved(PACKAGE, false)

        // THEN the timeout runnable is cancelled
        assertThat(mainExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun testOnMediaDataLoaded_playbackActionsChanged_noCallback() {
        // Load media data once
        val pausedState = PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE).build()
        loadMediaDataWithPlaybackState(pausedState)

        // When media data is loaded again, with different actions
        val playingState = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY).build()
        loadMediaDataWithPlaybackState(playingState)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_playbackActionsChanged_sendsCallback() {
        // Load media data once
        val pausedState = PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE).build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state changes, and has different actions
        val playingState = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY).build()
        onPlaybackStateChanged(playingState)
        assertThat(uiExecutor.runAllReady()).isEqualTo(1)

        // Then the callback is invoked
        verify(stateCallback).invoke(eq(KEY), eq(playingState!!))
    }

    @Test
    fun testOnPlaybackStateChanged_differentCustomActions_sendsCallback() {
        val customOne =
            PlaybackState.CustomAction.Builder(
                    "ACTION_1",
                    "custom action 1",
                    android.R.drawable.ic_media_ff,
                )
                .build()
        val pausedState =
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customOne)
                .build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state actions change
        val customTwo =
            PlaybackState.CustomAction.Builder(
                    "ACTION_2",
                    "custom action 2",
                    android.R.drawable.ic_media_rew,
                )
                .build()
        val pausedStateTwoActions =
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customOne)
                .addCustomAction(customTwo)
                .build()
        onPlaybackStateChanged(pausedStateTwoActions)
        assertThat(uiExecutor.runAllReady()).isEqualTo(1)

        // Then the callback is invoked
        verify(stateCallback).invoke(eq(KEY), eq(pausedStateTwoActions!!))
    }

    @Test
    fun testOnPlaybackStateChanged_sameActions_noCallback() {
        val stateWithActions = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY).build()
        loadMediaDataWithPlaybackState(stateWithActions)

        // When the playback state updates with the same actions
        onPlaybackStateChanged(stateWithActions)

        // Then the callback is not invoked again
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_sameCustomActions_noCallback() {
        val actionName = "custom action"
        val actionIcon = android.R.drawable.ic_media_ff
        val customOne =
            PlaybackState.CustomAction.Builder(actionName, actionName, actionIcon).build()
        val stateOne =
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customOne)
                .build()
        loadMediaDataWithPlaybackState(stateOne)

        // When the playback state is updated, but has the same actions
        val customTwo =
            PlaybackState.CustomAction.Builder(actionName, actionName, actionIcon).build()
        val stateTwo =
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE)
                .addCustomAction(customTwo)
                .build()
        onPlaybackStateChanged(stateTwo)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnMediaDataLoaded_isPlayingChanged_noCallback() {
        // Load media data in paused state
        val pausedState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        loadMediaDataWithPlaybackState(pausedState)

        // When media data is loaded again but playing
        val playingState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build()
        loadMediaDataWithPlaybackState(playingState)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), any())
    }

    @Test
    fun testOnPlaybackStateChanged_isPlayingChanged_sendsCallback() {
        // Load media data in paused state
        val pausedState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state changes to playing
        val playingState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build()
        onPlaybackStateChanged(playingState)
        uiExecutor.runAllReady()

        // Then the callback is invoked
        verify(stateCallback).invoke(eq(KEY), eq(playingState!!))
    }

    @Test
    fun testOnPlaybackStateChanged_isPlayingSame_noCallback() {
        // Load media data in paused state
        val pausedState =
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        loadMediaDataWithPlaybackState(pausedState)

        // When the playback state is updated, but still not playing
        val playingState =
            PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build()
        onPlaybackStateChanged(playingState)

        // Then the callback is not invoked
        verify(stateCallback, never()).invoke(eq(KEY), eq(playingState!!))
    }

    @Test
    fun testTimeoutCallback_dozedPastTimeout_invokedOnWakeup() {
        // When paused media is loaded
        testOnMediaDataLoaded_registersPlaybackListener()
        onPlaybackStateChanged(
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        )
        verify(statusBarStateController).addCallback(capture(dozingCallbackCaptor))

        // And we doze past the scheduled timeout
        val time = clock.currentTimeMillis()
        clock.setElapsedRealtime(time + PAUSED_MEDIA_TIMEOUT)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // Then when no longer dozing, the timeout runs immediately
        dozingCallbackCaptor.value.onDozingChanged(false)
        verify(timeoutCallback).invoke(eq(KEY), eq(true))
        verify(logger).logTimeout(eq(KEY))

        // and cancel any later scheduled timeout
        verify(logger).logTimeoutCancelled(eq(KEY), any())
        assertThat(mainExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun testTimeoutCallback_dozeShortTime_notInvokedOnWakeup() {
        // When paused media is loaded
        val time = clock.currentTimeMillis()
        clock.setElapsedRealtime(time)
        testOnMediaDataLoaded_registersPlaybackListener()
        onPlaybackStateChanged(
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
        )
        verify(statusBarStateController).addCallback(capture(dozingCallbackCaptor))

        // And we doze, but not past the scheduled timeout
        clock.setElapsedRealtime(time + PAUSED_MEDIA_TIMEOUT / 2L)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // Then when no longer dozing, the timeout remains scheduled
        dozingCallbackCaptor.value.onDozingChanged(false)
        verify(timeoutCallback, never()).invoke(eq(KEY), eq(true))
        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun testSmartspaceDataLoaded_schedulesTimeout() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
        val duration = 60_000
        val createTime = 1234L
        val expireTime = createTime + duration
        whenever(smartspaceData.headphoneConnectionTimeMillis).thenReturn(createTime)
        whenever(smartspaceData.expiryTimeMs).thenReturn(expireTime)

        mediaTimeoutListener.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
        assertThat(mainExecutor.numPending()).isEqualTo(1)
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(duration)
    }

    @Test
    fun testSmartspaceMediaData_timesOut_invokesCallback() {
        // Given a pending timeout
        testSmartspaceDataLoaded_schedulesTimeout()

        mainExecutor.runAllReady()
        verify(timeoutCallback).invoke(eq(SMARTSPACE_KEY), eq(true))
    }

    @Test
    fun testSmartspaceDataLoaded_alreadyExists_updatesTimeout() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
        val duration = 100
        val createTime = 1234L
        val expireTime = createTime + duration
        whenever(smartspaceData.headphoneConnectionTimeMillis).thenReturn(createTime)
        whenever(smartspaceData.expiryTimeMs).thenReturn(expireTime)

        mediaTimeoutListener.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        val expiryLonger = expireTime + duration
        whenever(smartspaceData.expiryTimeMs).thenReturn(expiryLonger)
        mediaTimeoutListener.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
        assertThat(mainExecutor.advanceClockToNext()).isEqualTo(duration * 2)
    }

    @Test
    fun testSmartspaceDataRemoved_cancelTimeout() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)

        mediaTimeoutListener.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        mediaTimeoutListener.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)
        assertThat(mainExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun testSmartspaceData_dozedPastTimeout_invokedOnWakeup() {
        // Given a pending timeout
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
        verify(statusBarStateController).addCallback(capture(dozingCallbackCaptor))
        val duration = 60_000
        val createTime = 1234L
        val expireTime = createTime + duration
        whenever(smartspaceData.headphoneConnectionTimeMillis).thenReturn(createTime)
        whenever(smartspaceData.expiryTimeMs).thenReturn(expireTime)

        mediaTimeoutListener.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // And we doze past the scheduled timeout
        val time = clock.currentTimeMillis()
        clock.setElapsedRealtime(time + duration * 2)
        assertThat(mainExecutor.numPending()).isEqualTo(1)

        // Then when no longer dozing, the timeout runs immediately
        dozingCallbackCaptor.value.onDozingChanged(false)
        verify(timeoutCallback).invoke(eq(SMARTSPACE_KEY), eq(true))
        verify(logger).logTimeout(eq(SMARTSPACE_KEY))

        // and cancel any later scheduled timeout
        assertThat(mainExecutor.numPending()).isEqualTo(0)
    }

    private fun loadMediaDataWithPlaybackState(state: PlaybackState) {
        whenever(mediaController.playbackState).thenReturn(state)
        loadMediaData(data = mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))
    }

    private fun loadMediaData(key: String = KEY, oldKey: String? = null, data: MediaData) {
        mediaTimeoutListener.onMediaDataLoaded(key, oldKey, data)
        bgExecutor.runAllReady()
        uiExecutor.runAllReady()
    }

    private fun onPlaybackStateChanged(state: PlaybackState) {
        mediaCallbackCaptor.value.onPlaybackStateChanged(state)
        bgExecutor.runAllReady()
    }
}
