package com.android.systemui.media

import android.app.IUriGrantsManager
import android.app.Notification.MediaStyle
import android.app.PendingIntent
import android.app.UriGrantsManager
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
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
import org.junit.Ignore
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
import org.mockito.MockitoSession
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever
import org.mockito.quality.Strictness

private const val KEY = "KEY"
private const val KEY_2 = "KEY_2"
private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
private const val PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "com.android.systemui.tests"
private const val SESSION_ARTIST = "artist"
private const val SESSION_TITLE = "title"
private const val SESSION_BLANK_TITLE = " "
private const val SESSION_EMPTY_TITLE = ""
private const val USER_ID = 0

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
    @Mock lateinit var playbackInfo: MediaController.PlaybackInfo
    lateinit var session: MediaSession
    lateinit var metadataBuilder: MediaMetadata.Builder
    lateinit var backgroundExecutor: FakeExecutor
    lateinit var foregroundExecutor: FakeExecutor
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
    lateinit var smartspaceMediaDataProvider: SmartspaceMediaDataProvider
    @Mock lateinit var mediaSmartspaceTarget: SmartspaceTarget
    @Mock private lateinit var mediaRecommendationItem: SmartspaceAction
    lateinit var mediaDataManager: MediaDataManager
    lateinit var mediaNotification: StatusBarNotification
    @Captor lateinit var mediaDataCaptor: ArgumentCaptor<MediaData>
    private val clock = FakeSystemClock()
    @Mock private lateinit var tunerService: TunerService
    @Captor lateinit var tunableCaptor: ArgumentCaptor<TunerService.Tunable>
    @Mock private lateinit var ugm: IUriGrantsManager
    @Mock private lateinit var imageSource: ImageDecoder.Source

    private val originalSmartspaceSetting = Settings.Secure.getInt(context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 1)

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
        smartspaceMediaDataProvider = SmartspaceMediaDataProvider()
        Settings.Secure.putInt(context.contentResolver,
                Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 1)
        mediaDataManager = MediaDataManager(
            context = context,
            backgroundExecutor = backgroundExecutor,
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
            tunerService = tunerService
        )
        verify(tunerService).addTunable(capture(tunableCaptor),
                eq(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION))
        session = MediaSession(context, "MediaDataManagerTestSession")
        mediaNotification = SbnBuilder().run {
            setPkg(PACKAGE_NAME)
            modifyNotification(context).also {
                it.setSmallIcon(android.R.drawable.ic_media_pause)
                it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
            }
            build()
        }
        metadataBuilder = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
            putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
        }
        whenever(mediaControllerFactory.create(eq(session.sessionToken))).thenReturn(controller)
        whenever(controller.playbackInfo).thenReturn(playbackInfo)
        whenever(playbackInfo.playbackType).thenReturn(
                MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)

        // This is an ugly hack for now. The mediaSessionBasedFilter is one of the internal
        // listeners in the internal processing pipeline. It receives events, but ince it is a
        // mock, it doesn't pass those events along the chain to the external listeners. So, just
        // treat mediaSessionBasedFilter as a listener for testing.
        listener = mediaSessionBasedFilter

        val recommendationExtras = Bundle()
        recommendationExtras.putString("package_name", PACKAGE_NAME)
        whenever(mediaRecommendationItem.extras).thenReturn(recommendationExtras)
        whenever(mediaSmartspaceTarget.smartspaceTargetId).thenReturn(KEY_MEDIA_SMARTSPACE)
        whenever(mediaSmartspaceTarget.featureType).thenReturn(SmartspaceTarget.FEATURE_MEDIA)
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf(mediaRecommendationItem))
    }

    @After
    fun tearDown() {
        staticMockSession.finishMocking()
        session.release()
        mediaDataManager.destroy()
        Settings.Secure.putInt(context.contentResolver,
                Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, originalSmartspaceSetting)
    }

    @Test
    fun testSetTimedOut_active_deactivatesMedia() {
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.active).isTrue()

        mediaDataManager.setTimedOut(KEY, timedOut = true)
        assertThat(data.active).isFalse()
    }

    @Test
    fun testLoadsMetadataOnBackground() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun testOnMetaDataLoaded_callsListener() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), anyObject(), eq(true),
                eq(false))
    }

    @Test
    fun testOnMetaDataLoaded_conservesActiveFlag() {
        whenever(mediaControllerFactory.create(anyObject())).thenReturn(controller)
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
        assertThat(mediaDataCaptor.value!!.active).isTrue()
    }

    @Test
    fun testOnNotificationRemoved_callsListener() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        mediaDataManager.onNotificationRemoved(KEY)
        verify(listener).onMediaDataRemoved(eq(KEY))
    }

    @Ignore("b/301081714")
    @Test
    fun testOnNotificationAdded_emptyTitle_hasPlaceholder() {
        // When the manager has a notification with an empty title
        val listener = mock(MediaDataManager.Listener::class.java)
        mediaDataManager.addListener(listener)
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
                capture(mediaDataCaptor)
            )
        val placeholderTitle = context.getString(R.string.controls_media_empty_title, APP_NAME)
        assertThat(mediaDataCaptor.value.song).isEqualTo(placeholderTitle)
    }

    @Ignore("b/301081714")
    @Test
    fun testOnNotificationAdded_blankTitle_hasPlaceholder() {
        // GIVEN that the manager has a notification with a blank title
        val listener = mock(MediaDataManager.Listener::class.java)
        mediaDataManager.addListener(listener)
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
                capture(mediaDataCaptor)
            )
        val placeholderTitle = context.getString(R.string.controls_media_empty_title, APP_NAME)
        assertThat(mediaDataCaptor.value.song).isEqualTo(placeholderTitle)
    }

    @Ignore("b/301081714")
    @Test
    fun testOnNotificationAdded_emptyMetadata_usesNotificationTitle() {
        // When the app sets the metadata title fields to empty strings, but does include a
        // non-blank notification title
        val listener = mock(MediaDataManager.Listener::class.java)
        mediaDataManager.addListener(listener)
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
                capture(mediaDataCaptor)
            )
        assertThat(mediaDataCaptor.value.song).isEqualTo(SESSION_TITLE)
    }

    @Test
    fun testOnNotificationRemoved_withResumption() {
        // GIVEN that the manager has a notification with a resume action
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the media data indicates that it is for resumption
        verify(listener)
            .onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor), eq(true),
                    eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
    }

    @Test
    fun testOnNotificationRemoved_twoWithResumption() {
        // GIVEN that the manager has two notifications with resume actions
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onNotificationAdded(KEY_2, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(2)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(2)
        verify(listener)
            .onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                    eq(false))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        val resumableData = data.copy(resumeAction = Runnable {})
        mediaDataManager.onMediaDataLoaded(KEY, null, resumableData)
        mediaDataManager.onMediaDataLoaded(KEY_2, null, resumableData)
        reset(listener)
        // WHEN the first is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the data is for resumption and the key is migrated to the package name
        verify(listener)
            .onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor), eq(true),
                    eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
        // WHEN the second is removed
        mediaDataManager.onNotificationRemoved(KEY_2)
        // THEN the data is for resumption and the second key is removed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME), eq(PACKAGE_NAME), capture(mediaDataCaptor), eq(true),
                    eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener).onMediaDataRemoved(eq(KEY_2))
    }

    @Test
    fun testOnNotificationRemoved_withResumption_butNotLocal() {
        // GIVEN that the manager has a notification with a resume action, but is not local
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        whenever(playbackInfo.playbackType).thenReturn(
                MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
        val data = mediaDataCaptor.value
        val dataRemoteWithResume = data.copy(resumeAction = Runnable {}, isLocalSession = false)
        mediaDataManager.onMediaDataLoaded(KEY, null, dataRemoteWithResume)

        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN the media data is removed
        verify(listener).onMediaDataRemoved(eq(KEY))
    }

    @Test
    fun testAddResumptionControls() {
        // WHEN resumption controls are added
        val desc = MediaDescription.Builder().run {
            setTitle(SESSION_TITLE)
            build()
        }
        val currentTime = clock.elapsedRealtime()
        mediaDataManager.addResumptionControls(USER_ID, desc, Runnable {}, session.sessionToken,
                APP_NAME, pendingIntent, PACKAGE_NAME)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        // THEN the media data indicates that it is for resumption
        verify(listener)
            .onMediaDataLoaded(eq(PACKAGE_NAME), eq(null), capture(mediaDataCaptor), eq(true),
                    eq(false))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.song).isEqualTo(SESSION_TITLE)
        assertThat(data.app).isEqualTo(APP_NAME)
        assertThat(data.actions).hasSize(1)
        assertThat(data.lastActive).isAtLeast(currentTime)
    }

    @Test
    fun testDismissMedia_listenerCalled() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        val removed = mediaDataManager.dismissMediaData(KEY, 0L)
        assertThat(removed).isTrue()

        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()

        verify(listener).onMediaDataRemoved(eq(KEY))
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
        val notif = SbnBuilder().run {
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
            .onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                    eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNewValidMediaTarget_callsListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(listener).onSmartspaceMediaDataLoaded(
            eq(KEY_MEDIA_SMARTSPACE),
            eq(SmartspaceMediaData(KEY_MEDIA_SMARTSPACE, true /* isActive */, true /*isValid */,
            PACKAGE_NAME, null, listOf(mediaRecommendationItem), 0)),
            eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNewInvalidMediaTarget_callsListener() {
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf())
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(listener).onSmartspaceMediaDataLoaded(
            eq(KEY_MEDIA_SMARTSPACE),
            eq(EMPTY_SMARTSPACE_MEDIA_DATA
                .copy(targetId = KEY_MEDIA_SMARTSPACE, isActive = true, isValid = false)),
            eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNoneMediaTarget_notCallsListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf())
        verify(listener, never())
                .onSmartspaceMediaDataLoaded(anyObject(), anyObject(), anyBoolean())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNoneMediaTarget_callsRemoveListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        smartspaceMediaDataProvider.onTargetsAvailable(listOf())
        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()

        verify(listener).onSmartspaceMediaDataRemoved(eq(KEY_MEDIA_SMARTSPACE), eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_settingDisabled_doesNothing() {
        // WHEN media recommendation setting is off
        Settings.Secure.putInt(context.contentResolver,
                Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 0)
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
        verify(listener).onSmartspaceMediaDataLoaded(eq(KEY_MEDIA_SMARTSPACE), anyObject(),
                anyBoolean())

        // WHEN the media recommendation setting is turned off
        Settings.Secure.putInt(context.contentResolver,
                Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 0)
        tunableCaptor.value.onTuningChanged(Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, "0")

        // THEN listeners are notified
        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()
        verify(listener).onSmartspaceMediaDataRemoved(eq(KEY_MEDIA_SMARTSPACE), eq(true))
    }

    @Test
    fun testOnMediaDataChanged_updatesLastActiveTime() {
        val currentTime = clock.elapsedRealtime()
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
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
        verify(listener).onMediaDataLoaded(eq(KEY), eq(KEY), capture(mediaDataCaptor), eq(true),
                eq(false))
        assertThat(mediaDataCaptor.value.lastActive).isLessThan(currentTime)
    }

    @Test
    fun testOnActiveMediaConverted_doesNotUpdateLastActiveTime() {
        // GIVEN that the manager has a notification with a resume action
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))

        // WHEN the notification is removed
        clock.advanceTime(100)
        val currentTime = clock.elapsedRealtime()
        mediaDataManager.onNotificationRemoved(KEY)

        // THEN the last active time is not changed
        verify(listener)
            .onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor), eq(true),
                    eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.lastActive).isLessThan(currentTime)
    }

    @Test
    fun testTooManyCompactActions_isTruncated() {
        // GIVEN a notification where too many compact actions were specified
        val notif = SbnBuilder().run {
            setPkg(PACKAGE_NAME)
            modifyNotification(context).also {
                it.setSmallIcon(android.R.drawable.ic_media_pause)
                it.setStyle(MediaStyle().apply {
                    setMediaSession(session.sessionToken)
                    setShowActionsInCompactView(0, 1, 2, 3, 4)
                })
            }
            build()
        }

        // WHEN the notification is loaded
        mediaDataManager.onNotificationAdded(KEY, notif)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)

        // THEN only the first MAX_COMPACT_ACTIONS are actually set
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
        assertThat(mediaDataCaptor.value.actionsToShowInCompact.size).isEqualTo(
                MediaDataManager.MAX_COMPACT_ACTIONS)
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

    /**
     * Helper function to add a media notification and capture the resulting MediaData
     */
    private fun addNotificationAndLoad() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(false))
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
                eq(false)
            )
    }
}
