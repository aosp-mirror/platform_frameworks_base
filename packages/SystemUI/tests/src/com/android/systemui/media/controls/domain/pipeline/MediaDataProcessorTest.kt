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

package com.android.systemui.media.controls.domain.pipeline

import android.app.IUriGrantsManager
import android.app.Notification
import android.app.Notification.FLAG_NO_CLEAR
import android.app.Notification.MediaStyle
import android.app.PendingIntent
import android.app.UriGrantsManager
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceTarget
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.testing.TestableLooper.RunWithLooper
import androidx.media.utils.MediaConstants
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.logging.InstanceId
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags.MEDIA_REMOTE_RESUME
import com.android.systemui.flags.Flags.MEDIA_RESUME_PROGRESS
import com.android.systemui.flags.Flags.MEDIA_RETAIN_RECOMMENDATIONS
import com.android.systemui.flags.Flags.MEDIA_RETAIN_SESSIONS
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.data.repository.mediaDataRepository
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.controls.domain.resume.MediaResumeListener
import com.android.systemui.media.controls.domain.resume.ResumeMediaBrowser
import com.android.systemui.media.controls.shared.mediaLogger
import com.android.systemui.media.controls.shared.mockMediaLogger
import com.android.systemui.media.controls.shared.model.EXTRA_KEY_TRIGGER_SOURCE
import com.android.systemui.media.controls.shared.model.EXTRA_VALUE_TRIGGER_PERIODIC
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaDataProvider
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.mediaFlags
import com.android.systemui.plugins.activityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.fakeSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoSession
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

private const val KEY = "KEY"
private const val KEY_2 = "KEY_2"
private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
private const val SMARTSPACE_CREATION_TIME = 1234L
private const val SMARTSPACE_EXPIRY_TIME = 5678L
private const val PACKAGE_NAME = "com.example.app"
private const val SYSTEM_PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "SystemUI"
private const val SESSION_ARTIST = "artist"
private const val SESSION_TITLE = "title"
private const val SESSION_BLANK_TITLE = " "
private const val SESSION_EMPTY_TITLE = ""
private const val USER_ID = 0
private val DISMISS_INTENT = Intent().apply { action = "dismiss" }

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(ParameterizedAndroidJunit4::class)
@EnableSceneContainer
class MediaDataProcessorTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos().apply { mediaLogger = mockMediaLogger }
    private val testDispatcher = kosmos.testDispatcher
    private val testScope = kosmos.testScope
    private val settings = kosmos.fakeSettings

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @Mock lateinit var controller: MediaController
    @Mock lateinit var transportControls: MediaController.TransportControls
    @Mock lateinit var playbackInfo: MediaController.PlaybackInfo
    lateinit var session: MediaSession
    private lateinit var metadataBuilder: MediaMetadata.Builder
    lateinit var backgroundExecutor: FakeExecutor
    private lateinit var foregroundExecutor: FakeExecutor
    lateinit var uiExecutor: FakeExecutor
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock lateinit var mediaTimeoutListener: MediaTimeoutListener
    @Mock lateinit var mediaResumeListener: MediaResumeListener
    @Mock lateinit var mediaSessionBasedFilter: MediaSessionBasedFilter
    @Mock lateinit var mediaDeviceManager: MediaDeviceManager
    @Mock lateinit var mediaDataCombineLatest: MediaDataCombineLatest
    @Mock lateinit var listener: MediaDataManager.Listener
    @Mock lateinit var pendingIntent: PendingIntent
    @Mock lateinit var smartspaceManager: SmartspaceManager
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    private lateinit var smartspaceMediaDataProvider: SmartspaceMediaDataProvider
    @Mock lateinit var mediaSmartspaceTarget: SmartspaceTarget
    @Mock private lateinit var mediaRecommendationItem: SmartspaceAction
    private lateinit var validRecommendationList: List<SmartspaceAction>
    @Mock private lateinit var mediaSmartspaceBaseAction: SmartspaceAction
    @Mock private lateinit var logger: MediaUiEventLogger
    private lateinit var mediaCarouselInteractor: MediaCarouselInteractor
    private lateinit var mediaDataProcessor: MediaDataProcessor
    private lateinit var mediaNotification: StatusBarNotification
    private lateinit var remoteCastNotification: StatusBarNotification
    @Captor lateinit var mediaDataCaptor: ArgumentCaptor<MediaData>
    private val clock = FakeSystemClock()
    @Captor lateinit var stateCallbackCaptor: ArgumentCaptor<(String, PlaybackState) -> Unit>
    @Captor lateinit var sessionCallbackCaptor: ArgumentCaptor<(String) -> Unit>
    @Captor lateinit var smartSpaceConfigBuilderCaptor: ArgumentCaptor<SmartspaceConfig>
    @Mock private lateinit var ugm: IUriGrantsManager
    @Mock private lateinit var imageSource: ImageDecoder.Source

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.progressionOf(
                Flags.FLAG_MEDIA_LOAD_METADATA_VIA_MEDIA_DATA_LOADER
            )
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val fakeFeatureFlags = kosmos.fakeFeatureFlagsClassic
    private val activityStarter = kosmos.activityStarter
    private val mediaControllerFactory = kosmos.fakeMediaControllerFactory
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager
    private val mediaFilterRepository = kosmos.mediaFilterRepository
    private val mediaDataFilter = kosmos.mediaDataFilter

    private val instanceIdSequence = InstanceIdSequenceFake(1 shl 20)

    private val originalSmartspaceSetting =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            1,
        )

    private lateinit var staticMockSession: MockitoSession

    @Before
    fun setup() {
        staticMockSession =
            ExtendedMockito.mockitoSession()
                .mockStatic<UriGrantsManager>(UriGrantsManager::class.java)
                .mockStatic<ImageDecoder>(ImageDecoder::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()
        whenever(UriGrantsManager.getService()).thenReturn(ugm)
        foregroundExecutor = FakeExecutor(clock)
        backgroundExecutor = FakeExecutor(clock)
        uiExecutor = FakeExecutor(clock)
        smartspaceMediaDataProvider = SmartspaceMediaDataProvider()
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            1,
        )
        mediaDataProcessor =
            MediaDataProcessor(
                context = context,
                applicationScope = testScope,
                backgroundDispatcher = testDispatcher,
                backgroundExecutor = backgroundExecutor,
                uiExecutor = uiExecutor,
                foregroundExecutor = foregroundExecutor,
                mainDispatcher = testDispatcher,
                mediaControllerFactory = mediaControllerFactory,
                broadcastDispatcher = broadcastDispatcher,
                dumpManager = dumpManager,
                activityStarter = activityStarter,
                smartspaceMediaDataProvider = smartspaceMediaDataProvider,
                useMediaResumption = true,
                useQsMediaPlayer = true,
                systemClock = clock,
                secureSettings = settings,
                mediaFlags = kosmos.mediaFlags,
                logger = logger,
                smartspaceManager = smartspaceManager,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                mediaDataRepository = kosmos.mediaDataRepository,
                mediaDataLoader = { kosmos.mediaDataLoader },
                mediaLogger = kosmos.mediaLogger,
            )
        mediaDataProcessor.start()
        testScope.runCurrent()
        mediaCarouselInteractor =
            MediaCarouselInteractor(
                applicationScope = testScope.backgroundScope,
                mediaDataProcessor = mediaDataProcessor,
                mediaTimeoutListener = mediaTimeoutListener,
                mediaResumeListener = mediaResumeListener,
                mediaSessionBasedFilter = mediaSessionBasedFilter,
                mediaDeviceManager = mediaDeviceManager,
                mediaDataCombineLatest = mediaDataCombineLatest,
                mediaDataFilter = mediaDataFilter,
                mediaFilterRepository = mediaFilterRepository,
                mediaFlags = kosmos.mediaFlags,
            )
        mediaCarouselInteractor.start()
        verify(mediaTimeoutListener).stateCallback = capture(stateCallbackCaptor)
        verify(mediaTimeoutListener).sessionCallback = capture(sessionCallbackCaptor)
        session = MediaSession(context, "MediaDataProcessorTestSession")
        mediaNotification =
            SbnBuilder().run {
                setUser(UserHandle(USER_ID))
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                }
                build()
            }
        remoteCastNotification =
            SbnBuilder().run {
                setPkg(SYSTEM_PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(
                        MediaStyle().apply {
                            setMediaSession(session.sessionToken)
                            setRemotePlaybackInfo("Remote device", 0, null)
                        }
                    )
                }
                build()
            }
        metadataBuilder =
            MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
                putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
            }
        verify(smartspaceManager).createSmartspaceSession(capture(smartSpaceConfigBuilderCaptor))
        mediaControllerFactory.setControllerForToken(session.sessionToken, controller)
        whenever(controller.transportControls).thenReturn(transportControls)
        whenever(controller.playbackInfo).thenReturn(playbackInfo)
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        whenever(playbackInfo.playbackType)
            .thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)

        // This is an ugly hack for now. The mediaSessionBasedFilter is one of the internal
        // listeners in the internal processing pipeline. It receives events, but ince it is a
        // mock, it doesn't pass those events along the chain to the external listeners. So, just
        // treat mediaSessionBasedFilter as a listener for testing.
        listener = mediaSessionBasedFilter

        val recommendationExtras =
            Bundle().apply {
                putString("package_name", PACKAGE_NAME)
                putParcelable("dismiss_intent", DISMISS_INTENT)
            }
        val icon = Icon.createWithResource(context, android.R.drawable.ic_media_play)
        whenever(mediaSmartspaceBaseAction.extras).thenReturn(recommendationExtras)
        whenever(mediaSmartspaceTarget.baseAction).thenReturn(mediaSmartspaceBaseAction)
        whenever(mediaRecommendationItem.extras).thenReturn(recommendationExtras)
        whenever(mediaRecommendationItem.icon).thenReturn(icon)
        validRecommendationList =
            listOf(mediaRecommendationItem, mediaRecommendationItem, mediaRecommendationItem)
        whenever(mediaSmartspaceTarget.smartspaceTargetId).thenReturn(KEY_MEDIA_SMARTSPACE)
        whenever(mediaSmartspaceTarget.featureType).thenReturn(SmartspaceTarget.FEATURE_MEDIA)
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(validRecommendationList)
        whenever(mediaSmartspaceTarget.creationTimeMillis).thenReturn(SMARTSPACE_CREATION_TIME)
        whenever(mediaSmartspaceTarget.expiryTimeMillis).thenReturn(SMARTSPACE_EXPIRY_TIME)
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, false)
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, false)
        fakeFeatureFlags.set(MEDIA_REMOTE_RESUME, false)
        fakeFeatureFlags.set(MEDIA_RETAIN_RECOMMENDATIONS, false)
        whenever(logger.getNewInstanceId()).thenReturn(instanceIdSequence.newInstanceId())
        whenever(keyguardUpdateMonitor.isUserInLockdown(any())).thenReturn(false)
    }

    @After
    fun tearDown() {
        staticMockSession.finishMocking()
        session.release()
        mediaDataProcessor.destroy()
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            originalSmartspaceSetting,
        )
    }

    @Test
    fun testsetInactive_active_deactivatesMedia() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()

        mediaDataProcessor.setInactive(KEY, timedOut = true)
        assertThat(data.active).isFalse()
        verify(logger).logMediaTimeout(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testsetInactive_resume_dismissesMedia() {
        // WHEN resume controls are present, and time out
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        mediaDataProcessor.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME,
        )

        testScope.runCurrent()
        backgroundExecutor.runAllReady()
        foregroundExecutor.runAllReady()
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )

        mediaDataProcessor.setInactive(PACKAGE_NAME, timedOut = true)
        verify(logger)
            .logMediaTimeout(anyInt(), eq(PACKAGE_NAME), eq(mediaDataCaptor.value.instanceId))

        // THEN it is removed and listeners are informed
        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()
        verify(listener).onMediaDataRemoved(PACKAGE_NAME, false)
    }

    @Test
    fun testLoadsMetadataOnBackground() {
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)
        testScope.assertRunAllReady(foreground = 0, background = 1)
    }

    @Test
    fun testLoadMetadata_withExplicitIndicator() {
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putLong(
                        MediaConstants.METADATA_KEY_IS_EXPLICIT,
                        MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT,
                    )
                    .build()
            )

        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)

        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value!!.isExplicit).isTrue()
    }

    @Test
    fun testOnMetaDataLoaded_withoutExplicitIndicator() {
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)

        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value!!.isExplicit).isFalse()
    }

    @Test
    fun testOnMetaDataLoaded_callsListener() {
        addNotificationAndLoad()
        verify(logger)
            .logActiveMediaAdded(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
                eq(MediaData.PLAYBACK_LOCAL),
            )
    }

    @Test
    fun testOnMetaDataLoaded_conservesActiveFlag() {
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value!!.active).isTrue()
    }

    @Test
    fun testOnNotificationAdded_isRcn_markedRemote() {
        addNotificationAndLoad(remoteCastNotification)

        assertThat(mediaDataCaptor.value!!.playbackLocation)
            .isEqualTo(MediaData.PLAYBACK_CAST_REMOTE)
        verify(logger)
            .logActiveMediaAdded(
                anyInt(),
                eq(SYSTEM_PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
                eq(MediaData.PLAYBACK_CAST_REMOTE),
            )
    }

    @Test
    fun testOnNotificationAdded_hasSubstituteName_isUsed() {
        val subName = "Substitute Name"
        val notif =
            SbnBuilder().run {
                modifyNotification(context).also {
                    it.extras =
                        Bundle().apply {
                            putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, subName)
                        }
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                }
                build()
            }

        mediaDataProcessor.onNotificationAdded(KEY, notif)
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )

        assertThat(mediaDataCaptor.value!!.app).isEqualTo(subName)
    }

    @Test
    fun testLoadMediaDataInBg_invalidTokenNoCrash() {
        val bundle = Bundle()
        // wrong data type
        bundle.putParcelable(Notification.EXTRA_MEDIA_SESSION, Bundle())
        val rcn =
            SbnBuilder().run {
                setPkg(SYSTEM_PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.addExtras(bundle)
                    it.setStyle(
                        MediaStyle().apply { setRemotePlaybackInfo("Remote device", 0, null) }
                    )
                }
                build()
            }

        mediaDataProcessor.loadMediaDataInBg(KEY, rcn, null)
        // no crash even though the data structure is incorrect
    }

    @Test
    fun testLoadMediaDataInBg_invalidMediaRemoteIntentNoCrash() {
        val bundle = Bundle()
        // wrong data type
        bundle.putParcelable(Notification.EXTRA_MEDIA_REMOTE_INTENT, Bundle())
        val rcn =
            SbnBuilder().run {
                setPkg(SYSTEM_PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.addExtras(bundle)
                    it.setStyle(
                        MediaStyle().apply {
                            setMediaSession(session.sessionToken)
                            setRemotePlaybackInfo("Remote device", 0, null)
                        }
                    )
                }
                build()
            }

        mediaDataProcessor.loadMediaDataInBg(KEY, rcn, null)
        // no crash even though the data structure is incorrect
    }

    @Test
    fun testOnNotificationRemoved_callsListener() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        mediaDataProcessor.onNotificationRemoved(KEY)
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testOnNotificationAdded_emptyTitle_hasPlaceholder() {
        // When the manager has a notification with an empty title, and the app is not
        // required to include a non-empty title
        val mockPackageManager = mock(PackageManager::class.java)
        context.setMockPackageManager(mockPackageManager)
        whenever(mockPackageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_EMPTY_TITLE)
                    .build()
            )
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)

        // Then a media control is created with a placeholder title string
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        val placeholderTitle = context.getString(R.string.controls_media_empty_title, APP_NAME)
        assertThat(mediaDataCaptor.value.song).isEqualTo(placeholderTitle)
    }

    @Test
    fun testOnNotificationAdded_blankTitle_hasPlaceholder() {
        // GIVEN that the manager has a notification with a blank title, and the app is not
        // required to include a non-empty title
        val mockPackageManager = mock(PackageManager::class.java)
        context.setMockPackageManager(mockPackageManager)
        whenever(mockPackageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_BLANK_TITLE)
                    .build()
            )
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)

        // Then a media control is created with a placeholder title string
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        val placeholderTitle = context.getString(R.string.controls_media_empty_title, APP_NAME)
        assertThat(mediaDataCaptor.value.song).isEqualTo(placeholderTitle)
    }

    @Test
    fun testOnNotificationAdded_emptyMetadata_usesNotificationTitle() {
        // When the app sets the metadata title fields to empty strings, but does include a
        // non-blank notification title
        val mockPackageManager = mock(PackageManager::class.java)
        context.setMockPackageManager(mockPackageManager)
        whenever(mockPackageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_EMPTY_TITLE)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, SESSION_EMPTY_TITLE)
                    .build()
            )
        mediaNotification =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setContentTitle(SESSION_TITLE)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                }
                build()
            }
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)

        // Then the media control is added using the notification's title
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.song).isEqualTo(SESSION_TITLE)
    }

    @Test
    fun testOnNotificationRemoved_emptyTitle_notConverted() {
        // GIVEN that the manager has a notification with a resume action and empty title.
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val instanceId = data.instanceId
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(
            KEY,
            null,
            data.copy(song = SESSION_EMPTY_TITLE, resumeAction = Runnable {}),
        )

        // WHEN the notification is removed
        reset(listener)
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN active media is not converted to resume.
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        verify(logger, never())
            .logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
        verify(logger, never()).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), any())
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
    }

    @Test
    fun testOnNotificationRemoved_blankTitle_notConverted() {
        // GIVEN that the manager has a notification with a resume action and blank title.
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val instanceId = data.instanceId
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(
            KEY,
            null,
            data.copy(song = SESSION_BLANK_TITLE, resumeAction = Runnable {}),
        )

        // WHEN the notification is removed
        reset(listener)
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN active media is not converted to resume.
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        verify(logger, never())
            .logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
        verify(logger, never()).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), any())
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
    }

    @Test
    fun testOnNotificationRemoved_withResumption() {
        // GIVEN that the manager has a notification with a resume action
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        // WHEN the notification is removed
        mediaDataProcessor.onNotificationRemoved(KEY)
        // THEN the media data indicates that it is for resumption
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()
        verify(logger).logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testOnNotificationRemoved_twoWithResumption() {
        // GIVEN that the manager has two notifications with resume actions
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)
        mediaDataProcessor.onNotificationAdded(KEY_2, mediaNotification)
        testScope.assertRunAllReady(foreground = 2, background = 2)

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY_2),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        val data2 = mediaDataCaptor.value
        assertThat(data2.resumption).isFalse()

        mediaDataProcessor.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        mediaDataProcessor.onMediaDataLoaded(KEY_2, null, data2.copy(resumeAction = Runnable {}))
        reset(listener)
        // WHEN the first is removed
        mediaDataProcessor.onNotificationRemoved(KEY)
        // THEN the data is for resumption and the key is migrated to the package name
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener, never()).onMediaDataRemoved(eq(KEY), anyBoolean())
        // WHEN the second is removed
        mediaDataProcessor.onNotificationRemoved(KEY_2)
        // THEN the data is for resumption and the second key is removed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(PACKAGE_NAME),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener).onMediaDataRemoved(eq(KEY_2), eq(false))
    }

    @Test
    fun testOnNotificationRemoved_withResumption_butNotLocal() {
        // GIVEN that the manager has a notification with a resume action, but is not local
        whenever(playbackInfo.playbackType)
            .thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val dataRemoteWithResume =
            data.copy(resumeAction = Runnable {}, playbackLocation = MediaData.PLAYBACK_CAST_LOCAL)
        mediaDataProcessor.onMediaDataLoaded(KEY, null, dataRemoteWithResume)
        verify(logger)
            .logActiveMediaAdded(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
                eq(MediaData.PLAYBACK_CAST_LOCAL),
            )

        // WHEN the notification is removed
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN the media data is removed
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
    }

    @Test
    fun testOnNotificationRemoved_withResumption_isRemoteAndRemoteAllowed() {
        // With the flag enabled to allow remote media to resume
        fakeFeatureFlags.set(MEDIA_REMOTE_RESUME, true)

        // GIVEN that the manager has a notification with a resume action, but is not local
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        whenever(playbackInfo.playbackType)
            .thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val dataRemoteWithResume =
            data.copy(resumeAction = Runnable {}, playbackLocation = MediaData.PLAYBACK_CAST_LOCAL)
        mediaDataProcessor.onMediaDataLoaded(KEY, null, dataRemoteWithResume)

        // WHEN the notification is removed
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN the media data is converted to a resume state
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
    }

    @Test
    fun testOnNotificationRemoved_withResumption_isRcnAndRemoteAllowed() {
        // With the flag enabled to allow remote media to resume
        fakeFeatureFlags.set(MEDIA_REMOTE_RESUME, true)

        // GIVEN that the manager has a remote cast notification
        addNotificationAndLoad(remoteCastNotification)
        val data = mediaDataCaptor.value
        assertThat(data.playbackLocation).isEqualTo(MediaData.PLAYBACK_CAST_REMOTE)
        val dataRemoteWithResume = data.copy(resumeAction = Runnable {})
        mediaDataProcessor.onMediaDataLoaded(KEY, null, dataRemoteWithResume)

        // WHEN the RCN is removed
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN the media data is removed
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
    }

    @Test
    fun testOnNotificationRemoved_withResumption_tooManyPlayers() {
        // Given the maximum number of resume controls already
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        for (i in 0..ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS) {
            addResumeControlAndLoad(desc, "$i:$PACKAGE_NAME")
            clock.advanceTime(1000)
        }

        // And an active, resumable notification
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))

        // When the notification is removed
        mediaDataProcessor.onNotificationRemoved(KEY)

        // Then it is converted to resumption
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()

        // And the oldest resume control was removed
        verify(listener).onMediaDataRemoved(eq("0:$PACKAGE_NAME"), eq(false))
    }

    fun testOnNotificationRemoved_lockDownMode() {
        whenever(keyguardUpdateMonitor.isUserInLockdown(any())).thenReturn(true)

        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        mediaDataProcessor.onNotificationRemoved(KEY)

        verify(listener, never()).onMediaDataRemoved(eq(KEY), eq(false))
        verify(logger, never())
            .logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testAddResumptionControls() {
        // WHEN resumption controls are added
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        val currentTime = clock.elapsedRealtime()
        addResumeControlAndLoad(desc)

        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.song).isEqualTo(SESSION_TITLE)
        assertThat(data.app).isEqualTo(APP_NAME)
        // resume button is a semantic action.
        assertThat(data.actions).hasSize(0)
        assertThat(data.semanticActions!!.playOrPause).isNotNull()
        assertThat(data.lastActive).isAtLeast(currentTime)
        verify(logger).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testAddResumptionControls_withExplicitIndicator() {
        val bundle = Bundle()
        // WHEN resumption controls are added with explicit indicator
        bundle.putLong(
            MediaConstants.METADATA_KEY_IS_EXPLICIT,
            MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT,
        )
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                setExtras(bundle)
                build()
            }
        val currentTime = clock.elapsedRealtime()
        addResumeControlAndLoad(desc)

        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.song).isEqualTo(SESSION_TITLE)
        assertThat(data.app).isEqualTo(APP_NAME)
        // resume button is a semantic action.
        assertThat(data.actions).hasSize(0)
        assertThat(data.semanticActions!!.playOrPause).isNotNull()
        assertThat(data.lastActive).isAtLeast(currentTime)
        assertThat(data.isExplicit).isTrue()
        verify(logger).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testAddResumptionControls_hasPartialProgress() {
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

        // WHEN resumption controls are added with partial progress
        val progress = 0.5
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
                )
                putDouble(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress)
            }
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                setExtras(extras)
                build()
            }
        addResumeControlAndLoad(desc)

        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.resumeProgress).isEqualTo(progress)
    }

    @Test
    fun testAddResumptionControls_hasNotPlayedProgress() {
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

        // WHEN resumption controls are added that have not been played
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED,
                )
            }
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                setExtras(extras)
                build()
            }
        addResumeControlAndLoad(desc)

        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.resumeProgress).isEqualTo(0)
    }

    @Test
    fun testAddResumptionControls_hasFullProgress() {
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

        // WHEN resumption controls are added with progress info
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
                )
            }
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                setExtras(extras)
                build()
            }
        addResumeControlAndLoad(desc)

        // THEN the media data includes the progress
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.resumeProgress).isEqualTo(1)
    }

    @Test
    fun testAddResumptionControls_hasNoExtras() {
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

        // WHEN resumption controls are added that do not have any extras
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        addResumeControlAndLoad(desc)

        // Resume progress is null
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.resumeProgress).isEqualTo(null)
    }

    @Test
    fun testAddResumptionControls_hasEmptyTitle() {
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

        // WHEN resumption controls are added that have empty title
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_EMPTY_TITLE)
                build()
            }
        mediaDataProcessor.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME,
        )

        // Resumption controls are not added.
        testScope.assertRunAllReady(foreground = 0, background = 1)
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
    }

    @Test
    fun testAddResumptionControls_hasBlankTitle() {
        fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

        // WHEN resumption controls are added that have a blank title
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_BLANK_TITLE)
                build()
            }
        mediaDataProcessor.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME,
        )

        // Resumption controls are not added.
        testScope.assertRunAllReady(foreground = 0, background = 1)
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
    }

    @Test
    fun testResumptionDisabled_dismissesResumeControls() {
        // WHEN there are resume controls and resumption is switched off
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        addResumeControlAndLoad(desc)

        val data = mediaDataCaptor.value
        mediaDataProcessor.setMediaResumptionEnabled(false)

        // THEN the resume controls are dismissed
        verify(listener).onMediaDataRemoved(eq(PACKAGE_NAME), eq(false))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testDismissMedia_listenerCalled() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val removed = mediaDataProcessor.dismissMediaData(KEY, 0L, true)
        assertThat(removed).isTrue()

        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()

        verify(listener).onMediaDataRemoved(eq(KEY), eq(true))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testDismissMedia_keyDoesNotExist_returnsFalse() {
        val removed = mediaDataProcessor.dismissMediaData(KEY, 0L, true)
        assertThat(removed).isFalse()
    }

    @Test
    fun testBadArtwork_doesNotUse() {
        // WHEN notification has a too-small artwork
        val artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val notif =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                    it.setLargeIcon(artwork)
                }
                build()
            }
        mediaDataProcessor.onNotificationAdded(KEY, notif)

        // THEN it still loads
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNewValidMediaTarget_callsListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(logger).getNewInstanceId()
        val instanceId = instanceIdSequence.lastInstanceId

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = true,
                        packageName = PACKAGE_NAME,
                        cardAction = mediaSmartspaceBaseAction,
                        recommendations = validRecommendationList,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNewInvalidMediaTarget_callsListener() {
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf())
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(logger).getNewInstanceId()
        val instanceId = instanceIdSequence.lastInstanceId

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = true,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNullIntent_callsListener() {
        val recommendationExtras =
            Bundle().apply {
                putString("package_name", PACKAGE_NAME)
                putParcelable("dismiss_intent", null)
            }
        whenever(mediaSmartspaceBaseAction.extras).thenReturn(recommendationExtras)
        whenever(mediaSmartspaceTarget.baseAction).thenReturn(mediaSmartspaceBaseAction)
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf())

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(logger).getNewInstanceId()
        val instanceId = instanceIdSequence.lastInstanceId

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = true,
                        dismissIntent = null,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNoneMediaTarget_notCallsListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf())
        verify(logger, never()).getNewInstanceId()
        verify(listener, never())
            .onSmartspaceMediaDataLoaded(anyObject(), anyObject(), anyBoolean())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNoneMediaTarget_callsRemoveListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(logger).getNewInstanceId()

        smartspaceMediaDataProvider.onTargetsAvailable(listOf())
        uiExecutor.advanceClockToLast()
        uiExecutor.runAllReady()

        verify(listener).onSmartspaceMediaDataRemoved(eq(KEY_MEDIA_SMARTSPACE), eq(false))
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_persistentEnabled_headphoneTrigger_isActive() {
        fakeFeatureFlags.set(MEDIA_RETAIN_RECOMMENDATIONS, true)
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        val instanceId = instanceIdSequence.lastInstanceId

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = true,
                        packageName = PACKAGE_NAME,
                        cardAction = mediaSmartspaceBaseAction,
                        recommendations = validRecommendationList,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_persistentEnabled_periodicTrigger_notActive() {
        fakeFeatureFlags.set(MEDIA_RETAIN_RECOMMENDATIONS, true)
        val extras =
            Bundle().apply {
                putString("package_name", PACKAGE_NAME)
                putParcelable("dismiss_intent", DISMISS_INTENT)
                putString(EXTRA_KEY_TRIGGER_SOURCE, EXTRA_VALUE_TRIGGER_PERIODIC)
            }
        whenever(mediaSmartspaceBaseAction.extras).thenReturn(extras)

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        val instanceId = instanceIdSequence.lastInstanceId

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = false,
                        packageName = PACKAGE_NAME,
                        cardAction = mediaSmartspaceBaseAction,
                        recommendations = validRecommendationList,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_persistentEnabled_noTargets_inactive() {
        fakeFeatureFlags.set(MEDIA_RETAIN_RECOMMENDATIONS, true)

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        val instanceId = instanceIdSequence.lastInstanceId

        smartspaceMediaDataProvider.onTargetsAvailable(listOf())
        uiExecutor.advanceClockToLast()
        uiExecutor.runAllReady()

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = false,
                        packageName = PACKAGE_NAME,
                        cardAction = mediaSmartspaceBaseAction,
                        recommendations = validRecommendationList,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
        verify(listener, never()).onSmartspaceMediaDataRemoved(eq(KEY_MEDIA_SMARTSPACE), eq(false))
    }

    @Test
    fun testSetRecommendationInactive_notifiesListeners() {
        fakeFeatureFlags.set(MEDIA_RETAIN_RECOMMENDATIONS, true)

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        val instanceId = instanceIdSequence.lastInstanceId

        mediaDataProcessor.setRecommendationInactive(KEY_MEDIA_SMARTSPACE)
        uiExecutor.advanceClockToLast()
        uiExecutor.runAllReady()

        verify(listener)
            .onSmartspaceMediaDataLoaded(
                eq(KEY_MEDIA_SMARTSPACE),
                eq(
                    SmartspaceMediaData(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = false,
                        packageName = PACKAGE_NAME,
                        cardAction = mediaSmartspaceBaseAction,
                        recommendations = validRecommendationList,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false),
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_settingDisabled_doesNothing() {
        // WHEN media recommendation setting is off
        settings.putInt(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 0)
        testScope.runCurrent()

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))

        // THEN smartspace signal is ignored
        verify(listener, never())
            .onSmartspaceMediaDataLoaded(anyObject(), anyObject(), anyBoolean())
    }

    @Test
    fun testMediaRecommendationDisabled_removesSmartspaceData() {
        // GIVEN a media recommendation card is present
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(listener)
            .onSmartspaceMediaDataLoaded(eq(KEY_MEDIA_SMARTSPACE), anyObject(), anyBoolean())

        // WHEN the media recommendation setting is turned off
        settings.putInt(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 0)
        testScope.runCurrent()

        // THEN listeners are notified
        uiExecutor.advanceClockToLast()
        foregroundExecutor.advanceClockToLast()
        uiExecutor.runAllReady()
        foregroundExecutor.runAllReady()
        verify(listener).onSmartspaceMediaDataRemoved(eq(KEY_MEDIA_SMARTSPACE), eq(true))
    }

    @Test
    fun testOnMediaDataChanged_updatesLastActiveTime() {
        val currentTime = clock.elapsedRealtime()
        addNotificationAndLoad()
        assertThat(mediaDataCaptor.value!!.lastActive).isAtLeast(currentTime)
    }

    @Test
    fun testOnMediaDataTimedOut_updatesLastActiveTime() {
        // GIVEN that the manager has a notification
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)
        testScope.assertRunAllReady(foreground = 1, background = 1)

        // WHEN the notification times out
        clock.advanceTime(100)
        val currentTime = clock.elapsedRealtime()
        mediaDataProcessor.setInactive(KEY, timedOut = true, forceUpdate = true)

        // THEN the last active time is changed
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.lastActive).isAtLeast(currentTime)
    }

    @Test
    fun testOnActiveMediaConverted_updatesLastActiveTime() {
        // GIVEN that the manager has a notification with a resume action
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val instanceId = data.instanceId
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))

        // WHEN the notification is removed
        clock.advanceTime(100)
        val currentTime = clock.elapsedRealtime()
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN the last active time is changed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.lastActive).isAtLeast(currentTime)

        // Log as a conversion event, not as a new resume control
        verify(logger).logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
        verify(logger, never()).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), any())
    }

    @Test
    fun testOnInactiveMediaConverted_doesNotUpdateLastActiveTime() {
        // GIVEN that the manager has a notification with a resume action
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val instanceId = data.instanceId
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(
            KEY,
            null,
            data.copy(resumeAction = Runnable {}, active = false),
        )

        // WHEN the notification is removed
        clock.advanceTime(100)
        val currentTime = clock.elapsedRealtime()
        mediaDataProcessor.onNotificationRemoved(KEY)

        // THEN the last active time is not changed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.lastActive).isLessThan(currentTime)

        // Log as a conversion event, not as a new resume control
        verify(logger).logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
        verify(logger, never()).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), any())
    }

    @Test
    fun testTooManyCompactActions_isTruncated() {
        // GIVEN a notification where too many compact actions were specified
        val notif =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(
                        MediaStyle().apply {
                            setMediaSession(session.sessionToken)
                            setShowActionsInCompactView(0, 1, 2, 3, 4)
                        }
                    )
                }
                build()
            }

        // WHEN the notification is loaded
        mediaDataProcessor.onNotificationAdded(KEY, notif)
        testScope.assertRunAllReady(foreground = 1, background = 1)

        // THEN only the first MAX_COMPACT_ACTIONS are actually set
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.actionsToShowInCompact.size)
            .isEqualTo(MediaDataProcessor.MAX_COMPACT_ACTIONS)
    }

    @Test
    fun testTooManyNotificationActions_isTruncated() {
        // GIVEN a notification where too many notification actions are added
        val action = Notification.Action(R.drawable.ic_android, "action", null)
        val notif =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                    for (i in 0..MediaDataProcessor.MAX_NOTIFICATION_ACTIONS) {
                        it.addAction(action)
                    }
                }
                build()
            }

        // WHEN the notification is loaded
        mediaDataProcessor.onNotificationAdded(KEY, notif)
        testScope.assertRunAllReady(foreground = 1, background = 1)

        // THEN only the first MAX_NOTIFICATION_ACTIONS are actually included
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.actions.size)
            .isEqualTo(MediaDataProcessor.MAX_NOTIFICATION_ACTIONS)
    }

    @Test
    fun testPlaybackActions_noState_usesNotification() {
        val desc = "Notification Action"
        whenever(controller.playbackState).thenReturn(null)

        val notifWithAction =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                    it.addAction(android.R.drawable.ic_media_play, desc, null)
                }
                build()
            }
        mediaDataProcessor.onNotificationAdded(KEY, notifWithAction)

        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )

        assertThat(mediaDataCaptor.value!!.semanticActions).isNull()
        assertThat(mediaDataCaptor.value!!.actions).hasSize(1)
        assertThat(mediaDataCaptor.value!!.actions[0]!!.contentDescription).isEqualTo(desc)
    }

    @Test
    fun testPlaybackActions_hasPrevNext() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4")
        val stateActions =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT
        val stateBuilder = PlaybackState.Builder().setActions(stateActions)
        customDesc.forEach {
            stateBuilder.addCustomAction("action: $it", it, android.R.drawable.ic_media_pause)
        }
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_play))
        actions.playOrPause!!.action!!.run()
        verify(transportControls).play()

        assertThat(actions.prevOrCustom).isNotNull()
        assertThat(actions.prevOrCustom!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_prev))
        actions.prevOrCustom!!.action!!.run()
        verify(transportControls).skipToPrevious()

        assertThat(actions.nextOrCustom).isNotNull()
        assertThat(actions.nextOrCustom!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_next))
        actions.nextOrCustom!!.action!!.run()
        verify(transportControls).skipToNext()

        assertThat(actions.custom0).isNotNull()
        assertThat(actions.custom0!!.contentDescription).isEqualTo(customDesc[0])

        assertThat(actions.custom1).isNotNull()
        assertThat(actions.custom1!!.contentDescription).isEqualTo(customDesc[1])
    }

    @Test
    fun testPlaybackActions_noPrevNext_usesCustom() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4", "custom 5")
        val stateActions = PlaybackState.ACTION_PLAY
        val stateBuilder = PlaybackState.Builder().setActions(stateActions)
        customDesc.forEach {
            stateBuilder.addCustomAction("action: $it", it, android.R.drawable.ic_media_pause)
        }
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_play))

        assertThat(actions.prevOrCustom).isNotNull()
        assertThat(actions.prevOrCustom!!.contentDescription).isEqualTo(customDesc[0])

        assertThat(actions.nextOrCustom).isNotNull()
        assertThat(actions.nextOrCustom!!.contentDescription).isEqualTo(customDesc[1])

        assertThat(actions.custom0).isNotNull()
        assertThat(actions.custom0!!.contentDescription).isEqualTo(customDesc[2])

        assertThat(actions.custom1).isNotNull()
        assertThat(actions.custom1!!.contentDescription).isEqualTo(customDesc[3])
    }

    @Test
    fun testPlaybackActions_connecting() {
        val stateActions = PlaybackState.ACTION_PLAY
        val stateBuilder =
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_BUFFERING, 0, 10f)
                .setActions(stateActions)
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_connecting))
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_CONTROLS_DRAWABLES_REUSE)
    fun postWithPlaybackActions_drawablesReused() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        val stateActions =
            PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT
        val stateBuilder =
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 10f)
                .setActions(stateActions)
        whenever(controller.playbackState).thenReturn(stateBuilder.build())
        val userEntries by testScope.collectLastValue(mediaFilterRepository.selectedUserEntries)

        mediaDataProcessor.addInternalListener(mediaDataFilter)
        mediaDataFilter.mediaDataProcessor = mediaDataProcessor
        addNotificationAndLoad()

        assertThat(userEntries).hasSize(1)
        val firstSemanticActions = userEntries?.values?.toList()?.get(0)?.semanticActions!!

        addNotificationAndLoad()

        assertThat(userEntries).hasSize(1)
        val secondSemanticActions = userEntries?.values?.toList()?.get(0)?.semanticActions!!
        assertThat(secondSemanticActions.nextOrCustom?.icon)
            .isEqualTo(firstSemanticActions.nextOrCustom?.icon)
        assertThat(secondSemanticActions.prevOrCustom?.icon)
            .isEqualTo(firstSemanticActions.prevOrCustom?.icon)
    }

    @Test
    @DisableFlags(Flags.FLAG_MEDIA_CONTROLS_DRAWABLES_REUSE)
    fun postWithPlaybackActions_drawablesNotReused() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        val stateActions =
            PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT
        val stateBuilder =
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 10f)
                .setActions(stateActions)
        whenever(controller.playbackState).thenReturn(stateBuilder.build())
        val userEntries by testScope.collectLastValue(mediaFilterRepository.selectedUserEntries)

        mediaDataProcessor.addInternalListener(mediaDataFilter)
        mediaDataFilter.mediaDataProcessor = mediaDataProcessor
        addNotificationAndLoad()

        assertThat(userEntries).hasSize(1)
        val firstSemanticActions = userEntries?.values?.toList()?.get(0)?.semanticActions!!

        addNotificationAndLoad()

        assertThat(userEntries).hasSize(1)
        val secondSemanticActions = userEntries?.values?.toList()?.get(0)?.semanticActions!!
        assertThat(secondSemanticActions.nextOrCustom?.icon)
            .isNotEqualTo(firstSemanticActions.nextOrCustom?.icon)
        assertThat(secondSemanticActions.prevOrCustom?.icon)
            .isNotEqualTo(firstSemanticActions.prevOrCustom?.icon)
    }

    @Test
    fun testPlaybackActions_reservedSpace() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4")
        val stateActions = PlaybackState.ACTION_PLAY
        val stateBuilder = PlaybackState.Builder().setActions(stateActions)
        customDesc.forEach {
            stateBuilder.addCustomAction("action: $it", it, android.R.drawable.ic_media_pause)
        }
        val extras =
            Bundle().apply {
                putBoolean(MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV, true)
                putBoolean(MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT, true)
            }
        whenever(controller.playbackState).thenReturn(stateBuilder.build())
        whenever(controller.extras).thenReturn(extras)

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_play))

        assertThat(actions.prevOrCustom).isNull()
        assertThat(actions.nextOrCustom).isNull()

        assertThat(actions.custom0).isNotNull()
        assertThat(actions.custom0!!.contentDescription).isEqualTo(customDesc[0])

        assertThat(actions.custom1).isNotNull()
        assertThat(actions.custom1!!.contentDescription).isEqualTo(customDesc[1])

        assertThat(actions.reserveNext).isTrue()
        assertThat(actions.reservePrev).isTrue()
    }

    @Test
    fun testPlaybackActions_playPause_hasButton() {
        val stateActions = PlaybackState.ACTION_PLAY_PAUSE
        val stateBuilder = PlaybackState.Builder().setActions(stateActions)
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription)
            .isEqualTo(context.getString(R.string.controls_media_button_play))
        actions.playOrPause!!.action!!.run()
        verify(transportControls).play()
    }

    @Test
    fun testPlaybackLocationChange_isLogged() {
        // Media control added for local playback
        addNotificationAndLoad()
        val instanceId = mediaDataCaptor.value.instanceId

        // Location is updated to local cast
        whenever(playbackInfo.playbackType)
            .thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        addNotificationAndLoad()
        verify(logger)
            .logPlaybackLocationChange(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(instanceId),
                eq(MediaData.PLAYBACK_CAST_LOCAL),
            )

        // update to remote cast
        mediaDataProcessor.onNotificationAdded(KEY, remoteCastNotification)
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(logger)
            .logPlaybackLocationChange(
                anyInt(),
                eq(SYSTEM_PACKAGE_NAME),
                eq(instanceId),
                eq(MediaData.PLAYBACK_CAST_REMOTE),
            )
    }

    @Test
    fun testPlaybackStateChange_keyExists_callsListener() {
        // Notification has been added
        addNotificationAndLoad()

        // Callback gets an updated state
        val state = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build()
        testScope.onStateUpdated(KEY, state)

        // Listener is notified of updated state
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.isPlaying).isTrue()
    }

    @Test
    fun testPlaybackStateChange_keyDoesNotExist_doesNothing() {
        val state = PlaybackState.Builder().build()

        // No media added with this key

        testScope.onStateUpdated(KEY, state)
        verify(listener, never())
            .onMediaDataLoaded(eq(KEY), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testPlaybackStateChange_keyHasNullToken_doesNothing() {
        // When we get an update that sets the data's token to null
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataProcessor.onMediaDataLoaded(KEY, null, data.copy(token = null))

        // And then get a state update
        val state = PlaybackState.Builder().build()

        // Then no changes are made
        testScope.onStateUpdated(KEY, state)
        verify(listener, never())
            .onMediaDataLoaded(eq(KEY), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testPlaybackState_PauseWhenFlagTrue_keyExists_callsListener() {
        val state = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 1f).build()
        whenever(controller.playbackState).thenReturn(state)

        addNotificationAndLoad()
        testScope.onStateUpdated(KEY, state)

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()
        assertThat(mediaDataCaptor.value.semanticActions).isNotNull()
    }

    @Test
    fun testPlaybackState_PauseStateAfterAddingResumption_keyExists_callsListener() {
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        val state =
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build()

        // Add resumption controls in order to have semantic actions.
        // To make sure that they are not null after changing state.
        mediaDataProcessor.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME,
        )
        testScope.runCurrent()
        backgroundExecutor.runAllReady()
        foregroundExecutor.runAllReady()

        testScope.onStateUpdated(PACKAGE_NAME, state)

        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(PACKAGE_NAME),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()
        assertThat(mediaDataCaptor.value.semanticActions).isNotNull()
    }

    @Test
    fun testPlaybackStateNull_Pause_keyExists_callsListener() {
        whenever(controller.playbackState).thenReturn(null)
        val state =
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build()

        addNotificationAndLoad()
        testScope.onStateUpdated(KEY, state)

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()
        assertThat(mediaDataCaptor.value.semanticActions).isNull()
    }

    @Test
    fun testNoClearNotOngoing_canDismiss() {
        mediaNotification =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                    it.setOngoing(false)
                    it.setFlag(FLAG_NO_CLEAR, true)
                }
                build()
            }
        addNotificationAndLoad()
        assertThat(mediaDataCaptor.value.isClearable).isTrue()
    }

    @Test
    fun testOngoing_cannotDismiss() {
        mediaNotification =
            SbnBuilder().run {
                setPkg(PACKAGE_NAME)
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_pause)
                    it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                    it.setOngoing(true)
                }
                build()
            }
        addNotificationAndLoad()
        assertThat(mediaDataCaptor.value.isClearable).isFalse()
    }

    @Test
    fun testRetain_notifPlayer_notifRemoved_setToResume() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)

        // When a media control based on notification is added, times out, and then removed
        addNotificationAndLoad()
        mediaDataProcessor.setInactive(KEY, timedOut = true)
        assertThat(mediaDataCaptor.value.active).isFalse()
        mediaDataProcessor.onNotificationRemoved(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
            )
    }

    @Test
    fun testRetain_notifPlayer_sessionDestroyed_doesNotChange() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)

        // When a media control based on notification is added and times out
        addNotificationAndLoad()
        mediaDataProcessor.setInactive(KEY, timedOut = true)
        assertThat(mediaDataCaptor.value.active).isFalse()

        // and then the session is destroyed
        sessionCallbackCaptor.value.invoke(KEY)

        // It remains as a regular player
        verify(listener, never()).onMediaDataRemoved(eq(KEY), anyBoolean())
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_notifPlayer_removeWhileActive_fullyRemoved() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)

        // When a media control based on notification is added and then removed, without timing out
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataProcessor.onNotificationRemoved(KEY)

        // It is fully removed
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_canResume_removeWhileActive_setToResume() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)

        // When a media control that supports resumption is added
        addNotificationAndLoad()
        val dataResumable = mediaDataCaptor.value.copy(resumeAction = Runnable {})
        mediaDataProcessor.onMediaDataLoaded(KEY, null, dataResumable)

        // And then removed while still active
        mediaDataProcessor.onNotificationRemoved(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
            )
    }

    @Test
    fun testRetain_sessionPlayer_notifRemoved_doesNotChange() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)
        addPlaybackStateAction()

        // When a media control with PlaybackState actions is added, times out,
        // and then the notification is removed
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataProcessor.setInactive(KEY, timedOut = true)
        mediaDataProcessor.onNotificationRemoved(KEY)

        // It remains as a regular player
        verify(listener, never()).onMediaDataRemoved(eq(KEY), anyBoolean())
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_sessionPlayer_sessionDestroyed_setToResume() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)
        addPlaybackStateAction()

        // When a media control with PlaybackState actions is added, times out,
        // and then the session is destroyed
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataProcessor.setInactive(KEY, timedOut = true)
        sessionCallbackCaptor.value.invoke(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
            )
    }

    @Test
    fun testRetain_sessionPlayer_destroyedWhileActive_noResume_fullyRemoved() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)
        addPlaybackStateAction()

        // When a media control using session actions is added, and then the session is destroyed
        // without timing out first
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        sessionCallbackCaptor.value.invoke(KEY)

        // It is fully removed
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_sessionPlayer_canResume_destroyedWhileActive_setToResume() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)
        addPlaybackStateAction()

        // When a media control using session actions and that does allow resumption is added,
        addNotificationAndLoad()
        val dataResumable = mediaDataCaptor.value.copy(resumeAction = Runnable {})
        mediaDataProcessor.onMediaDataLoaded(KEY, null, dataResumable)

        // And then the session is destroyed without timing out first
        sessionCallbackCaptor.value.invoke(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
            )
    }

    @Test
    fun testSessionPlayer_sessionDestroyed_noResume_fullyRemoved() {
        addPlaybackStateAction()

        // When a media control with PlaybackState actions is added, times out,
        // and then the session is destroyed
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataProcessor.setInactive(KEY, timedOut = true)
        sessionCallbackCaptor.value.invoke(KEY)

        // It is fully removed.
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
    }

    @Test
    fun testSessionPlayer_destroyedWhileActive_noResume_fullyRemoved() {
        addPlaybackStateAction()

        // When a media control using session actions is added, and then the session is destroyed
        // without timing out first
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        sessionCallbackCaptor.value.invoke(KEY)

        // It is fully removed
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testSessionPlayer_canResume_destroyedWhileActive_setToResume() {
        addPlaybackStateAction()

        // When a media control using session actions and that does allow resumption is added,
        addNotificationAndLoad()
        val dataResumable = mediaDataCaptor.value.copy(resumeAction = Runnable {})
        mediaDataProcessor.onMediaDataLoaded(KEY, null, dataResumable)

        // And then the session is destroyed without timing out first
        sessionCallbackCaptor.value.invoke(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
            )
    }

    @Test
    fun testSessionDestroyed_noNotificationKey_stillRemoved() {
        fakeFeatureFlags.set(MEDIA_RETAIN_SESSIONS, true)

        // When a notiifcation is added and then removed before it is fully processed
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)
        backgroundExecutor.runAllReady()
        mediaDataProcessor.onNotificationRemoved(KEY)

        // We still make sure to remove it
        verify(listener).onMediaDataRemoved(eq(KEY), eq(false))
    }

    @Test
    fun testResumeMediaLoaded_hasArtPermission_artLoaded() {
        // When resume media is loaded and user/app has permission to access the art URI,
        whenever(
                ugm.checkGrantUriPermission_ignoreNonSystem(
                    anyInt(),
                    any(),
                    any(),
                    anyInt(),
                    anyInt(),
                )
            )
            .thenReturn(1)
        val artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val uri = Uri.parse("content://example")
        whenever(ImageDecoder.createSource(any(), eq(uri))).thenReturn(imageSource)
        whenever(ImageDecoder.decodeBitmap(any(), any())).thenReturn(artwork)

        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                setIconUri(uri)
                build()
            }
        addResumeControlAndLoad(desc)

        // Then the artwork is loaded
        assertThat(mediaDataCaptor.value.artwork).isNotNull()
    }

    @Test
    fun testResumeMediaLoaded_noArtPermission_noArtLoaded() {
        // When resume media is loaded and user/app does not have permission to access the art URI
        whenever(
                ugm.checkGrantUriPermission_ignoreNonSystem(
                    anyInt(),
                    any(),
                    any(),
                    anyInt(),
                    anyInt(),
                )
            )
            .thenThrow(SecurityException("Test no permission"))
        val artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val uri = Uri.parse("content://example")
        whenever(ImageDecoder.createSource(any(), eq(uri))).thenReturn(imageSource)
        whenever(ImageDecoder.decodeBitmap(any(), any())).thenReturn(artwork)

        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                setIconUri(uri)
                build()
            }
        addResumeControlAndLoad(desc)

        // Then the artwork is not loaded
        assertThat(mediaDataCaptor.value.artwork).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_CONTROLS_POSTS_OPTIMIZATION)
    fun postDuplicateNotification_doesNotCallListeners() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)

        mediaDataProcessor.addInternalListener(mediaDataFilter)
        mediaDataFilter.mediaDataProcessor = mediaDataProcessor
        addNotificationAndLoad()
        reset(listener)
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)

        testScope.assertRunAllReady(foreground = 0, background = 1)
        verify(listener, never())
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        verify(kosmos.mediaLogger).logDuplicateMediaNotification(eq(KEY))
    }

    @Test
    @DisableFlags(Flags.FLAG_MEDIA_CONTROLS_POSTS_OPTIMIZATION)
    fun postDuplicateNotification_callsListeners() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)

        mediaDataProcessor.addInternalListener(mediaDataFilter)
        mediaDataFilter.mediaDataProcessor = mediaDataProcessor
        addNotificationAndLoad()
        reset(listener)
        mediaDataProcessor.onNotificationAdded(KEY, mediaNotification)
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
        verify(kosmos.mediaLogger, never()).logDuplicateMediaNotification(eq(KEY))
    }

    private fun TestScope.assertRunAllReady(foreground: Int = 0, background: Int = 0) {
        runCurrent()
        if (Flags.mediaLoadMetadataViaMediaDataLoader()) {
            advanceUntilIdle()
            // It doesn't make much sense to count tasks when we use coroutines in loader
            // so this check is skipped in that scenario.
            backgroundExecutor.runAllReady()
            foregroundExecutor.runAllReady()
        } else {
            if (background > 0) {
                assertThat(backgroundExecutor.runAllReady()).isEqualTo(background)
            }
            if (foreground > 0) {
                assertThat(foregroundExecutor.runAllReady()).isEqualTo(foreground)
            }
        }
    }

    /** Helper function to add a basic media notification and capture the resulting MediaData */
    private fun addNotificationAndLoad() {
        addNotificationAndLoad(mediaNotification)
    }

    /** Helper function to add the given notification and capture the resulting MediaData */
    private fun addNotificationAndLoad(sbn: StatusBarNotification) {
        mediaDataProcessor.onNotificationAdded(KEY, sbn)
        testScope.assertRunAllReady(foreground = 1, background = 1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
    }

    /** Helper function to set up a PlaybackState with action */
    private fun addPlaybackStateAction() {
        val stateActions = PlaybackState.ACTION_PLAY_PAUSE
        val stateBuilder = PlaybackState.Builder().setActions(stateActions)
        stateBuilder.setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
        whenever(controller.playbackState).thenReturn(stateBuilder.build())
    }

    /** Helper function to add a resumption control and capture the resulting MediaData */
    private fun addResumeControlAndLoad(
        desc: MediaDescription,
        packageName: String = PACKAGE_NAME,
    ) {
        mediaDataProcessor.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            packageName,
        )
        testScope.assertRunAllReady(foreground = 1, background = 1)

        verify(listener)
            .onMediaDataLoaded(
                eq(packageName),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false),
            )
    }

    /** Helper function to update state and run executors */
    private fun TestScope.onStateUpdated(key: String, state: PlaybackState) {
        stateCallbackCaptor.value.invoke(key, state)
        runCurrent()
        advanceUntilIdle()
    }
}
