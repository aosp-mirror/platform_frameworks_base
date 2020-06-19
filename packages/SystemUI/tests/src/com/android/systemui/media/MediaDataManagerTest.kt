package com.android.systemui.media

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
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
import java.util.concurrent.Executor
import org.mockito.Mockito.`when` as whenever

private const val KEY = "KEY"
private const val PACKAGE_NAME = "com.android.systemui"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class MediaDataManagerTest : SysuiTestCase() {

    @Mock lateinit var mediaControllerFactory: MediaControllerFactory
    @Mock lateinit var backgroundExecutor: Executor
    @Mock lateinit var foregroundExecutor: Executor
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock lateinit var mediaTimeoutListener: MediaTimeoutListener
    @Mock lateinit var mediaResumeListener: MediaResumeListener
    @JvmField @Rule val mockito = MockitoJUnit.rule()
    lateinit var mediaDataManager: MediaDataManager
    lateinit var mediaNotification: StatusBarNotification

    @Before
    fun setup() {
        mediaDataManager = MediaDataManager(context, backgroundExecutor, foregroundExecutor,
                mediaControllerFactory, broadcastDispatcher, dumpManager,
                mediaTimeoutListener, mediaResumeListener, useMediaResumption = true,
                useQsMediaPlayer = true)
        val sbn = mock(StatusBarNotification::class.java)
        val notification = mock(Notification::class.java)
        whenever(notification.hasMediaSession()).thenReturn(true)
        whenever(notification.notificationStyle).thenReturn(Notification.MediaStyle::class.java)
        whenever(sbn.notification).thenReturn(notification)
        whenever(sbn.packageName).thenReturn(PACKAGE_NAME)
        mediaNotification = sbn
    }

    @After
    fun tearDown() {
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
        verify(backgroundExecutor).execute(anyObject())
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
}