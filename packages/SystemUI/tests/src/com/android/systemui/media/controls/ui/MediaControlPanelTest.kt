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

import android.animation.Animator
import android.animation.AnimatorSet
import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.LiveData
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.widget.CachingIconView
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.bluetooth.BroadcastDialogController
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.models.GutsViewHolder
import com.android.systemui.media.controls.models.player.MediaAction
import com.android.systemui.media.controls.models.player.MediaButton
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.player.MediaDeviceData
import com.android.systemui.media.controls.models.player.MediaViewHolder
import com.android.systemui.media.controls.models.player.SeekBarViewModel
import com.android.systemui.media.controls.models.recommendation.KEY_SMARTSPACE_APP_NAME
import com.android.systemui.media.controls.models.recommendation.RecommendationViewHolder
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData
import com.android.systemui.media.controls.pipeline.EMPTY_SMARTSPACE_MEDIA_DATA
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.media.dialog.MediaOutputDialogFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.surfaceeffects.ripple.MultiRippleView
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseView
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val KEY = "TEST_KEY"
private const val PACKAGE = "PKG"
private const val ARTIST = "ARTIST"
private const val TITLE = "TITLE"
private const val DEVICE_NAME = "DEVICE_NAME"
private const val SESSION_KEY = "SESSION_KEY"
private const val SESSION_ARTIST = "SESSION_ARTIST"
private const val SESSION_TITLE = "SESSION_TITLE"
private const val DISABLED_DEVICE_NAME = "DISABLED_DEVICE_NAME"
private const val REC_APP_NAME = "REC APP NAME"
private const val APP_NAME = "APP_NAME"

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MediaControlPanelTest : SysuiTestCase() {

    private lateinit var player: MediaControlPanel

    private lateinit var bgExecutor: FakeExecutor
    private lateinit var mainExecutor: FakeExecutor
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var broadcastSender: BroadcastSender

    @Mock private lateinit var gutsViewHolder: GutsViewHolder
    @Mock private lateinit var viewHolder: MediaViewHolder
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
    @Mock private lateinit var transitionParent: ViewGroup
    @Mock private lateinit var broadcastDialogController: BroadcastDialogController
    private lateinit var appIcon: ImageView
    @Mock private lateinit var albumView: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var explicitIndicator: CachingIconView
    private lateinit var seamless: ViewGroup
    private lateinit var seamlessButton: View
    @Mock private lateinit var seamlessBackground: RippleDrawable
    private lateinit var seamlessIcon: ImageView
    private lateinit var seamlessText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var action0: ImageButton
    private lateinit var action1: ImageButton
    private lateinit var action2: ImageButton
    private lateinit var action3: ImageButton
    private lateinit var action4: ImageButton
    private lateinit var actionPlayPause: ImageButton
    private lateinit var actionNext: ImageButton
    private lateinit var actionPrev: ImageButton
    private lateinit var scrubbingElapsedTimeView: TextView
    private lateinit var scrubbingTotalTimeView: TextView
    private lateinit var actionsTopBarrier: Barrier
    @Mock private lateinit var gutsText: TextView
    @Mock private lateinit var mockAnimator: AnimatorSet
    private lateinit var settings: ImageButton
    private lateinit var cancel: View
    private lateinit var cancelText: TextView
    private lateinit var dismiss: FrameLayout
    private lateinit var dismissText: TextView
    private lateinit var multiRippleView: MultiRippleView
    private lateinit var turbulenceNoiseView: TurbulenceNoiseView

    private lateinit var session: MediaSession
    private lateinit var device: MediaDeviceData
    private val disabledDevice =
        MediaDeviceData(false, null, DISABLED_DEVICE_NAME, null, showBroadcastButton = false)
    private lateinit var mediaData: MediaData
    private val clock = FakeSystemClock()
    @Mock private lateinit var logger: MediaUiEventLogger
    @Mock private lateinit var instanceId: InstanceId
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var applicationInfo: ApplicationInfo
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var activityIntentHelper: ActivityIntentHelper
    @Mock private lateinit var lockscreenUserManager: NotificationLockscreenUserManager

    @Mock private lateinit var recommendationViewHolder: RecommendationViewHolder
    @Mock private lateinit var smartspaceAction: SmartspaceAction
    private lateinit var smartspaceData: SmartspaceMediaData
    @Mock private lateinit var coverContainer1: ViewGroup
    @Mock private lateinit var coverContainer2: ViewGroup
    @Mock private lateinit var coverContainer3: ViewGroup
    private lateinit var coverItem1: ImageView
    private lateinit var coverItem2: ImageView
    private lateinit var coverItem3: ImageView
    private lateinit var recTitle1: TextView
    private lateinit var recTitle2: TextView
    private lateinit var recTitle3: TextView
    private lateinit var recSubtitle1: TextView
    private lateinit var recSubtitle2: TextView
    private lateinit var recSubtitle3: TextView
    private var shouldShowBroadcastButton: Boolean = false
    private val fakeFeatureFlag =
        FakeFeatureFlags().apply {
            this.set(Flags.UMO_SURFACE_RIPPLE, false)
            this.set(Flags.UMO_TURBULENCE_NOISE, false)
            this.set(Flags.MEDIA_FALSING_PENALTY, true)
            this.set(Flags.MEDIA_EXPLICIT_INDICATOR, true)
        }

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        bgExecutor = FakeExecutor(FakeSystemClock())
        mainExecutor = FakeExecutor(FakeSystemClock())
        whenever(mediaViewController.expandedLayout).thenReturn(expandedSet)
        whenever(mediaViewController.collapsedLayout).thenReturn(collapsedSet)

        // Set up package manager mocks
        val icon = context.getDrawable(R.drawable.ic_android)
        whenever(packageManager.getApplicationIcon(anyString())).thenReturn(icon)
        whenever(packageManager.getApplicationIcon(any(ApplicationInfo::class.java)))
            .thenReturn(icon)
        whenever(packageManager.getApplicationInfo(eq(PACKAGE), anyInt()))
            .thenReturn(applicationInfo)
        whenever(packageManager.getApplicationLabel(any())).thenReturn(PACKAGE)
        context.setMockPackageManager(packageManager)

        player =
            object :
                MediaControlPanel(
                    context,
                    bgExecutor,
                    mainExecutor,
                    activityStarter,
                    broadcastSender,
                    mediaViewController,
                    seekBarViewModel,
                    Lazy { mediaDataManager },
                    mediaOutputDialogFactory,
                    mediaCarouselController,
                    falsingManager,
                    clock,
                    logger,
                    keyguardStateController,
                    activityIntentHelper,
                    lockscreenUserManager,
                    broadcastDialogController,
                    fakeFeatureFlag
                ) {
                override fun loadAnimator(
                    animId: Int,
                    otionInterpolator: Interpolator,
                    vararg targets: View
                ): AnimatorSet {
                    return mockAnimator
                }
            }

        initGutsViewHolderMocks()
        initMediaViewHolderMocks()

        initDeviceMediaData(false, DEVICE_NAME)

        // Set up recommendation view
        initRecommendationViewHolderMocks()

        // Set valid recommendation data
        val extras = Bundle()
        extras.putString(KEY_SMARTSPACE_APP_NAME, REC_APP_NAME)
        val intent =
            Intent().apply {
                putExtras(extras)
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        whenever(smartspaceAction.intent).thenReturn(intent)
        whenever(smartspaceAction.extras).thenReturn(extras)
        smartspaceData =
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                packageName = PACKAGE,
                instanceId = instanceId,
                recommendations = listOf(smartspaceAction, smartspaceAction, smartspaceAction),
                cardAction = smartspaceAction
            )
    }

    private fun initGutsViewHolderMocks() {
        settings = ImageButton(context)
        cancel = View(context)
        cancelText = TextView(context)
        dismiss = FrameLayout(context)
        dismissText = TextView(context)
        whenever(gutsViewHolder.gutsText).thenReturn(gutsText)
        whenever(gutsViewHolder.settings).thenReturn(settings)
        whenever(gutsViewHolder.cancel).thenReturn(cancel)
        whenever(gutsViewHolder.cancelText).thenReturn(cancelText)
        whenever(gutsViewHolder.dismiss).thenReturn(dismiss)
        whenever(gutsViewHolder.dismissText).thenReturn(dismissText)
    }

    private fun initDeviceMediaData(shouldShowBroadcastButton: Boolean, name: String) {
        device =
            MediaDeviceData(true, null, name, null, showBroadcastButton = shouldShowBroadcastButton)

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
        session =
            MediaSession(context, SESSION_KEY).apply {
                setMetadata(metadataBuilder.build())
                setPlaybackState(playbackBuilder.build())
            }
        session.setActive(true)

        mediaData =
            MediaTestUtils.emptyMediaData.copy(
                artist = ARTIST,
                song = TITLE,
                packageName = PACKAGE,
                token = session.sessionToken,
                device = device,
                instanceId = instanceId
            )
    }

    /** Initialize elements in media view holder */
    private fun initMediaViewHolderMocks() {
        whenever(seekBarViewModel.progress).thenReturn(seekBarData)

        // Set up mock views for the players
        appIcon = ImageView(context)
        titleText = TextView(context)
        artistText = TextView(context)
        explicitIndicator = CachingIconView(context).also { it.id = R.id.media_explicit_indicator }
        seamless = FrameLayout(context)
        seamless.foreground = seamlessBackground
        seamlessButton = View(context)
        seamlessIcon = ImageView(context)
        seamlessText = TextView(context)
        seekBar = SeekBar(context).also { it.id = R.id.media_progress_bar }

        action0 = ImageButton(context).also { it.setId(R.id.action0) }
        action1 = ImageButton(context).also { it.setId(R.id.action1) }
        action2 = ImageButton(context).also { it.setId(R.id.action2) }
        action3 = ImageButton(context).also { it.setId(R.id.action3) }
        action4 = ImageButton(context).also { it.setId(R.id.action4) }

        actionPlayPause = ImageButton(context).also { it.setId(R.id.actionPlayPause) }
        actionPrev = ImageButton(context).also { it.setId(R.id.actionPrev) }
        actionNext = ImageButton(context).also { it.setId(R.id.actionNext) }
        scrubbingElapsedTimeView =
            TextView(context).also { it.setId(R.id.media_scrubbing_elapsed_time) }
        scrubbingTotalTimeView =
            TextView(context).also { it.setId(R.id.media_scrubbing_total_time) }

        actionsTopBarrier =
            Barrier(context).also {
                it.id = R.id.media_action_barrier_top
                it.referencedIds =
                    intArrayOf(
                        actionPrev.id,
                        seekBar.id,
                        actionNext.id,
                        action0.id,
                        action1.id,
                        action2.id,
                        action3.id,
                        action4.id
                    )
            }

        multiRippleView = MultiRippleView(context, null)
        turbulenceNoiseView = TurbulenceNoiseView(context, null)

        whenever(viewHolder.player).thenReturn(view)
        whenever(viewHolder.appIcon).thenReturn(appIcon)
        whenever(viewHolder.albumView).thenReturn(albumView)
        whenever(albumView.foreground).thenReturn(mock(Drawable::class.java))
        whenever(viewHolder.titleText).thenReturn(titleText)
        whenever(viewHolder.artistText).thenReturn(artistText)
        whenever(viewHolder.explicitIndicator).thenReturn(explicitIndicator)
        whenever(seamlessBackground.getDrawable(0)).thenReturn(mock(GradientDrawable::class.java))
        whenever(viewHolder.seamless).thenReturn(seamless)
        whenever(viewHolder.seamlessButton).thenReturn(seamlessButton)
        whenever(viewHolder.seamlessIcon).thenReturn(seamlessIcon)
        whenever(viewHolder.seamlessText).thenReturn(seamlessText)
        whenever(viewHolder.seekBar).thenReturn(seekBar)
        whenever(viewHolder.scrubbingElapsedTimeView).thenReturn(scrubbingElapsedTimeView)
        whenever(viewHolder.scrubbingTotalTimeView).thenReturn(scrubbingTotalTimeView)

        whenever(viewHolder.gutsViewHolder).thenReturn(gutsViewHolder)

        // Transition View
        whenever(view.parent).thenReturn(transitionParent)
        whenever(view.rootView).thenReturn(transitionParent)

        // Action buttons
        whenever(viewHolder.actionPlayPause).thenReturn(actionPlayPause)
        whenever(viewHolder.getAction(R.id.actionPlayPause)).thenReturn(actionPlayPause)
        whenever(viewHolder.actionNext).thenReturn(actionNext)
        whenever(viewHolder.getAction(R.id.actionNext)).thenReturn(actionNext)
        whenever(viewHolder.actionPrev).thenReturn(actionPrev)
        whenever(viewHolder.getAction(R.id.actionPrev)).thenReturn(actionPrev)
        whenever(viewHolder.action0).thenReturn(action0)
        whenever(viewHolder.getAction(R.id.action0)).thenReturn(action0)
        whenever(viewHolder.action1).thenReturn(action1)
        whenever(viewHolder.getAction(R.id.action1)).thenReturn(action1)
        whenever(viewHolder.action2).thenReturn(action2)
        whenever(viewHolder.getAction(R.id.action2)).thenReturn(action2)
        whenever(viewHolder.action3).thenReturn(action3)
        whenever(viewHolder.getAction(R.id.action3)).thenReturn(action3)
        whenever(viewHolder.action4).thenReturn(action4)
        whenever(viewHolder.getAction(R.id.action4)).thenReturn(action4)

        whenever(viewHolder.actionsTopBarrier).thenReturn(actionsTopBarrier)

        whenever(viewHolder.multiRippleView).thenReturn(multiRippleView)
        whenever(viewHolder.turbulenceNoiseView).thenReturn(turbulenceNoiseView)
    }

    /** Initialize elements for the recommendation view holder */
    private fun initRecommendationViewHolderMocks() {
        recTitle1 = TextView(context)
        recTitle2 = TextView(context)
        recTitle3 = TextView(context)
        recSubtitle1 = TextView(context)
        recSubtitle2 = TextView(context)
        recSubtitle3 = TextView(context)

        whenever(recommendationViewHolder.recommendations).thenReturn(view)
        whenever(recommendationViewHolder.cardIcon).thenReturn(appIcon)

        // Add a recommendation item
        coverItem1 = ImageView(context).also { it.setId(R.id.media_cover1) }
        coverItem2 = ImageView(context).also { it.setId(R.id.media_cover2) }
        coverItem3 = ImageView(context).also { it.setId(R.id.media_cover3) }

        whenever(recommendationViewHolder.mediaCoverItems)
            .thenReturn(listOf(coverItem1, coverItem2, coverItem3))
        whenever(recommendationViewHolder.mediaCoverContainers)
            .thenReturn(listOf(coverContainer1, coverContainer2, coverContainer3))
        whenever(recommendationViewHolder.mediaTitles)
            .thenReturn(listOf(recTitle1, recTitle2, recTitle3))
        whenever(recommendationViewHolder.mediaSubtitles)
            .thenReturn(listOf(recSubtitle1, recSubtitle2, recSubtitle3))

        whenever(recommendationViewHolder.gutsViewHolder).thenReturn(gutsViewHolder)

        val actionIcon = Icon.createWithResource(context, R.drawable.ic_android)
        whenever(smartspaceAction.icon).thenReturn(actionIcon)

        // Needed for card and item action click
        val mockContext = mock(Context::class.java)
        whenever(view.context).thenReturn(mockContext)
        whenever(coverContainer1.context).thenReturn(mockContext)
        whenever(coverContainer2.context).thenReturn(mockContext)
        whenever(coverContainer3.context).thenReturn(mockContext)
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
    fun bindSemanticActions() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val bg = context.getDrawable(R.drawable.qs_media_round_button_background)
        val semanticActions =
            MediaButton(
                playOrPause = MediaAction(icon, Runnable {}, "play", bg),
                nextOrCustom = MediaAction(icon, Runnable {}, "next", bg),
                custom0 = MediaAction(icon, null, "custom 0", bg),
                custom1 = MediaAction(icon, null, "custom 1", bg)
            )
        val state = mediaData.copy(semanticActions = semanticActions)
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        assertThat(actionPrev.isEnabled()).isFalse()
        assertThat(actionPrev.drawable).isNull()
        verify(collapsedSet).setVisibility(R.id.actionPrev, ConstraintSet.GONE)

        assertThat(actionPlayPause.isEnabled()).isTrue()
        assertThat(actionPlayPause.contentDescription).isEqualTo("play")
        verify(collapsedSet).setVisibility(R.id.actionPlayPause, ConstraintSet.VISIBLE)

        assertThat(actionNext.isEnabled()).isTrue()
        assertThat(actionNext.contentDescription).isEqualTo("next")
        verify(collapsedSet).setVisibility(R.id.actionNext, ConstraintSet.VISIBLE)

        // Called twice since these IDs are used as generic buttons
        assertThat(action0.contentDescription).isEqualTo("custom 0")
        assertThat(action0.isEnabled()).isFalse()
        verify(collapsedSet, times(2)).setVisibility(R.id.action0, ConstraintSet.GONE)

        assertThat(action1.contentDescription).isEqualTo("custom 1")
        assertThat(action1.isEnabled()).isFalse()
        verify(collapsedSet, times(2)).setVisibility(R.id.action1, ConstraintSet.GONE)

        // Verify generic buttons are hidden
        verify(collapsedSet).setVisibility(R.id.action2, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.action2, ConstraintSet.GONE)

        verify(collapsedSet).setVisibility(R.id.action3, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.action3, ConstraintSet.GONE)

        verify(collapsedSet).setVisibility(R.id.action4, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.action4, ConstraintSet.GONE)
    }

    @Test
    fun bindSemanticActions_reservedPrev() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val bg = context.getDrawable(R.drawable.qs_media_round_button_background)

        // Setup button state: no prev or next button and their slots reserved
        val semanticActions =
            MediaButton(
                playOrPause = MediaAction(icon, Runnable {}, "play", bg),
                nextOrCustom = null,
                prevOrCustom = null,
                custom0 = MediaAction(icon, null, "custom 0", bg),
                custom1 = MediaAction(icon, null, "custom 1", bg),
                false,
                true
            )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        assertThat(actionPrev.isEnabled()).isFalse()
        assertThat(actionPrev.drawable).isNull()
        verify(expandedSet).setVisibility(R.id.actionPrev, ConstraintSet.INVISIBLE)

        assertThat(actionNext.isEnabled()).isFalse()
        assertThat(actionNext.drawable).isNull()
        verify(expandedSet).setVisibility(R.id.actionNext, ConstraintSet.GONE)
    }

    @Test
    fun bindSemanticActions_reservedNext() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val bg = context.getDrawable(R.drawable.qs_media_round_button_background)

        // Setup button state: no prev or next button and their slots reserved
        val semanticActions =
            MediaButton(
                playOrPause = MediaAction(icon, Runnable {}, "play", bg),
                nextOrCustom = null,
                prevOrCustom = null,
                custom0 = MediaAction(icon, null, "custom 0", bg),
                custom1 = MediaAction(icon, null, "custom 1", bg),
                true,
                false
            )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        assertThat(actionPrev.isEnabled()).isFalse()
        assertThat(actionPrev.drawable).isNull()
        verify(expandedSet).setVisibility(R.id.actionPrev, ConstraintSet.GONE)

        assertThat(actionNext.isEnabled()).isFalse()
        assertThat(actionNext.drawable).isNull()
        verify(expandedSet).setVisibility(R.id.actionNext, ConstraintSet.INVISIBLE)
    }

    @Test
    fun bindAlbumView_testHardwareAfterAttach() {
        player.attachPlayer(viewHolder)

        verify(albumView).setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    @Test
    fun bindAlbumView_artUsesResource() {
        val albumArt = Icon.createWithResource(context, R.drawable.ic_android)
        val state = mediaData.copy(artwork = albumArt)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        bgExecutor.runAllReady()
        mainExecutor.runAllReady()

        verify(albumView).setImageDrawable(any(Drawable::class.java))
    }

    @Test
    fun bindAlbumView_setAfterExecutors() {
        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.RED)
        val albumArt = Icon.createWithBitmap(bmp)
        val state = mediaData.copy(artwork = albumArt)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        bgExecutor.runAllReady()
        mainExecutor.runAllReady()

        verify(albumView).setImageDrawable(any(Drawable::class.java))
    }

    @Test
    fun bindAlbumView_bitmapInLaterStates_setAfterExecutors() {
        val redBmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val redCanvas = Canvas(redBmp)
        redCanvas.drawColor(Color.RED)
        val redArt = Icon.createWithBitmap(redBmp)

        val greenBmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val greenCanvas = Canvas(greenBmp)
        greenCanvas.drawColor(Color.GREEN)
        val greenArt = Icon.createWithBitmap(greenBmp)

        val state0 = mediaData.copy(artwork = null)
        val state1 = mediaData.copy(artwork = redArt)
        val state2 = mediaData.copy(artwork = redArt)
        val state3 = mediaData.copy(artwork = greenArt)
        player.attachPlayer(viewHolder)

        // First binding sets (empty) drawable
        player.bindPlayer(state0, PACKAGE)
        bgExecutor.runAllReady()
        mainExecutor.runAllReady()
        verify(albumView).setImageDrawable(any(Drawable::class.java))

        // Run Metadata update so that later states don't update
        val captor = argumentCaptor<Animator.AnimatorListener>()
        verify(mockAnimator, times(2)).addListener(captor.capture())
        captor.value.onAnimationEnd(mockAnimator)
        assertThat(titleText.getText()).isEqualTo(TITLE)
        assertThat(artistText.getText()).isEqualTo(ARTIST)

        // Second binding sets transition drawable
        player.bindPlayer(state1, PACKAGE)
        bgExecutor.runAllReady()
        mainExecutor.runAllReady()
        val drawableCaptor = argumentCaptor<Drawable>()
        verify(albumView, times(2)).setImageDrawable(drawableCaptor.capture())
        assertTrue(drawableCaptor.allValues[1] is TransitionDrawable)

        // Third binding doesn't run transition or update background
        player.bindPlayer(state2, PACKAGE)
        bgExecutor.runAllReady()
        mainExecutor.runAllReady()
        verify(albumView, times(2)).setImageDrawable(any(Drawable::class.java))

        // Fourth binding to new image runs transition due to color scheme change
        player.bindPlayer(state3, PACKAGE)
        bgExecutor.runAllReady()
        mainExecutor.runAllReady()
        verify(albumView, times(3)).setImageDrawable(any(Drawable::class.java))
    }

    @Test
    fun bind_seekBarDisabled_hasActions_seekBarVisibilityIsSetToInvisible() {
        useRealConstraintSets()

        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(
                playOrPause = MediaAction(icon, Runnable {}, "play", null),
                nextOrCustom = MediaAction(icon, Runnable {}, "next", null)
            )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = false)

        player.bindPlayer(state, PACKAGE)

        assertThat(expandedSet.getVisibility(seekBar.id)).isEqualTo(ConstraintSet.INVISIBLE)
    }

    @Test
    fun bind_seekBarDisabled_noActions_seekBarVisibilityIsSetToInvisible() {
        useRealConstraintSets()

        val state = mediaData.copy(semanticActions = MediaButton())
        player.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = false)

        player.bindPlayer(state, PACKAGE)

        assertThat(expandedSet.getVisibility(seekBar.id)).isEqualTo(ConstraintSet.INVISIBLE)
    }

    @Test
    fun bind_seekBarEnabled_seekBarVisible() {
        useRealConstraintSets()

        val state = mediaData.copy(semanticActions = MediaButton())
        player.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = true)

        player.bindPlayer(state, PACKAGE)

        assertThat(expandedSet.getVisibility(seekBar.id)).isEqualTo(ConstraintSet.VISIBLE)
    }

    @Test
    fun seekBarChangesToEnabledAfterBind_seekBarChangesToVisible() {
        useRealConstraintSets()

        val state = mediaData.copy(semanticActions = MediaButton())
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        getEnabledChangeListener().onEnabledChanged(enabled = true)

        assertThat(expandedSet.getVisibility(seekBar.id)).isEqualTo(ConstraintSet.VISIBLE)
    }

    @Test
    fun seekBarChangesToDisabledAfterBind_noActions_seekBarChangesToInvisible() {
        useRealConstraintSets()

        val state = mediaData.copy(semanticActions = MediaButton())

        player.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = true)
        player.bindPlayer(state, PACKAGE)

        getEnabledChangeListener().onEnabledChanged(enabled = false)

        assertThat(expandedSet.getVisibility(seekBar.id)).isEqualTo(ConstraintSet.INVISIBLE)
    }

    @Test
    fun seekBarChangesToDisabledAfterBind_hasActions_seekBarChangesToInvisible() {
        useRealConstraintSets()

        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(nextOrCustom = MediaAction(icon, Runnable {}, "next", null))
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = true)
        player.bindPlayer(state, PACKAGE)

        getEnabledChangeListener().onEnabledChanged(enabled = false)

        assertThat(expandedSet.getVisibility(seekBar.id)).isEqualTo(ConstraintSet.INVISIBLE)
    }

    @Test
    fun bind_notScrubbing_scrubbingViewsGone() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(
                prevOrCustom = MediaAction(icon, {}, "prev", null),
                nextOrCustom = MediaAction(icon, {}, "next", null)
            )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        verify(expandedSet).setVisibility(R.id.media_scrubbing_elapsed_time, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_total_time, ConstraintSet.GONE)
    }

    @Test
    fun setIsScrubbing_noSemanticActions_viewsNotChanged() {
        val state = mediaData.copy(semanticActions = null)
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        reset(expandedSet)

        val listener = getScrubbingChangeListener()

        listener.onScrubbingChanged(true)
        mainExecutor.runAllReady()

        verify(expandedSet, never()).setVisibility(eq(R.id.actionPrev), anyInt())
        verify(expandedSet, never()).setVisibility(eq(R.id.actionNext), anyInt())
        verify(expandedSet, never()).setVisibility(eq(R.id.media_scrubbing_elapsed_time), anyInt())
        verify(expandedSet, never()).setVisibility(eq(R.id.media_scrubbing_total_time), anyInt())
    }

    @Test
    fun setIsScrubbing_noPrevButton_scrubbingTimesNotShown() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(prevOrCustom = null, nextOrCustom = MediaAction(icon, {}, "next", null))
        val state = mediaData.copy(semanticActions = semanticActions)
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        reset(expandedSet)

        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        verify(expandedSet).setVisibility(R.id.actionNext, View.VISIBLE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_elapsed_time, View.GONE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_total_time, View.GONE)
    }

    @Test
    fun setIsScrubbing_noNextButton_scrubbingTimesNotShown() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(prevOrCustom = MediaAction(icon, {}, "prev", null), nextOrCustom = null)
        val state = mediaData.copy(semanticActions = semanticActions)
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        reset(expandedSet)

        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        verify(expandedSet).setVisibility(R.id.actionPrev, View.VISIBLE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_elapsed_time, View.GONE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_total_time, View.GONE)
    }

    @Test
    fun setIsScrubbing_true_scrubbingViewsShownAndPrevNextHiddenOnlyInExpanded() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(
                prevOrCustom = MediaAction(icon, {}, "prev", null),
                nextOrCustom = MediaAction(icon, {}, "next", null)
            )
        val state = mediaData.copy(semanticActions = semanticActions)
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        reset(expandedSet)

        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        // Only in expanded, we should show the scrubbing times and hide prev+next
        verify(expandedSet).setVisibility(R.id.media_scrubbing_elapsed_time, ConstraintSet.VISIBLE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_total_time, ConstraintSet.VISIBLE)
        verify(expandedSet).setVisibility(R.id.actionPrev, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.actionNext, ConstraintSet.GONE)
    }

    @Test
    fun setIsScrubbing_trueThenFalse_scrubbingTimeGoneAtEnd() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val semanticActions =
            MediaButton(
                prevOrCustom = MediaAction(icon, {}, "prev", null),
                nextOrCustom = MediaAction(icon, {}, "next", null)
            )
        val state = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()
        reset(expandedSet)

        getScrubbingChangeListener().onScrubbingChanged(false)
        mainExecutor.runAllReady()

        // Only in expanded, we should hide the scrubbing times and show prev+next
        verify(expandedSet).setVisibility(R.id.media_scrubbing_elapsed_time, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.media_scrubbing_total_time, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.actionPrev, ConstraintSet.VISIBLE)
        verify(expandedSet).setVisibility(R.id.actionNext, ConstraintSet.VISIBLE)
    }

    @Test
    fun bindNotificationActions() {
        val icon = context.getDrawable(android.R.drawable.ic_media_play)
        val bg = context.getDrawable(R.drawable.qs_media_round_button_background)
        val actions =
            listOf(
                MediaAction(icon, Runnable {}, "previous", bg),
                MediaAction(icon, Runnable {}, "play", bg),
                MediaAction(icon, null, "next", bg),
                MediaAction(icon, null, "custom 0", bg),
                MediaAction(icon, Runnable {}, "custom 1", bg)
            )
        val state =
            mediaData.copy(
                actions = actions,
                actionsToShowInCompact = listOf(1, 2),
                semanticActions = null
            )

        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)

        // Verify semantic actions are hidden
        verify(collapsedSet).setVisibility(R.id.actionPrev, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.actionPrev, ConstraintSet.GONE)

        verify(collapsedSet).setVisibility(R.id.actionPlayPause, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.actionPlayPause, ConstraintSet.GONE)

        verify(collapsedSet).setVisibility(R.id.actionNext, ConstraintSet.GONE)
        verify(expandedSet).setVisibility(R.id.actionNext, ConstraintSet.GONE)

        // Generic actions all enabled
        assertThat(action0.contentDescription).isEqualTo("previous")
        assertThat(action0.isEnabled()).isTrue()
        verify(collapsedSet).setVisibility(R.id.action0, ConstraintSet.GONE)

        assertThat(action1.contentDescription).isEqualTo("play")
        assertThat(action1.isEnabled()).isTrue()
        verify(collapsedSet).setVisibility(R.id.action1, ConstraintSet.VISIBLE)

        assertThat(action2.contentDescription).isEqualTo("next")
        assertThat(action2.isEnabled()).isFalse()
        verify(collapsedSet).setVisibility(R.id.action2, ConstraintSet.VISIBLE)

        assertThat(action3.contentDescription).isEqualTo("custom 0")
        assertThat(action3.isEnabled()).isFalse()
        verify(collapsedSet).setVisibility(R.id.action3, ConstraintSet.GONE)

        assertThat(action4.contentDescription).isEqualTo("custom 1")
        assertThat(action4.isEnabled()).isTrue()
        verify(collapsedSet).setVisibility(R.id.action4, ConstraintSet.GONE)
    }

    @Test
    fun bindAnimatedSemanticActions() {
        val mockAvd0 = mock(AnimatedVectorDrawable::class.java)
        val mockAvd1 = mock(AnimatedVectorDrawable::class.java)
        val mockAvd2 = mock(AnimatedVectorDrawable::class.java)
        whenever(mockAvd0.mutate()).thenReturn(mockAvd0)
        whenever(mockAvd1.mutate()).thenReturn(mockAvd1)
        whenever(mockAvd2.mutate()).thenReturn(mockAvd2)

        val icon = context.getDrawable(R.drawable.ic_media_play)
        val bg = context.getDrawable(R.drawable.ic_media_play_container)
        val semanticActions0 =
            MediaButton(playOrPause = MediaAction(mockAvd0, Runnable {}, "play", null))
        val semanticActions1 =
            MediaButton(playOrPause = MediaAction(mockAvd1, Runnable {}, "pause", null))
        val semanticActions2 =
            MediaButton(playOrPause = MediaAction(mockAvd2, Runnable {}, "loading", null))
        val state0 = mediaData.copy(semanticActions = semanticActions0)
        val state1 = mediaData.copy(semanticActions = semanticActions1)
        val state2 = mediaData.copy(semanticActions = semanticActions2)

        player.attachPlayer(viewHolder)
        player.bindPlayer(state0, PACKAGE)

        // Validate first binding
        assertThat(actionPlayPause.isEnabled()).isTrue()
        assertThat(actionPlayPause.contentDescription).isEqualTo("play")
        assertThat(actionPlayPause.getBackground()).isNull()
        verify(collapsedSet).setVisibility(R.id.actionPlayPause, ConstraintSet.VISIBLE)
        assertThat(actionPlayPause.hasOnClickListeners()).isTrue()

        // Trigger animation & update mock
        actionPlayPause.performClick()
        verify(mockAvd0, times(1)).start()
        whenever(mockAvd0.isRunning()).thenReturn(true)

        // Validate states no longer bind
        player.bindPlayer(state1, PACKAGE)
        player.bindPlayer(state2, PACKAGE)
        assertThat(actionPlayPause.contentDescription).isEqualTo("play")

        // Complete animation and run callbacks
        whenever(mockAvd0.isRunning()).thenReturn(false)
        val captor = ArgumentCaptor.forClass(Animatable2.AnimationCallback::class.java)
        verify(mockAvd0, times(1)).registerAnimationCallback(captor.capture())
        verify(mockAvd1, never())
            .registerAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        verify(mockAvd2, never())
            .registerAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        captor.getValue().onAnimationEnd(mockAvd0)

        // Validate correct state was bound
        assertThat(actionPlayPause.contentDescription).isEqualTo("loading")
        assertThat(actionPlayPause.getBackground()).isNull()
        verify(mockAvd0, times(1))
            .registerAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        verify(mockAvd1, times(1))
            .registerAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        verify(mockAvd2, times(1))
            .registerAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        verify(mockAvd0, times(1))
            .unregisterAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        verify(mockAvd1, times(1))
            .unregisterAnimationCallback(any(Animatable2.AnimationCallback::class.java))
        verify(mockAvd2, never())
            .unregisterAnimationCallback(any(Animatable2.AnimationCallback::class.java))
    }

    @Test
    fun bindText() {
        useRealConstraintSets()
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, PACKAGE)

        // Capture animation handler
        val captor = argumentCaptor<Animator.AnimatorListener>()
        verify(mockAnimator, times(2)).addListener(captor.capture())
        val handler = captor.value

        // Validate text views unchanged but animation started
        assertThat(titleText.getText()).isEqualTo("")
        assertThat(artistText.getText()).isEqualTo("")
        verify(mockAnimator, times(1)).start()

        // Binding only after animator runs
        handler.onAnimationEnd(mockAnimator)
        assertThat(titleText.getText()).isEqualTo(TITLE)
        assertThat(artistText.getText()).isEqualTo(ARTIST)
        assertThat(expandedSet.getVisibility(explicitIndicator.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(collapsedSet.getVisibility(explicitIndicator.id)).isEqualTo(ConstraintSet.GONE)

        // Rebinding should not trigger animation
        player.bindPlayer(mediaData, PACKAGE)
        verify(mockAnimator, times(2)).start()
    }

    @Test
    fun bindTextWithExplicitIndicator() {
        useRealConstraintSets()
        val mediaDataWitExp = mediaData.copy(isExplicit = true)
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaDataWitExp, PACKAGE)

        // Capture animation handler
        val captor = argumentCaptor<Animator.AnimatorListener>()
        verify(mockAnimator, times(2)).addListener(captor.capture())
        val handler = captor.value

        // Validate text views unchanged but animation started
        assertThat(titleText.getText()).isEqualTo("")
        assertThat(artistText.getText()).isEqualTo("")
        verify(mockAnimator, times(1)).start()

        // Binding only after animator runs
        handler.onAnimationEnd(mockAnimator)
        assertThat(titleText.getText()).isEqualTo(TITLE)
        assertThat(artistText.getText()).isEqualTo(ARTIST)
        assertThat(expandedSet.getVisibility(explicitIndicator.id)).isEqualTo(ConstraintSet.VISIBLE)
        assertThat(collapsedSet.getVisibility(explicitIndicator.id))
            .isEqualTo(ConstraintSet.VISIBLE)

        // Rebinding should not trigger animation
        player.bindPlayer(mediaData, PACKAGE)
        verify(mockAnimator, times(3)).start()
    }

    @Test
    fun bindTextInterrupted() {
        val data0 = mediaData.copy(artist = "ARTIST_0")
        val data1 = mediaData.copy(artist = "ARTIST_1")
        val data2 = mediaData.copy(artist = "ARTIST_2")

        player.attachPlayer(viewHolder)
        player.bindPlayer(data0, PACKAGE)

        // Capture animation handler
        val captor = argumentCaptor<Animator.AnimatorListener>()
        verify(mockAnimator, times(2)).addListener(captor.capture())
        val handler = captor.value

        handler.onAnimationEnd(mockAnimator)
        assertThat(artistText.getText()).isEqualTo("ARTIST_0")

        // Bind trigges new animation
        player.bindPlayer(data1, PACKAGE)
        verify(mockAnimator, times(3)).start()
        whenever(mockAnimator.isRunning()).thenReturn(true)

        // Rebind before animation end binds corrct data
        player.bindPlayer(data2, PACKAGE)
        handler.onAnimationEnd(mockAnimator)
        assertThat(artistText.getText()).isEqualTo("ARTIST_2")
    }

    @Test
    fun bindDevice() {
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.contentDescription).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isTrue()
    }

    @Test
    fun bindDisabledDevice() {
        seamless.id = 1
        player.attachPlayer(viewHolder)
        val state = mediaData.copy(device = disabledDevice)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamless.isEnabled()).isFalse()
        assertThat(seamlessText.getText()).isEqualTo(DISABLED_DEVICE_NAME)
        assertThat(seamless.contentDescription).isEqualTo(DISABLED_DEVICE_NAME)
    }

    @Test
    fun bindNullDevice() {
        val fallbackString = context.getResources().getString(R.string.media_seamless_other_device)
        player.attachPlayer(viewHolder)
        val state = mediaData.copy(device = null)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamless.isEnabled()).isTrue()
        assertThat(seamlessText.getText()).isEqualTo(fallbackString)
        assertThat(seamless.contentDescription).isEqualTo(fallbackString)
    }

    @Test
    fun bindDeviceWithNullName() {
        val fallbackString = context.getResources().getString(R.string.media_seamless_other_device)
        player.attachPlayer(viewHolder)
        val state = mediaData.copy(device = device.copy(name = null))
        player.bindPlayer(state, PACKAGE)
        assertThat(seamless.isEnabled()).isTrue()
        assertThat(seamlessText.getText()).isEqualTo(fallbackString)
        assertThat(seamless.contentDescription).isEqualTo(fallbackString)
    }

    @Test
    fun bindDeviceResumptionPlayer() {
        player.attachPlayer(viewHolder)
        val state = mediaData.copy(resumption = true)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(DEVICE_NAME)
        assertThat(seamless.isEnabled()).isFalse()
    }

    @Test
    fun bindBroadcastButton() {
        initMediaViewHolderMocks()
        initDeviceMediaData(true, APP_NAME)

        val mockAvd0 = mock(AnimatedVectorDrawable::class.java)
        whenever(mockAvd0.mutate()).thenReturn(mockAvd0)
        val semanticActions0 =
            MediaButton(playOrPause = MediaAction(mockAvd0, Runnable {}, "play", null))
        val state =
            mediaData.copy(resumption = true, semanticActions = semanticActions0, isPlaying = false)
        player.attachPlayer(viewHolder)
        player.bindPlayer(state, PACKAGE)
        assertThat(seamlessText.getText()).isEqualTo(APP_NAME)
        assertThat(seamless.isEnabled()).isTrue()

        seamless.callOnClick()

        verify(logger).logOpenBroadcastDialog(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    /* ***** Guts tests for the player ***** */

    @Test
    fun player_longClick_isFalse() {
        whenever(falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)).thenReturn(true)
        player.attachPlayer(viewHolder)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()

        captor.value.onLongClick(viewHolder.player)
        verify(mediaViewController, never()).openGuts()
        verify(mediaViewController, never()).closeGuts()
    }

    @Test
    fun player_longClickWhenGutsClosed_gutsOpens() {
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, KEY)
        whenever(mediaViewController.isGutsVisible).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(viewHolder.player)
        verify(mediaViewController).openGuts()
        verify(logger).logLongPressOpen(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun player_longClickWhenGutsOpen_gutsCloses() {
        player.attachPlayer(viewHolder)
        whenever(mediaViewController.isGutsVisible).thenReturn(true)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(viewHolder.player)
        verify(mediaViewController, never()).openGuts()
        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun player_cancelButtonClick_animation() {
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, KEY)

        cancel.callOnClick()

        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun player_settingsButtonClick() {
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, KEY)

        settings.callOnClick()
        verify(logger).logLongPressSettings(anyInt(), eq(PACKAGE), eq(instanceId))

        val captor = ArgumentCaptor.forClass(Intent::class.java)
        verify(activityStarter).startActivity(captor.capture(), eq(true))

        assertThat(captor.value.action).isEqualTo(ACTION_MEDIA_CONTROLS_SETTINGS)
    }

    @Test
    fun player_dismissButtonClick() {
        val mediaKey = "key for dismissal"
        player.attachPlayer(viewHolder)
        val state = mediaData.copy(notificationKey = KEY)
        player.bindPlayer(state, mediaKey)

        assertThat(dismiss.isEnabled).isEqualTo(true)
        dismiss.callOnClick()
        verify(logger).logLongPressDismiss(anyInt(), eq(PACKAGE), eq(instanceId))
        verify(mediaDataManager).dismissMediaData(eq(mediaKey), anyLong())
    }

    @Test
    fun player_dismissButtonDisabled() {
        val mediaKey = "key for dismissal"
        player.attachPlayer(viewHolder)
        val state = mediaData.copy(isClearable = false, notificationKey = KEY)
        player.bindPlayer(state, mediaKey)

        assertThat(dismiss.isEnabled).isEqualTo(false)
    }

    @Test
    fun player_dismissButtonClick_notInManager() {
        val mediaKey = "key for dismissal"
        whenever(mediaDataManager.dismissMediaData(eq(mediaKey), anyLong())).thenReturn(false)

        player.attachPlayer(viewHolder)
        val state = mediaData.copy(notificationKey = KEY)
        player.bindPlayer(state, mediaKey)

        assertThat(dismiss.isEnabled).isEqualTo(true)
        dismiss.callOnClick()

        verify(mediaDataManager).dismissMediaData(eq(mediaKey), anyLong())
        verify(mediaCarouselController).removePlayer(eq(mediaKey), eq(false), eq(false))
    }

    @Test
    fun player_gutsOpen_contentDescriptionIsForGuts() {
        whenever(mediaViewController.isGutsVisible).thenReturn(true)
        player.attachPlayer(viewHolder)

        val gutsTextString = "gutsText"
        whenever(gutsText.text).thenReturn(gutsTextString)
        player.bindPlayer(mediaData, KEY)

        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).isEqualTo(gutsTextString)
    }

    @Test
    fun player_gutsClosed_contentDescriptionIsForPlayer() {
        whenever(mediaViewController.isGutsVisible).thenReturn(false)
        player.attachPlayer(viewHolder)

        val app = "appName"
        player.bindPlayer(mediaData.copy(app = app), KEY)

        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).contains(mediaData.song!!)
        assertThat(description).contains(mediaData.artist!!)
        assertThat(description).contains(app)
    }

    @Test
    fun player_gutsChangesFromOpenToClosed_contentDescriptionUpdated() {
        // Start out open
        whenever(mediaViewController.isGutsVisible).thenReturn(true)
        whenever(gutsText.text).thenReturn("gutsText")
        player.attachPlayer(viewHolder)
        val app = "appName"
        player.bindPlayer(mediaData.copy(app = app), KEY)

        // Update to closed by long pressing
        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()
        reset(viewHolder.player)

        whenever(mediaViewController.isGutsVisible).thenReturn(false)
        captor.value.onLongClick(viewHolder.player)

        // Then content description is now the player content description
        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).contains(mediaData.song!!)
        assertThat(description).contains(mediaData.artist!!)
        assertThat(description).contains(app)
    }

    @Test
    fun player_gutsChangesFromClosedToOpen_contentDescriptionUpdated() {
        // Start out closed
        whenever(mediaViewController.isGutsVisible).thenReturn(false)
        val gutsTextString = "gutsText"
        whenever(gutsText.text).thenReturn(gutsTextString)
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData.copy(app = "appName"), KEY)

        // Update to open by long pressing
        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()
        reset(viewHolder.player)

        whenever(mediaViewController.isGutsVisible).thenReturn(true)
        captor.value.onLongClick(viewHolder.player)

        // Then content description is now the guts content description
        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).isEqualTo(gutsTextString)
    }

    /* ***** END guts tests for the player ***** */

    /* ***** Guts tests for the recommendations ***** */

    @Test
    fun recommendations_longClick_isFalse() {
        whenever(falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)).thenReturn(true)
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()

        captor.value.onLongClick(viewHolder.player)
        verify(mediaViewController, never()).openGuts()
        verify(mediaViewController, never()).closeGuts()
    }

    @Test
    fun recommendations_longClickWhenGutsClosed_gutsOpens() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)
        whenever(mediaViewController.isGutsVisible).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()

        captor.value.onLongClick(viewHolder.player)
        verify(mediaViewController).openGuts()
        verify(logger).logLongPressOpen(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun recommendations_longClickWhenGutsOpen_gutsCloses() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)
        whenever(mediaViewController.isGutsVisible).thenReturn(true)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()

        captor.value.onLongClick(viewHolder.player)
        verify(mediaViewController, never()).openGuts()
        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun recommendations_cancelButtonClick_animation() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        cancel.callOnClick()

        verify(mediaViewController).closeGuts(false)
    }

    @Test
    fun recommendations_settingsButtonClick() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        settings.callOnClick()
        verify(logger).logLongPressSettings(anyInt(), eq(PACKAGE), eq(instanceId))

        val captor = ArgumentCaptor.forClass(Intent::class.java)
        verify(activityStarter).startActivity(captor.capture(), eq(true))

        assertThat(captor.value.action).isEqualTo(ACTION_MEDIA_CONTROLS_SETTINGS)
    }

    @Test
    fun recommendations_dismissButtonClick() {
        val mediaKey = "key for dismissal"
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData.copy(targetId = mediaKey))

        assertThat(dismiss.isEnabled).isEqualTo(true)
        dismiss.callOnClick()
        verify(logger).logLongPressDismiss(anyInt(), eq(PACKAGE), eq(instanceId))
        verify(mediaDataManager).dismissSmartspaceRecommendation(eq(mediaKey), anyLong())
    }

    @Test
    fun recommendation_gutsOpen_contentDescriptionIsForGuts() {
        whenever(mediaViewController.isGutsVisible).thenReturn(true)
        player.attachRecommendation(recommendationViewHolder)

        val gutsTextString = "gutsText"
        whenever(gutsText.text).thenReturn(gutsTextString)
        player.bindRecommendation(smartspaceData)

        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).isEqualTo(gutsTextString)
    }

    @Test
    fun recommendation_gutsClosed_contentDescriptionIsForPlayer() {
        whenever(mediaViewController.isGutsVisible).thenReturn(false)
        player.attachRecommendation(recommendationViewHolder)

        player.bindRecommendation(smartspaceData)

        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).contains(REC_APP_NAME)
    }

    @Test
    fun recommendation_gutsChangesFromOpenToClosed_contentDescriptionUpdated() {
        // Start out open
        whenever(mediaViewController.isGutsVisible).thenReturn(true)
        whenever(gutsText.text).thenReturn("gutsText")
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        // Update to closed by long pressing
        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()
        reset(viewHolder.player)

        whenever(mediaViewController.isGutsVisible).thenReturn(false)
        captor.value.onLongClick(viewHolder.player)

        // Then content description is now the player content description
        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).contains(REC_APP_NAME)
    }

    @Test
    fun recommendation_gutsChangesFromClosedToOpen_contentDescriptionUpdated() {
        // Start out closed
        whenever(mediaViewController.isGutsVisible).thenReturn(false)
        val gutsTextString = "gutsText"
        whenever(gutsText.text).thenReturn(gutsTextString)
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        // Update to open by long pressing
        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(viewHolder.player).onLongClickListener = captor.capture()
        reset(viewHolder.player)

        whenever(mediaViewController.isGutsVisible).thenReturn(true)
        captor.value.onLongClick(viewHolder.player)

        // Then content description is now the guts content description
        val descriptionCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(viewHolder.player).contentDescription = descriptionCaptor.capture()
        val description = descriptionCaptor.value.toString()

        assertThat(description).isEqualTo(gutsTextString)
    }

    /* ***** END guts tests for the recommendations ***** */

    @Test
    fun actionPlayPauseClick_isLogged() {
        val semanticActions =
            MediaButton(playOrPause = MediaAction(null, Runnable {}, "play", null))
        val data = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.actionPlayPause.callOnClick()
        verify(logger).logTapAction(eq(R.id.actionPlayPause), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionPrevClick_isLogged() {
        val semanticActions =
            MediaButton(prevOrCustom = MediaAction(null, Runnable {}, "previous", null))
        val data = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.actionPrev.callOnClick()
        verify(logger).logTapAction(eq(R.id.actionPrev), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionNextClick_isLogged() {
        val semanticActions =
            MediaButton(nextOrCustom = MediaAction(null, Runnable {}, "next", null))
        val data = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.actionNext.callOnClick()
        verify(logger).logTapAction(eq(R.id.actionNext), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionCustom0Click_isLogged() {
        val semanticActions =
            MediaButton(custom0 = MediaAction(null, Runnable {}, "custom 0", null))
        val data = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.action0.callOnClick()
        verify(logger).logTapAction(eq(R.id.action0), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionCustom1Click_isLogged() {
        val semanticActions =
            MediaButton(custom1 = MediaAction(null, Runnable {}, "custom 1", null))
        val data = mediaData.copy(semanticActions = semanticActions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.action1.callOnClick()
        verify(logger).logTapAction(eq(R.id.action1), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionCustom2Click_isLogged() {
        val actions =
            listOf(
                MediaAction(null, Runnable {}, "action 0", null),
                MediaAction(null, Runnable {}, "action 1", null),
                MediaAction(null, Runnable {}, "action 2", null),
                MediaAction(null, Runnable {}, "action 3", null),
                MediaAction(null, Runnable {}, "action 4", null)
            )
        val data = mediaData.copy(actions = actions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.action2.callOnClick()
        verify(logger).logTapAction(eq(R.id.action2), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionCustom3Click_isLogged() {
        val actions =
            listOf(
                MediaAction(null, Runnable {}, "action 0", null),
                MediaAction(null, Runnable {}, "action 1", null),
                MediaAction(null, Runnable {}, "action 2", null),
                MediaAction(null, Runnable {}, "action 3", null),
                MediaAction(null, Runnable {}, "action 4", null)
            )
        val data = mediaData.copy(actions = actions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.action1.callOnClick()
        verify(logger).logTapAction(eq(R.id.action1), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun actionCustom4Click_isLogged() {
        val actions =
            listOf(
                MediaAction(null, Runnable {}, "action 0", null),
                MediaAction(null, Runnable {}, "action 1", null),
                MediaAction(null, Runnable {}, "action 2", null),
                MediaAction(null, Runnable {}, "action 3", null),
                MediaAction(null, Runnable {}, "action 4", null)
            )
        val data = mediaData.copy(actions = actions)

        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.action1.callOnClick()
        verify(logger).logTapAction(eq(R.id.action1), anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun openOutputSwitcher_isLogged() {
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, KEY)

        seamless.callOnClick()

        verify(logger).logOpenOutputSwitcher(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun tapContentView_isLogged() {
        val pendingIntent = mock(PendingIntent::class.java)
        val captor = ArgumentCaptor.forClass(View.OnClickListener::class.java)
        val data = mediaData.copy(clickIntent = pendingIntent)
        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)
        verify(viewHolder.player).setOnClickListener(captor.capture())

        captor.value.onClick(viewHolder.player)

        verify(logger).logTapContentView(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun logSeek() {
        player.attachPlayer(viewHolder)
        player.bindPlayer(mediaData, KEY)

        val callback: () -> Unit = {}
        val captor = KotlinArgumentCaptor(callback::class.java)
        verify(seekBarViewModel).logSeek = captor.capture()
        captor.value.invoke()

        verify(logger).logSeek(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun tapContentView_showOverLockscreen_openActivity() {
        // WHEN we are on lockscreen and this activity can show over lockscreen
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(activityIntentHelper.wouldShowOverLockscreen(any(), any())).thenReturn(true)

        val clickIntent = mock(Intent::class.java)
        val pendingIntent = mock(PendingIntent::class.java)
        whenever(pendingIntent.intent).thenReturn(clickIntent)
        val captor = ArgumentCaptor.forClass(View.OnClickListener::class.java)
        val data = mediaData.copy(clickIntent = pendingIntent)
        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)
        verify(viewHolder.player).setOnClickListener(captor.capture())

        // THEN it shows without dismissing keyguard first
        captor.value.onClick(viewHolder.player)
        verify(activityStarter).startActivity(eq(clickIntent), eq(true), nullable(), eq(true))
    }

    @Test
    fun tapContentView_noShowOverLockscreen_dismissKeyguard() {
        // WHEN we are on lockscreen and the activity cannot show over lockscreen
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(activityIntentHelper.wouldShowOverLockscreen(any(), any())).thenReturn(false)

        val clickIntent = mock(Intent::class.java)
        val pendingIntent = mock(PendingIntent::class.java)
        whenever(pendingIntent.intent).thenReturn(clickIntent)
        val captor = ArgumentCaptor.forClass(View.OnClickListener::class.java)
        val data = mediaData.copy(clickIntent = pendingIntent)
        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)
        verify(viewHolder.player).setOnClickListener(captor.capture())

        // THEN keyguard has to be dismissed
        captor.value.onClick(viewHolder.player)
        verify(activityStarter).postStartActivityDismissingKeyguard(eq(pendingIntent), any())
    }

    @Test
    fun recommendation_gutsClosed_longPressOpens() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)
        whenever(mediaViewController.isGutsVisible).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLongClickListener::class.java)
        verify(recommendationViewHolder.recommendations).setOnLongClickListener(captor.capture())

        captor.value.onLongClick(recommendationViewHolder.recommendations)
        verify(mediaViewController).openGuts()
        verify(logger).logLongPressOpen(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun recommendation_settingsButtonClick_isLogged() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        settings.callOnClick()
        verify(logger).logLongPressSettings(anyInt(), eq(PACKAGE), eq(instanceId))

        val captor = ArgumentCaptor.forClass(Intent::class.java)
        verify(activityStarter).startActivity(captor.capture(), eq(true))

        assertThat(captor.value.action).isEqualTo(ACTION_MEDIA_CONTROLS_SETTINGS)
    }

    @Test
    fun recommendation_dismissButton_isLogged() {
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        dismiss.callOnClick()
        verify(logger).logLongPressDismiss(anyInt(), eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun recommendation_tapOnCard_isLogged() {
        val captor = ArgumentCaptor.forClass(View.OnClickListener::class.java)
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        verify(recommendationViewHolder.recommendations).setOnClickListener(captor.capture())
        captor.value.onClick(recommendationViewHolder.recommendations)

        verify(logger).logRecommendationCardTap(eq(PACKAGE), eq(instanceId))
    }

    @Test
    fun recommendation_tapOnItem_isLogged() {
        val captor = ArgumentCaptor.forClass(View.OnClickListener::class.java)
        player.attachRecommendation(recommendationViewHolder)
        player.bindRecommendation(smartspaceData)

        verify(coverContainer1).setOnClickListener(captor.capture())
        captor.value.onClick(recommendationViewHolder.recommendations)

        verify(logger).logRecommendationItemTap(eq(PACKAGE), eq(instanceId), eq(0))
    }

    @Test
    fun bindRecommendation_listHasTooFewRecs_notDisplayed() {
        player.attachRecommendation(recommendationViewHolder)
        val icon = Icon.createWithResource(context, R.drawable.ic_1x_mobiledata)
        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "title1")
                            .setSubtitle("subtitle1")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "title2")
                            .setSubtitle("subtitle2")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                    )
            )

        player.bindRecommendation(data)

        assertThat(recTitle1.text).isEqualTo("")
        verify(mediaViewController, never()).refreshState()
    }

    @Test
    fun bindRecommendation_listHasTooFewRecsWithIcons_notDisplayed() {
        player.attachRecommendation(recommendationViewHolder)
        val icon = Icon.createWithResource(context, R.drawable.ic_1x_mobiledata)
        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "title1")
                            .setSubtitle("subtitle1")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "title2")
                            .setSubtitle("subtitle2")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "empty icon 1")
                            .setSubtitle("subtitle2")
                            .setIcon(null)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "empty icon 2")
                            .setSubtitle("subtitle2")
                            .setIcon(null)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                    )
            )

        player.bindRecommendation(data)

        assertThat(recTitle1.text).isEqualTo("")
        verify(mediaViewController, never()).refreshState()
    }

    @Test
    fun bindRecommendation_hasTitlesAndSubtitles() {
        player.attachRecommendation(recommendationViewHolder)

        val title1 = "Title1"
        val title2 = "Title2"
        val title3 = "Title3"
        val subtitle1 = "Subtitle1"
        val subtitle2 = "Subtitle2"
        val subtitle3 = "Subtitle3"
        val icon = Icon.createWithResource(context, R.drawable.ic_1x_mobiledata)

        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", title1)
                            .setSubtitle(subtitle1)
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", title2)
                            .setSubtitle(subtitle2)
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id3", title3)
                            .setSubtitle(subtitle3)
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build()
                    )
            )
        player.bindRecommendation(data)

        assertThat(recTitle1.text).isEqualTo(title1)
        assertThat(recTitle2.text).isEqualTo(title2)
        assertThat(recTitle3.text).isEqualTo(title3)
        assertThat(recSubtitle1.text).isEqualTo(subtitle1)
        assertThat(recSubtitle2.text).isEqualTo(subtitle2)
        assertThat(recSubtitle3.text).isEqualTo(subtitle3)
    }

    @Test
    fun bindRecommendation_noTitle_subtitleNotShown() {
        player.attachRecommendation(recommendationViewHolder)

        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "")
                            .setSubtitle("fake subtitle")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_1x_mobiledata))
                            .setExtras(Bundle.EMPTY)
                            .build()
                    )
            )
        player.bindRecommendation(data)

        assertThat(recSubtitle1.text).isEqualTo("")
    }

    @Test
    fun bindRecommendation_someHaveTitles_allTitleViewsShown() {
        useRealConstraintSets()
        player.attachRecommendation(recommendationViewHolder)

        val icon = Icon.createWithResource(context, R.drawable.ic_1x_mobiledata)
        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "")
                            .setSubtitle("fake subtitle")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "title2")
                            .setSubtitle("fake subtitle")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id3", "")
                            .setSubtitle("fake subtitle")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build()
                    )
            )
        player.bindRecommendation(data)

        assertThat(expandedSet.getVisibility(recTitle1.id)).isEqualTo(ConstraintSet.VISIBLE)
        assertThat(expandedSet.getVisibility(recTitle2.id)).isEqualTo(ConstraintSet.VISIBLE)
        assertThat(expandedSet.getVisibility(recTitle3.id)).isEqualTo(ConstraintSet.VISIBLE)
    }

    @Test
    fun bindRecommendation_someHaveSubtitles_allSubtitleViewsShown() {
        useRealConstraintSets()
        player.attachRecommendation(recommendationViewHolder)

        val icon = Icon.createWithResource(context, R.drawable.ic_1x_mobiledata)
        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "")
                            .setSubtitle("")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "title2")
                            .setSubtitle("")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id3", "title3")
                            .setSubtitle("subtitle3")
                            .setIcon(icon)
                            .setExtras(Bundle.EMPTY)
                            .build()
                    )
            )
        player.bindRecommendation(data)

        assertThat(expandedSet.getVisibility(recSubtitle1.id)).isEqualTo(ConstraintSet.VISIBLE)
        assertThat(expandedSet.getVisibility(recSubtitle2.id)).isEqualTo(ConstraintSet.VISIBLE)
        assertThat(expandedSet.getVisibility(recSubtitle3.id)).isEqualTo(ConstraintSet.VISIBLE)
    }

    @Test
    fun bindRecommendation_noneHaveSubtitles_subtitleViewsGone() {
        useRealConstraintSets()
        player.attachRecommendation(recommendationViewHolder)
        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "title1")
                            .setSubtitle("")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_1x_mobiledata))
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "title2")
                            .setSubtitle("")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_alarm))
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id3", "title3")
                            .setSubtitle("")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_3g_mobiledata))
                            .setExtras(Bundle.EMPTY)
                            .build()
                    )
            )

        player.bindRecommendation(data)

        assertThat(expandedSet.getVisibility(recSubtitle1.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recSubtitle2.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recSubtitle3.id)).isEqualTo(ConstraintSet.GONE)
    }

    @Test
    fun bindRecommendation_noneHaveTitles_titleAndSubtitleViewsGone() {
        useRealConstraintSets()
        player.attachRecommendation(recommendationViewHolder)
        val data =
            smartspaceData.copy(
                recommendations =
                    listOf(
                        SmartspaceAction.Builder("id1", "")
                            .setSubtitle("subtitle1")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_1x_mobiledata))
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id2", "")
                            .setSubtitle("subtitle2")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_alarm))
                            .setExtras(Bundle.EMPTY)
                            .build(),
                        SmartspaceAction.Builder("id3", "")
                            .setSubtitle("subtitle3")
                            .setIcon(Icon.createWithResource(context, R.drawable.ic_3g_mobiledata))
                            .setExtras(Bundle.EMPTY)
                            .build()
                    )
            )

        player.bindRecommendation(data)

        assertThat(expandedSet.getVisibility(recTitle1.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recTitle2.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recTitle3.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recSubtitle1.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recSubtitle2.id)).isEqualTo(ConstraintSet.GONE)
        assertThat(expandedSet.getVisibility(recSubtitle3.id)).isEqualTo(ConstraintSet.GONE)
    }

    @Test
    fun onButtonClick_touchRippleFlagEnabled_playsTouchRipple() {
        fakeFeatureFlag.set(Flags.UMO_SURFACE_RIPPLE, true)
        val semanticActions =
            MediaButton(
                playOrPause =
                    MediaAction(
                        icon = null,
                        action = {},
                        contentDescription = "play",
                        background = null
                    )
            )
        val data = mediaData.copy(semanticActions = semanticActions)
        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.actionPlayPause.callOnClick()

        assertThat(viewHolder.multiRippleView.ripples.size).isEqualTo(1)
    }

    @Test
    fun onButtonClick_touchRippleFlagDisabled_doesNotPlayTouchRipple() {
        fakeFeatureFlag.set(Flags.UMO_SURFACE_RIPPLE, false)
        val semanticActions =
            MediaButton(
                playOrPause =
                    MediaAction(
                        icon = null,
                        action = {},
                        contentDescription = "play",
                        background = null
                    )
            )
        val data = mediaData.copy(semanticActions = semanticActions)
        player.attachPlayer(viewHolder)
        player.bindPlayer(data, KEY)

        viewHolder.actionPlayPause.callOnClick()

        assertThat(viewHolder.multiRippleView.ripples.size).isEqualTo(0)
    }

    @Test
    fun onButtonClick_turbulenceNoiseFlagEnabled_createsRipplesFinishedListener() {
        fakeFeatureFlag.set(Flags.UMO_SURFACE_RIPPLE, true)
        fakeFeatureFlag.set(Flags.UMO_TURBULENCE_NOISE, true)

        player.attachPlayer(viewHolder)

        assertThat(player.mRipplesFinishedListener).isNotNull()
    }

    @Test
    fun onButtonClick_turbulenceNoiseFlagDisabled_doesNotCreateRipplesFinishedListener() {
        fakeFeatureFlag.set(Flags.UMO_SURFACE_RIPPLE, true)
        fakeFeatureFlag.set(Flags.UMO_TURBULENCE_NOISE, false)

        player.attachPlayer(viewHolder)

        assertThat(player.mRipplesFinishedListener).isNull()
    }

    private fun getScrubbingChangeListener(): SeekBarViewModel.ScrubbingChangeListener =
        withArgCaptor {
            verify(seekBarViewModel).setScrubbingChangeListener(capture())
        }

    private fun getEnabledChangeListener(): SeekBarViewModel.EnabledChangeListener = withArgCaptor {
        verify(seekBarViewModel).setEnabledChangeListener(capture())
    }

    /**
     * Update our test to use real ConstraintSets instead of mocks.
     *
     * Some item visibilities, such as the seekbar visibility, are dependent on other action's
     * visibilities. If we use mocks for the ConstraintSets, then action visibility changes are just
     * thrown away instead of being saved for reference later. This method sets us up to use
     * ConstraintSets so that we do save visibility changes.
     *
     * TODO(b/229740380): Can/should we use real expanded and collapsed sets for all tests?
     */
    private fun useRealConstraintSets() {
        expandedSet = ConstraintSet()
        collapsedSet = ConstraintSet()
        whenever(mediaViewController.expandedLayout).thenReturn(expandedSet)
        whenever(mediaViewController.collapsedLayout).thenReturn(collapsedSet)
    }
}
