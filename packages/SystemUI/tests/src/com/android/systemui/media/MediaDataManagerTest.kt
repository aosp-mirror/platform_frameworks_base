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
import com.android.systemui.util.mockito.eq
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
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

private const val KEY = "KEY"
private const val KEY_2 = "KEY_2"
private const val PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "SystemUI"
private const val SESSION_ARTIST = "artist"
private const val SESSION_TITLE = "title"
private const val USER_ID = 0

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
        val listener = mock(MediaDataManager.Listener::class.java)
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onMediaDataLoaded(KEY, oldKey = null, data = mock(MediaData::class.java))
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), anyObject())
    }

    @Test
    fun testOnMetaDataLoaded_conservesActiveFlag() {
        val listener = TestListener()
        whenever(mediaControllerFactory.create(anyObject())).thenReturn(controller)
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.addListener(listener)
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(1)
        assertThat(listener.data!!.active).isTrue()
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
        // THEN the media data indicates that it is for resumption
        assertThat(listener.data!!.resumption).isTrue()
        // AND the new key is the package name
        assertThat(listener.key!!).isEqualTo(PACKAGE_NAME)
        assertThat(listener.oldKey!!).isEqualTo(KEY)
        assertThat(listener.removedKey).isNull()
    }

    @Test
    fun testOnNotificationRemoved_twoWithResumption() {
        // GIVEN that the manager has two notifications with resume actions
        val listener = TestListener()
        mediaDataManager.addListener(listener)
        whenever(controller.metadata).thenReturn(metadataBuilder.build())
        mediaDataManager.onNotificationAdded(KEY, mediaNotification)
        mediaDataManager.onNotificationAdded(KEY_2, mediaNotification)
        assertThat(backgroundExecutor.runAllReady()).isEqualTo(2)
        assertThat(foregroundExecutor.runAllReady()).isEqualTo(2)
        val data = listener.data!!
        assertThat(data.resumption).isFalse()
        val resumableData = data.copy(resumeAction = Runnable {})
        mediaDataManager.onMediaDataLoaded(KEY, null, resumableData)
        mediaDataManager.onMediaDataLoaded(KEY_2, null, resumableData)
        // WHEN the first is removed
        mediaDataManager.onNotificationRemoved(KEY)
        // THEN the data is for resumption and the key is migrated to the package name
        assertThat(listener.data!!.resumption).isTrue()
        assertThat(listener.key!!).isEqualTo(PACKAGE_NAME)
        assertThat(listener.oldKey!!).isEqualTo(KEY)
        assertThat(listener.removedKey).isNull()
        // WHEN the second is removed
        mediaDataManager.onNotificationRemoved(KEY_2)
        // THEN the data is for resumption and the second key is removed
        assertThat(listener.data!!.resumption).isTrue()
        assertThat(listener.key!!).isEqualTo(PACKAGE_NAME)
        assertThat(listener.oldKey!!).isEqualTo(PACKAGE_NAME)
        assertThat(listener.removedKey!!).isEqualTo(KEY_2)
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
        mediaDataManager.addResumptionControls(USER_ID, desc, Runnable {}, session.sessionToken,
                APP_NAME, pendingIntent, PACKAGE_NAME)
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
        var removedKey: String? = null

        override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
            this.key = key
            this.oldKey = oldKey
            this.data = data
        }

        override fun onMediaDataRemoved(key: String) {
            removedKey = key
        }
    }
}
