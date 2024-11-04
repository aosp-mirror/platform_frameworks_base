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

package com.android.systemui.media.controls.ui.viewmodel

import android.R
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDeviceData
import com.android.systemui.media.controls.util.mediaInstanceId
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaControlViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaDataFilter = kosmos.mediaDataFilter
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager
    private val packageManager = kosmos.packageManager
    private val drawable = context.getDrawable(R.drawable.ic_media_play)
    private val instanceId = kosmos.mediaInstanceId
    private val underTest: MediaControlViewModel = kosmos.mediaControlViewModel

    @Before
    fun setUp() {
        whenever(packageManager.getApplicationIcon(Mockito.anyString())).thenReturn(drawable)
        whenever(packageManager.getApplicationIcon(any(ApplicationInfo::class.java)))
            .thenReturn(drawable)
        whenever(packageManager.getApplicationInfo(eq(PACKAGE_NAME), ArgumentMatchers.anyInt()))
            .thenReturn(ApplicationInfo())
        whenever(packageManager.getApplicationLabel(any())).thenReturn(PACKAGE_NAME)
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        context.setMockPackageManager(packageManager)
    }

    @Test
    fun addMediaControl_mediaControlViewModelIsLoaded() =
        testScope.runTest {
            val playerModel by collectLastValue(underTest.player)
            val mediaData = initMediaData(ARTIST, TITLE)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(playerModel).isNotNull()
            assertThat(playerModel?.titleName).isEqualTo(TITLE)
            assertThat(playerModel?.artistName).isEqualTo(ARTIST)
            assertThat(playerModel?.gutsMenu).isNotNull()
            assertThat(playerModel?.outputSwitcher).isNotNull()
            assertThat(playerModel?.actionButtons).isNotNull()
            assertThat(playerModel?.playTurbulenceNoise).isFalse()
        }

    @Test
    fun emitDuplicateMediaControls_mediaControlIsNotBound() =
        testScope.runTest {
            val playerModel by collectLastValue(underTest.player)
            val mediaData = initMediaData(ARTIST, TITLE)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(playerModel).isNotNull()
            assertThat(playerModel?.titleName).isEqualTo(TITLE)
            assertThat(playerModel?.artistName).isEqualTo(ARTIST)
            assertThat(underTest.isNewPlayer(playerModel!!)).isTrue()

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(playerModel).isNotNull()
            assertThat(playerModel?.titleName).isEqualTo(TITLE)
            assertThat(playerModel?.artistName).isEqualTo(ARTIST)
            assertThat(underTest.isNewPlayer(playerModel!!)).isFalse()
        }

    @Test
    fun emitDifferentMediaControls_mediaControlIsBound() =
        testScope.runTest {
            val playerModel by collectLastValue(underTest.player)
            var mediaData = initMediaData(ARTIST, TITLE)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(playerModel).isNotNull()
            assertThat(playerModel?.titleName).isEqualTo(TITLE)
            assertThat(playerModel?.artistName).isEqualTo(ARTIST)
            assertThat(underTest.isNewPlayer(playerModel!!)).isTrue()

            mediaData = initMediaData(ARTIST_2, TITLE_2)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(playerModel).isNotNull()
            assertThat(playerModel?.titleName).isEqualTo(TITLE_2)
            assertThat(playerModel?.artistName).isEqualTo(ARTIST_2)
            assertThat(underTest.isNewPlayer(playerModel!!)).isTrue()
        }

    private fun initMediaData(artist: String, title: String): MediaData {
        val device = MediaDeviceData(true, null, DEVICE_NAME, null, showBroadcastButton = true)

        // Create media session
        val metadataBuilder =
            MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
                putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
            }
        val playbackBuilder =
            PlaybackState.Builder().apply {
                setState(PlaybackState.STATE_PAUSED, 6000L, 1f)
                setActions(PlaybackState.ACTION_PLAY)
            }
        val session =
            MediaSession(context, SESSION_KEY).apply {
                setMetadata(metadataBuilder.build())
                setPlaybackState(playbackBuilder.build())
            }
        session.isActive = true

        return MediaData(
            userId = USER_ID,
            artist = artist,
            song = title,
            packageName = PACKAGE,
            token = session.sessionToken,
            device = device,
            instanceId = instanceId,
        )
    }

    companion object {
        private const val USER_ID = 0
        private const val KEY = "key"
        private const val PACKAGE_NAME = "com.example.app"
        private const val PACKAGE = "PKG"
        private const val ARTIST = "ARTIST"
        private const val TITLE = "TITLE"
        private const val ARTIST_2 = "ARTIST_2"
        private const val TITLE_2 = "TITLE_2"
        private const val DEVICE_NAME = "DEVICE_NAME"
        private const val SESSION_KEY = "SESSION_KEY"
        private const val SESSION_ARTIST = "SESSION_ARTIST"
        private const val SESSION_TITLE = "SESSION_TITLE"
    }
}
