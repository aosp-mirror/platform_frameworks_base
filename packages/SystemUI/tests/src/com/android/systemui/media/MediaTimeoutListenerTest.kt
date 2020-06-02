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

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

private const val KEY = "KEY"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWith(AndroidTestingRunner::class)
class MediaTimeoutListenerTest : SysuiTestCase() {

    @Mock private lateinit var mediaControllerFactory: MediaControllerFactory
    @Mock private lateinit var mediaController: MediaController
    @Mock private lateinit var executor: DelayableExecutor
    @Mock private lateinit var mediaData: MediaData
    @Mock private lateinit var timeoutCallback: (String, Boolean) -> Unit
    @Mock private lateinit var cancellationRunnable: Runnable
    @Captor private lateinit var timeoutCaptor: ArgumentCaptor<Runnable>
    @Captor private lateinit var mediaCallbackCaptor: ArgumentCaptor<MediaController.Callback>
    @JvmField @Rule val mockito = MockitoJUnit.rule()
    private lateinit var mediaTimeoutListener: MediaTimeoutListener

    @Before
    fun setup() {
        `when`(mediaControllerFactory.create(any())).thenReturn(mediaController)
        `when`(executor.executeDelayed(any(), anyLong())).thenReturn(cancellationRunnable)
        mediaTimeoutListener = MediaTimeoutListener(mediaControllerFactory, executor)
        mediaTimeoutListener.timeoutCallback = timeoutCallback
    }

    @Test
    fun testOnMediaDataLoaded_registersPlaybackListener() {
        mediaTimeoutListener.onMediaDataLoaded(KEY, mediaData)
        verify(mediaController).registerCallback(capture(mediaCallbackCaptor))

        // Ignores is same key
        clearInvocations(mediaController)
        mediaTimeoutListener.onMediaDataLoaded(KEY, mediaData)
        verify(mediaController, never()).registerCallback(anyObject())
    }

    @Test
    fun testOnMediaDataRemoved_unregistersPlaybackListener() {
        mediaTimeoutListener.onMediaDataLoaded(KEY, mediaData)
        mediaTimeoutListener.onMediaDataRemoved(KEY)
        verify(mediaController).unregisterCallback(anyObject())

        // Ignores duplicate requests
        clearInvocations(mediaController)
        mediaTimeoutListener.onMediaDataRemoved(KEY)
        verify(mediaController, never()).unregisterCallback(anyObject())
    }

    @Test
    fun testOnPlaybackStateChanged_schedulesTimeout_whenPaused() {
        // Assuming we're registered
        testOnMediaDataLoaded_registersPlaybackListener()

        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
        verify(executor).executeDelayed(capture(timeoutCaptor), anyLong())
    }

    @Test
    fun testOnPlaybackStateChanged_cancelsTimeout_whenResumed() {
        // Assuming we're have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        mediaCallbackCaptor.value.onPlaybackStateChanged(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 0f).build())
        verify(cancellationRunnable).run()
    }

    @Test
    fun testTimeoutCallback_invokedIfTimeout() {
        // Assuming we're have a pending timeout
        testOnPlaybackStateChanged_schedulesTimeout_whenPaused()

        timeoutCaptor.value.run()
        verify(timeoutCallback).invoke(eq(KEY), eq(true))
    }

    @Test
    fun testIsTimedOut() {
        mediaTimeoutListener.onMediaDataLoaded(KEY, mediaData)
        assertThat(mediaTimeoutListener.isTimedOut(KEY)).isFalse()
    }
}