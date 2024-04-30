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

import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val PACKAGE = "PKG"
private const val KEY = "TEST_KEY"
private const val NOTIF_KEY = "TEST_KEY"

private val info =
    MediaTestUtils.emptyMediaData.copy(packageName = PACKAGE, notificationKey = NOTIF_KEY)

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class MediaSessionBasedFilterTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    // Unit to be tested
    private lateinit var filter: MediaSessionBasedFilter

    private lateinit var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener
    @Mock private lateinit var mediaListener: MediaDataManager.Listener

    // MediaSessionBasedFilter dependencies
    @Mock private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var fgExecutor: FakeExecutor
    private lateinit var bgExecutor: FakeExecutor

    @Mock private lateinit var controller1: MediaController
    @Mock private lateinit var controller2: MediaController
    @Mock private lateinit var controller3: MediaController
    @Mock private lateinit var controller4: MediaController

    private lateinit var token1: MediaSession.Token
    private lateinit var token2: MediaSession.Token
    private lateinit var token3: MediaSession.Token
    private lateinit var token4: MediaSession.Token

    @Mock private lateinit var remotePlaybackInfo: PlaybackInfo
    @Mock private lateinit var localPlaybackInfo: PlaybackInfo

    private lateinit var session1: MediaSession
    private lateinit var session2: MediaSession
    private lateinit var session3: MediaSession
    private lateinit var session4: MediaSession

    private lateinit var mediaData1: MediaData
    private lateinit var mediaData2: MediaData
    private lateinit var mediaData3: MediaData
    private lateinit var mediaData4: MediaData

    @Before
    fun setUp() {
        fgExecutor = FakeExecutor(FakeSystemClock())
        bgExecutor = FakeExecutor(FakeSystemClock())
        filter = MediaSessionBasedFilter(context, mediaSessionManager, fgExecutor, bgExecutor)

        // Configure mocks.
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(emptyList())

        session1 = MediaSession(context, "MediaSessionBasedFilter1")
        session2 = MediaSession(context, "MediaSessionBasedFilter2")
        session3 = MediaSession(context, "MediaSessionBasedFilter3")
        session4 = MediaSession(context, "MediaSessionBasedFilter4")

        token1 = session1.sessionToken
        token2 = session2.sessionToken
        token3 = session3.sessionToken
        token4 = session4.sessionToken

        whenever(controller1.getSessionToken()).thenReturn(token1)
        whenever(controller2.getSessionToken()).thenReturn(token2)
        whenever(controller3.getSessionToken()).thenReturn(token3)
        whenever(controller4.getSessionToken()).thenReturn(token4)

        whenever(controller1.getPackageName()).thenReturn(PACKAGE)
        whenever(controller2.getPackageName()).thenReturn(PACKAGE)
        whenever(controller3.getPackageName()).thenReturn(PACKAGE)
        whenever(controller4.getPackageName()).thenReturn(PACKAGE)

        mediaData1 = info.copy(token = token1)
        mediaData2 = info.copy(token = token2)
        mediaData3 = info.copy(token = token3)
        mediaData4 = info.copy(token = token4)

        whenever(remotePlaybackInfo.getPlaybackType()).thenReturn(PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        whenever(localPlaybackInfo.getPlaybackType()).thenReturn(PlaybackInfo.PLAYBACK_TYPE_LOCAL)

        whenever(controller1.getPlaybackInfo()).thenReturn(localPlaybackInfo)
        whenever(controller2.getPlaybackInfo()).thenReturn(localPlaybackInfo)
        whenever(controller3.getPlaybackInfo()).thenReturn(localPlaybackInfo)
        whenever(controller4.getPlaybackInfo()).thenReturn(localPlaybackInfo)

        // Capture listener
        bgExecutor.runAllReady()
        val listenerCaptor =
            ArgumentCaptor.forClass(MediaSessionManager.OnActiveSessionsChangedListener::class.java)
        verify(mediaSessionManager)
            .addOnActiveSessionsChangedListener(listenerCaptor.capture(), any())
        sessionListener = listenerCaptor.value

        filter.addListener(mediaListener)
    }

    @After
    fun tearDown() {
        session1.release()
        session2.release()
        session3.release()
        session4.release()
    }

    @Test
    fun noMediaSession_loadedEventNotFiltered() {
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
    }

    @Test
    fun noMediaSession_removedEventNotFiltered() {
        filter.onMediaDataRemoved(KEY, false)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        verify(mediaListener).onMediaDataRemoved(eq(KEY), eq(false))
    }

    @Test
    fun matchingMediaSession_loadedEventNotFiltered() {
        // GIVEN an active session
        val controllers = listOf(controller1)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the session
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
    }

    @Test
    fun matchingMediaSession_removedEventNotFiltered() {
        // GIVEN an active session
        val controllers = listOf(controller1)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a removed event is received
        filter.onMediaDataRemoved(KEY, false)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener).onMediaDataRemoved(eq(KEY), eq(false))
    }

    @Test
    fun remoteSession_loadedEventNotFiltered() {
        // GIVEN a remote session
        whenever(controller1.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matche the session
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
    }

    @Test
    fun remoteAndLocalSessions_localLoadedEventFiltered() {
        // GIVEN remote and local sessions
        whenever(controller1.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the remote session
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
        // WHEN a loaded event is received that matches the local session
        filter.onMediaDataLoaded(KEY, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is filtered
        verify(mediaListener, never())
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                eq(mediaData2),
                anyBoolean(),
                anyInt(),
                anyBoolean()
            )
    }

    @Test
    fun remoteAndLocalSessions_remoteSessionWithoutNotification() {
        // GIVEN remote and local sessions
        whenever(controller2.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the local session
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered because there isn't a notification for the remote
        // session.
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
    }

    @Test
    fun remoteAndLocalHaveDifferentKeys_localLoadedEventFiltered() {
        // GIVEN remote and local sessions
        val key1 = "KEY_1"
        val key2 = "KEY_2"
        whenever(controller1.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the remote session
        filter.onMediaDataLoaded(key1, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(key1), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
        // WHEN a loaded event is received that matches the local session
        filter.onMediaDataLoaded(key2, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is filtered
        verify(mediaListener, never())
            .onMediaDataLoaded(
                eq(key2),
                eq(null),
                eq(mediaData2),
                anyBoolean(),
                anyInt(),
                anyBoolean()
            )
        // AND there should be a removed event for key2
        verify(mediaListener).onMediaDataRemoved(eq(key2), eq(false))
    }

    @Test
    fun remoteAndLocalHaveDifferentKeys_remoteSessionWithoutNotification() {
        // GIVEN remote and local sessions
        val key1 = "KEY_1"
        val key2 = "KEY_2"
        whenever(controller2.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the local session
        filter.onMediaDataLoaded(key1, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(key1), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
        // WHEN a loaded event is received that matches the remote session
        filter.onMediaDataLoaded(key2, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(key2), eq(null), eq(mediaData2), eq(true), eq(0), eq(false))
    }

    @Test
    fun multipleRemoteSessions_loadedEventNotFiltered() {
        // GIVEN two remote sessions
        whenever(controller1.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        whenever(controller2.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the remote session
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
        // WHEN a loaded event is received that matches the local session
        filter.onMediaDataLoaded(KEY, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData2), eq(true), eq(0), eq(false))
    }

    @Test
    fun multipleOtherSessions_loadedEventNotFiltered() {
        // GIVEN multiple active sessions from other packages
        val controllers = listOf(controller1, controller2, controller3, controller4)
        whenever(controller1.getPackageName()).thenReturn("PKG_1")
        whenever(controller2.getPackageName()).thenReturn("PKG_2")
        whenever(controller3.getPackageName()).thenReturn("PKG_3")
        whenever(controller4.getPackageName()).thenReturn("PKG_4")
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received
        filter.onMediaDataLoaded(KEY, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the event is not filtered
        verify(mediaListener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
    }

    @Test
    fun doNotFilterDuringKeyMigration() {
        val key1 = "KEY_1"
        val key2 = "KEY_2"
        // GIVEN a loaded event
        filter.onMediaDataLoaded(key1, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        reset(mediaListener)
        // GIVEN remote and local sessions
        whenever(controller1.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // WHEN a loaded event is received that matches the local session but it is a key migration
        filter.onMediaDataLoaded(key2, key1, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the key migration event is fired
        verify(mediaListener)
            .onMediaDataLoaded(eq(key2), eq(key1), eq(mediaData2), eq(true), eq(0), eq(false))
    }

    @Test
    fun filterAfterKeyMigration() {
        val key1 = "KEY_1"
        val key2 = "KEY_2"
        // GIVEN a loaded event
        filter.onMediaDataLoaded(key1, null, mediaData1)
        filter.onMediaDataLoaded(key1, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        reset(mediaListener)
        // GIVEN remote and local sessions
        whenever(controller1.getPlaybackInfo()).thenReturn(remotePlaybackInfo)
        val controllers = listOf(controller1, controller2)
        whenever(mediaSessionManager.getActiveSessions(any())).thenReturn(controllers)
        sessionListener.onActiveSessionsChanged(controllers)
        // GIVEN that the keys have been migrated
        filter.onMediaDataLoaded(key2, key1, mediaData1)
        filter.onMediaDataLoaded(key2, key1, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        reset(mediaListener)
        // WHEN a loaded event is received that matches the local session
        filter.onMediaDataLoaded(key2, null, mediaData2)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the key migration event is filtered
        verify(mediaListener, never())
            .onMediaDataLoaded(
                eq(key2),
                eq(null),
                eq(mediaData2),
                anyBoolean(),
                anyInt(),
                anyBoolean()
            )
        // WHEN a loaded event is received that matches the remote session
        filter.onMediaDataLoaded(key2, null, mediaData1)
        bgExecutor.runAllReady()
        fgExecutor.runAllReady()
        // THEN the key migration event is fired
        verify(mediaListener)
            .onMediaDataLoaded(eq(key2), eq(null), eq(mediaData1), eq(true), eq(0), eq(false))
    }
}
