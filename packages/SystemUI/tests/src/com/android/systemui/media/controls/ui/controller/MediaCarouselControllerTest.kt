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

package com.android.systemui.media.controls.ui.controller

import android.app.PendingIntent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.LocaleList
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.util.MathUtils.abs
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.domain.pipeline.EMPTY_SMARTSPACE_MEDIA_DATA
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.MediaScrollView
import com.android.systemui.media.controls.ui.viewmodel.mediaCarouselViewModel
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.res.R
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.FakeSystemClock
import java.util.Locale
import javax.inject.Provider
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.floatThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq

private val DATA = MediaTestUtils.emptyMediaData

private val SMARTSPACE_KEY = "smartspace"
private const val PAUSED_LOCAL = "paused local"
private const val PLAYING_LOCAL = "playing local"

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class MediaCarouselControllerTest : SysuiTestCase() {
    val kosmos = testKosmos()

    @Mock lateinit var mediaControlPanelFactory: Provider<MediaControlPanel>
    @Mock lateinit var mediaViewControllerFactory: Provider<MediaViewController>
    @Mock lateinit var panel: MediaControlPanel
    @Mock lateinit var visualStabilityProvider: VisualStabilityProvider
    @Mock lateinit var mediaHostStatesManager: MediaHostStatesManager
    @Mock lateinit var mediaHostState: MediaHostState
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock @Main private lateinit var executor: DelayableExecutor
    @Mock lateinit var mediaDataManager: MediaDataManager
    @Mock lateinit var configurationController: ConfigurationController
    @Mock lateinit var falsingManager: FalsingManager
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var logger: MediaUiEventLogger
    @Mock lateinit var debugLogger: MediaCarouselControllerLogger
    @Mock lateinit var mediaViewController: MediaViewController
    @Mock lateinit var mediaCarousel: MediaScrollView
    @Mock lateinit var pageIndicator: PageIndicator
    @Mock lateinit var mediaFlags: MediaFlags
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock lateinit var globalSettings: GlobalSettings
    private lateinit var secureSettings: SecureSettings
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository
    @Captor lateinit var listener: ArgumentCaptor<MediaDataManager.Listener>
    @Captor
    lateinit var configListener: ArgumentCaptor<ConfigurationController.ConfigurationListener>
    @Captor lateinit var visualStabilityCallback: ArgumentCaptor<OnReorderingAllowedListener>
    @Captor lateinit var keyguardCallback: ArgumentCaptor<KeyguardUpdateMonitorCallback>
    @Captor lateinit var hostStateCallback: ArgumentCaptor<MediaHostStatesManager.Callback>
    @Captor lateinit var settingsObserverCaptor: ArgumentCaptor<ContentObserver>

    private val clock = FakeSystemClock()
    private lateinit var bgExecutor: FakeExecutor
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var mediaCarouselController: MediaCarouselController

    private var originalResumeSetting =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.MEDIA_CONTROLS_RESUME, 1)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        secureSettings = FakeSettings()
        context.resources.configuration.setLocales(LocaleList(Locale.US, Locale.UK))
        bgExecutor = FakeExecutor(clock)
        testDispatcher = UnconfinedTestDispatcher()
        mediaCarouselController =
            MediaCarouselController(
                applicationScope = kosmos.applicationCoroutineScope,
                context = context,
                mediaControlPanelFactory = mediaControlPanelFactory,
                visualStabilityProvider = visualStabilityProvider,
                mediaHostStatesManager = mediaHostStatesManager,
                activityStarter = activityStarter,
                systemClock = clock,
                mainDispatcher = kosmos.testDispatcher,
                executor = executor,
                bgExecutor = bgExecutor,
                backgroundDispatcher = testDispatcher,
                mediaManager = mediaDataManager,
                configurationController = configurationController,
                falsingManager = falsingManager,
                dumpManager = dumpManager,
                logger = logger,
                debugLogger = debugLogger,
                mediaFlags = mediaFlags,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor,
                globalSettings = globalSettings,
                secureSettings = secureSettings,
                mediaCarouselViewModel = kosmos.mediaCarouselViewModel,
                mediaViewControllerFactory = mediaViewControllerFactory,
                sceneInteractor = kosmos.sceneInteractor,
            )
        verify(configurationController).addCallback(capture(configListener))
        verify(mediaDataManager).addListener(capture(listener))
        verify(visualStabilityProvider)
            .addPersistentReorderingAllowedListener(capture(visualStabilityCallback))
        verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCallback))
        verify(mediaHostStatesManager).addCallback(capture(hostStateCallback))
        whenever(mediaControlPanelFactory.get()).thenReturn(panel)
        whenever(panel.mediaViewController).thenReturn(mediaViewController)
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(false)
        MediaPlayerData.clear()
        verify(globalSettings)
            .registerContentObserver(
                eq(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)),
                capture(settingsObserverCaptor)
            )
    }

    @After
    fun tearDown() {
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RESUME,
            originalResumeSetting
        )
    }

    @Test
    fun testPlayerOrdering() {
        // Test values: key, data, last active time
        val playingLocal =
            Triple(
                PLAYING_LOCAL,
                DATA.copy(
                    active = true,
                    isPlaying = true,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = false
                ),
                4500L
            )

        val playingCast =
            Triple(
                "playing cast",
                DATA.copy(
                    active = true,
                    isPlaying = true,
                    playbackLocation = MediaData.PLAYBACK_CAST_LOCAL,
                    resumption = false
                ),
                5000L
            )

        val pausedLocal =
            Triple(
                PAUSED_LOCAL,
                DATA.copy(
                    active = true,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = false
                ),
                1000L
            )

        val pausedCast =
            Triple(
                "paused cast",
                DATA.copy(
                    active = true,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_CAST_LOCAL,
                    resumption = false
                ),
                2000L
            )

        val playingRcn =
            Triple(
                "playing RCN",
                DATA.copy(
                    active = true,
                    isPlaying = true,
                    playbackLocation = MediaData.PLAYBACK_CAST_REMOTE,
                    resumption = false
                ),
                5000L
            )

        val pausedRcn =
            Triple(
                "paused RCN",
                DATA.copy(
                    active = true,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_CAST_REMOTE,
                    resumption = false
                ),
                5000L
            )

        val active =
            Triple(
                "active",
                DATA.copy(
                    active = true,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = true
                ),
                250L
            )

        val resume1 =
            Triple(
                "resume 1",
                DATA.copy(
                    active = false,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = true
                ),
                500L
            )

        val resume2 =
            Triple(
                "resume 2",
                DATA.copy(
                    active = false,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = true
                ),
                1000L
            )

        val activeMoreRecent =
            Triple(
                "active more recent",
                DATA.copy(
                    active = false,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = true,
                    lastActive = 2L
                ),
                1000L
            )

        val activeLessRecent =
            Triple(
                "active less recent",
                DATA.copy(
                    active = false,
                    isPlaying = false,
                    playbackLocation = MediaData.PLAYBACK_LOCAL,
                    resumption = true,
                    lastActive = 1L
                ),
                1000L
            )
        // Expected ordering for media players:
        // Actively playing local sessions
        // Actively playing cast sessions
        // Paused local and cast sessions, by last active
        // RCNs
        // Resume controls, by last active

        val expected =
            listOf(
                playingLocal,
                playingCast,
                pausedCast,
                pausedLocal,
                playingRcn,
                pausedRcn,
                active,
                resume2,
                resume1
            )

        expected.forEach {
            clock.setCurrentTimeMillis(it.third)
            MediaPlayerData.addMediaPlayer(
                it.first,
                it.second.copy(notificationKey = it.first),
                panel,
                clock,
                isSsReactivated = false
            )
        }

        for ((index, key) in MediaPlayerData.playerKeys().withIndex()) {
            assertEquals(expected.get(index).first, key.data.notificationKey)
        }

        for ((index, key) in MediaPlayerData.visiblePlayerKeys().withIndex()) {
            assertEquals(expected.get(index).first, key.data.notificationKey)
        }
    }

    @Test
    fun testOrderWithSmartspace_prioritized() {
        testPlayerOrdering()

        // If smartspace is prioritized
        MediaPlayerData.addMediaRecommendation(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(isActive = true),
            panel,
            true,
            clock
        )

        // Then it should be shown immediately after any actively playing controls
        assertTrue(MediaPlayerData.playerKeys().elementAt(2).isSsMediaRec)
    }

    @Test
    fun testOrderWithSmartspace_prioritized_updatingVisibleMediaPlayers() {
        testPlayerOrdering()

        // If smartspace is prioritized
        listener.value.onSmartspaceMediaDataLoaded(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(isActive = true),
            true
        )

        // Then it should be shown immediately after any actively playing controls
        assertTrue(MediaPlayerData.playerKeys().elementAt(2).isSsMediaRec)
        assertTrue(MediaPlayerData.visiblePlayerKeys().elementAt(2).isSsMediaRec)
    }

    @Test
    fun testOrderWithSmartspace_notPrioritized() {
        testPlayerOrdering()

        // If smartspace is not prioritized
        MediaPlayerData.addMediaRecommendation(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(isActive = true),
            panel,
            false,
            clock
        )

        // Then it should be shown at the end of the carousel's active entries
        val idx = MediaPlayerData.playerKeys().count { it.data.active } - 1
        assertTrue(MediaPlayerData.playerKeys().elementAt(idx).isSsMediaRec)
    }

    @Test
    fun testPlayingExistingMediaPlayerFromCarousel_visibleMediaPlayersNotUpdated() {
        testPlayerOrdering()
        // playing paused player
        listener.value.onMediaDataLoaded(
            PAUSED_LOCAL,
            PAUSED_LOCAL,
            DATA.copy(
                active = true,
                isPlaying = true,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )
        listener.value.onMediaDataLoaded(
            PLAYING_LOCAL,
            PLAYING_LOCAL,
            DATA.copy(
                active = true,
                isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = true
            )
        )

        assertEquals(
            MediaPlayerData.getMediaPlayerIndex(PAUSED_LOCAL),
            mediaCarouselController.mediaCarouselScrollHandler.visibleMediaIndex
        )
        // paused player order should stays the same in visibleMediaPLayer map.
        // paused player order should be first in mediaPlayer map.
        assertEquals(
            MediaPlayerData.visiblePlayerKeys().elementAt(3),
            MediaPlayerData.playerKeys().elementAt(0)
        )
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
            LOCATION_QS,
            mediaHostState,
            animate = false
        )
        bgExecutor.runAllReady()
        verify(logger).logCarouselPosition(LOCATION_QS)
    }

    @Test
    fun testLocationChangeQqs_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_QQS,
            mediaHostState,
            animate = false
        )
        bgExecutor.runAllReady()
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_QQS)
    }

    @Test
    fun testLocationChangeLockscreen_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_LOCKSCREEN,
            mediaHostState,
            animate = false
        )
        bgExecutor.runAllReady()
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_LOCKSCREEN)
    }

    @Test
    fun testLocationChangeDream_logged() {
        mediaCarouselController.onDesiredLocationChanged(
            MediaHierarchyManager.LOCATION_DREAM_OVERLAY,
            mediaHostState,
            animate = false
        )
        bgExecutor.runAllReady()
        verify(logger).logCarouselPosition(MediaHierarchyManager.LOCATION_DREAM_OVERLAY)
    }

    @Test
    fun testRecommendationRemoved_logged() {
        val packageName = "smartspace package"
        val instanceId = InstanceId.fakeInstanceId(123)

        val smartspaceData =
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(packageName = packageName, instanceId = instanceId)
        MediaPlayerData.addMediaRecommendation(SMARTSPACE_KEY, smartspaceData, panel, true, clock)
        mediaCarouselController.removePlayer(SMARTSPACE_KEY)

        verify(logger).logRecommendationRemoved(eq(packageName), eq(instanceId!!))
    }

    @Test
    fun testMediaLoaded_ScrollToActivePlayer() {
        listener.value.onMediaDataLoaded(
            PLAYING_LOCAL,
            null,
            DATA.copy(
                active = true,
                isPlaying = true,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )
        listener.value.onMediaDataLoaded(
            PAUSED_LOCAL,
            null,
            DATA.copy(
                active = true,
                isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )
        // adding a media recommendation card.
        listener.value.onSmartspaceMediaDataLoaded(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA,
            false
        )
        mediaCarouselController.shouldScrollToKey = true
        // switching between media players.
        listener.value.onMediaDataLoaded(
            PLAYING_LOCAL,
            PLAYING_LOCAL,
            DATA.copy(
                active = true,
                isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = true
            )
        )
        listener.value.onMediaDataLoaded(
            PAUSED_LOCAL,
            PAUSED_LOCAL,
            DATA.copy(
                active = true,
                isPlaying = true,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )

        assertEquals(
            MediaPlayerData.getMediaPlayerIndex(PAUSED_LOCAL),
            mediaCarouselController.mediaCarouselScrollHandler.visibleMediaIndex
        )
    }

    @Test
    fun testMediaLoadedFromRecommendationCard_ScrollToActivePlayer() {
        listener.value.onSmartspaceMediaDataLoaded(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(packageName = "PACKAGE_NAME", isActive = true),
            false
        )
        listener.value.onMediaDataLoaded(
            PLAYING_LOCAL,
            null,
            DATA.copy(
                active = true,
                isPlaying = true,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )

        var playerIndex = MediaPlayerData.getMediaPlayerIndex(PLAYING_LOCAL)
        assertEquals(
            playerIndex,
            mediaCarouselController.mediaCarouselScrollHandler.visibleMediaIndex
        )
        assertEquals(playerIndex, 0)

        // Replaying the same media player one more time.
        // And check that the card stays in its position.
        mediaCarouselController.shouldScrollToKey = true
        listener.value.onMediaDataLoaded(
            PLAYING_LOCAL,
            null,
            DATA.copy(
                active = true,
                isPlaying = true,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false,
                packageName = "PACKAGE_NAME"
            )
        )
        playerIndex = MediaPlayerData.getMediaPlayerIndex(PLAYING_LOCAL)
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
        val player1 = Triple("player1", DATA.copy(clickIntent = clickIntent1), 1000L)
        clock.setCurrentTimeMillis(player1.third)
        MediaPlayerData.addMediaPlayer(
            player1.first,
            player1.second.copy(notificationKey = player1.first),
            panel,
            clock,
            isSsReactivated = false
        )

        assertEquals(mediaCarouselController.getCurrentVisibleMediaContentIntent(), clickIntent1)

        val clickIntent2 = mock(PendingIntent::class.java)
        val player2 = Triple("player2", DATA.copy(clickIntent = clickIntent2), 2000L)
        clock.setCurrentTimeMillis(player2.third)
        MediaPlayerData.addMediaPlayer(
            player2.first,
            player2.second.copy(notificationKey = player2.first),
            panel,
            clock,
            isSsReactivated = false
        )

        // mediaCarouselScrollHandler.visibleMediaIndex is unchanged (= 0), and the new player is
        // added to the front because it was active more recently.
        assertEquals(mediaCarouselController.getCurrentVisibleMediaContentIntent(), clickIntent2)

        val clickIntent3 = mock(PendingIntent::class.java)
        val player3 = Triple("player3", DATA.copy(clickIntent = clickIntent3), 500L)
        clock.setCurrentTimeMillis(player3.third)
        MediaPlayerData.addMediaPlayer(
            player3.first,
            player3.second.copy(notificationKey = player3.first),
            panel,
            clock,
            isSsReactivated = false
        )

        // mediaCarouselScrollHandler.visibleMediaIndex is unchanged (= 0), and the new player is
        // added to the end because it was active less recently.
        assertEquals(mediaCarouselController.getCurrentVisibleMediaContentIntent(), clickIntent2)
    }

    @Test
    fun testSetCurrentState_UpdatePageIndicatorAlphaWhenSquish() {
        val delta = 0.0001F
        mediaCarouselController.mediaCarousel = mediaCarousel
        mediaCarouselController.pageIndicator = pageIndicator
        whenever(mediaCarousel.measuredHeight).thenReturn(100)
        whenever(pageIndicator.translationY).thenReturn(80F)
        whenever(pageIndicator.height).thenReturn(10)
        whenever(mediaHostStatesManager.mediaHostStates)
            .thenReturn(mutableMapOf(LOCATION_QS to mediaHostState))
        whenever(mediaHostState.visible).thenReturn(true)
        mediaCarouselController.currentEndLocation = LOCATION_QS
        whenever(mediaHostState.squishFraction).thenReturn(0.938F)
        mediaCarouselController.updatePageIndicatorAlpha()
        verify(pageIndicator).alpha = floatThat { abs(it - 0.5F) < delta }

        whenever(mediaHostState.squishFraction).thenReturn(1.0F)
        mediaCarouselController.updatePageIndicatorAlpha()
        verify(pageIndicator).alpha = floatThat { abs(it - 1.0F) < delta }
    }

    @Test
    fun testOnConfigChanged_playersAreAddedBack() {
        testConfigurationChange { configListener.value.onConfigChanged(Configuration()) }
    }

    @Test
    fun testOnUiModeChanged_playersAreAddedBack() {
        testConfigurationChange(configListener.value::onUiModeChanged)

        verify(pageIndicator).tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        verify(pageIndicator, times(2)).setNumPages(any())
    }

    @Test
    fun testOnDensityOrFontScaleChanged_playersAreAddedBack() {
        testConfigurationChange(configListener.value::onDensityOrFontScaleChanged)

        verify(pageIndicator).tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        // when recreateMedia is set to true, page indicator is updated on removal and addition.
        verify(pageIndicator, times(4)).setNumPages(any())
    }

    @Test
    fun testOnThemeChanged_playersAreAddedBack() {
        testConfigurationChange(configListener.value::onThemeChanged)

        verify(pageIndicator).tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        verify(pageIndicator, times(2)).setNumPages(any())
    }

    @Test
    fun testOnLocaleListChanged_playersAreAddedBack() {
        context.resources.configuration.setLocales(LocaleList(Locale.US, Locale.UK, Locale.CANADA))
        testConfigurationChange(configListener.value::onLocaleListChanged)

        verify(pageIndicator, never()).tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))

        context.resources.configuration.setLocales(LocaleList(Locale.UK, Locale.US, Locale.CANADA))
        testConfigurationChange(configListener.value::onLocaleListChanged)

        verify(pageIndicator).tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        // When recreateMedia is set to true, page indicator is updated on removal and addition.
        verify(pageIndicator, times(4)).setNumPages(any())
    }

    @Test
    fun testRecommendation_persistentEnabled_newSmartspaceLoaded_updatesSort() {
        testRecommendation_persistentEnabled_inactiveSmartspaceDataLoaded_isAdded()

        // When an update to existing smartspace data is loaded
        listener.value.onSmartspaceMediaDataLoaded(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(isActive = true),
            true
        )

        // Then the carousel is updated
        assertTrue(MediaPlayerData.playerKeys().elementAt(0).data.active)
        assertTrue(MediaPlayerData.visiblePlayerKeys().elementAt(0).data.active)
    }

    @Test
    fun testRecommendation_persistentEnabled_inactiveSmartspaceDataLoaded_isAdded() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)

        // When inactive smartspace data is loaded
        listener.value.onSmartspaceMediaDataLoaded(
            SMARTSPACE_KEY,
            EMPTY_SMARTSPACE_MEDIA_DATA,
            false
        )

        // Then it is added to the carousel with correct state
        assertTrue(MediaPlayerData.playerKeys().elementAt(0).isSsMediaRec)
        assertFalse(MediaPlayerData.playerKeys().elementAt(0).data.active)

        assertTrue(MediaPlayerData.visiblePlayerKeys().elementAt(0).isSsMediaRec)
        assertFalse(MediaPlayerData.visiblePlayerKeys().elementAt(0).data.active)
    }

    @Test
    fun testOnLockDownMode_hideMediaCarousel() {
        whenever(keyguardUpdateMonitor.isUserInLockdown(context.userId)).thenReturn(true)
        mediaCarouselController.mediaCarousel = mediaCarousel

        keyguardCallback.value.onStrongAuthStateChanged(context.userId)

        verify(mediaCarousel).visibility = View.GONE
    }

    @Test
    fun testLockDownModeOff_showMediaCarousel() {
        whenever(keyguardUpdateMonitor.isUserInLockdown(context.userId)).thenReturn(false)
        whenever(keyguardUpdateMonitor.isUserUnlocked(context.userId)).thenReturn(true)
        mediaCarouselController.mediaCarousel = mediaCarousel

        keyguardCallback.value.onStrongAuthStateChanged(context.userId)

        verify(mediaCarousel).visibility = View.VISIBLE
    }

    @DisableSceneContainer
    @ExperimentalCoroutinesApi
    @Test
    fun testKeyguardGone_showMediaCarousel() =
        kosmos.testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)
            var updatedVisibility = false
            mediaCarouselController.updateHostVisibility = { updatedVisibility = true }
            mediaCarouselController.mediaCarousel = mediaCarousel

            val job = mediaCarouselController.listenForAnyStateToGoneKeyguardTransition(this)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                this
            )

            verify(mediaCarousel).visibility = View.VISIBLE
            assertEquals(true, updatedVisibility)
            assertEquals(false, mediaCarouselController.isLockedAndHidden())

            job.cancel()
        }

    @EnableSceneContainer
    @ExperimentalCoroutinesApi
    @Test
    fun testKeyguardGone_showMediaCarousel_scene_container() =
        kosmos.testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)
            var updatedVisibility = false
            mediaCarouselController.updateHostVisibility = { updatedVisibility = true }
            mediaCarouselController.mediaCarousel = mediaCarousel

            val job = mediaCarouselController.listenForAnyStateToGoneKeyguardTransition(this)
            kosmos.setSceneTransition(Idle(Scenes.Gone))

            verify(mediaCarousel).visibility = View.VISIBLE
            assertEquals(true, updatedVisibility)

            job.cancel()
        }

    @ExperimentalCoroutinesApi
    @Test
    fun keyguardShowing_notAllowedOnLockscreen_updateVisibility() {
        kosmos.testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)
            var updatedVisibility = false
            mediaCarouselController.updateHostVisibility = { updatedVisibility = true }
            mediaCarouselController.mediaCarousel = mediaCarousel

            val settingsJob =
                mediaCarouselController.listenForLockscreenSettingChanges(
                    kosmos.applicationCoroutineScope
                )
            secureSettings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, false)

            val keyguardJob = mediaCarouselController.listenForAnyStateToLockscreenTransition(this)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                this
            )

            assertEquals(true, updatedVisibility)
            assertEquals(true, mediaCarouselController.isLockedAndHidden())

            settingsJob.cancel()
            keyguardJob.cancel()
        }
    }

    @ExperimentalCoroutinesApi
    @Test
    fun keyguardShowing_allowedOnLockscreen_updateVisibility() {
        kosmos.testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)
            var updatedVisibility = false
            mediaCarouselController.updateHostVisibility = { updatedVisibility = true }
            mediaCarouselController.mediaCarousel = mediaCarousel

            val settingsJob =
                mediaCarouselController.listenForLockscreenSettingChanges(
                    kosmos.applicationCoroutineScope
                )
            secureSettings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, true)

            val keyguardJob = mediaCarouselController.listenForAnyStateToLockscreenTransition(this)
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                this
            )

            assertEquals(true, updatedVisibility)
            assertEquals(false, mediaCarouselController.isLockedAndHidden())

            settingsJob.cancel()
            keyguardJob.cancel()
        }
    }

    @Test
    fun testInvisibleToUserAndExpanded_playersNotListening() {
        // Add players to carousel.
        testPlayerOrdering()

        // Make the carousel visible to user in expanded layout.
        mediaCarouselController.currentlyExpanded = true
        mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = true

        // panel is the player for each MediaPlayerData.
        // Verify that seekbar listening attribute in media control panel is set to true.
        verify(panel, times(MediaPlayerData.players().size)).listening = true

        // Make the carousel invisible to user.
        mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = false

        // panel is the player for each MediaPlayerData.
        // Verify that seekbar listening attribute in media control panel is set to false.
        verify(panel, times(MediaPlayerData.players().size)).listening = false
    }

    @Test
    fun testVisibleToUserAndExpanded_playersListening() {
        // Add players to carousel.
        testPlayerOrdering()

        // Make the carousel visible to user in expanded layout.
        mediaCarouselController.currentlyExpanded = true
        mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = true

        // panel is the player for each MediaPlayerData.
        // Verify that seekbar listening attribute in media control panel is set to true.
        verify(panel, times(MediaPlayerData.players().size)).listening = true
    }

    @Test
    fun testUMOCollapsed_playersNotListening() {
        // Add players to carousel.
        testPlayerOrdering()

        // Make the carousel in collapsed layout.
        mediaCarouselController.currentlyExpanded = false

        // panel is the player for each MediaPlayerData.
        // Verify that seekbar listening attribute in media control panel is set to false.
        verify(panel, times(MediaPlayerData.players().size)).listening = false

        // Make the carousel visible to user.
        reset(panel)
        mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = true

        // Verify that seekbar listening attribute in media control panel is set to false.
        verify(panel, times(MediaPlayerData.players().size)).listening = false
    }

    @Test
    fun testOnHostStateChanged_updateVisibility() {
        var stateUpdated = false
        mediaCarouselController.updateUserVisibility = { stateUpdated = true }

        // When the host state updates
        hostStateCallback.value!!.onHostStateChanged(LOCATION_QS, mediaHostState)

        // Then the carousel visibility is updated
        assertTrue(stateUpdated)
    }

    @Test
    fun testAnimationScaleChanged_mediaControlPanelsNotified() {
        MediaPlayerData.addMediaPlayer("key", DATA, panel, clock, isSsReactivated = false)

        globalSettings.putFloat(Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        settingsObserverCaptor.value!!.onChange(false)
        verify(panel).updateAnimatorDurationScale()
    }

    @Test
    fun swipeToDismiss_pausedAndResumeOff_userInitiated() {
        // When resumption is disabled, paused media should be dismissed after being swiped away
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.MEDIA_CONTROLS_RESUME, 0)

        val pausedMedia = DATA.copy(isPlaying = false)
        listener.value.onMediaDataLoaded(PAUSED_LOCAL, PAUSED_LOCAL, pausedMedia)
        mediaCarouselController.onSwipeToDismiss()

        // When it can be removed immediately on update
        whenever(visualStabilityProvider.isReorderingAllowed).thenReturn(true)
        val inactiveMedia = pausedMedia.copy(active = false)
        listener.value.onMediaDataLoaded(PAUSED_LOCAL, PAUSED_LOCAL, inactiveMedia)

        // This is processed as a user-initiated dismissal
        verify(debugLogger).logMediaRemoved(eq(PAUSED_LOCAL), eq(true))
        verify(mediaDataManager).dismissMediaData(eq(PAUSED_LOCAL), anyLong(), eq(true))
    }

    @Test
    fun swipeToDismiss_pausedAndResumeOff_delayed_userInitiated() {
        // When resumption is disabled, paused media should be dismissed after being swiped away
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.MEDIA_CONTROLS_RESUME, 0)
        mediaCarouselController.updateHostVisibility = {}

        val pausedMedia = DATA.copy(isPlaying = false)
        listener.value.onMediaDataLoaded(PAUSED_LOCAL, PAUSED_LOCAL, pausedMedia)
        mediaCarouselController.onSwipeToDismiss()

        // When it can't be removed immediately on update
        whenever(visualStabilityProvider.isReorderingAllowed).thenReturn(false)
        val inactiveMedia = pausedMedia.copy(active = false)
        listener.value.onMediaDataLoaded(PAUSED_LOCAL, PAUSED_LOCAL, inactiveMedia)
        visualStabilityCallback.value.onReorderingAllowed()

        // This is processed as a user-initiated dismissal
        verify(mediaDataManager).dismissMediaData(eq(PAUSED_LOCAL), anyLong(), eq(true))
    }

    /**
     * Helper method when a configuration change occurs.
     *
     * @param function called when a certain configuration change occurs.
     */
    private fun testConfigurationChange(function: () -> Unit) {
        mediaCarouselController.pageIndicator = pageIndicator
        listener.value.onMediaDataLoaded(
            PLAYING_LOCAL,
            null,
            DATA.copy(
                active = true,
                isPlaying = true,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )
        listener.value.onMediaDataLoaded(
            PAUSED_LOCAL,
            null,
            DATA.copy(
                active = true,
                isPlaying = false,
                playbackLocation = MediaData.PLAYBACK_LOCAL,
                resumption = false
            )
        )

        val playersSize = MediaPlayerData.players().size
        reset(pageIndicator)
        function()

        assertEquals(playersSize, MediaPlayerData.players().size)
        assertEquals(
            MediaPlayerData.getMediaPlayerIndex(PLAYING_LOCAL),
            mediaCarouselController.mediaCarouselScrollHandler.visibleMediaIndex
        )
    }
}
