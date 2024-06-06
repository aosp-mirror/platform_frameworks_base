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

package com.android.systemui.statusbar

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.fakeExecutorHandler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.service.notification.NotificationListenerService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.dumpManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.mockNotifCollection
import com.android.systemui.statusbar.notification.collection.notifPipeline
import com.android.systemui.statusbar.notification.collection.render.notificationVisibilityProvider
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationMediaManagerTest : SysuiTestCase() {

    private val KEY = "KEY"

    private val kosmos = testKosmos()
    private val visibilityProvider = kosmos.notificationVisibilityProvider
    private val notifPipeline = kosmos.notifPipeline
    private val notifCollection = kosmos.mockNotifCollection
    private val dumpManager = kosmos.dumpManager
    private val handler = kosmos.fakeExecutorHandler
    private val mediaDataManager = mock<MediaDataManager>()
    private val backgroundExecutor = FakeExecutor(FakeSystemClock())

    private var listenerCaptor = argumentCaptor<MediaDataManager.Listener>()

    private lateinit var notificationMediaManager: NotificationMediaManager

    @Before
    fun setup() {
        notificationMediaManager =
            NotificationMediaManager(
                context,
                visibilityProvider,
                notifPipeline,
                notifCollection,
                mediaDataManager,
                dumpManager,
                backgroundExecutor,
                handler,
            )
        val mediaSession = MediaSession(context, "TEST")
        notificationMediaManager.mMediaController =
            MediaController(context, mediaSession.sessionToken)

        verify(mediaDataManager).addListener(listenerCaptor.capture())
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_CONTROLS_USER_INITIATED_DELETEINTENT)
    fun mediaDataRemoved_userInitiated_dismissNotif() {
        val notifEntryCaptor = argumentCaptor<NotificationEntry>()
        val notifEntry = mock<NotificationEntry>()
        whenever(notifEntry.key).thenReturn(KEY)
        whenever(notifEntry.ranking).thenReturn(NotificationListenerService.Ranking())
        whenever(notifPipeline.allNotifs).thenReturn(listOf(notifEntry))

        listenerCaptor.lastValue.onMediaDataRemoved(KEY, true)

        verify(notifCollection).dismissNotification(notifEntryCaptor.capture(), any())
        assertThat(notifEntryCaptor.lastValue.key).isEqualTo(KEY)
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_CONTROLS_USER_INITIATED_DELETEINTENT)
    fun mediaDataRemoved_notUserInitiated_doesNotDismissNotif() {
        listenerCaptor.lastValue.onMediaDataRemoved(KEY, false)

        verify(notifCollection, never()).dismissNotification(any(), any())
    }

    @Test
    @DisableFlags(Flags.FLAG_MEDIA_CONTROLS_USER_INITIATED_DELETEINTENT)
    fun mediaDataRemoved_notUserInitiated_flagOff_dismissNotif() {
        val notifEntryCaptor = argumentCaptor<NotificationEntry>()
        val notifEntry = mock<NotificationEntry>()
        whenever(notifEntry.key).thenReturn(KEY)
        whenever(notifEntry.ranking).thenReturn(NotificationListenerService.Ranking())
        whenever(notifPipeline.allNotifs).thenReturn(listOf(notifEntry))

        listenerCaptor.lastValue.onMediaDataRemoved(KEY, false)

        verify(notifCollection).dismissNotification(notifEntryCaptor.capture(), any())
        assertThat(notifEntryCaptor.lastValue.key).isEqualTo(KEY)
    }

    @Test
    @EnableFlags(Flags.FLAG_NOTIFICATION_MEDIA_MANAGER_BACKGROUND_EXECUTION)
    fun clearMediaNotification_flagOn_resetMediaMetadata() {
        // set up media metadata.
        notificationMediaManager.mMediaListener.onMetadataChanged(MediaMetadata.Builder().build())
        backgroundExecutor.runAllReady()

        // clear media notification.
        notificationMediaManager.clearCurrentMediaNotification()
        backgroundExecutor.runAllReady()

        assertThat(notificationMediaManager.mediaMetadata).isNull()
        assertThat(notificationMediaManager.mMediaController).isNull()
    }

    @Test
    @DisableFlags(Flags.FLAG_NOTIFICATION_MEDIA_MANAGER_BACKGROUND_EXECUTION)
    fun clearMediaNotification_flagOff_resetMediaMetadata() {
        // set up media metadata.
        notificationMediaManager.mMediaListener.onMetadataChanged(MediaMetadata.Builder().build())

        // clear media notification.
        notificationMediaManager.clearCurrentMediaNotification()

        assertThat(notificationMediaManager.mediaMetadata).isNull()
        assertThat(notificationMediaManager.mMediaController).isNull()
    }
}
