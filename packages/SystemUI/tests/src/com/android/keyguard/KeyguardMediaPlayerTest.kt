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

package com.android.keyguard

import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.widget.TextView
import androidx.test.filters.SmallTest

import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class KeyguardMediaPlayerTest : SysuiTestCase() {

    private lateinit var keyguardMediaPlayer: KeyguardMediaPlayer
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var mediaMetadata: MediaMetadata.Builder
    private lateinit var entry: NotificationEntryBuilder
    @Mock private lateinit var mockView: View
    private lateinit var textView: TextView
    @Mock private lateinit var mockIcon: Icon

    @Before
    public fun setup() {
        fakeExecutor = FakeExecutor(FakeSystemClock())
        keyguardMediaPlayer = KeyguardMediaPlayer(context, fakeExecutor)
        mockView = mock(View::class.java)
        textView = TextView(context)
        mockIcon = mock(Icon::class.java)
        mediaMetadata = MediaMetadata.Builder()
        entry = NotificationEntryBuilder()

        keyguardMediaPlayer.bindView(mockView)
    }

    @After
    public fun tearDown() {
        keyguardMediaPlayer.unbindView()
    }

    @Test
    public fun testBind() {
        keyguardMediaPlayer.unbindView()
        keyguardMediaPlayer.bindView(mockView)
    }

    @Test
    public fun testUnboundClearControls() {
        keyguardMediaPlayer.unbindView()
        keyguardMediaPlayer.clearControls()
        keyguardMediaPlayer.bindView(mockView)
    }

    @Test
    public fun testUpdateControls() {
        keyguardMediaPlayer.updateControls(entry.build(), mockIcon, mediaMetadata.build())
        verify(mockView).setVisibility(View.VISIBLE)
    }

    @Test
    public fun testClearControls() {
        keyguardMediaPlayer.clearControls()
        verify(mockView).setVisibility(View.GONE)
    }

    @Test
    public fun testSongName() {
        whenever<TextView>(mockView.findViewById(R.id.header_title)).thenReturn(textView)
        val song: String = "Song"
        mediaMetadata.putText(MediaMetadata.METADATA_KEY_TITLE, song)

        keyguardMediaPlayer.updateControls(entry.build(), mockIcon, mediaMetadata.build())

        assertThat(textView.getText()).isEqualTo(song)
    }

    @Test
    public fun testArtistName() {
        whenever<TextView>(mockView.findViewById(R.id.header_artist)).thenReturn(textView)
        val artist: String = "Artist"
        mediaMetadata.putText(MediaMetadata.METADATA_KEY_ARTIST, artist)

        keyguardMediaPlayer.updateControls(entry.build(), mockIcon, mediaMetadata.build())

        assertThat(textView.getText()).isEqualTo(artist)
    }
}
