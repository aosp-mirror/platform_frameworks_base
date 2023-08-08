/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.pipeline

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
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.media.utils.MediaConstants
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.logging.InstanceId
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.recommendation.EXTRA_KEY_TRIGGER_SOURCE
import com.android.systemui.media.controls.models.recommendation.EXTRA_VALUE_TRIGGER_PERIODIC
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaDataProvider
import com.android.systemui.media.controls.resume.MediaResumeListener
import com.android.systemui.media.controls.resume.ResumeMediaBrowser
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
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
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoSession
import org.mockito.junit.MockitoJUnit
import org.mockito.quality.Strictness

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

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class MediaDataManagerTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @Mock lateinit var mediaControllerFactory: MediaControllerFactory
    @Mock lateinit var controller: MediaController
    @Mock lateinit var transportControls: MediaController.TransportControls
    @Mock lateinit var playbackInfo: MediaController.PlaybackInfo
    lateinit var session: MediaSession
    lateinit var metadataBuilder: MediaMetadata.Builder
    lateinit var backgroundExecutor: FakeExecutor
    lateinit var foregroundExecutor: FakeExecutor
    lateinit var uiExecutor: FakeExecutor
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock lateinit var mediaTimeoutListener: MediaTimeoutListener
    @Mock lateinit var mediaResumeListener: MediaResumeListener
    @Mock lateinit var mediaSessionBasedFilter: MediaSessionBasedFilter
    @Mock lateinit var mediaDeviceManager: MediaDeviceManager
    @Mock lateinit var mediaDataCombineLatest: MediaDataCombineLatest
    @Mock lateinit var mediaDataFilter: MediaDataFilter
    @Mock lateinit var listener: MediaDataManager.Listener
    @Mock lateinit var pendingIntent: PendingIntent
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock lateinit var smartspaceManager: SmartspaceManager
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    lateinit var smartspaceMediaDataProvider: SmartspaceMediaDataProvider
    @Mock lateinit var mediaSmartspaceTarget: SmartspaceTarget
    @Mock private lateinit var mediaRecommendationItem: SmartspaceAction
    lateinit var validRecommendationList: List<SmartspaceAction>
    @Mock private lateinit var mediaSmartspaceBaseAction: SmartspaceAction
    @Mock private lateinit var mediaFlags: MediaFlags
    @Mock private lateinit var logger: MediaUiEventLogger
    lateinit var mediaDataManager: MediaDataManager
    lateinit var mediaNotification: StatusBarNotification
    lateinit var remoteCastNotification: StatusBarNotification
    @Captor lateinit var mediaDataCaptor: ArgumentCaptor<MediaData>
    private val clock = FakeSystemClock()
    @Mock private lateinit var tunerService: TunerService
    @Captor lateinit var tunableCaptor: ArgumentCaptor<TunerService.Tunable>
    @Captor lateinit var stateCallbackCaptor: ArgumentCaptor<(String, PlaybackState) -> Unit>
    @Captor lateinit var sessionCallbackCaptor: ArgumentCaptor<(String) -> Unit>
    @Captor lateinit var smartSpaceConfigBuilderCaptor: ArgumentCaptor<SmartspaceConfig>
    @Mock private lateinit var ugm: IUriGrantsManager
    @Mock private lateinit var imageSource: ImageDecoder.Source

    private val instanceIdSequence = InstanceIdSequenceFake(1 shl 20)

    private val originalSmartspaceSetting =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            1
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
            1
        )
        mediaDataManager =
            MediaDataManager(
                context = context,
                backgroundExecutor = backgroundExecutor,
                uiExecutor = uiExecutor,
                foregroundExecutor = foregroundExecutor,
                mediaControllerFactory = mediaControllerFactory,
                broadcastDispatcher = broadcastDispatcher,
                dumpManager = dumpManager,
                mediaTimeoutListener = mediaTimeoutListener,
                mediaResumeListener = mediaResumeListener,
                mediaSessionBasedFilter = mediaSessionBasedFilter,
                mediaDeviceManager = mediaDeviceManager,
                mediaDataCombineLatest = mediaDataCombineLatest,
                mediaDataFilter = mediaDataFilter,
                activityStarter = activityStarter,
                smartspaceMediaDataProvider = smartspaceMediaDataProvider,
                useMediaResumption = true,
                useQsMediaPlayer = true,
                systemClock = clock,
                tunerService = tunerService,
                mediaFlags = mediaFlags,
                logger = logger,
                smartspaceManager = smartspaceManager,
                keyguardUpdateMonitor = keyguardUpdateMonitor
            )
        verify(tunerService)
            .addTunable(capture(tunableCaptor), eq(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION))
        verify(mediaTimeoutListener).stateCallback = capture(stateCallbackCaptor)
        verify(mediaTimeoutListener).sessionCallback = capture(sessionCallbackCaptor)
        session = MediaSession(context, "MediaDataManagerTestSession")
        mediaNotification =
            SbnBuilder().run {
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
        whenever(mediaControllerFactory.create(eq(session.sessionToken))).thenReturn(controller)
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
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(false)
        whenever(mediaFlags.isExplicitIndicatorEnabled()).thenReturn(true)
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(false)
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(false)
        whenever(mediaFlags.isRemoteResumeAllowed()).thenReturn(false)
        whenever(logger.getNewInstanceId()).thenReturn(instanceIdSequence.newInstanceId())
        whenever(keyguardUpdateMonitor.isUserInLockdown(any())).thenReturn(false)
    }

    @After
    fun tearDown() {
        staticMockSession.finishMocking()
        session.release()
        mediaDataManager.destroy()
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            originalSmartspaceSetting
        )
    }

    @Test
    fun testSetTimedOut_active_deactivatesMedia() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()

        mediaDataManager.setTimedOut(KEY, timedOut = true)
        assertThat(data.active).isFalse()
        verify(logger).logMediaTimeout(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testSetTimedOut_resume_dismissesMedia() {
        // WHEN resume controls are present, and time out
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_TITLE)
                build()
            }
        mediaDataManager.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME
        )

        backgroundExecutor.runAllReady()
        foregroundExecutor.runAllReady()
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )

        mediaDataManager.setTimedOut(PACKAGE_NAME, timedOut = true)
        verify(logger)
            .logMediaTimeout(anyInt(), eq(PACKAGE_NAME), eq(mediaDataCaptor.value.instanceId))

        // THEN it is removed and listeners are informed
        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()
        verify(listener).onMediaDataRemoved(PACKAGE_NAME)
    }

    @Test
    fun testLoadsMetadataOnBackground() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun testLoadMetadata_withExplicitIndicator() {
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putLong(
                        MediaConstants.METADATA_KEY_IS_EXPLICIT,
                        MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT
                    )
                    .build()
            )

        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)

        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value!!.isExplicit).isTrue()
    }

    @Test
    fun testOnMetaDataLoaded_withoutExplicitIndicator() {
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)

        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
                eq(MediaData.PLAYBACK_LOCAL)
            )
    }

    @Test
    fun testOnMetaDataLoaded_conservesActiveFlag() {
        whenever(mediaControllerFactory.create(anyObject())).thenReturn(controller)
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
                eq(MediaData.PLAYBACK_CAST_REMOTE)
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

        mediaDataManager.onNotificationAdded(KEY, notif)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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

        mediaDataManager.loadMediaDataInBg(KEY, rcn, null)
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

        mediaDataManager.loadMediaDataInBg(KEY, rcn, null)
        // no crash even though the data structure is incorrect
    }

    @Test
    fun testOnNotificationRemoved_callsListener() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        mediaDataManager.onNotificationRemoved(KEY)
        verify(listener).onMediaDataRemoved(eq(KEY))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testOnNotificationAdded_emptyTitle_hasPlaceholder() {
        // When the manager has a notification with an empty title
        val mockPackageManager = mock(PackageManager::class.java)
        context.setMockPackageManager(mockPackageManager)
        whenever(mockPackageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_EMPTY_TITLE)
                    .build()
            )
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)

        // Then a media control is created with a placeholder title string
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        val placeholderTitle = context.getString(R.string.controls_media_empty_title, APP_NAME)
        assertThat(mediaDataCaptor.value.song).isEqualTo(placeholderTitle)
    }

    @Test
    fun testOnNotificationAdded_blankTitle_hasPlaceholder() {
        // GIVEN that the manager has a notification with a blank title
        val mockPackageManager = mock(PackageManager::class.java)
        context.setMockPackageManager(mockPackageManager)
        whenever(mockPackageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_BLANK_TITLE)
                    .build()
            )
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)

        // Then a media control is created with a placeholder title string
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)

        // Then the media control is added using the notification's title
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.song).isEqualTo(SESSION_TITLE)
    }

    @Test
    fun testOnNotificationRemoved_emptyTitle_notConverted() {
        // GIVEN that the manager has a notification with a resume action and empty title.
        whenever(controller.metadata)
            .thenReturn(
                metadataBuilder
                    .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_EMPTY_TITLE)
                    .build()
            )
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val instanceId = data.instanceId
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(
            KEY,
            null,
            data.copy(song = SESSION_EMPTY_TITLE, resumeAction = Runnable {})
        )
        // WHEN the notification is removed
        reset(listener)
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN active media is not converted to resume.
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        mediaDataManager.onMediaDataLoaded(
            KEY,
            null,
            data.copy(song = SESSION_BLANK_TITLE, resumeAction = Runnable {})
        )

        // WHEN the notification is removed
        reset(listener)
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN active media is not converted to resume.
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the media data indicates that it is for resumption
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()
        verify(logger).logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testOnNotificationRemoved_twoWithResumption() {
        // GIVEN that the manager has two notifications with resume actions
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onNotificationAdded(KEY_2, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(2)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(2)

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
                eq(false)
            )
        val data2 = mediaDataCaptor.value
        assertThat(data2.resumption).isFalse()

        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        mediaDataManager.onMediaDataLoaded(KEY_2, null, data2.copy(resumeAction = Runnable {}))
        reset(listener)
        // WHEN the first is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the data is for resumption and the key is migrated to the package name
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
        // WHEN the second is removed
        mediaDataManager.onNotificationRemoved(KEY_2)
        // THEN the data is for resumption and the second key is removed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(PACKAGE_NAME),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener).onMediaDataRemoved(eq(KEY_2))
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
        mediaDataManager.onMediaDataLoaded(KEY, null, dataRemoteWithResume)
        verify(logger)
            .logActiveMediaAdded(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId),
                eq(MediaData.PLAYBACK_CAST_LOCAL)
            )

        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN the media data is removed
        verify(listener).onMediaDataRemoved(eq(KEY))
    }

    @Test
    fun testOnNotificationRemoved_withResumption_isRemoteAndRemoteAllowed() {
        // With the flag enabled to allow remote media to resume
        whenever(mediaFlags.isRemoteResumeAllowed()).thenReturn(true)

        // GIVEN that the manager has a notification with a resume action, but is not local
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        whenever(playbackInfo.playbackType)
            .thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val dataRemoteWithResume =
            data.copy(resumeAction = Runnable {}, playbackLocation = MediaData.PLAYBACK_CAST_LOCAL)
        mediaDataManager.onMediaDataLoaded(KEY, null, dataRemoteWithResume)

        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN the media data is converted to a resume state
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
    }

    @Test
    fun testOnNotificationRemoved_withResumption_isRcnAndRemoteAllowed() {
        // With the flag enabled to allow remote media to resume
        whenever(mediaFlags.isRemoteResumeAllowed()).thenReturn(true)

        // GIVEN that the manager has a remote cast notification
        addNotificationAndLoad(remoteCastNotification)
        val data = mediaDataCaptor.value
        assertThat(data.playbackLocation).isEqualTo(MediaData.PLAYBACK_CAST_REMOTE)
        val dataRemoteWithResume = data.copy(resumeAction = Runnable {})
        mediaDataManager.onMediaDataLoaded(KEY, null, dataRemoteWithResume)

        // WHEN the RCN is removed
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN the media data is removed
        verify(listener).onMediaDataRemoved(eq(KEY))
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
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))

        // When the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)

        // Then it is converted to resumption
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()

        // And the oldest resume control was removed
        verify(listener).onMediaDataRemoved(eq("0:$PACKAGE_NAME"))
    }

    fun testOnNotificationRemoved_lockDownMode() {
        whenever(keyguardUpdateMonitor.isUserInLockdown(any())).thenReturn(true)

        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        mediaDataManager.onNotificationRemoved(KEY)

        verify(listener, never()).onMediaDataRemoved(eq(KEY))
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
        assertThat(data.actions).hasSize(1)
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
            MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT
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
        assertThat(data.actions).hasSize(1)
        assertThat(data.semanticActions!!.playOrPause).isNotNull()
        assertThat(data.lastActive).isAtLeast(currentTime)
        assertThat(data.isExplicit).isTrue()
        verify(logger).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testAddResumptionControls_hasPartialProgress() {
        whenever(mediaFlags.isResumeProgressEnabled()).thenReturn(true)

        // WHEN resumption controls are added with partial progress
        val progress = 0.5
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
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
        whenever(mediaFlags.isResumeProgressEnabled()).thenReturn(true)

        // WHEN resumption controls are added that have not been played
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
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
        whenever(mediaFlags.isResumeProgressEnabled()).thenReturn(true)

        // WHEN resumption controls are added with progress info
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
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
        whenever(mediaFlags.isResumeProgressEnabled()).thenReturn(true)

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
        whenever(mediaFlags.isResumeProgressEnabled()).thenReturn(true)

        // WHEN resumption controls are added that have empty title
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_EMPTY_TITLE)
                build()
            }
        mediaDataManager.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME
        )

        // Resumption controls are not added.
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(0)
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
    }

    @Test
    fun testAddResumptionControls_hasBlankTitle() {
        whenever(mediaFlags.isResumeProgressEnabled()).thenReturn(true)

        // WHEN resumption controls are added that have a blank title
        val desc =
            MediaDescription.Builder().run {
                setTitle(SESSION_BLANK_TITLE)
                build()
            }
        mediaDataManager.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME
        )

        // Resumption controls are not added.
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(0)
        verify(listener, never())
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        mediaDataManager.setMediaResumptionEnabled(false)

        // THEN the resume controls are dismissed
        verify(listener).onMediaDataRemoved(eq(PACKAGE_NAME))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testDismissMedia_listenerCalled() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val removed = mediaDataManager.dismissMediaData(KEY, 0L)
        assertThat(removed).isTrue()

        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()

        verify(listener).onMediaDataRemoved(eq(KEY))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testDismissMedia_keyDoesNotExist_returnsFalse() {
        val removed = mediaDataManager.dismissMediaData(KEY, 0L)
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
        mediaDataManager.onNotificationAdded(KEY, notif)

        // THEN it still loads
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
                eq(false)
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
                    EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = true,
                        dismissIntent = DISMISS_INTENT,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false)
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
                    EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                        targetId = KEY_MEDIA_SMARTSPACE,
                        isActive = true,
                        dismissIntent = null,
                        headphoneConnectionTimeMillis = SMARTSPACE_CREATION_TIME,
                        instanceId = InstanceId.fakeInstanceId(instanceId),
                        expiryTimeMs = SMARTSPACE_EXPIRY_TIME,
                    )
                ),
                eq(false)
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
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
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
                eq(false)
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_persistentEnabled_periodicTrigger_notActive() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
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
                eq(false)
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_persistentEnabled_noTargets_inactive() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)

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
                eq(false)
            )
        verify(listener, never()).onSmartspaceMediaDataRemoved(eq(KEY_MEDIA_SMARTSPACE), eq(false))
    }

    @Test
    fun testSetRecommendationInactive_notifiesListeners() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        val instanceId = instanceIdSequence.lastInstanceId

        mediaDataManager.setRecommendationInactive(KEY_MEDIA_SMARTSPACE)
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
                eq(false)
            )
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_settingDisabled_doesNothing() {
        // WHEN media recommendation setting is off
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            0
        )
        tunableCaptor.value.onTuningChanged(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, "0")

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
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
            0
        )
        tunableCaptor.value.onTuningChanged(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, "0")

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
    fun testOnMediaDataTimedOut_doesNotUpdateLastActiveTime() {
        // GIVEN that the manager has a notification
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)

        // WHEN the notification times out
        clock.advanceTime(100)
        val currentTime = clock.elapsedRealtime()
        mediaDataManager.setTimedOut(KEY, true, true)

        // THEN the last active time is not changed
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.lastActive).isLessThan(currentTime)
    }

    @Test
    fun testOnActiveMediaConverted_doesNotUpdateLastActiveTime() {
        // GIVEN that the manager has a notification with a resume action
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val instanceId = data.instanceId
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))

        // WHEN the notification is removed
        clock.advanceTime(100)
        val currentTime = clock.elapsedRealtime()
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN the last active time is not changed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        mediaDataManager.onNotificationAdded(KEY, notif)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)

        // THEN only the first MAX_COMPACT_ACTIONS are actually set
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.actionsToShowInCompact.size)
            .isEqualTo(MediaDataManager.MAX_COMPACT_ACTIONS)
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
                    for (i in 0..MediaDataManager.MAX_NOTIFICATION_ACTIONS) {
                        it.addAction(action)
                    }
                }
                build()
            }

        // WHEN the notification is loaded
        mediaDataManager.onNotificationAdded(KEY, notif)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)

        // THEN only the first MAX_NOTIFICATION_ACTIONS are actually included
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.actions.size)
            .isEqualTo(MediaDataManager.MAX_NOTIFICATION_ACTIONS)
    }

    @Test
    fun testPlaybackActions_noState_usesNotification() {
        val desc = "Notification Action"
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
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
        mediaDataManager.onNotificationAdded(KEY, notifWithAction)

        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )

        assertThat(mediaDataCaptor.value!!.semanticActions).isNull()
        assertThat(mediaDataCaptor.value!!.actions).hasSize(1)
        assertThat(mediaDataCaptor.value!!.actions[0]!!.contentDescription).isEqualTo(desc)
    }

    @Test
    fun testPlaybackActions_hasPrevNext() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4")
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
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
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
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
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
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
    fun testPlaybackActions_reservedSpace() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4")
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
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
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
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
                eq(MediaData.PLAYBACK_CAST_LOCAL)
            )

        // update to remote cast
        mediaDataManager.onNotificationAdded(KEY, remoteCastNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(logger)
            .logPlaybackLocationChange(
                anyInt(),
                eq(SYSTEM_PACKAGE_NAME),
                eq(instanceId),
                eq(MediaData.PLAYBACK_CAST_REMOTE)
            )
    }

    @Test
    fun testPlaybackStateChange_keyExists_callsListener() {
        // Notification has been added
        addNotificationAndLoad()

        // Callback gets an updated state
        val state = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build()
        stateCallbackCaptor.value.invoke(KEY, state)

        // Listener is notified of updated state
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.isPlaying).isTrue()
    }

    @Test
    fun testPlaybackStateChange_keyDoesNotExist_doesNothing() {
        val state = PlaybackState.Builder().build()

        // No media added with this key

        stateCallbackCaptor.value.invoke(KEY, state)
        verify(listener, never())
            .onMediaDataLoaded(eq(KEY), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testPlaybackStateChange_keyHasNullToken_doesNothing() {
        // When we get an update that sets the data's token to null
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(token = null))

        // And then get a state update
        val state = PlaybackState.Builder().build()

        // Then no changes are made
        stateCallbackCaptor.value.invoke(KEY, state)
        verify(listener, never())
            .onMediaDataLoaded(eq(KEY), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testPlaybackState_PauseWhenFlagTrue_keyExists_callsListener() {
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        val state = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 1f).build()
        whenever(controller.playbackState).thenReturn(state)

        addNotificationAndLoad()
        stateCallbackCaptor.value.invoke(KEY, state)

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        mediaDataManager.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            PACKAGE_NAME
        )
        backgroundExecutor.runAllReady()
        foregroundExecutor.runAllReady()

        stateCallbackCaptor.value.invoke(PACKAGE_NAME, state)

        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(PACKAGE_NAME),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        stateCallbackCaptor.value.invoke(KEY, state)

        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)

        // When a media control based on notification is added, times out, and then removed
        addNotificationAndLoad()
        mediaDataManager.setTimedOut(KEY, timedOut = true)
        assertThat(mediaDataCaptor.value.active).isFalse()
        mediaDataManager.onNotificationRemoved(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId)
            )
    }

    @Test
    fun testRetain_notifPlayer_sessionDestroyed_doesNotChange() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)

        // When a media control based on notification is added and times out
        addNotificationAndLoad()
        mediaDataManager.setTimedOut(KEY, timedOut = true)
        assertThat(mediaDataCaptor.value.active).isFalse()

        // and then the session is destroyed
        sessionCallbackCaptor.value.invoke(KEY)

        // It remains as a regular player
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_notifPlayer_removeWhileActive_fullyRemoved() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)

        // When a media control based on notification is added and then removed, without timing out
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataManager.onNotificationRemoved(KEY)

        // It is fully removed
        verify(listener).onMediaDataRemoved(eq(KEY))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_canResume_removeWhileActive_setToResume() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)

        // When a media control that supports resumption is added
        addNotificationAndLoad()
        val dataResumable = mediaDataCaptor.value.copy(resumeAction = Runnable {})
        mediaDataManager.onMediaDataLoaded(KEY, null, dataResumable)

        // And then removed while still active
        mediaDataManager.onNotificationRemoved(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId)
            )
    }

    @Test
    fun testRetain_sessionPlayer_notifRemoved_doesNotChange() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        addPlaybackStateAction()

        // When a media control with PlaybackState actions is added, times out,
        // and then the notification is removed
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataManager.setTimedOut(KEY, timedOut = true)
        mediaDataManager.onNotificationRemoved(KEY)

        // It remains as a regular player
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_sessionPlayer_sessionDestroyed_setToResume() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        addPlaybackStateAction()

        // When a media control with PlaybackState actions is added, times out,
        // and then the session is destroyed
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        mediaDataManager.setTimedOut(KEY, timedOut = true)
        sessionCallbackCaptor.value.invoke(KEY)

        // It is converted to a resume player
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME),
                eq(KEY),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId)
            )
    }

    @Test
    fun testRetain_sessionPlayer_destroyedWhileActive_noResume_fullyRemoved() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        addPlaybackStateAction()

        // When a media control using session actions is added, and then the session is destroyed
        // without timing out first
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()
        sessionCallbackCaptor.value.invoke(KEY)

        // It is fully removed
        verify(listener).onMediaDataRemoved(eq(KEY))
        verify(logger).logMediaRemoved(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
        verify(listener, never())
            .onMediaDataLoaded(eq(PACKAGE_NAME), any(), any(), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun testRetain_sessionPlayer_canResume_destroyedWhileActive_setToResume() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        addPlaybackStateAction()

        // When a media control using session actions and that does allow resumption is added,
        addNotificationAndLoad()
        val dataResumable = mediaDataCaptor.value.copy(resumeAction = Runnable {})
        mediaDataManager.onMediaDataLoaded(KEY, null, dataResumable)

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
                eq(false)
            )
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.active).isFalse()
        verify(logger)
            .logActiveConvertedToResume(
                anyInt(),
                eq(PACKAGE_NAME),
                eq(mediaDataCaptor.value.instanceId)
            )
    }

    @Test
    fun testSessionDestroyed_noNotificationKey_stillRemoved() {
        whenever(mediaFlags.isRetainingPlayersEnabled()).thenReturn(true)
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)

        // When a notiifcation is added and then removed before it is fully processed
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        backgroundExecutor.runAllReady()
        mediaDataManager.onNotificationRemoved(KEY)

        // We still make sure to remove it
        verify(listener).onMediaDataRemoved(eq(KEY))
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
                    anyInt()
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
                    anyInt()
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

    /** Helper function to add a basic media notification and capture the resulting MediaData */
    private fun addNotificationAndLoad() {
        addNotificationAndLoad(mediaNotification)
    }

    /** Helper function to add the given notification and capture the resulting MediaData */
    private fun addNotificationAndLoad(sbn: StatusBarNotification) {
        mediaDataManager.onNotificationAdded(KEY, sbn)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
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
        packageName: String = PACKAGE_NAME
    ) {
        mediaDataManager.addResumptionControls(
            USER_ID,
            desc,
            Runnable {},
            session.sessionToken,
            APP_NAME,
            pendingIntent,
            packageName
        )
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)

        verify(listener)
            .onMediaDataLoaded(
                eq(packageName),
                eq(null),
                capture(mediaDataCaptor),
                eq(true),
                eq(0),
                eq(false)
            )
    }
}
