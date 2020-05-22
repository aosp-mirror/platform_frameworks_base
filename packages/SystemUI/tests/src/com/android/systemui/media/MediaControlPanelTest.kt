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

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest

import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock

import com.google.common.truth.Truth.assertThat

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

import java.util.ArrayList

private const val KEY = "TEST_KEY"
private const val APP = "APP"
private const val BG_COLOR = Color.RED
private const val PACKAGE = "PKG"
private const val ARTIST = "ARTIST"
private const val TITLE = "TITLE"
private const val DEVICE_NAME = "DEVICE_NAME"
private const val SESSION_KEY = "SESSION_KEY"
private const val SESSION_ARTIST = "SESSION_ARTIST"
private const val SESSION_TITLE = "SESSION_TITLE"

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MediaControlPanelTest : SysuiTestCase() {

    private lateinit var player: MediaControlPanel

    private lateinit var fgExecutor: FakeExecutor
    private lateinit var bgExecutor: FakeExecutor
    @Mock private lateinit var activityStarter: ActivityStarter

    @Mock private lateinit var holder: PlayerViewHolder
    @Mock private lateinit var motion: MotionLayout
    private lateinit var background: TextView
    private lateinit var appIcon: ImageView
    private lateinit var appName: TextView
    private lateinit var albumView: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seamless: ViewGroup
    private lateinit var seamlessIcon: ImageView
    private lateinit var seamlessText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var elapsedTimeView: TextView
    private lateinit var totalTimeView: TextView
    private lateinit var action0: ImageButton
    private lateinit var action1: ImageButton
    private lateinit var action2: ImageButton
    private lateinit var action3: ImageButton
    private lateinit var action4: ImageButton

    private lateinit var session: MediaSession
    private val device = MediaDeviceData(true, null, DEVICE_NAME)
    private val disabledDevice = MediaDeviceData(false, null, null)

    @Before
    fun setUp() {
        fgExecutor = FakeExecutor(FakeSystemClock())
        bgExecutor = FakeExecutor(FakeSystemClock())

        activityStarter = mock(ActivityStarter::class.java)

        player = MediaControlPanel(context, fgExecutor, bgExecutor, activityStarter)

        // Mock out a view holder for the player to attach to.
        holder = mock(PlayerViewHolder::class.java)
        motion = mock(MotionLayout::class.java)
        val trans: ArrayList<MotionScene.Transition> = ArrayList()
        trans.add(mock(MotionScene.Transition::class.java))
        whenever(motion.definedTransitions).thenReturn(trans)
        val constraintSet = mock(ConstraintSet::class.java)
        whenever(motion.getConstraintSet(R.id.expanded)).thenReturn(constraintSet)
        whenever(motion.getConstraintSet(R.id.collapsed)).thenReturn(constraintSet)
        whenever(holder.player).thenReturn(motion)
        background = TextView(context)
        whenever(holder.background).thenReturn(background)
        appIcon = ImageView(context)
        whenever(holder.appIcon).thenReturn(appIcon)
        appName = TextView(context)
        whenever(holder.appName).thenReturn(appName)
        albumView = ImageView(context)
        whenever(holder.albumView).thenReturn(albumView)
        titleText = TextView(context)
        whenever(holder.titleText).thenReturn(titleText)
        artistText = TextView(context)
        whenever(holder.artistText).thenReturn(artistText)
        seamless = FrameLayout(context)
        val seamlessBackground = mock(RippleDrawable::class.java)
        seamless.setBackground(seamlessBackground)
        whenever(seamlessBackground.getDrawable(0)).thenReturn(mock(GradientDrawable::class.java))
        whenever(holder.seamless).thenReturn(seamless)
        seamlessIcon = ImageView(context)
        whenever(holder.seamlessIcon).thenReturn(seamlessIcon)
        seamlessText = TextView(context)
        whenever(holder.seamlessText).thenReturn(seamlessText)
        seekBar = SeekBar(context)
        whenever(holder.seekBar).thenReturn(seekBar)
        elapsedTimeView = TextView(context)
        whenever(holder.elapsedTimeView).thenReturn(elapsedTimeView)
        totalTimeView = TextView(context)
        whenever(holder.totalTimeView).thenReturn(totalTimeView)
        action0 = ImageButton(context)
        whenever(holder.action0).thenReturn(action0)
        action1 = ImageButton(context)
        whenever(holder.action1).thenReturn(action1)
        action2 = ImageButton(context)
        whenever(holder.action2).thenReturn(action2)
        action3 = ImageButton(context)
        whenever(holder.action3).thenReturn(action3)
        action4 = ImageButton(context)
        whenever(holder.action4).thenReturn(action4)

        // Create media session
        val metadataBuilder = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
            putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
        }
        val playbackBuilder = PlaybackState.Builder().apply {
            setState(PlaybackState.STATE_PAUSED, 6000L, 1f)
            setActions(PlaybackState.ACTION_PLAY)
        }
        session = MediaSession(context, SESSION_KEY).apply {
            setMetadata(metadataBuilder.build())
            setPlaybackState(playbackBuilder.build())
        }
        session.setActive(true)
    }

    @After
    fun tearDown() {
        session.release()
        player.onDestroy()
    }

    @Test
    fun bindWhenUnattached() {
        val state = MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, null, null, device)
        player.bind(state)
        assertThat(player.isPlaying()).isFalse()
    }

    @Test
    fun bindText() {
        player.attach(holder)
        val state = MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device)
        player.bind(state)
        assertThat(appName.getText()).isEqualTo(APP)
        assertThat(titleText.getText()).isEqualTo(TITLE)
        assertThat(artistText.getText()).isEqualTo(ARTIST)
    }

    @Test
    fun bindBackgroundColor() {
        player.attach(holder)
        val state = MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device)
        player.bind(state)
        assertThat(background.getBackgroundTintList()).isEqualTo(ColorStateList.valueOf(BG_COLOR))
    }

    @Test
    fun bindDevice() {
        player.attach(holder)
        val state = MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device)
        player.bind(state)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isTrue()
    }

    @Test
    fun bindDisabledDevice() {
        player.attach(holder)
        val state = MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, disabledDevice)
        player.bind(state)
        assertThat(seamless.isEnabled()).isFalse()
        assertThat(seamlessText.getText()).isEqualTo(context.getResources().getString(
                R.string.media_seamless_remote_device))
    }

    @Test
    fun bindNullDevice() {
        player.attach(holder)
        val state = MediaData(true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, null)
        player.bind(state)
        assertThat(seamless.isEnabled()).isTrue()
        assertThat(seamlessText.getText()).isEqualTo(context.getResources().getString(
                com.android.internal.R.string.ext_media_seamless_action))
    }
}
