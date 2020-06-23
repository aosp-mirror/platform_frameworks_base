package com.android.systemui.media

import android.app.Notification.MediaStyle
import android.app.PendingIntent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val KEY = "KEY"
private const val PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "SystemUI"
private const val SESSION_ARTIST = "artist"
private const val SESSION_TITLE = "title"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class MediaDataManagerTest : SysuiTestCase() {

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
    @Mock lateinit var pendingIntent: PendingIntent
    @JvmField @Rule val mockito = MockitoJUnit.rule()
    lateinit var mediaDataManager: MediaDataManager
    lateinit var mediaNotification: StatusBarNotification

    @Before
    fun setup() {
        foregroundExecutor = FakeExecutor(FakeSystemClock())
        backgroundExecutor = FakeExecutor(FakeSystemClock())
        mediaDataManager = MediaDataManager(context, backgroundExecutor, foregroundExecutor,
                mediaControllerFactory, broadcastDispatcher, dumpManager,
                mediaTimeoutListener, mediaResumeListener, useMediaResumption = true,
                useQsMediaPlayer = true)
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
    }

    @After
    fun tearDown() {
        session.release()
        mediaDataManager.destroy()
    }

    @Test
    fun testHasActiveMedia() {
        assertThat(mediaDataManager.hasActiveMedia()).isFalse()
        val data = mock(MediaData::class.java)

        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = data)
        assertThat(mediaDataManager.hasActiveMedia()).isFalse()

        whenever(data.active).thenReturn(true)
        assertThat(mediaDataManager.hasActiveMedia()).isTrue()
    }

    @Test
    fun testLoadsMetadataOnBackground() {
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun testOnMetaDataLoaded_callsListener() {
        val listener = mock(MediaDataManager.Listener::class.java)
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), anyObject())
    }

    @Test
    fun testHasAnyMedia_whenAddingMedia() {
        assertThat(mediaDataManager.hasAnyMedia()).isFalse()
        val data = mock(MediaData::class.java)

        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = data)
        assertThat(mediaDataManager.hasAnyMedia()).isTrue()
    }

    @Test
    fun testOnNotificationRemoved_doesntHaveMedia() {
        val data = mock(MediaData::class.java)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = data)
        mediaDataManager.onNotificationRemoved(KEY)
        assertThat(mediaDataManager.hasAnyMedia()).isFalse()
    }

    @Test
    fun testOnNotificationRemoved_callsListener() {
        val listener = mock(MediaDataManager.Listener::class.java)
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        mediaDataManager.onNotificationRemoved(KEY)

        verify(listener).onMediaDataRemoved(eq(KEY))
    }

    @Test
    fun testOnNotificationRemoved_withResumption() {
        // GIVEN that the manager has a notification with a resume action
        val listener = TestListener()
        mediaDataManager.addListener(listener)
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        val data = listener.data!!
        assertThat(data.resumption).isFalse()
        mediaDataManager.onMediaDataLoaded(KEY, null, data.copy(resumeAction = Runnable {}))
        // WHEN the notification is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the media data indicates that it is
        assertThat(listener.data!!.resumption).isTrue()
    }

    @Test
    fun testAddResumptionControls() {
        val listener = TestListener()
        mediaDataManager.addListener(listener)
        // WHEN resumption controls are added`
        val desc = MediaDescription.Builder().run {
            setTitle(SESSION_TITLE)
            build()
        }
        mediaDataManager.addResumptionControls(desc, Runnable {}, session.sessionToken, APP_NAME,
                pendingIntent, PACKAGE_NAME)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        // THEN the media data indicates that it is for resumption
        val data = listener.data!!
        assertThat(data.resumption).isTrue()
        assertThat(data.song).isEqualTo(SESSION_TITLE)
        assertThat(data.app).isEqualTo(APP_NAME)
        assertThat(data.actions).hasSize(1)
    }

    /**
     * Simple implementation of [MediaDataManager.Listener] for the test.
     *
     * Giving up on trying to get a mock Listener and ArgumentCaptor to work.
     */
    private class TestListener : MediaDataManager.Listener {
        var data: MediaData? = null
        var key: String? = null
        var oldKey: String? = null

        override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
            this.key = key
            this.oldKey = oldKey
            this.data = data
        }

        override fun onMediaDataRemoved(key: String) {
            this.key = key
            oldKey = null
            data = null
        }
    }
}
