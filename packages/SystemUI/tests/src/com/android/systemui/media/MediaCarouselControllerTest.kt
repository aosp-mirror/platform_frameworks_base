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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.FakeSystemClock
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import javax.inject.Provider

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
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock @Main private lateinit var executor: DelayableExecutor
    @Mock lateinit var mediaDataManager: MediaDataManager
    @Mock lateinit var configurationController: ConfigurationController
    @Mock lateinit var falsingCollector: FalsingCollector
    @Mock lateinit var falsingManager: FalsingManager
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var logger: MediaUiEventLogger

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
            logger
        )

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

        val resume1 = Triple("resume 1",
            DATA.copy(active = false, isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true),
            500L)

        val resume2 = Triple("resume 2",
            DATA.copy(active = false, isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL, resumption = true),
            1000L)

        // Expected ordering for media players:
        // Actively playing local sessions
        // Actively playing cast sessions
        // Paused local and cast sessions, by last active
        // RCNs
        // Resume controls, by last active

        val expected = listOf(playingLocal, playingCast, pausedCast, pausedLocal, playingRcn,
                pausedRcn, resume2, resume1)

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

        // Then it should be shown at the end of the carousel
        val size = MediaPlayerData.playerKeys().size
        assertTrue(MediaPlayerData.playerKeys().elementAt(size - 1).isSsMediaRec)
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
}