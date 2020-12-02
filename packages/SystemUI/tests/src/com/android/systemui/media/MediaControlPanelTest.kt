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

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.LiveData
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

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
private const val USER_ID = 0

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MediaControlPanelTest : SysuiTestCase() {

    private lateinit var player: MediaControlPanel

    private lateinit var bgExecutor: FakeExecutor
    @Mock private lateinit var activityStarter: ActivityStarter

    @Mock private lateinit var holder: PlayerViewHolder
    @Mock private lateinit var view: TransitionLayout
    @Mock private lateinit var seekBarViewModel: SeekBarViewModel
    @Mock private lateinit var seekBarData: LiveData<SeekBarViewModel.Progress>
    @Mock private lateinit var mediaViewController: MediaViewController
    @Mock private lateinit var keyguardDismissUtil: KeyguardDismissUtil
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var expandedSet: ConstraintSet
    @Mock private lateinit var collapsedSet: ConstraintSet
    private lateinit var appIcon: ImageView
    private lateinit var appName: TextView
    private lateinit var albumView: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seamless: ViewGroup
    private lateinit var seamlessIcon: ImageView
    private lateinit var seamlessText: TextView
    private lateinit var seamlessFallback: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var elapsedTimeView: TextView
    private lateinit var totalTimeView: TextView
    private lateinit var action0: ImageButton
    private lateinit var action1: ImageButton
    private lateinit var action2: ImageButton
    private lateinit var action3: ImageButton
    private lateinit var action4: ImageButton
    private lateinit var settings: View
    private lateinit var cancel: View
    private lateinit var dismiss: View

    private lateinit var session: MediaSession
    private val device = MediaDeviceData(true, null, DEVICE_NAME)
    private val disabledDevice = MediaDeviceData(false, null, null)

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        bgExecutor = FakeExecutor(FakeSystemClock())
        whenever(mediaViewController.expandedLayout).thenReturn(expandedSet)
        whenever(mediaViewController.collapsedLayout).thenReturn(collapsedSet)

        player = MediaControlPanel(context, bgExecutor, activityStarter, mediaViewController,
                seekBarViewModel, Lazy { mediaDataManager }, keyguardDismissUtil)
        whenever(seekBarViewModel.progress).thenReturn(seekBarData)

        // Mock out a view holder for the player to attach to.
        whenever(holder.player).thenReturn(view)
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
        seamless.foreground = seamlessBackground
        whenever(seamlessBackground.getDrawable(0)).thenReturn(mock(GradientDrawable::class.java))
        whenever(holder.seamless).thenReturn(seamless)
        seamlessIcon = ImageView(context)
        whenever(holder.seamlessIcon).thenReturn(seamlessIcon)
        seamlessText = TextView(context)
        whenever(holder.seamlessText).thenReturn(seamlessText)
        seamlessFallback = ImageView(context)
        whenever(holder.seamlessFallback).thenReturn(seamlessFallback)
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
        settings = View(context)
        whenever(holder.settings).thenReturn(settings)
        cancel = View(context)
        whenever(holder.cancel).thenReturn(cancel)
        dismiss = View(context)
        whenever(holder.dismiss).thenReturn(dismiss)

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
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, null, null, device, true, null)
        player.bind(state, PACKAGE)
        assertThat(player.isPlaying()).isFalse()
    }

    @Test
    fun bindText() {
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device, true, null)
        player.bind(state, PACKAGE)
        assertThat(appName.getText()).isEqualTo(APP)
        assertThat(titleText.getText()).isEqualTo(TITLE)
        assertThat(artistText.getText()).isEqualTo(ARTIST)
    }

    @Test
    fun bindBackgroundColor() {
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device, true, null)
        player.bind(state, PACKAGE)
        val list = ArgumentCaptor.forClass(ColorStateList::class.java)
        verify(view).setBackgroundTintList(list.capture())
        assertThat(list.value).isEqualTo(ColorStateList.valueOf(BG_COLOR))
    }

    @Test
    fun bindDevice() {
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device, true, null)
        player.bind(state, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isTrue()
    }

    @Test
    fun bindDisabledDevice() {
        seamless.id = 1
        seamlessFallback.id = 2
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, disabledDevice, true, null)
        player.bind(state, PACKAGE)
        verify(expandedSet).setVisibility(seamless.id, View.GONE)
        verify(expandedSet).setVisibility(seamlessFallback.id, View.VISIBLE)
        verify(collapsedSet).setVisibility(seamless.id, View.GONE)
        verify(collapsedSet).setVisibility(seamlessFallback.id, View.VISIBLE)
    }

    @Test
    fun bindNullDevice() {
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, null, true, null)
        player.bind(state, PACKAGE)
        assertThat(seamless.isEnabled()).isTrue()
        assertThat(seamlessText.getText()).isEqualTo(context.getResources().getString(
                com.android.internal.R.string.ext_media_seamless_action))
    }

    @Test
    fun bindDeviceResumptionPlayer() {
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, device, true, null,
                resumption = true)
        player.bind(state, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isFalse()
    }

    @Test
    fun longClick_gutsClosed() {
        player.attach(holder)
        whenever(mediaViewController.isGutsVisible).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(holder.player).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(holder.player)
        verify(mediaViewController).openGuts()
    }

    @Test
    fun longClick_gutsOpen() {
        player.attach(holder)
        whenever(mediaViewController.isGutsVisible).thenReturn(true)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(holder.player).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(holder.player)
        verify(mediaViewController, never()).openGuts()
    }

    @Test
    fun cancelButtonClick_animation() {
        player.attach(holder)

        cancel.callOnClick()

        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun settingsButtonClick() {
        player.attach(holder)

        settings.callOnClick()

        val captor = ArgumentCaptor.forClass(Intent::class.java)
        verify(activityStarter).startActivity(captor.capture(), eq(true))

        assertThat(captor.value.action).isEqualTo(ACTION_MEDIA_CONTROLS_SETTINGS)
    }

    @Test
    fun dismissButtonClick() {
        val mediaKey = "key for dismissal"
        player.attach(holder)
        val state = MediaData(USER_ID, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
                emptyList(), PACKAGE, session.getSessionToken(), null, null, true, null,
                notificationKey = KEY)
        player.bind(state, mediaKey)

        dismiss.callOnClick()
        val captor = ArgumentCaptor.forClass(ActivityStarter.OnDismissAction::class.java)
        verify(keyguardDismissUtil).executeWhenUnlocked(captor.capture(), anyBoolean())

        captor.value.onDismiss()
        verify(mediaDataManager).dismissMediaData(eq(mediaKey), anyLong())
    }
}
