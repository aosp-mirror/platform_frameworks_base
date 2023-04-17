/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Notification
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class NotificationMediaManagerTest : SysuiTestCase() {

    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var visibilityProvider: NotificationVisibilityProvider
    @Mock private lateinit var mediaArtworkProcessor: MediaArtworkProcessor
    @Mock private lateinit var keyguardBypassController: KeyguardBypassController
    @Mock private lateinit var notifPipeline: NotifPipeline
    @Mock private lateinit var notifCollection: NotifCollection
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var colorExtractor: SysuiColorExtractor
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var wallpaperManager: WallpaperManager

    @Mock private lateinit var notificationEntry: NotificationEntry

    lateinit var manager: NotificationMediaManager
    val clock = FakeSystemClock()
    val mainExecutor: DelayableExecutor = FakeExecutor(clock)

    @Mock private lateinit var mockManager: NotificationMediaManager
    @Mock private lateinit var mockBackDropView: BackDropView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doCallRealMethod().whenever(mockManager).updateMediaMetaData(anyBoolean(), anyBoolean())
        doReturn(mockBackDropView).whenever(mockManager).backDropView

        manager =
            NotificationMediaManager(
                context,
                Lazy { Optional.of(centralSurfaces) },
                Lazy { notificationShadeWindowController },
                visibilityProvider,
                mediaArtworkProcessor,
                keyguardBypassController,
                notifPipeline,
                notifCollection,
                mainExecutor,
                mediaDataManager,
                statusBarStateController,
                colorExtractor,
                keyguardStateController,
                dumpManager,
                wallpaperManager,
            )
    }

    /**
     * Check that updateMediaMetaData is a no-op with mIsLockscreenLiveWallpaperEnabled = true
     * Temporary test for the lock screen live wallpaper project.
     *
     * TODO(b/273443374): remove this test
     */
    @Test
    fun testUpdateMediaMetaDataDisabled() {
        mockManager.mIsLockscreenLiveWallpaperEnabled = true
        for (metaDataChanged in listOf(true, false)) {
            for (allowEnterAnimation in listOf(true, false)) {
                mockManager.updateMediaMetaData(metaDataChanged, allowEnterAnimation)
                verify(mockManager, never()).mediaMetadata
            }
        }
    }

    @Test
    fun testMetadataUpdated_doesNotRetainArtwork() {
        val artBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val artUri = "content://example"
        val inputMetadata =
            MediaMetadata.Builder()
                .putBitmap(MediaMetadata.METADATA_KEY_ART, artBmp)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBmp)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artBmp)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artUri)
                .putString(MediaMetadata.METADATA_KEY_ART_URI, artUri)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artUri)
                .build()

        // Create a playing media notification
        val state = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build()
        val session = MediaSession(context, "NotificationMediaManagerTest")
        session.setMetadata(inputMetadata)
        session.setPlaybackState(state)
        val sbn =
            SbnBuilder().run {
                modifyNotification(context).also {
                    it.setSmallIcon(android.R.drawable.ic_media_play)
                    it.setStyle(
                        Notification.MediaStyle().apply { setMediaSession(session.sessionToken) }
                    )
                }
                build()
            }
        whenever(notificationEntry.sbn).thenReturn(sbn)
        val collection = ArrayList<NotificationEntry>()
        collection.add(notificationEntry)
        whenever(notifPipeline.allNotifs).thenReturn(collection)

        // Trigger update in NotificationMediaManager
        manager.findAndUpdateMediaNotifications()

        // Verify that there is no artwork data retained
        val metadata = manager.mediaMetadata
        assertThat(metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)).isNull()
        assertThat(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)).isNull()
        assertThat(metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)).isNull()
        assertThat(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)).isNull()
        assertThat(metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)).isNull()
        assertThat(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)).isNull()
    }
}
