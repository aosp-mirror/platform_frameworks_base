package com.android.systemui.media

import android.app.Notification.MediaStyle
import android.app.PendingIntent
import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.util.concurrency.FakeExecutor
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
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

private const val KEY = "KEY"
private const val KEY_2 = "KEY_2"
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
    lateinit var mediaDataManager: MediaDataManager
    lateinit var mediaNotification: StatusBarNotification
    @Captor lateinit var mediaDataCaptor: ArgumentCaptor<MediaData>

    @Before
    fun setup() {
        foregroundExecutor = FakeExecutor(FakeSystemClock())
        backgroundExecutor = FakeExecutor(FakeSystemClock())
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
            useMediaResumption = true,
            useQsMediaPlayer = true
        )
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

        // This is an ugly hack for now. The mediaSessionBasedFilter is one of the internal
        // listeners in the internal processing pipeline. It receives events, but ince it is a
        // mock, it doesn't pass those events along the chain to the external listeners. So, just
        // treat mediaSessionBasedFilter as a listener for testing.
        listener = mediaSessionBasedFilter
    }

    @After
    fun tearDown() {
        session.release()
        mediaDataManager.destroy()
    }

    @Test
    fun testSetTimedOut_deactivatesMedia() {
        val data = MediaData(userId = USER_ID, initialized = true, backgroundColor = 0, app = null,
                appIcon = null, artist = null, song = null, artwork = null, actions = emptyList(),
                actionsToShowInCompact = emptyList(), packageName = "INVALID", token = null,
                clickIntent = null, device = null, active = true, resumeAction = null)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = data)

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
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), anyObject())
    }

    @Test
    fun testOnMetaDataLoaded_conservesActiveFlag() {
        whenever(mediaControllerFactory.create(anyObject())).thenReturn(controller)
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor))
        assertThat(mediaDataCaptor.value!!.active).isTrue()
    }

    @Test
    fun testOnNotificationRemoved_callsListener() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        mediaDataManager.onNotificationRemoved(KEY)
        verify(listener).onMediaDataRemoved(eq(KEY))
    }

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
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the media data indicates that it is for resumption
        verify(listener).onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor))
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
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isFalse()
        val resumableData = data.copy(resumeAction = Runnable {})
        mediaDataManager.onMediaDataLoaded(KEY, null, resumableData)
        mediaDataManager.onMediaDataLoaded(KEY_2, null, resumableData)
        reset(listener)
        // WHEN the first is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the data is for resumption and the key is migrated to the package name
        verify(listener).onMediaDataLoaded(eq(PACKAGE_NAME), eq(KEY), capture(mediaDataCaptor))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
        // WHEN the second is removed
        mediaDataManager.onNotificationRemoved(KEY_2)
        // THEN the data is for resumption and the second key is removed
        verify(listener).onMediaDataLoaded(eq(PACKAGE_NAME), eq(PACKAGE_NAME),
                capture(mediaDataCaptor))
        assertThat(mediaDataCaptor.value.resumption).isTrue()
        verify(listener).onMediaDataRemoved(eq(KEY_2))
    }

    @Test
    fun testAddResumptionControls() {
        // WHEN resumption controls are added`
        val desc = MediaDescription.Builder().run {
            setTitle(SESSION_TITLE)
            build()
        }
        mediaDataManager.addResumptionControls(USER_ID, desc, Runnable {}, session.sessionToken,
                APP_NAME, pendingIntent, PACKAGE_NAME)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        // THEN the media data indicates that it is for resumption
        verify(listener).onMediaDataLoaded(eq(PACKAGE_NAME), eq(null), capture(mediaDataCaptor))
        val data = mediaDataCaptor.value
        assertThat(data.resumption).isTrue()
        assertThat(data.song).isEqualTo(SESSION_TITLE)
        assertThat(data.app).isEqualTo(APP_NAME)
        assertThat(data.actions).hasSize(1)
    }

    @Test
    fun testDismissMedia_listenerCalled() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        mediaDataManager.dismissMediaData(KEY, 0L)

        foregroundExecutor.advanceClockToLast()
        foregroundExecutor.runAllReady()

        verify(listener).onMediaDataRemoved(eq(KEY))
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

        // THEN it loads and uses the default background color
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), capture(mediaDataCaptor))
        assertThat(mediaDataCaptor.value!!.backgroundColor).isEqualTo(DEFAULT_COLOR)
    }
}
