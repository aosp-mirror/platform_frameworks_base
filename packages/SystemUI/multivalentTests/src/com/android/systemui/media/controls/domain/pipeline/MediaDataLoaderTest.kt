/**
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.systemui.media.controls.domain.pipeline

import android.app.Notification
import android.app.Notification.MediaStyle
import android.app.PendingIntent
import android.app.statusBarManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.media.utils.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.Flags.MEDIA_RESUME_PROGRESS
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.graphics.imageLoader
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.mediaFlags
import com.android.systemui.res.R
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val KEY = "KEY"
private const val PACKAGE_NAME = "com.example.app"
private const val SYSTEM_PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "SystemUI"
private const val SESSION_ARTIST = "artist"
private const val SESSION_TITLE = "title"
private const val SESSION_EMPTY_TITLE = ""

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaDataLoaderTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val testDispatcher = kosmos.testDispatcher
    private val statusBarManager = kosmos.statusBarManager
    private val mediaController = mock<MediaController>()
    private val fakeFeatureFlags = kosmos.fakeFeatureFlagsClassic
    private val mediaFlags = kosmos.mediaFlags
    private val mediaControllerFactory = kosmos.fakeMediaControllerFactory
    private val session = MediaSession(context, "MediaDataLoaderTestSession")
    private val metadataBuilder =
        MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
            putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
        }

    private val underTest: MediaDataLoader =
        MediaDataLoader(
            context,
            testDispatcher,
            testScope,
            mediaControllerFactory,
            mediaFlags,
            kosmos.imageLoader,
            statusBarManager,
        )

    @Before
    fun setUp() {
        mediaControllerFactory.setControllerForToken(session.sessionToken, mediaController)
        whenever(mediaController.metadata).then { metadataBuilder.build() }
    }

    @Test
    fun loadMediaData_returnsMediaData() =
        testScope.runTest {
            val song = "THIS_IS_A_SONG"
            val artist = "THIS_IS_AN_ARTIST"
            val albumArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

            whenever(mediaController.playbackState)
                .thenReturn(
                    PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 12, 1.0f).build()
                )
            whenever(mediaController.playbackInfo)
                .thenReturn(
                    MediaController.PlaybackInfo(
                        MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
                        0,
                        0,
                        0,
                        AudioAttributes.Builder().build(),
                        null,
                    )
                )
            whenever(mediaController.metadata)
                .thenReturn(
                    metadataBuilder
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, song)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt)
                        .putLong(
                            MediaConstants.METADATA_KEY_IS_EXPLICIT,
                            MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT,
                        )
                        .build()
                )

            val result = underTest.loadMediaData(KEY, createMediaNotification())
            assertThat(result).isNotNull()
            assertThat(result?.appIcon).isNotNull()
            assertThat(result?.appIcon?.resId).isEqualTo(android.R.drawable.ic_media_pause)
            assertThat(result?.artist).isEqualTo(artist)
            assertThat(result?.song).isEqualTo(song)
            assertThat(result?.artworkIcon).isNotNull()
            assertThat(result?.artworkIcon?.bitmap?.width).isEqualTo(albumArt.width)
            assertThat(result?.artworkIcon?.bitmap?.height).isEqualTo(albumArt.height)
            assertThat(result?.token).isEqualTo(session.sessionToken)
            assertThat(result?.device).isNull()
            assertThat(result?.playbackLocation).isEqualTo(MediaData.PLAYBACK_LOCAL)
            assertThat(result?.isPlaying).isTrue()
            assertThat(result?.isExplicit).isTrue()
            assertThat(result?.resumeAction).isNull()
            assertThat(result?.resumeProgress).isNull()
        }

    @Test
    fun loadMediaDataForResumption_returnsMediaData() =
        testScope.runTest {
            fakeFeatureFlags.set(MEDIA_RESUME_PROGRESS, true)

            val song = "THIS_IS_A_SONG"
            val artist = "THIS_IS_AN_ARTIST"
            val albumArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

            val extras = Bundle()
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            )
            extras.putDouble(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, 0.3)
            extras.putLong(
                MediaConstants.METADATA_KEY_IS_EXPLICIT,
                MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT,
            )

            val description =
                MediaDescription.Builder()
                    .setTitle(song)
                    .setSubtitle(artist)
                    .setIconBitmap(albumArt)
                    .setExtras(extras)
                    .build()

            val intent =
                PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

            val result =
                underTest.loadMediaDataForResumption(
                    0,
                    description,
                    Runnable {},
                    null,
                    session.sessionToken,
                    APP_NAME,
                    intent,
                    PACKAGE_NAME,
                )
            assertThat(result).isNotNull()
            assertThat(result?.appName).isEqualTo(APP_NAME)
            assertThat(result?.song).isEqualTo(song)
            assertThat(result?.artist).isEqualTo(artist)
            assertThat(result?.artworkIcon).isNotNull()
            assertThat(result?.artworkIcon?.bitmap?.width).isEqualTo(100)
            assertThat(result?.artworkIcon?.bitmap?.height).isEqualTo(100)
            assertThat(result?.token).isEqualTo(session.sessionToken)
            assertThat(result?.clickIntent).isEqualTo(intent)
            assertThat(result?.isExplicit).isTrue()
            assertThat(result?.resumeProgress).isEqualTo(0.3)
        }

    @Test
    fun loadMediaData_songNameFallbacks() =
        testScope.runTest {
            // Check ordering of Song resolution:
            // DISPLAY_TITLE > TITLE > notification TITLE > notification TITLE_BIG

            // DISPLAY_TITLE
            whenever(mediaController.metadata)
                .thenReturn(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "title1")
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "title2")
                        .build()
                )
            val result1 = underTest.loadMediaData(KEY, createMediaNotification())
            assertThat(result1?.song).isEqualTo("title1")

            // TITLE
            whenever(mediaController.metadata)
                .thenReturn(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "title2")
                        .build()
                )
            val result2 = underTest.loadMediaData(KEY, createMediaNotification())
            assertThat(result2?.song).isEqualTo("title2")

            // notification TITLE
            val notif =
                SbnBuilder().run {
                    setPkg(PACKAGE_NAME)
                    modifyNotification(context).also {
                        it.setSmallIcon(android.R.drawable.ic_media_pause)
                        it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                        it.setContentTitle("notiftitle")
                    }
                    build()
                }
            whenever(mediaController.metadata).thenReturn(MediaMetadata.Builder().build())
            val result3 = underTest.loadMediaData(KEY, notif)
            assertThat(result3?.song).isEqualTo("notiftitle")

            // Final fallback
            whenever(mediaController.metadata).thenReturn(MediaMetadata.Builder().build())
            val result4 = underTest.loadMediaData(KEY, createMediaNotification())
            assertThat(result4?.song)
                .isEqualTo(context.getString(R.string.controls_media_empty_title, result4?.appName))
        }

    @Test
    fun loadMediaData_emptyTitle_hasPlaceholder() =
        testScope.runTest {
            val packageManager = mock<PackageManager>()
            context.setMockPackageManager(packageManager)
            whenever(packageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
            whenever(mediaController.metadata)
                .thenReturn(
                    metadataBuilder
                        .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_EMPTY_TITLE)
                        .build()
                )

            val result = underTest.loadMediaData(KEY, createMediaNotification())

            val placeholderTitle = context.getString(R.string.controls_media_empty_title, APP_NAME)
            assertThat(result).isNotNull()
            assertThat(result?.song).isEqualTo(placeholderTitle)
        }

    @Test
    fun loadMediaData_emptyMetadata_usesNotificationTitle() =
        testScope.runTest {
            val packageManager = mock<PackageManager>()
            context.setMockPackageManager(packageManager)
            whenever(packageManager.getApplicationLabel(any())).thenReturn(APP_NAME)
            whenever(mediaController.metadata)
                .thenReturn(
                    metadataBuilder
                        .putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_EMPTY_TITLE)
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, SESSION_EMPTY_TITLE)
                        .build()
                )
            val mediaNotification =
                SbnBuilder().run {
                    setPkg(PACKAGE_NAME)
                    modifyNotification(context).also {
                        it.setSmallIcon(android.R.drawable.ic_media_pause)
                        it.setContentTitle(SESSION_TITLE)
                        it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                    }
                    build()
                }

            val result = underTest.loadMediaData(KEY, mediaNotification)

            assertThat(result).isNotNull()
            assertThat(result?.song).isEqualTo(SESSION_TITLE)
        }

    @Test
    fun loadMediaData_badArtwork_isNotUsed() =
        testScope.runTest {
            val artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val mediaNotification =
                SbnBuilder().run {
                    setPkg(PACKAGE_NAME)
                    modifyNotification(context).also {
                        it.setSmallIcon(android.R.drawable.ic_media_pause)
                        it.setStyle(MediaStyle().apply { setMediaSession(session.sessionToken) })
                        it.setLargeIcon(artwork)
                    }
                    build()
                }

            val result = underTest.loadMediaData(KEY, mediaNotification)

            assertThat(result).isNotNull()
        }

    @Test
    fun loadMediaData_invalidTokenNoCrash() =
        testScope.runTest {
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

            val result = underTest.loadMediaData(KEY, rcn)
            assertThat(result).isNull()
        }

    @Test
    fun testLoadMediaDataInBg_invalidMediaRemoteIntentNoCrash() =
        testScope.runTest {
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

            val result = underTest.loadMediaData(KEY, rcn)
            assertThat(result).isNotNull()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testLoadMediaDataInBg_cancelMultipleScheduledTasks() =
        testScope.runTest {
            val mockImageLoader = mock<ImageLoader>()
            val mediaDataLoader =
                MediaDataLoader(
                    context,
                    testDispatcher,
                    testScope,
                    mediaControllerFactory,
                    mediaFlags,
                    mockImageLoader,
                    statusBarManager,
                )
            metadataBuilder.putString(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                "content://album_art_uri",
            )

            testScope.launch { mediaDataLoader.loadMediaData(KEY, createMediaNotification()) }
            testScope.launch { mediaDataLoader.loadMediaData(KEY, createMediaNotification()) }
            testScope.launch { mediaDataLoader.loadMediaData(KEY, createMediaNotification()) }
            testScope.advanceUntilIdle()

            verify(mockImageLoader, times(1)).loadBitmap(any(), anyInt(), anyInt(), anyInt())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testLoadMediaDataInBg_fromResumeToActive_doesNotCancelResumeToActiveTask() =
        testScope.runTest {
            val mockImageLoader = mock<ImageLoader>()
            val mediaDataLoader =
                MediaDataLoader(
                    context,
                    testDispatcher,
                    testScope,
                    mediaControllerFactory,
                    mediaFlags,
                    mockImageLoader,
                    statusBarManager,
                )
            metadataBuilder.putString(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                "content://album_art_uri",
            )

            testScope.launch {
                mediaDataLoader.loadMediaData(
                    KEY,
                    createMediaNotification(),
                    isConvertingToActive = true,
                )
            }
            testScope.launch { mediaDataLoader.loadMediaData(KEY, createMediaNotification()) }
            testScope.launch { mediaDataLoader.loadMediaData(KEY, createMediaNotification()) }
            testScope.advanceUntilIdle()

            verify(mockImageLoader, times(2)).loadBitmap(any(), anyInt(), anyInt(), anyInt())
        }

    private fun createMediaNotification(
        mediaSession: MediaSession? = session,
        applicationInfo: ApplicationInfo? = null,
    ): StatusBarNotification =
        SbnBuilder().run {
            setPkg(PACKAGE_NAME)
            modifyNotification(context).also {
                it.setSmallIcon(android.R.drawable.ic_media_pause)
                it.setStyle(MediaStyle().apply { setMediaSession(mediaSession?.sessionToken) })
                if (applicationInfo != null) {
                    val bundle = Bundle()
                    bundle.putParcelable(
                        Notification.EXTRA_BUILDER_APPLICATION_INFO,
                        applicationInfo,
                    )
                    it.addExtras(bundle)
                }
            }
            build()
        }
}
