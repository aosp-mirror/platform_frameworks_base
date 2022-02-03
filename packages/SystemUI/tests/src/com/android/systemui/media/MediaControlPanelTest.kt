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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
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
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.dialog.MediaOutputDialogFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
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
    @Mock private lateinit var sessionHolder: PlayerSessionViewHolder
    @Mock private lateinit var view: TransitionLayout
    @Mock private lateinit var seekBarViewModel: SeekBarViewModel
    @Mock private lateinit var seekBarData: LiveData<SeekBarViewModel.Progress>
    @Mock private lateinit var mediaViewController: MediaViewController
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var expandedSet: ConstraintSet
    @Mock private lateinit var collapsedSet: ConstraintSet
    @Mock private lateinit var mediaOutputDialogFactory: MediaOutputDialogFactory
    @Mock private lateinit var mediaCarouselController: MediaCarouselController
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var mediaFlags: MediaFlags
    private lateinit var appIcon: ImageView
    private lateinit var albumView: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seamless: ViewGroup
    private lateinit var seamlessButton: View
    @Mock private lateinit var seamlessBackground: RippleDrawable
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
    private lateinit var actionPlayPause: ImageButton
    private lateinit var actionNext: ImageButton
    private lateinit var actionPrev: ImageButton
    private lateinit var actionStart: ImageButton
    private lateinit var actionEnd: ImageButton
    @Mock private lateinit var longPressText: TextView
    @Mock private lateinit var handler: Handler
    private lateinit var settings: View
    private lateinit var settingsText: TextView
    private lateinit var cancel: View
    private lateinit var cancelText: TextView
    private lateinit var dismiss: FrameLayout
    private lateinit var dismissText: TextView

    private lateinit var session: MediaSession
    private val device = MediaDeviceData(true, null, DEVICE_NAME)
    private val disabledDevice = MediaDeviceData(false, null, "Disabled Device")
    private lateinit var mediaData: MediaData
    private val clock = FakeSystemClock()

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        bgExecutor = FakeExecutor(FakeSystemClock())
        whenever(mediaViewController.expandedLayout).thenReturn(expandedSet)
        whenever(mediaViewController.collapsedLayout).thenReturn(collapsedSet)

        player = MediaControlPanel(context, bgExecutor, activityStarter, mediaViewController,
            seekBarViewModel, Lazy { mediaDataManager },
            mediaOutputDialogFactory, mediaCarouselController, falsingManager, mediaFlags, clock)
        whenever(seekBarViewModel.progress).thenReturn(seekBarData)

        // Set up mock views for the players
        appIcon = ImageView(context)
        albumView = ImageView(context)
        titleText = TextView(context)
        artistText = TextView(context)
        seamless = FrameLayout(context)
        seamless.foreground = seamlessBackground
        seamlessButton = View(context)
        seamlessIcon = ImageView(context)
        seamlessText = TextView(context)
        seekBar = SeekBar(context)
        elapsedTimeView = TextView(context)
        totalTimeView = TextView(context)
        settings = View(context)
        settingsText = TextView(context)
        cancel = View(context)
        cancelText = TextView(context)
        dismiss = FrameLayout(context)
        dismissText = TextView(context)
        initPlayerHolderMocks()
        initSessionHolderMocks()

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

        mediaData = MediaData(
                userId = USER_ID,
                initialized = true,
                backgroundColor = BG_COLOR,
                app = APP,
                appIcon = null,
                artist = ARTIST,
                song = TITLE,
                artwork = null,
                actions = emptyList(),
                actionsToShowInCompact = emptyList(),
                packageName = PACKAGE,
                token = session.sessionToken,
                clickIntent = null,
                device = device,
                active = true,
                resumeAction = null)

        whenever(mediaFlags.areMediaSessionActionsEnabled()).thenReturn(false)
        whenever(mediaFlags.useMediaSessionLayout()).thenReturn(false)
    }

    /** Mock view holder for the notification player */
    private fun initPlayerHolderMocks() {
        whenever(holder.player).thenReturn(view)
        whenever(holder.appIcon).thenReturn(appIcon)
        whenever(holder.albumView).thenReturn(albumView)
        whenever(holder.titleText).thenReturn(titleText)
        whenever(holder.artistText).thenReturn(artistText)
        whenever(seamlessBackground.getDrawable(0)).thenReturn(mock(GradientDrawable::class.java))
        whenever(holder.seamless).thenReturn(seamless)
        whenever(holder.seamlessButton).thenReturn(seamlessButton)
        whenever(holder.seamlessIcon).thenReturn(seamlessIcon)
        whenever(holder.seamlessText).thenReturn(seamlessText)
        whenever(holder.seekBar).thenReturn(seekBar)
        whenever(holder.elapsedTimeView).thenReturn(elapsedTimeView)
        whenever(holder.totalTimeView).thenReturn(totalTimeView)

        // Action buttons
        action0 = ImageButton(context)
        whenever(holder.action0).thenReturn(action0)
        whenever(holder.getAction(R.id.action0)).thenReturn(action0)
        action1 = ImageButton(context)
        whenever(holder.action1).thenReturn(action1)
        whenever(holder.getAction(R.id.action1)).thenReturn(action1)
        action2 = ImageButton(context)
        whenever(holder.action2).thenReturn(action2)
        whenever(holder.getAction(R.id.action2)).thenReturn(action2)
        action3 = ImageButton(context)
        whenever(holder.action3).thenReturn(action3)
        whenever(holder.getAction(R.id.action3)).thenReturn(action3)
        action4 = ImageButton(context)
        whenever(holder.action4).thenReturn(action4)
        whenever(holder.getAction(R.id.action4)).thenReturn(action4)

        // Long press menu
        whenever(holder.longPressText).thenReturn(longPressText)
        whenever(longPressText.handler).thenReturn(handler)
        whenever(holder.settings).thenReturn(settings)
        whenever(holder.settingsText).thenReturn(settingsText)
        whenever(holder.cancel).thenReturn(cancel)
        whenever(holder.cancelText).thenReturn(cancelText)
        whenever(holder.dismiss).thenReturn(dismiss)
        whenever(holder.dismissText).thenReturn(dismissText)
    }

    /** Mock view holder for session player */
    private fun initSessionHolderMocks() {
        whenever(sessionHolder.player).thenReturn(view)
        whenever(sessionHolder.albumView).thenReturn(albumView)
        whenever(sessionHolder.appIcon).thenReturn(appIcon)
        whenever(sessionHolder.titleText).thenReturn(titleText)
        whenever(sessionHolder.artistText).thenReturn(artistText)
        val seamlessBackground = mock(RippleDrawable::class.java)
        whenever(seamlessBackground.getDrawable(0)).thenReturn(mock(GradientDrawable::class.java))
        whenever(sessionHolder.seamless).thenReturn(seamless)
        whenever(sessionHolder.seamlessButton).thenReturn(seamlessButton)
        whenever(sessionHolder.seamlessIcon).thenReturn(seamlessIcon)
        whenever(sessionHolder.seamlessText).thenReturn(seamlessText)
        whenever(sessionHolder.seekBar).thenReturn(seekBar)

        // Action buttons
        actionPlayPause = ImageButton(context)
        whenever(sessionHolder.actionPlayPause).thenReturn(actionPlayPause)
        whenever(sessionHolder.getAction(R.id.actionPlayPause)).thenReturn(actionPlayPause)
        actionNext = ImageButton(context)
        whenever(sessionHolder.actionNext).thenReturn(actionNext)
        whenever(sessionHolder.getAction(R.id.actionNext)).thenReturn(actionNext)
        actionPrev = ImageButton(context)
        whenever(sessionHolder.actionPrev).thenReturn(actionPrev)
        whenever(sessionHolder.getAction(R.id.actionPrev)).thenReturn(actionPrev)
        actionStart = ImageButton(context)
        whenever(sessionHolder.actionStart).thenReturn(actionStart)
        whenever(sessionHolder.getAction(R.id.actionStart)).thenReturn(actionStart)
        actionEnd = ImageButton(context)
        whenever(sessionHolder.actionEnd).thenReturn(actionEnd)
        whenever(sessionHolder.getAction(R.id.actionEnd)).thenReturn(actionEnd)

        // Long press menu
        whenever(sessionHolder.longPressText).thenReturn(longPressText)
        whenever(sessionHolder.settings).thenReturn(settings)
        whenever(sessionHolder.settingsText).thenReturn(settingsText)
        whenever(sessionHolder.cancel).thenReturn(cancel)
        whenever(sessionHolder.cancelText).thenReturn(cancelText)
        whenever(sessionHolder.dismiss).thenReturn(dismiss)
        whenever(sessionHolder.dismissText).thenReturn(dismissText)
    }

    @After
    fun tearDown() {
        session.release()
        player.onDestroy()
    }

    @Test
    fun bindWhenUnattached() {
        val state = mediaData.copy(token = null)
        player.bindPlayer(state, PACKAGE)
        assertThat(player.isPlaying()).isFalse()
    }

    @Test
    fun bindSemanticActionsOldLayout() {
        whenever(mediaFlags.areMediaSessionActionsEnabled()).thenReturn(true)
        whenever(mediaFlags.useMediaSessionLayout()).thenReturn(false)

        val icon = Icon.createWithResource(context, android.R.drawable.ic_media_play)
        val semanticActions = MediaButton(
            playOrPause = MediaAction(icon, Runnable {}, "play"),
            nextOrCustom = MediaAction(icon, Runnable {}, "next"),
            startCustom = MediaAction(icon, null, "custom 1"),
            endCustom = MediaAction(icon, null, "custom 2")
        )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        player.bindPlayer(state, PACKAGE)

        verify(expandedSet).setVisibility(R.id.action0, ConstraintSet.VISIBLE)
        assertThat(action0.contentDescription).isEqualTo("custom 1")
        assertThat(action0.isEnabled()).isFalse()

        verify(expandedSet).setVisibility(R.id.action1, ConstraintSet.INVISIBLE)
        assertThat(action1.isEnabled()).isFalse()

        verify(expandedSet).setVisibility(R.id.action2, ConstraintSet.VISIBLE)
        assertThat(action2.isEnabled()).isTrue()
        assertThat(action2.contentDescription).isEqualTo("play")

        verify(expandedSet).setVisibility(R.id.action3, ConstraintSet.VISIBLE)
        assertThat(action3.isEnabled()).isTrue()
        assertThat(action3.contentDescription).isEqualTo("next")

        verify(expandedSet).setVisibility(R.id.action4, ConstraintSet.VISIBLE)
        assertThat(action4.contentDescription).isEqualTo("custom 2")
        assertThat(action4.isEnabled()).isFalse()
    }

    @Test
    fun bindSemanticActionsNewLayout() {
        whenever(mediaFlags.areMediaSessionActionsEnabled()).thenReturn(true)
        whenever(mediaFlags.useMediaSessionLayout()).thenReturn(true)

        val icon = Icon.createWithResource(context, android.R.drawable.ic_media_play)
        val semanticActions = MediaButton(
                playOrPause = MediaAction(icon, Runnable {}, "play"),
                nextOrCustom = MediaAction(icon, Runnable {}, "next"),
                startCustom = MediaAction(icon, null, "custom 1"),
                endCustom = MediaAction(icon, null, "custom 2")
        )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(sessionHolder, MediaViewController.TYPE.PLAYER_SESSION)
        player.bindPlayer(state, PACKAGE)

        assertThat(actionStart.contentDescription).isEqualTo("custom 1")
        assertThat(actionStart.isEnabled()).isFalse()

        assertThat(actionPrev.isEnabled()).isFalse()
        assertThat(actionPrev.drawable).isNull()

        assertThat(actionPlayPause.isEnabled()).isTrue()
        assertThat(actionPlayPause.contentDescription).isEqualTo("play")

        assertThat(actionNext.isEnabled()).isTrue()
        assertThat(actionNext.contentDescription).isEqualTo("next")

        assertThat(actionEnd.contentDescription).isEqualTo("custom 2")
        assertThat(actionEnd.isEnabled()).isFalse()
    }

    @Test
    fun bindText() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        player.bindPlayer(mediaData, PACKAGE)
        assertThat(titleText.getText()).isEqualTo(TITLE)
        assertThat(artistText.getText()).isEqualTo(ARTIST)
    }

    @Test
    fun bindDevice() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        player.bindPlayer(mediaData, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.contentDescription).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isTrue()
    }

    @Test
    fun bindDisabledDevice() {
        seamless.id = 1
        val fallbackString = context.getString(R.string.media_seamless_other_device)
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        val state = mediaData.copy(device = disabledDevice)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamless.isEnabled()).isFalse()
        assertThat(seamlessText.getText()).isEqualTo(fallbackString)
        assertThat(seamless.contentDescription).isEqualTo(fallbackString)
    }

    @Test
    fun bindNullDevice() {
        val fallbackString = context.getResources().getString(R.string.media_seamless_other_device)
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        val state = mediaData.copy(device = null)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamless.isEnabled()).isTrue()
        assertThat(seamlessText.getText()).isEqualTo(fallbackString)
        assertThat(seamless.contentDescription).isEqualTo(fallbackString)
    }

    @Test
    fun bindDeviceResumptionPlayer() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        val state = mediaData.copy(resumption = true)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isFalse()
    }

    @Test
    fun longClick_gutsClosed() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        whenever(mediaViewController.isGutsVisible).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(holder.player).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(holder.player)
        verify(mediaViewController).openGuts()
    }

    @Test
    fun longClick_gutsOpen() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        whenever(mediaViewController.isGutsVisible).thenReturn(true)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(holder.player).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(holder.player)
        verify(mediaViewController, never()).openGuts()
        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun cancelButtonClick_animation() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)

        cancel.callOnClick()

        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun settingsButtonClick() {
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)

        settings.callOnClick()

        val captor = ArgumentCaptor.forClass(Intent::class.java)
        verify(activityStarter).startActivity(captor.capture(), eq(true))

        assertThat(captor.value.action).isEqualTo(ACTION_MEDIA_CONTROLS_SETTINGS)
    }

    @Test
    fun dismissButtonClick() {
        val mediaKey = "key for dismissal"
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        val state = mediaData.copy(notificationKey = KEY)
        player.bindPlayer(state, mediaKey)

        assertThat(dismiss.isEnabled).isEqualTo(true)
        dismiss.callOnClick()
        verify(mediaDataManager).dismissMediaData(eq(mediaKey), anyLong())
    }

    @Test
    fun dismissButtonDisabled() {
        val mediaKey = "key for dismissal"
        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        val state = mediaData.copy(isClearable = false, notificationKey = KEY)
        player.bindPlayer(state, mediaKey)

        assertThat(dismiss.isEnabled).isEqualTo(false)
    }

    @Test
    fun dismissButtonClick_notInManager() {
        val mediaKey = "key for dismissal"
        whenever(mediaDataManager.dismissMediaData(eq(mediaKey), anyLong())).thenReturn(false)

        player.attachPlayer(holder, MediaViewController.TYPE.PLAYER)
        val state = mediaData.copy(notificationKey = KEY)
        player.bindPlayer(state, mediaKey)

        assertThat(dismiss.isEnabled).isEqualTo(true)
        dismiss.callOnClick()

        verify(mediaDataManager).dismissMediaData(eq(mediaKey), anyLong())
        verify(mediaCarouselController).removePlayer(eq(mediaKey), eq(false), eq(false))
    }
}
