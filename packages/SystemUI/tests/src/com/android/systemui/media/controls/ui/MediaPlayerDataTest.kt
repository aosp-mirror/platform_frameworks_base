/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.ui.controller.MediaControlPanel
import com.android.systemui.media.controls.ui.controller.MediaPlayerData
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
public class MediaPlayerDataTest : SysuiTestCase() {

    @Mock private lateinit var playerIsPlaying: MediaControlPanel
    private var systemClock: FakeSystemClock = FakeSystemClock()

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    companion object {
        val LOCAL = MediaData.PLAYBACK_LOCAL
        val REMOTE = MediaData.PLAYBACK_CAST_LOCAL
        val RESUMPTION = true
        val PLAYING = true
        val UNDETERMINED = null
    }

    @Before
    fun setup() {
        MediaPlayerData.clear()
    }

    @Test
    fun addPlayingThenRemote() {
        val dataIsPlaying = createMediaData("app1", PLAYING, LOCAL, !RESUMPTION)

        val playerIsRemote = mock(MediaControlPanel::class.java)
        val dataIsRemote = createMediaData("app2", PLAYING, REMOTE, !RESUMPTION)

        MediaPlayerData.addMediaPlayer(
            "2",
            dataIsRemote,
            playerIsRemote,
            systemClock,
            isSsReactivated = false
        )
        MediaPlayerData.addMediaPlayer(
            "1",
            dataIsPlaying,
            playerIsPlaying,
            systemClock,
            isSsReactivated = false
        )

        val players = MediaPlayerData.players()
        assertThat(players).hasSize(2)
        assertThat(players).containsExactly(playerIsPlaying, playerIsRemote).inOrder()
    }

    @Test
    fun switchPlayersPlaying() {
        val playerIsPlaying1 = mock(MediaControlPanel::class.java)
        var dataIsPlaying1 = createMediaData("app1", PLAYING, LOCAL, !RESUMPTION)

        val playerIsPlaying2 = mock(MediaControlPanel::class.java)
        var dataIsPlaying2 = createMediaData("app2", !PLAYING, LOCAL, !RESUMPTION)

        MediaPlayerData.addMediaPlayer(
            "1",
            dataIsPlaying1,
            playerIsPlaying1,
            systemClock,
            isSsReactivated = false
        )
        systemClock.advanceTime(1)
        MediaPlayerData.addMediaPlayer(
            "2",
            dataIsPlaying2,
            playerIsPlaying2,
            systemClock,
            isSsReactivated = false
        )
        systemClock.advanceTime(1)

        dataIsPlaying1 = createMediaData("app1", !PLAYING, LOCAL, !RESUMPTION)
        dataIsPlaying2 = createMediaData("app2", PLAYING, LOCAL, !RESUMPTION)

        MediaPlayerData.addMediaPlayer(
            "1",
            dataIsPlaying1,
            playerIsPlaying1,
            systemClock,
            isSsReactivated = false
        )
        systemClock.advanceTime(1)

        MediaPlayerData.addMediaPlayer(
            "2",
            dataIsPlaying2,
            playerIsPlaying2,
            systemClock,
            isSsReactivated = false
        )
        systemClock.advanceTime(1)

        val players = MediaPlayerData.players()
        assertThat(players).hasSize(2)
        assertThat(players).containsExactly(playerIsPlaying2, playerIsPlaying1).inOrder()
    }

    @Test
    fun fullOrderTest() {
        val dataIsPlaying = createMediaData("app1", PLAYING, LOCAL, !RESUMPTION)

        val playerIsPlayingAndRemote = mock(MediaControlPanel::class.java)
        val dataIsPlayingAndRemote = createMediaData("app2", PLAYING, REMOTE, !RESUMPTION)

        val playerIsStoppedAndLocal = mock(MediaControlPanel::class.java)
        val dataIsStoppedAndLocal = createMediaData("app3", !PLAYING, LOCAL, !RESUMPTION)

        val playerIsStoppedAndRemote = mock(MediaControlPanel::class.java)
        val dataIsStoppedAndRemote = createMediaData("app4", !PLAYING, REMOTE, !RESUMPTION)

        val playerCanResume = mock(MediaControlPanel::class.java)
        val dataCanResume = createMediaData("app5", !PLAYING, LOCAL, RESUMPTION)

        val playerUndetermined = mock(MediaControlPanel::class.java)
        val dataUndetermined = createMediaData("app6", UNDETERMINED, LOCAL, RESUMPTION)

        MediaPlayerData.addMediaPlayer(
            "3",
            dataIsStoppedAndLocal,
            playerIsStoppedAndLocal,
            systemClock,
            isSsReactivated = false
        )
        MediaPlayerData.addMediaPlayer(
            "5",
            dataIsStoppedAndRemote,
            playerIsStoppedAndRemote,
            systemClock,
            isSsReactivated = false
        )
        MediaPlayerData.addMediaPlayer(
            "4",
            dataCanResume,
            playerCanResume,
            systemClock,
            isSsReactivated = false
        )
        MediaPlayerData.addMediaPlayer(
            "1",
            dataIsPlaying,
            playerIsPlaying,
            systemClock,
            isSsReactivated = false
        )
        MediaPlayerData.addMediaPlayer(
            "2",
            dataIsPlayingAndRemote,
            playerIsPlayingAndRemote,
            systemClock,
            isSsReactivated = false
        )
        MediaPlayerData.addMediaPlayer(
            "6",
            dataUndetermined,
            playerUndetermined,
            systemClock,
            isSsReactivated = false
        )

        val players = MediaPlayerData.players()
        assertThat(players).hasSize(6)
        assertThat(players)
            .containsExactly(
                playerIsPlaying,
                playerIsPlayingAndRemote,
                playerIsStoppedAndRemote,
                playerIsStoppedAndLocal,
                playerUndetermined,
                playerCanResume
            )
            .inOrder()
    }

    @Test
    fun testMoveMediaKeysAround() {
        val keyA = "a"
        val keyB = "b"

        val data = createMediaData("app1", PLAYING, LOCAL, !RESUMPTION)

        assertThat(MediaPlayerData.players()).hasSize(0)

        MediaPlayerData.addMediaPlayer(
            keyA,
            data,
            playerIsPlaying,
            systemClock,
            isSsReactivated = false
        )
        systemClock.advanceTime(1)

        assertThat(MediaPlayerData.players()).hasSize(1)
        MediaPlayerData.addMediaPlayer(
            keyB,
            data,
            playerIsPlaying,
            systemClock,
            isSsReactivated = false
        )
        systemClock.advanceTime(1)

        assertThat(MediaPlayerData.players()).hasSize(2)

        MediaPlayerData.moveIfExists(keyA, keyB)

        assertThat(MediaPlayerData.players()).hasSize(1)

        assertThat(MediaPlayerData.getMediaPlayer(keyA)).isNull()
        assertThat(MediaPlayerData.getMediaPlayer(keyB)).isNotNull()
    }

    private fun createMediaData(
        app: String,
        isPlaying: Boolean?,
        location: Int,
        resumption: Boolean
    ) =
        MediaTestUtils.emptyMediaData.copy(
            app = app,
            packageName = "package: $app",
            playbackLocation = location,
            resumption = resumption,
            notificationKey = "key: $app",
            isPlaying = isPlaying
        )
}
