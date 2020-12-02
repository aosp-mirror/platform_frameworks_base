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

package com.android.systemui.media

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@SmallTest
@RunWith(AndroidTestingRunner::class)
public class MediaPlayerDataTest : SysuiTestCase() {

    companion object {
        val LOCAL = true
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
        val playerIsPlaying = mock(MediaControlPanel::class.java)
        val dataIsPlaying = createMediaData("app1", PLAYING, LOCAL, !RESUMPTION)

        val playerIsRemote = mock(MediaControlPanel::class.java)
        val dataIsRemote = createMediaData("app2", PLAYING, !LOCAL, !RESUMPTION)

        MediaPlayerData.addMediaPlayer("2", dataIsRemote, playerIsRemote)
        MediaPlayerData.addMediaPlayer("1", dataIsPlaying, playerIsPlaying)

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

        MediaPlayerData.addMediaPlayer("1", dataIsPlaying1, playerIsPlaying1)
        MediaPlayerData.addMediaPlayer("2", dataIsPlaying2, playerIsPlaying2)

        dataIsPlaying1 = createMediaData("app1", !PLAYING, LOCAL, !RESUMPTION)
        dataIsPlaying2 = createMediaData("app2", PLAYING, LOCAL, !RESUMPTION)

        MediaPlayerData.addMediaPlayer("1", dataIsPlaying1, playerIsPlaying1)
        MediaPlayerData.addMediaPlayer("2", dataIsPlaying2, playerIsPlaying2)

        val players = MediaPlayerData.players()
        assertThat(players).hasSize(2)
        assertThat(players).containsExactly(playerIsPlaying2, playerIsPlaying1).inOrder()
    }

    @Test
    fun fullOrderTest() {
        val playerIsPlaying = mock(MediaControlPanel::class.java)
        val dataIsPlaying = createMediaData("app1", PLAYING, LOCAL, !RESUMPTION)

        val playerIsPlayingAndRemote = mock(MediaControlPanel::class.java)
        val dataIsPlayingAndRemote = createMediaData("app2", PLAYING, !LOCAL, !RESUMPTION)

        val playerIsStoppedAndLocal = mock(MediaControlPanel::class.java)
        val dataIsStoppedAndLocal = createMediaData("app3", !PLAYING, LOCAL, !RESUMPTION)

        val playerIsStoppedAndRemote = mock(MediaControlPanel::class.java)
        val dataIsStoppedAndRemote = createMediaData("app4", !PLAYING, !LOCAL, !RESUMPTION)

        val playerCanResume = mock(MediaControlPanel::class.java)
        val dataCanResume = createMediaData("app5", !PLAYING, LOCAL, RESUMPTION)

        val playerUndetermined = mock(MediaControlPanel::class.java)
        val dataUndetermined = createMediaData("app6", UNDETERMINED, LOCAL, RESUMPTION)

        MediaPlayerData.addMediaPlayer("3", dataIsStoppedAndLocal, playerIsStoppedAndLocal)
        MediaPlayerData.addMediaPlayer("5", dataIsStoppedAndRemote, playerIsStoppedAndRemote)
        MediaPlayerData.addMediaPlayer("4", dataCanResume, playerCanResume)
        MediaPlayerData.addMediaPlayer("1", dataIsPlaying, playerIsPlaying)
        MediaPlayerData.addMediaPlayer("2", dataIsPlayingAndRemote, playerIsPlayingAndRemote)
        MediaPlayerData.addMediaPlayer("6", dataUndetermined, playerUndetermined)

        val players = MediaPlayerData.players()
        assertThat(players).hasSize(6)
        assertThat(players).containsExactly(playerIsPlaying, playerIsPlayingAndRemote,
            playerIsStoppedAndLocal, playerCanResume, playerIsStoppedAndRemote,
            playerUndetermined).inOrder()
    }

    private fun createMediaData(
        app: String,
        isPlaying: Boolean?,
        isLocalSession: Boolean,
        resumption: Boolean
    ) =
        MediaData(0, false, 0, app, null, null, null, null, emptyList(), emptyList<Int>(), "",
            null, null, null, true, null, isLocalSession, resumption, null, false, isPlaying)
}
