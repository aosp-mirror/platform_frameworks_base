/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.PendingIntent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import javax.inject.Provider
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

private val DATA = MediaTestUtils.emptyMediaData

private val SMARTSPACE_KEY = "smartspace"

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class MediaCarouselControllerTest : SysuiTestCase() {

    @Mock lateinit var mediaControlPanelFactory: Provider<MediaControlPanel>
    @Mock lateinit var panel: MediaControlPanel
    @Mock lateinit var visualStabilityProvider: VisualStabilityProvider
    @Mock lateinit var mediaHostStatesManager: MediaHostStatesManager
    @Mock lateinit var mediaHostState: MediaHostState
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock @Main private lateinit var executor: DelayableExecutor
    @Mock lateinit var mediaDataManager: MediaDataManager
    @Mock lateinit var configurationController: ConfigurationController
    @Mock lateinit var falsingCollector: FalsingCollector
    @Mock lateinit var falsingManager: FalsingManager
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var logger: MediaUiEventLogger
    @Mock lateinit var debugLogger: MediaCarouselControllerLogger
    @Mock lateinit var mediaPlayer: MediaControlPanel
    @Mock lateinit var mediaViewController: MediaViewController
    @Mock lateinit var smartspaceMediaData: SmartspaceMediaData
    @Captor lateinit var listener: ArgumentCaptor<MediaDataManager.Listener>
    @Captor lateinit var visualStabilityCallback: ArgumentCaptor<OnReorderingAllowedListener>

    private val clock = FakeSystemClock()
    private lateinit var mediaCarouselController: MediaCarouselController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mediaCarouselController = MediaCarouselController(
            context,
            mediaControlPanelFactory,
            visualStabilityProvider,
            mediaHostStatesManager,
            activityStarter,
            clock,
            executor,
            mediaDataManager,
            configurationController,
            falsingCollector,
            falsingManager,
            dumpManager,
            logger,
            debugLogger
        )
        verify(mediaDataManager).addListener(capture(listener))
        verify(visualStabilityProvider)
            .addPersistentReorderingAllowedListener(capture(visualStabilityCallback))
        whenever(mediaControlPanelFactory.get()).thenReturn(mediaPlayer)
        whenever(mediaPlayer.mediaViewController).thenReturn(mediaViewController)
        whenever(mediaDataManager.smartspaceMediaData).thenReturn(smartspaceMediaData)
        MediaPlayerData.clear()
    }

    @Test
    fun testPlayerOrdering() {
        // Test values: key, data, last active time
        val playingLocal = Triple("playing local",
            DATA.copy(active = true, isPlaying = true,
                    playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false),
            4500L)

        val playingCast = Triple("playing cast",
            DATA.copy(active = true, isPlaying = true,
                    playbackLocation = MediaData.PLAYBACK_CAST_LOCAL, resumption = false),
            5000L)

        val pausedLocal = Triple("paused local",
            DATA.copy(active = true, isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false),
            1000L)

        val pausedCast = Triple("paused cast",
            DATA.copy(active = true, isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_CAST_LOCAL, resumption = false),
            2000L)

        val playingRcn = Triple("playing RCN",
            DATA.copy(active = true, isPlaying = true,
                    playbackLocation = MediaData.PLAYBACK_CAST_REMOTE, resumption = false),
            5000L)

        val pausedRcn = Triple("paused RCN",
                DATA.copy(active = true, isPlaying = false,
                        playbackLocation = MediaData.PLAYBACK_CAST_REMOTE, resumption = false),
                5000L)

        val active = Triple("active",
            DATA.copy(active = true, isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true),
            250L)

        val resume1 = Triple("resume 1",
            DATA.copy(active = false, isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true),
            500L)

        val resume2 = Triple("resume 2",
            DATA.copy(active = false, isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true),
            1000L)

        val activeMoreRecent = Triple("active more recent",
            DATA.copy(active = false, isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true, lastActive = 2L),
            1000L)

        val activeLessRecent = Triple("active less recent",
            DATA.copy(active = false, isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true, lastActive = 1L),
            1000L)
        // Expected ordering for media players:
        // Actively playing local sessions
        // Actively playing cast sessions
        // Paused local and cast sessions, by last active
        // RCNs
        // Resume controls, by last active

        val expected = listOf(playingLocal, playingCast, pausedCast, pausedLocal, playingRcn,
                pausedRcn, active, resume2, resume1)

        expected.forEach {
            clock.setCurrentTimeMillis(it.third)
            MediaPlayerData.addMediaPlayer(it.first, it.second.copy(notificationKey = it.first),
                panel, clock, isSsReactivated = false)
        }

        for ((index, key) in MediaPlayerData.playerKeys().withIndex()) {
            assertEquals(expected.get(index).first, key.data.notificationKey)
        }
    }

    @Test
    fun testOrderWithSmartspace_prioritized() {
        testPlayerOrdering()

        // If smartspace is prioritized
        MediaPlayerData.addMediaRecommendation(SMARTSPACE_KEY, EMPTY_SMARTSPACE_MEDIA_DATA, panel,
            true, clock)

        // Then it should be shown immediately after any actively playing controls
        assertTrue(MediaPlayerData.playerKeys().elementAt(2).isSsMediaRec)
    }

    @Test
    fun testOrderWithSmartspace_notPrioritized() {
        testPlayerOrdering()

        // If smartspace is not prioritized
        MediaPlayerData.addMediaRecommendation(SMARTSPACE_KEY, EMPTY_SMARTSPACE_MEDIA_DATA, panel,
            false, clock)

        // Then it should be shown at the end of the carousel's active entries
        val idx = MediaPlayerData.playerKeys().count { it.data.active } - 1
        assertTrue(MediaPlayerData.playerKeys().elementAt(idx).isSsMediaRec)
    }

    @Test
    fun testSwipeDismiss_logged() {
        mediaCarouselController.mediaCarouselScrollHandler.dismissCallback.invoke()

        verify(logger).logSwipeDismiss()
    }

    @Test
    fun testSettingsButton_logged() {
        mediaCarouselController.settingsButton.callOnClick()

        verify(logger).logCarouselSettings()
    }

    @Test
    fun testLocationChangeQs_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_QS,
            mediaHostState,
            animate = false)
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_QS)
    }

    @Test
    fun testLocationChangeQqs_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_QQS,
            mediaHostState,
            animate = false)
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_QQS)
    }

    @Test
    fun testLocationChangeLockscreen_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_LOCKSCREEN,
            mediaHostState,
            animate = false)
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_LOCKSCREEN)
    }

    @Test
    fun testLocationChangeDream_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_DREAM_OVERLAY,
            mediaHostState,
            animate = false)
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_DREAM_OVERLAY)
    }

    @Test
    fun testRecommendationRemoved_logged() {
        val packageName = "smartspace package"
        val instanceId = InstanceId.fakeInstanceId(123)

        val smartspaceData = EMPTY_SMARTSPACE_MEDIA_DATA.copy(
            packageName = packageName,
            instanceId = instanceId
        )
        MediaPlayerData.addMediaRecommendation(SMARTSPACE_KEY, smartspaceData, panel, true, clock)
        mediaCarouselController.removePlayer(SMARTSPACE_KEY)

        verify(logger).logRecommendationRemoved(eq(packageName), eq(instanceId!!))
    }

    fun testMediaLoaded_ScrollToActivePlayer() {
        listener.value.onMediaDataLoaded("playing local",
                null,
                DATA.copy(active = true, isPlaying = true,
                        playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false)
        )
        listener.value.onMediaDataLoaded("paused local",
                null,
                DATA.copy(active = true, isPlaying = false,
                        playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false))
        // adding a media recommendation card.
        MediaPlayerData.addMediaRecommendation(SMARTSPACE_KEY, EMPTY_SMARTSPACE_MEDIA_DATA, panel,
                false, clock)
        mediaCarouselController.shouldScrollToActivePlayer = true
        // switching between media players.
        listener.value.onMediaDataLoaded("playing local",
        "playing local",
                DATA.copy(active = true, isPlaying = false,
                        playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true)
        )
        listener.value.onMediaDataLoaded("paused local",
                "paused local",
                DATA.copy(active = true, isPlaying = true,
                        playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false))

        assertEquals(
                MediaPlayerData.getMediaPlayerIndex("paused local"),
                mediaCarouselController.mediaCarouselScrollHandler.visibleMediaIndex
        )
    }

    @Test
    fun testMediaLoadedFromRecommendationCard_ScrollToActivePlayer() {
        MediaPlayerData.addMediaRecommendation(SMARTSPACE_KEY, EMPTY_SMARTSPACE_MEDIA_DATA, panel,
                false, clock)
        listener.value.onMediaDataLoaded("playing local",
                null,
                DATA.copy(active = true, isPlaying = true,
                        playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false)
        )

        var playerIndex = MediaPlayerData.getMediaPlayerIndex("playing local")
        assertEquals(
                playerIndex,
                mediaCarouselController.mediaCarouselScrollHandler.visibleMediaIndex
        )
        assertEquals(playerIndex, 0)

        // Replaying the same media player one more time.
        // And check that the card stays in its position.
        listener.value.onMediaDataLoaded("playing local",
                null,
                DATA.copy(active = true, isPlaying = true,
                        playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = false)
        )
        playerIndex = MediaPlayerData.getMediaPlayerIndex("playing local")
        assertEquals(playerIndex, 0)
    }

    @Test
    fun testRecommendationRemovedWhileNotVisible_updateHostVisibility() {
        var result = false
        mediaCarouselController.updateHostVisibility = { result = true }

        whenever(visualStabilityProvider.isReorderingAllowed).thenReturn(true)
        listener.value.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY, false)

        assertEquals(true, result)
    }

    @Test
    fun testRecommendationRemovedWhileVisible_thenReorders_updateHostVisibility() {
        var result = false
        mediaCarouselController.updateHostVisibility = { result = true }

        whenever(visualStabilityProvider.isReorderingAllowed).thenReturn(false)
        listener.value.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY, false)
        assertEquals(false, result)

        visualStabilityCallback.value.onReorderingAllowed()
        assertEquals(true, result)
    }

    @Test
    fun testGetCurrentVisibleMediaContentIntent() {
        val clickIntent1 = mock(PendingIntent::class.java)
        val player1 = Triple("player1",
                DATA.copy(clickIntent = clickIntent1),
                1000L)
        clock.setCurrentTimeMillis(player1.third)
        MediaPlayerData.addMediaPlayer(player1.first,
                player1.second.copy(notificationKey = player1.first),
                panel, clock, isSsReactivated = false)

        assertEquals(mediaCarouselController.getCurrentVisibleMediaContentIntent(), clickIntent1)

        val clickIntent2 = mock(PendingIntent::class.java)
        val player2 = Triple("player2",
                DATA.copy(clickIntent = clickIntent2),
                2000L)
        clock.setCurrentTimeMillis(player2.third)
        MediaPlayerData.addMediaPlayer(player2.first,
                player2.second.copy(notificationKey = player2.first),
                panel, clock, isSsReactivated = false)

        // mediaCarouselScrollHandler.visibleMediaIndex is unchanged (= 0), and the new player is
        // added to the front because it was active more recently.
        assertEquals(mediaCarouselController.getCurrentVisibleMediaContentIntent(), clickIntent2)

        val clickIntent3 = mock(PendingIntent::class.java)
        val player3 = Triple("player3",
                DATA.copy(clickIntent = clickIntent3),
                500L)
        clock.setCurrentTimeMillis(player3.third)
        MediaPlayerData.addMediaPlayer(player3.first,
                player3.second.copy(notificationKey = player3.first),
                panel, clock, isSsReactivated = false)

        // mediaCarouselScrollHandler.visibleMediaIndex is unchanged (= 0), and the new player is
        // added to the end because it was active less recently.
        assertEquals(mediaCarouselController.getCurrentVisibleMediaContentIntent(), clickIntent2)
    }
}
