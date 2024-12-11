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

package com.android.systemui.media.controls.ui.viewmodel

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.TestableLooper
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.Classifier
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.concurrency.FakeRepeatableExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class SeekBarViewModelTest : SysuiTestCase() {

    private lateinit var viewModel: SeekBarViewModel
    private lateinit var fakeExecutor: FakeExecutor
    private val taskExecutor: TaskExecutor =
        object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) {
                runnable.run()
            }

            override fun postToMainThread(runnable: Runnable) {
                runnable.run()
            }

            override fun isMainThread(): Boolean {
                return true
            }
        }
    @Mock private lateinit var mockController: MediaController
    @Mock private lateinit var mockTransport: MediaController.TransportControls
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var mockBar: SeekBar
    private val token1 = MediaSession.Token(1, null)
    private val token2 = MediaSession.Token(2, null)

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        fakeExecutor = FakeExecutor(FakeSystemClock())
        viewModel = SeekBarViewModel(FakeRepeatableExecutor(fakeExecutor), falsingManager)
        viewModel.logSeek = {}
        whenever(mockController.sessionToken).thenReturn(token1)
        whenever(mockBar.context).thenReturn(context)

        // LiveData to run synchronously
        ArchTaskExecutor.getInstance().setDelegate(taskExecutor)
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun updateRegistersCallback() {
        viewModel.updateController(mockController)
        verify(mockController).registerCallback(any())
    }

    @Test
    fun updateSecondTimeDoesNotRepeatRegistration() {
        viewModel.updateController(mockController)
        viewModel.updateController(mockController)
        verify(mockController, times(1)).registerCallback(any())
    }

    @Test
    fun updateDifferentControllerUnregistersCallback() {
        viewModel.updateController(mockController)
        viewModel.updateController(mock(MediaController::class.java))
        verify(mockController).unregisterCallback(any())
    }

    @Test
    fun updateDifferentControllerRegistersCallback() {
        viewModel.updateController(mockController)
        val controller2 = mock(MediaController::class.java)
        whenever(controller2.sessionToken).thenReturn(token2)
        viewModel.updateController(controller2)
        verify(controller2).registerCallback(any())
    }

    @Test
    fun updateToNullUnregistersCallback() {
        viewModel.updateController(mockController)
        viewModel.updateController(null)
        verify(mockController).unregisterCallback(any())
    }

    @Test
    @Ignore
    fun updateDurationWithPlayback() {
        // GIVEN that the duration is contained within the metadata
        val duration = 12000L
        val metadata =
            MediaMetadata.Builder().run {
                putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                build()
            }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // AND a valid playback state (ie. media session is not destroyed)
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN the duration is extracted
        assertThat(viewModel.progress.value!!.duration).isEqualTo(duration)
        assertThat(viewModel.progress.value!!.enabled).isTrue()
    }

    @Test
    @Ignore
    fun updateDurationWithoutPlayback() {
        // GIVEN that the duration is contained within the metadata
        val duration = 12000L
        val metadata =
            MediaMetadata.Builder().run {
                putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                build()
            }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN the duration is extracted
        assertThat(viewModel.progress.value!!.duration).isEqualTo(duration)
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    fun updateDurationNegative() {
        // GIVEN that the duration is negative
        val duration = -1L
        val metadata =
            MediaMetadata.Builder().run {
                putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                build()
            }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // AND a valid playback state (ie. media session is not destroyed)
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN the seek bar is disabled
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    fun updateDurationZero() {
        // GIVEN that the duration is zero
        val duration = 0L
        val metadata =
            MediaMetadata.Builder().run {
                putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                build()
            }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // AND a valid playback state (ie. media session is not destroyed)
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN the seek bar is disabled
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    @Ignore
    fun updateDurationNoMetadata() {
        // GIVEN that the metadata is null
        whenever(mockController.getMetadata()).thenReturn(null)
        // AND a valid playback state (ie. media session is not destroyed)
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN the seek bar is disabled
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    fun updateElapsedTime() {
        // GIVEN that the PlaybackState contains the current position
        val position = 200L
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, position, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN elapsed time is captured
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(200.toInt())
    }

    @Test
    @Ignore
    fun updateSeekAvailable() {
        // GIVEN that seek is included in actions
        val state =
            PlaybackState.Builder().run {
                setActions(PlaybackState.ACTION_SEEK_TO)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN seek is available
        assertThat(viewModel.progress.value!!.seekAvailable).isTrue()
    }

    @Test
    @Ignore
    fun updateSeekNotAvailable() {
        // GIVEN that seek is not included in actions
        val state =
            PlaybackState.Builder().run {
                setActions(PlaybackState.ACTION_PLAY)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN seek is not available
        assertThat(viewModel.progress.value!!.seekAvailable).isFalse()
    }

    @Test
    fun onSeek() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        // WHEN user input is dispatched
        val pos = 42L
        viewModel.onSeek(pos)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport).seekTo(pos)
    }

    @Test
    fun onSeekWithFalse() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        // WHEN a false is received during the seek gesture
        val pos = 42L
        with(viewModel) {
            onSeekStarting()
            onSeekFalse()
            onSeek(pos)
        }
        fakeExecutor.runAllReady()
        // THEN the seek is rejected and the transport never receives seekTo
        verify(mockTransport, never()).seekTo(pos)
    }

    @Test
    fun onSeekProgress() {
        val pos = 42L
        with(viewModel) {
            onSeekStarting()
            onSeekProgress(pos)
        }
        fakeExecutor.runAllReady()
        // THEN then elapsed time should be updated
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(pos)
    }

    @Test
    @Ignore
    fun onSeekProgressWithSeekStarting() {
        val pos = 42L
        with(viewModel) { onSeekProgress(pos) }
        fakeExecutor.runAllReady()
        // THEN then elapsed time should not be updated
        assertThat(viewModel.progress.value!!.elapsedTime).isNull()
    }

    @Test
    fun seekStarted_listenerNotified() {
        var isScrubbing: Boolean? = null
        val listener =
            object : SeekBarViewModel.ScrubbingChangeListener {
                override fun onScrubbingChanged(scrubbing: Boolean) {
                    isScrubbing = scrubbing
                }
            }
        viewModel.setScrubbingChangeListener(listener)

        viewModel.onSeekStarting()
        fakeExecutor.runAllReady()

        assertThat(isScrubbing).isTrue()
    }

    @Test
    fun seekEnded_listenerNotified() {
        var isScrubbing: Boolean? = null
        val listener =
            object : SeekBarViewModel.ScrubbingChangeListener {
                override fun onScrubbingChanged(scrubbing: Boolean) {
                    isScrubbing = scrubbing
                }
            }
        viewModel.setScrubbingChangeListener(listener)

        // Start seeking
        viewModel.onSeekStarting()
        fakeExecutor.runAllReady()
        // End seeking
        viewModel.onSeek(15L)
        fakeExecutor.runAllReady()

        assertThat(isScrubbing).isFalse()
    }

    @Test
    @Ignore
    fun onProgressChangedFromUser() {
        // WHEN user starts dragging the seek bar
        val pos = 42
        val bar = SeekBar(context)
        with(viewModel.seekBarListener) {
            onStartTrackingTouch(bar)
            onProgressChanged(bar, pos, true)
        }
        fakeExecutor.runAllReady()
        // THEN then elapsed time should be updated
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(pos)
    }

    @Test
    fun onProgressChangedFromUserWithoutStartTrackingTouch_transportUpdated() {
        whenever(mockController.transportControls).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        val pos = 42
        val bar = SeekBar(context)

        // WHEN we get an onProgressChanged event without an onStartTrackingTouch event
        with(viewModel.seekBarListener) { onProgressChanged(bar, pos, true) }
        fakeExecutor.runAllReady()

        // THEN we immediately update the transport
        verify(mockTransport).seekTo(pos.toLong())
    }

    @Test
    fun onProgressChangedNotFromUser() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        // WHEN user starts dragging the seek bar
        val pos = 42
        viewModel.seekBarListener.onProgressChanged(SeekBar(context), pos, false)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport, never()).seekTo(pos.toLong())
    }

    @Test
    fun onStartTrackingTouch() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        // WHEN user starts dragging the seek bar
        val pos = 42
        val bar = SeekBar(context).apply { progress = pos }
        viewModel.seekBarListener.onStartTrackingTouch(bar)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport, never()).seekTo(pos.toLong())
    }

    @Test
    fun onStopTrackingTouch() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        // WHEN user ends drag
        val pos = 42
        val bar = SeekBar(context).apply { progress = pos }
        viewModel.seekBarListener.onStopTrackingTouch(bar)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport).seekTo(pos.toLong())
    }

    @Test
    fun onStopTrackingTouchAfterProgress() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController)
        // WHEN user starts dragging the seek bar
        val pos = 42
        val progPos = 84
        val bar = SeekBar(context).apply { progress = pos }
        with(viewModel.seekBarListener) {
            onStartTrackingTouch(bar)
            onProgressChanged(bar, progPos, true)
            onStopTrackingTouch(bar)
        }
        fakeExecutor.runAllReady()
        // THEN then elapsed time should be updated
        verify(mockTransport).seekTo(eq(pos.toLong()))
    }

    @Test
    fun onFalseTapOrTouch() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        whenever(falsingManager.isFalseTouch(Classifier.MEDIA_SEEKBAR)).thenReturn(true)
        whenever(falsingManager.isFalseTap(anyInt())).thenReturn(true)

        viewModel.updateController(mockController)
        val pos = 40
        val bar = SeekBar(context).apply { progress = pos }
        with(viewModel.seekBarListener) {
            onStartTrackingTouch(bar)
            onStopTrackingTouch(bar)
        }
        fakeExecutor.runAllReady()

        // THEN transport controls should not be used
        verify(mockTransport, never()).seekTo(pos.toLong())
    }

    @Test
    fun onSeekbarGrabInvalidTouch() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.firstMotionEvent =
            MotionEvent.obtain(12L, 13L, MotionEvent.ACTION_DOWN, 76F, 0F, 0)
        viewModel.lastMotionEvent = MotionEvent.obtain(12L, 14L, MotionEvent.ACTION_UP, 78F, 4F, 0)
        val pos = 78

        viewModel.updateController(mockController)
        // WHEN user ends drag
        val bar = SeekBar(context).apply { progress = pos }
        with(viewModel.seekBarListener) {
            onStartTrackingTouch(bar)
            onStopTrackingTouch(bar)
        }
        fakeExecutor.runAllReady()

        // THEN transport controls should not be used
        verify(mockTransport, never()).seekTo(pos.toLong())
    }

    @Test
    fun onSeekbarGrabValidTouch() {
        whenever(mockController.transportControls).thenReturn(mockTransport)
        viewModel.firstMotionEvent =
            MotionEvent.obtain(12L, 13L, MotionEvent.ACTION_DOWN, 36F, 0F, 0)
        viewModel.lastMotionEvent = MotionEvent.obtain(12L, 14L, MotionEvent.ACTION_UP, 40F, 1F, 0)
        val pos = 40

        viewModel.updateController(mockController)
        // WHEN user ends drag
        val bar = SeekBar(context).apply { progress = pos }
        with(viewModel.seekBarListener) {
            onStartTrackingTouch(bar)
            onStopTrackingTouch(bar)
        }
        fakeExecutor.runAllReady()

        // THEN transport controls should be used
        verify(mockTransport).seekTo(pos.toLong())
    }

    @Test
    fun queuePollTaskWhenPlaying() {
        // GIVEN that the track is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 100L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController)
        // THEN a task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun noQueuePollTaskWhenStopped() {
        // GIVEN that the playback state is stopped
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_STOPPED, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN updated
        viewModel.updateController(mockController)
        // THEN an update task is not queued
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun queuePollTaskWhenListening() {
        // GIVEN listening
        viewModel.listening = true
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // AND the playback state is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN updated
        viewModel.updateController(mockController)
        // THEN an update task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun noQueuePollTaskWhenNotListening() {
        // GIVEN not listening
        viewModel.listening = false
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // AND the playback state is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN updated
        viewModel.updateController(mockController)
        // THEN an update task is not queued
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun pollTaskQueuesAnotherPollTaskWhenPlaying() {
        // GIVEN that the track is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 100L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController)
        // WHEN the next task runs
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN another task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun noQueuePollTaskWhenSeeking() {
        // GIVEN listening
        viewModel.listening = true
        // AND the playback state is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController)
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // WHEN seek starts
        viewModel.onSeekStarting()
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN an update task is not queued because we don't want it fighting with the user when
        // they are trying to move the thumb.
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun queuePollTaskWhenDoneSeekingWithFalse() {
        // GIVEN listening
        viewModel.listening = true
        // AND the playback state is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController)
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // WHEN seek finishes after a false
        with(viewModel) {
            onSeekStarting()
            onSeekFalse()
            onSeek(42L)
        }
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN an update task is queued because the gesture was ignored and progress was restored.
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun noQueuePollTaskWhenDoneSeeking() {
        // GIVEN listening
        viewModel.listening = true
        // AND the playback state is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController)
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // WHEN seek finishes after a false
        with(viewModel) {
            onSeekStarting()
            onSeek(42L)
        }
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN no update task is queued because we are waiting for an updated playback state to be
        // returned in response to the seek.
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun startListeningQueuesPollTask() {
        // GIVEN not listening
        viewModel.listening = false
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // AND the playback state is playing
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_STOPPED, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController)
        // WHEN start listening
        viewModel.listening = true
        // THEN an update task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun playbackChangeQueuesPollTask() {
        viewModel.updateController(mockController)
        val captor = ArgumentCaptor.forClass(MediaController.Callback::class.java)
        verify(mockController).registerCallback(captor.capture())
        val callback = captor.value
        // WHEN the callback receives an new state
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 100L, 1f)
                build()
            }
        callback.onPlaybackStateChanged(state)
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN an update task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    @Ignore
    fun clearSeekBar() {
        // GIVEN that the duration is contained within the metadata
        val metadata =
            MediaMetadata.Builder().run {
                putLong(MediaMetadata.METADATA_KEY_DURATION, 12000L)
                build()
            }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // AND a valid playback state (ie. media session is not destroyed)
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_PLAYING, 200L, 1f)
                build()
            }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // AND the controller has been updated
        viewModel.updateController(mockController)
        // WHEN the controller is cleared on the event when the session is destroyed
        viewModel.clearController()
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN the seek bar is disabled
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    fun clearSeekBarUnregistersCallback() {
        viewModel.updateController(mockController)
        viewModel.clearController()
        fakeExecutor.runAllReady()
        verify(mockController).unregisterCallback(any())
    }

    @Test
    fun destroyUnregistersCallback() {
        viewModel.updateController(mockController)
        viewModel.onDestroy()
        fakeExecutor.runAllReady()
        verify(mockController).unregisterCallback(any())
    }

    @Test
    fun nullPlaybackStateUnregistersCallback() {
        viewModel.updateController(mockController)
        val captor = ArgumentCaptor.forClass(MediaController.Callback::class.java)
        verify(mockController).registerCallback(captor.capture())
        val callback = captor.value
        // WHEN the callback receives a null state
        callback.onPlaybackStateChanged(null)
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN we unregister callback (as a result of clearing the controller)
        fakeExecutor.runAllReady()
        verify(mockController).unregisterCallback(any())
    }

    @Test
    fun positionUpdatedWhileStopped() {
        // When playback is stopped at one position
        val firstPosition = 200L
        val state =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_STOPPED, firstPosition, 1f)
                build()
            }
        whenever(mockController.playbackState).thenReturn(state)
        val captor = ArgumentCaptor.forClass(MediaController.Callback::class.java)
        viewModel.updateController(mockController)
        verify(mockController).registerCallback(captor.capture())
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(firstPosition.toInt())

        // And the state is updated with a new position
        val secondPosition = 42L
        val secondState =
            PlaybackState.Builder().run {
                setState(PlaybackState.STATE_STOPPED, secondPosition, 1f)
                build()
            }
        captor.value.onPlaybackStateChanged(secondState)

        // THEN then elapsed time should be updated
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(secondPosition.toInt())
    }
}
