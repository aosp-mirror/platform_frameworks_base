package com.android.systemui.media

import android.app.Notification.MediaStyle
import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.media.utils.MediaConstants
import androidx.test.filters.SmallTest
import com.android.systemui.InstanceIdSequenceFake
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

private const val KEY = "KEY"
private const val KEY_2 = "KEY_2"
private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
private const val PACKAGE_NAME = "com.example.app"
private const val SYSTEM_PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "SystemUI"
private const val SESSION_ARTIST = "artist"
private const val SESSION_TITLE = "title"
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
    @Mock private lateinit var mediaSmartspaceBaseAction: SmartspaceAction
    @Mock private lateinit var mediaFlags: MediaFlags
    @Mock private lateinit var logger: MediaUiEventLogger
    lateinit var mediaDataManager: MediaDataManager
    lateinit var mediaNotification: StatusBarNotification
    @Captor lateinit var mediaDataCaptor: ArgumentCaptor<MediaData>
    private val clock = FakeSystemClock()
    @Mock private lateinit var tunerService: TunerService
    @Captor lateinit var tunableCaptor: ArgumentCaptor<TunerService.Tunable>

    private val instanceIdSequence = InstanceIdSequenceFake(1 shl 20)

    private val originalSmartspaceSetting = Settings.Secure.getInt(context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 1)

    @Before
    fun setup() {
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
            tunerService = tunerService,
            mediaFlags = mediaFlags,
            logger = logger
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
        whenever(controller.transportControls).thenReturn(transportControls)
        whenever(controller.playbackInfo).thenReturn(playbackInfo)
        whenever(playbackInfo.playbackType).thenReturn(
                MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)

        // This is an ugly hack for now. The mediaSessionBasedFilter is one of the internal
        // listeners in the internal processing pipeline. It receives events, but ince it is a
        // mock, it doesn't pass those events along the chain to the external listeners. So, just
        // treat mediaSessionBasedFilter as a listener for testing.
        listener = mediaSessionBasedFilter

        val recommendationExtras = Bundle().apply {
            putString("package_name", PACKAGE_NAME)
            putParcelable("dismiss_intent", DISMISS_INTENT)
        }
        whenever(mediaSmartspaceBaseAction.extras).thenReturn(recommendationExtras)
        whenever(mediaSmartspaceTarget.baseAction).thenReturn(mediaSmartspaceBaseAction)
        whenever(mediaRecommendationItem.extras).thenReturn(recommendationExtras)
        whenever(mediaSmartspaceTarget.smartspaceTargetId).thenReturn(KEY_MEDIA_SMARTSPACE)
        whenever(mediaSmartspaceTarget.featureType).thenReturn(SmartspaceTarget.FEATURE_MEDIA)
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf(mediaRecommendationItem))
        whenever(mediaSmartspaceTarget.creationTimeMillis).thenReturn(1234L)
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(false)
        whenever(logger.getNewInstanceId()).thenReturn(instanceIdSequence.newInstanceId())
    }

    @After
    fun tearDown() {
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
        verify(logger).logMediaTimeout(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
    }

    @Test
    fun testSetTimedOut_resume_dismissesMedia() {
        // WHEN resume controls are present, and time out
        val desc = MediaDescription.Builder().run {
            setTitle(SESSION_TITLE)
            build()
        }
        mediaDataManager.addResumptionControls(USER_ID, desc, Runnable {}, session.sessionToken,
                APP_NAME, pendingIntent, PACKAGE_NAME)

        backgroundExecutor.runAllReady()
        foregroundExecutor.runAllReady()
        verify(listener).onMediaDataLoaded(eq(PACKAGE_NAME), eq(null), capture(mediaDataCaptor),
            eq(true), eq(0), eq(false))

        mediaDataManager.setTimedOut(PACKAGE_NAME, timedOut = true)
        verify(logger).logMediaTimeout(anyInt(), eq(PACKAGE_NAME),
            eq(mediaDataCaptor.value.instanceId))

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
    fun testOnMetaDataLoaded_callsListener() {
        addNotificationAndLoad()
        verify(logger).logActiveMediaAdded(anyInt(), eq(PACKAGE_NAME),
            eq(mediaDataCaptor.value.instanceId), eq(MediaData.PLAYBACK_LOCAL))
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
                eq(0), eq(false))
        assertThat(mediaDataCaptor.value!!.active).isTrue()
    }

    @Test
    fun testOnNotificationAdded_isRcn_markedRemote() {
        val rcn = SbnBuilder().run {
            setPkg(SYSTEM_PACKAGE_NAME)
            modifyNotification(context).also {
                it.setSmallIcon(android.R.drawable.ic_media_pause)
                it.setStyle(MediaStyle().apply {
                    setMediaSession(session.sessionToken)
                    setRemotePlaybackInfo("Remote device", 0, null)
                })
            }
            build()
        }

        mediaDataManager.onNotificationAdded(KEY, rcn)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(0), eq(false))
        assertThat(mediaDataCaptor.value!!.playbackLocation).isEqualTo(
                MediaData.PLAYBACK_CAST_REMOTE)
        verify(logger).logActiveMediaAdded(anyInt(), eq(SYSTEM_PACKAGE_NAME),
            eq(mediaDataCaptor.value.instanceId), eq(MediaData.PLAYBACK_CAST_REMOTE))
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
    fun testOnNotificationRemoved_withResumption() {
        // GIVEN that the manager has a notification with a resume action
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the media data indicates that it is for resumption
        verify(listener)
            .onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor), eq(true),
                    eq(0), eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.isPlaying).isFalse()
        verify(logger).logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(data.instanceId))
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
                    eq(0), eq(false))
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
                    eq(0), eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
        // WHEN the second is removed
        mediaDataManager.onNotificationRemoved(KEY_2)
        // THEN the data is for resumption and the second key is removed
        verify(listener)
            .onMediaDataLoaded(
                eq(PACKAGE_NAME), eq(PACKAGE_NAME), capture(mediaDataCaptor), eq(true),
                    eq(0), eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener).onMediaDataRemoved(eq(KEY_2))
    }

    @Test
    fun testOnNotificationRemoved_withResumption_butNotLocal() {
        // GIVEN that the manager has a notification with a resume action, but is not local
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        whenever(playbackInfo.playbackType).thenReturn(
                MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        addNotificationAndLoad()
        val data = mediaDataCaptor.value
        val dataRemoteWithResume = data.copy(resumeAction = Runnable {},
                playbackLocation = MediaData.PLAYBACK_CAST_LOCAL)
        mediaDataManager.onMediaDataLoaded(KEY, null, dataRemoteWithResume)
        verify(logger).logActiveMediaAdded(anyInt(), eq(PACKAGE_NAME),
            eq(mediaDataCaptor.value.instanceId), eq(MediaData.PLAYBACK_CAST_LOCAL))

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
                    eq(0), eq(false))
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
    fun testResumptionDisabled_dismissesResumeControls() {
        // WHEN there are resume controls and resumption is switched off
        val desc = MediaDescription.Builder().run {
            setTitle(SESSION_TITLE)
            build()
        }
        mediaDataManager.addResumptionControls(USER_ID, desc, Runnable {}, session.sessionToken,
            APP_NAME, pendingIntent, PACKAGE_NAME)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(PACKAGE_NAME), eq(null), capture(mediaDataCaptor),
            eq(true), eq(0), eq(false))
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
                    eq(0), eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNewValidMediaTarget_callsListener() {
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(listener).onSmartspaceMediaDataLoaded(
            eq(KEY_MEDIA_SMARTSPACE),
            eq(SmartspaceMediaData(KEY_MEDIA_SMARTSPACE, true /* isActive */, true /*isValid */,
                PACKAGE_NAME, mediaSmartspaceBaseAction, listOf(mediaRecommendationItem),
                DISMISS_INTENT, 0, 1234L)),
            eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNewInvalidMediaTarget_callsListener() {
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf())
        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))
        verify(listener).onSmartspaceMediaDataLoaded(
            eq(KEY_MEDIA_SMARTSPACE),
            eq(EMPTY_SMARTSPACE_MEDIA_DATA
                .copy(targetId = KEY_MEDIA_SMARTSPACE, isActive = true,
                    isValid = false, dismissIntent = DISMISS_INTENT,
                headphoneConnectionTimeMillis = 1234L)),
            eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasNullIntent_callsListener() {
        val recommendationExtras = Bundle().apply {
            putString("package_name", PACKAGE_NAME)
            putParcelable("dismiss_intent", null)
        }
        whenever(mediaSmartspaceBaseAction.extras).thenReturn(recommendationExtras)
        whenever(mediaSmartspaceTarget.baseAction).thenReturn(mediaSmartspaceBaseAction)
        whenever(mediaSmartspaceTarget.iconGrid).thenReturn(listOf())

        smartspaceMediaDataProvider.onTargetsAvailable(listOf(mediaSmartspaceTarget))

        verify(listener).onSmartspaceMediaDataLoaded(
            eq(KEY_MEDIA_SMARTSPACE),
            eq(EMPTY_SMARTSPACE_MEDIA_DATA
                .copy(targetId = KEY_MEDIA_SMARTSPACE, isActive = true,
                    isValid = false, dismissIntent = null, headphoneConnectionTimeMillis = 1234L)),
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
        verify(listener).onMediaDataLoaded(eq(KEY), eq(KEY), capture(mediaDataCaptor), eq(true),
                eq(0), eq(false))
        assertThat(mediaDataCaptor.value.lastActive).isLessThan(currentTime)
    }

    @Test
    fun testOnActiveMediaConverted_doesNotUpdateLastActiveTime() {
        // GIVEN that the manager has a notification with a resume action
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
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
            .onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor), eq(true),
                    eq(0), eq(false))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        assertThat(mediaDataCaptor.value.lastActive).isLessThan(currentTime)

        // Log as a conversion event, not as a new resume control
        verify(logger).logActiveConvertedToResume(anyInt(), eq(PACKAGE_NAME), eq(instanceId))
        verify(logger, never()).logResumeMediaAdded(anyInt(), eq(PACKAGE_NAME), any())
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
                eq(0), eq(false))
        assertThat(mediaDataCaptor.value.actionsToShowInCompact.size).isEqualTo(
                MediaDataManager.MAX_COMPACT_ACTIONS)
    }

    @Test
    fun testPlaybackActions_noState_usesNotification() {
        val desc = "Notification Action"
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        whenever(controller.playbackState).thenReturn(null)

        val notifWithAction = SbnBuilder().run {
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
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
                eq(0), eq(false))

        assertThat(mediaDataCaptor.value!!.semanticActions).isNull()
        assertThat(mediaDataCaptor.value!!.actions).hasSize(1)
        assertThat(mediaDataCaptor.value!!.actions[0]!!.contentDescription).isEqualTo(desc)
    }

    @Test
    fun testPlaybackActions_hasPrevNext() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4")
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        val stateActions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT
        val stateBuilder = PlaybackState.Builder()
                .setActions(stateActions)
        customDesc.forEach {
            stateBuilder.addCustomAction("action: $it", it, android.R.drawable.ic_media_pause)
        }
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription).isEqualTo(
                context.getString(R.string.controls_media_button_play))
        actions.playOrPause!!.action!!.run()
        verify(transportControls).play()

        assertThat(actions.prevOrCustom).isNotNull()
        assertThat(actions.prevOrCustom!!.contentDescription).isEqualTo(
                context.getString(R.string.controls_media_button_prev))
        actions.prevOrCustom!!.action!!.run()
        verify(transportControls).skipToPrevious()

        assertThat(actions.nextOrCustom).isNotNull()
        assertThat(actions.nextOrCustom!!.contentDescription).isEqualTo(
                context.getString(R.string.controls_media_button_next))
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
        val stateBuilder = PlaybackState.Builder()
                .setActions(stateActions)
        customDesc.forEach {
            stateBuilder.addCustomAction("action: $it", it, android.R.drawable.ic_media_pause)
        }
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription).isEqualTo(
                context.getString(R.string.controls_media_button_play))

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
        val stateBuilder = PlaybackState.Builder()
                .setState(PlaybackState.STATE_BUFFERING, 0, 10f)
                .setActions(stateActions)
        whenever(controller.playbackState).thenReturn(stateBuilder.build())

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription).isEqualTo(
                context.getString(R.string.controls_media_button_connecting))
    }

    @Test
    fun testPlaybackActions_reservedSpace() {
        val customDesc = arrayOf("custom 1", "custom 2", "custom 3", "custom 4")
        whenever(mediaFlags.areMediaSessionActionsEnabled(any(), any())).thenReturn(true)
        val stateActions = PlaybackState.ACTION_PLAY
        val stateBuilder = PlaybackState.Builder()
                .setActions(stateActions)
        customDesc.forEach {
            stateBuilder.addCustomAction("action: $it", it, android.R.drawable.ic_media_pause)
        }
        val extras = Bundle().apply {
            putBoolean(MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV, true)
            putBoolean(MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT, true)
        }
        whenever(controller.playbackState).thenReturn(stateBuilder.build())
        whenever(controller.extras).thenReturn(extras)

        addNotificationAndLoad()

        assertThat(mediaDataCaptor.value!!.semanticActions).isNotNull()
        val actions = mediaDataCaptor.value!!.semanticActions!!

        assertThat(actions.playOrPause).isNotNull()
        assertThat(actions.playOrPause!!.contentDescription).isEqualTo(
                context.getString(R.string.controls_media_button_play))

        assertThat(actions.prevOrCustom).isNull()
        assertThat(actions.nextOrCustom).isNull()

        assertThat(actions.custom0).isNotNull()
        assertThat(actions.custom0!!.contentDescription).isEqualTo(customDesc[0])

        assertThat(actions.custom1).isNotNull()
        assertThat(actions.custom1!!.contentDescription).isEqualTo(customDesc[1])
    }

    @Test
    fun testPlaybackLocationChange_isLogged() {
        // Media control added for local playback
        addNotificationAndLoad()
        val instanceId = mediaDataCaptor.value.instanceId

        // Location is updated to local cast
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        whenever(playbackInfo.playbackType).thenReturn(
            MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        addNotificationAndLoad()
        verify(logger).logPlaybackLocationChange(anyInt(), eq(PACKAGE_NAME),
            eq(instanceId), eq(MediaData.PLAYBACK_CAST_LOCAL))

        // update to remote cast
        val rcn = SbnBuilder().run {
            setPkg(SYSTEM_PACKAGE_NAME) // System package
            modifyNotification(context).also {
                it.setSmallIcon(android.R.drawable.ic_media_pause)
                it.setStyle(MediaStyle().apply {
                    setMediaSession(session.sessionToken)
                    setRemotePlaybackInfo("Remote device", 0, null)
                })
            }
            build()
        }

        mediaDataManager.onNotificationAdded(KEY, rcn)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(logger).logPlaybackLocationChange(anyInt(), eq(SYSTEM_PACKAGE_NAME),
            eq(instanceId), eq(MediaData.PLAYBACK_CAST_REMOTE))
    }

    /**
     * Helper function to add a media notification and capture the resulting MediaData
     */
    private fun addNotificationAndLoad() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor), eq(true),
            eq(0), eq(false))
    }
}
