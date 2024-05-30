/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.controller

import android.animation.AnimatorSet
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.LiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.CachingIconView
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.ui.view.GutsViewHolder
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.view.RecommendationViewHolder
import com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.res.R
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffectView
import com.android.systemui.surfaceeffects.ripple.MultiRippleView
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseView
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionViewState
import com.android.systemui.util.animation.WidgetState
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.floatThat
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class MediaViewControllerTest : SysuiTestCase() {
    private val mediaHostStateHolder = MediaHost.MediaHostStateHolder()
    private val mediaHostStatesManager = MediaHostStatesManager()
    private val configurationController =
        com.android.systemui.statusbar.phone.ConfigurationControllerImpl(context)
    private var player = TransitionLayout(context, /* attrs */ null, /* defStyleAttr */ 0)
    private var recommendation = TransitionLayout(context, /* attrs */ null, /* defStyleAttr */ 0)
    private val clock = FakeSystemClock()
    private lateinit var mainExecutor: FakeExecutor
    private lateinit var seekBar: SeekBar
    private lateinit var multiRippleView: MultiRippleView
    private lateinit var turbulenceNoiseView: TurbulenceNoiseView
    private lateinit var loadingEffectView: LoadingEffectView
    private lateinit var settings: ImageButton
    private lateinit var cancel: View
    private lateinit var cancelText: TextView
    private lateinit var dismiss: FrameLayout
    private lateinit var dismissText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var explicitIndicator: CachingIconView
    private lateinit var seamless: ViewGroup
    private lateinit var seamlessButton: View
    private lateinit var seamlessIcon: ImageView
    private lateinit var seamlessText: TextView
    private lateinit var scrubbingElapsedTimeView: TextView
    private lateinit var scrubbingTotalTimeView: TextView
    private lateinit var actionPlayPause: ImageButton
    private lateinit var actionNext: ImageButton
    private lateinit var actionPrev: ImageButton
    @Mock private lateinit var seamlessBackground: RippleDrawable
    @Mock private lateinit var albumView: ImageView
    @Mock lateinit var logger: MediaViewLogger
    @Mock private lateinit var mockViewState: TransitionViewState
    @Mock private lateinit var mockCopiedState: TransitionViewState
    @Mock private lateinit var detailWidgetState: WidgetState
    @Mock private lateinit var controlWidgetState: WidgetState
    @Mock private lateinit var mediaTitleWidgetState: WidgetState
    @Mock private lateinit var mediaSubTitleWidgetState: WidgetState
    @Mock private lateinit var mediaContainerWidgetState: WidgetState
    @Mock private lateinit var mediaFlags: MediaFlags
    @Mock private lateinit var seekBarViewModel: SeekBarViewModel
    @Mock private lateinit var seekBarData: LiveData<SeekBarViewModel.Progress>
    @Mock private lateinit var globalSettings: GlobalSettings
    @Mock private lateinit var viewHolder: MediaViewHolder
    @Mock private lateinit var view: TransitionLayout
    @Mock private lateinit var mockAnimator: AnimatorSet
    @Mock private lateinit var gutsViewHolder: GutsViewHolder
    @Mock private lateinit var gutsText: TextView

    private val delta = 0.1F

    private lateinit var mediaViewController: MediaViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mainExecutor = FakeExecutor(clock)
        mediaViewController =
            object :
                MediaViewController(
                    context,
                    configurationController,
                    mediaHostStatesManager,
                    logger,
                    seekBarViewModel,
                    mainExecutor,
                    mediaFlags,
                    globalSettings,
                ) {
                override fun loadAnimator(
                    context: Context,
                    animId: Int,
                    motionInterpolator: Interpolator?,
                    vararg targets: View?
                ): AnimatorSet {
                    return mockAnimator
                }
            }
        initGutsViewHolderMocks()
        initMediaViewHolderMocks()
    }

    @Test
    fun testOrientationChanged_heightOfPlayerIsUpdated() {
        val newConfig = Configuration()

        mediaViewController.attach(player, MediaViewController.TYPE.PLAYER)
        // Change the height to see the effect of orientation change.
        MediaViewHolder.backgroundIds.forEach { id ->
            mediaViewController.expandedLayout.getConstraint(id).layout.mHeight = 10
        }
        newConfig.orientation = ORIENTATION_LANDSCAPE
        configurationController.onConfigurationChanged(newConfig)

        MediaViewHolder.backgroundIds.forEach { id ->
            assertTrue(
                mediaViewController.expandedLayout.getConstraint(id).layout.mHeight ==
                    context.resources.getDimensionPixelSize(
                        R.dimen.qs_media_session_height_expanded
                    )
            )
        }
    }

    @Test
    fun testOrientationChanged_heightOfRecCardIsUpdated() {
        val newConfig = Configuration()

        mediaViewController.attach(recommendation, MediaViewController.TYPE.RECOMMENDATION)
        // Change the height to see the effect of orientation change.
        mediaViewController.expandedLayout
            .getConstraint(RecommendationViewHolder.backgroundId)
            .layout
            .mHeight = 10
        newConfig.orientation = ORIENTATION_LANDSCAPE
        configurationController.onConfigurationChanged(newConfig)

        assertTrue(
            mediaViewController.expandedLayout
                .getConstraint(RecommendationViewHolder.backgroundId)
                .layout
                .mHeight ==
                context.resources.getDimensionPixelSize(R.dimen.qs_media_session_height_expanded)
        )
    }

    @Test
    fun testObtainViewState_applySquishFraction_toPlayerTransitionViewState_height() {
        mediaViewController.attach(player, MediaViewController.TYPE.PLAYER)
        player.measureState =
            TransitionViewState().apply {
                this.height = 100
                this.measureHeight = 100
            }
        mediaHostStateHolder.expansion = 1f
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        mediaHostStateHolder.measurementInput =
            MeasurementInput(widthMeasureSpec, heightMeasureSpec)

        // Test no squish
        mediaHostStateHolder.squishFraction = 1f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 100)
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.measureHeight == 100)

        // Test half squish
        mediaHostStateHolder.squishFraction = 0.5f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 50)
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.measureHeight == 100)
    }

    @Test
    fun testObtainViewState_applySquishFraction_toRecommendationTransitionViewState_height() {
        mediaViewController.attach(recommendation, MediaViewController.TYPE.RECOMMENDATION)
        recommendation.measureState = TransitionViewState().apply { this.height = 100 }
        mediaHostStateHolder.expansion = 1f
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        mediaHostStateHolder.measurementInput =
            MeasurementInput(widthMeasureSpec, heightMeasureSpec)

        // Test no squish
        mediaHostStateHolder.squishFraction = 1f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 100)
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.measureHeight == 100)

        // Test half squish
        mediaHostStateHolder.squishFraction = 0.5f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 50)
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.measureHeight == 100)
    }

    @Test
    fun testObtainViewState_expandedMatchesParentHeight() {
        mediaViewController.attach(player, MediaViewController.TYPE.PLAYER)
        player.measureState =
            TransitionViewState().apply {
                this.height = 100
                this.measureHeight = 100
            }
        mediaHostStateHolder.expandedMatchesParentHeight = true
        mediaHostStateHolder.expansion = 1f
        mediaHostStateHolder.measurementInput =
            MeasurementInput(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            )

        // Assign the height of each expanded layout
        MediaViewHolder.backgroundIds.forEach { id ->
            mediaViewController.expandedLayout.getConstraint(id).layout.mHeight = 100
        }

        mediaViewController.obtainViewState(mediaHostStateHolder)

        // Verify height of each expanded layout is updated to match constraint
        MediaViewHolder.backgroundIds.forEach { id ->
            assertTrue(
                mediaViewController.expandedLayout.getConstraint(id).layout.mHeight ==
                    ConstraintSet.MATCH_CONSTRAINT
            )
        }
    }

    @Test
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_forMediaPlayer() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_progress_bar to controlWidgetState,
                    R.id.header_artist to detailWidgetState
                )
            )
        whenever(mockCopiedState.measureHeight).thenReturn(200)
        // detail widgets occupy [90, 100]
        whenever(detailWidgetState.y).thenReturn(90F)
        whenever(detailWidgetState.height).thenReturn(10)
        whenever(detailWidgetState.alpha).thenReturn(1F)
        // control widgets occupy [150, 170]
        whenever(controlWidgetState.y).thenReturn(150F)
        whenever(controlWidgetState.height).thenReturn(20)
        whenever(controlWidgetState.alpha).thenReturn(1F)
        // in current bezier, when the progress reach 0.38, the result will be 0.5
        mediaViewController.squishViewState(mockViewState, 181.4F / 200F)
        verify(controlWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }
        verify(detailWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
        mediaViewController.squishViewState(mockViewState, 200F / 200F)
        verify(controlWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
        verify(detailWidgetState, times(2)).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
    }

    @Test
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_invisibleElements() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_progress_bar to controlWidgetState,
                    R.id.header_artist to detailWidgetState
                )
            )
        whenever(mockCopiedState.measureHeight).thenReturn(200)
        // detail widgets occupy [90, 100]
        whenever(detailWidgetState.y).thenReturn(90F)
        whenever(detailWidgetState.height).thenReturn(10)
        whenever(detailWidgetState.alpha).thenReturn(0F)
        // control widgets occupy [150, 170]
        whenever(controlWidgetState.y).thenReturn(150F)
        whenever(controlWidgetState.height).thenReturn(20)
        whenever(controlWidgetState.alpha).thenReturn(0F)
        // Verify that alpha remains 0 throughout squishing
        mediaViewController.squishViewState(mockViewState, 181.4F / 200F)
        verify(controlWidgetState, never()).alpha = floatThat { it > 0 }
        verify(detailWidgetState, never()).alpha = floatThat { it > 0 }
        mediaViewController.squishViewState(mockViewState, 200F / 200F)
        verify(controlWidgetState, never()).alpha = floatThat { it > 0 }
        verify(detailWidgetState, never()).alpha = floatThat { it > 0 }
    }

    @Test
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_forRecommendation() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_title to mediaTitleWidgetState,
                    R.id.media_subtitle to mediaSubTitleWidgetState,
                    R.id.media_cover1_container to mediaContainerWidgetState
                )
            )
        whenever(mockCopiedState.measureHeight).thenReturn(360)
        // media container widgets occupy [20, 300]
        whenever(mediaContainerWidgetState.y).thenReturn(20F)
        whenever(mediaContainerWidgetState.height).thenReturn(280)
        whenever(mediaContainerWidgetState.alpha).thenReturn(1F)
        // media title widgets occupy [320, 330]
        whenever(mediaTitleWidgetState.y).thenReturn(320F)
        whenever(mediaTitleWidgetState.height).thenReturn(10)
        whenever(mediaTitleWidgetState.alpha).thenReturn(1F)
        // media subtitle widgets occupy [340, 350]
        whenever(mediaSubTitleWidgetState.y).thenReturn(340F)
        whenever(mediaSubTitleWidgetState.height).thenReturn(10)
        whenever(mediaSubTitleWidgetState.alpha).thenReturn(1F)

        // in current beizer, when the progress reach 0.38, the result will be 0.5
        mediaViewController.squishViewState(mockViewState, 307.6F / 360F)
        verify(mediaContainerWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }
        mediaViewController.squishViewState(mockViewState, 320F / 360F)
        verify(mediaContainerWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
        // media title and media subtitle are in same widget group, should be calculate together and
        // have same alpha
        mediaViewController.squishViewState(mockViewState, 353.8F / 360F)
        verify(mediaTitleWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }
        verify(mediaSubTitleWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }
        mediaViewController.squishViewState(mockViewState, 360F / 360F)
        verify(mediaTitleWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
        verify(mediaSubTitleWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
    }

    @Test
    fun attachPlayer_seekBarDisabled_seekBarVisibilityIsSetToInvisible() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = true)
        getEnabledChangeListener().onEnabledChanged(enabled = false)

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.media_progress_bar))
            .isEqualTo(ConstraintSet.INVISIBLE)
    }

    @Test
    fun attachPlayer_seekBarEnabled_seekBarVisible() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = true)

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.media_progress_bar))
            .isEqualTo(ConstraintSet.VISIBLE)
    }

    @Test
    fun attachPlayer_seekBarStatusUpdate_seekBarVisibilityChanges() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        getEnabledChangeListener().onEnabledChanged(enabled = true)

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.media_progress_bar))
            .isEqualTo(ConstraintSet.VISIBLE)

        getEnabledChangeListener().onEnabledChanged(enabled = false)

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.media_progress_bar))
            .isEqualTo(ConstraintSet.INVISIBLE)
    }

    @Test
    fun attachPlayer_notScrubbing_scrubbingViewsGone() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        mediaViewController.canShowScrubbingTime = true
        getScrubbingChangeListener().onScrubbingChanged(true)
        getScrubbingChangeListener().onScrubbingChanged(false)
        mainExecutor.runAllReady()

        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.GONE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.GONE)
    }

    @Test
    fun setIsScrubbing_noSemanticActions_scrubbingViewsGone() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        mediaViewController.canShowScrubbingTime = false
        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.GONE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.GONE)
    }

    @Test
    fun setIsScrubbing_noPrevButton_scrubbingTimesNotShown() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        mediaViewController.setUpNextButtonInfo(true)
        mediaViewController.setUpPrevButtonInfo(false)
        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionNext))
            .isEqualTo(ConstraintSet.VISIBLE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.GONE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.GONE)
    }

    @Test
    fun setIsScrubbing_noNextButton_scrubbingTimesNotShown() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        mediaViewController.setUpNextButtonInfo(false)
        mediaViewController.setUpPrevButtonInfo(true)
        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionPrev))
            .isEqualTo(ConstraintSet.VISIBLE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.GONE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.GONE)
    }

    @Test
    fun setIsScrubbing_scrubbingViewsShownAndPrevNextHiddenOnlyInExpanded() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        mediaViewController.setUpNextButtonInfo(true)
        mediaViewController.setUpPrevButtonInfo(true)
        mediaViewController.canShowScrubbingTime = true
        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        // Only in expanded, we should show the scrubbing times and hide prev+next
        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionPrev))
            .isEqualTo(ConstraintSet.GONE)
        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionNext))
            .isEqualTo(ConstraintSet.GONE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.VISIBLE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.VISIBLE)
    }

    @Test
    fun setIsScrubbing_trueThenFalse_reservePrevAndNextButtons() {
        whenever(mediaFlags.isMediaControlsRefactorEnabled()).thenReturn(true)

        mediaViewController.attachPlayer(viewHolder)
        mediaViewController.setUpNextButtonInfo(true, ConstraintSet.INVISIBLE)
        mediaViewController.setUpPrevButtonInfo(true, ConstraintSet.INVISIBLE)
        mediaViewController.canShowScrubbingTime = true

        getScrubbingChangeListener().onScrubbingChanged(true)
        mainExecutor.runAllReady()

        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionPrev))
            .isEqualTo(ConstraintSet.INVISIBLE)
        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionNext))
            .isEqualTo(ConstraintSet.INVISIBLE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.VISIBLE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.VISIBLE)

        getScrubbingChangeListener().onScrubbingChanged(false)
        mainExecutor.runAllReady()

        // Only in expanded, we should hide the scrubbing times and show prev+next
        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionPrev))
            .isEqualTo(ConstraintSet.VISIBLE)
        assertThat(mediaViewController.expandedLayout.getVisibility(R.id.actionNext))
            .isEqualTo(ConstraintSet.VISIBLE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_elapsed_time)
            )
            .isEqualTo(ConstraintSet.GONE)
        assertThat(
                mediaViewController.expandedLayout.getVisibility(R.id.media_scrubbing_total_time)
            )
            .isEqualTo(ConstraintSet.GONE)
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

    private fun initMediaViewHolderMocks() {
        titleText = TextView(context)
        artistText = TextView(context)
        explicitIndicator = CachingIconView(context).also { it.id = R.id.media_explicit_indicator }
        seamless = FrameLayout(context)
        seamless.foreground = seamlessBackground
        seamlessButton = View(context)
        seamlessIcon = ImageView(context)
        seamlessText = TextView(context)
        seekBar = SeekBar(context).also { it.id = R.id.media_progress_bar }

        actionPlayPause = ImageButton(context).also { it.id = R.id.actionPlayPause }
        actionPrev = ImageButton(context).also { it.id = R.id.actionPrev }
        actionNext = ImageButton(context).also { it.id = R.id.actionNext }
        scrubbingElapsedTimeView =
            TextView(context).also { it.id = R.id.media_scrubbing_elapsed_time }
        scrubbingTotalTimeView = TextView(context).also { it.id = R.id.media_scrubbing_total_time }

        multiRippleView = MultiRippleView(context, null)
        turbulenceNoiseView = TurbulenceNoiseView(context, null)
        loadingEffectView = LoadingEffectView(context, null)

        whenever(viewHolder.player).thenReturn(view)
        whenever(view.context).thenReturn(context)
        whenever(viewHolder.albumView).thenReturn(albumView)
        whenever(albumView.foreground).thenReturn(Mockito.mock(Drawable::class.java))
        whenever(viewHolder.titleText).thenReturn(titleText)
        whenever(viewHolder.artistText).thenReturn(artistText)
        whenever(viewHolder.explicitIndicator).thenReturn(explicitIndicator)
        whenever(seamlessBackground.getDrawable(0))
            .thenReturn(Mockito.mock(GradientDrawable::class.java))
        whenever(viewHolder.seamless).thenReturn(seamless)
        whenever(viewHolder.seamlessButton).thenReturn(seamlessButton)
        whenever(viewHolder.seamlessIcon).thenReturn(seamlessIcon)
        whenever(viewHolder.seamlessText).thenReturn(seamlessText)
        whenever(viewHolder.seekBar).thenReturn(seekBar)
        whenever(viewHolder.scrubbingElapsedTimeView).thenReturn(scrubbingElapsedTimeView)
        whenever(viewHolder.scrubbingTotalTimeView).thenReturn(scrubbingTotalTimeView)
        whenever(viewHolder.gutsViewHolder).thenReturn(gutsViewHolder)
        whenever(seekBarViewModel.progress).thenReturn(seekBarData)

        // Action buttons
        whenever(viewHolder.actionPlayPause).thenReturn(actionPlayPause)
        whenever(viewHolder.getAction(R.id.actionNext)).thenReturn(actionNext)
        whenever(viewHolder.getAction(R.id.actionPrev)).thenReturn(actionPrev)
        whenever(viewHolder.getAction(R.id.actionPlayPause)).thenReturn(actionPlayPause)

        whenever(viewHolder.multiRippleView).thenReturn(multiRippleView)
        whenever(viewHolder.turbulenceNoiseView).thenReturn(turbulenceNoiseView)
        whenever(viewHolder.loadingEffectView).thenReturn(loadingEffectView)
    }

    private fun getScrubbingChangeListener(): SeekBarViewModel.ScrubbingChangeListener =
        withArgCaptor {
            verify(seekBarViewModel).setScrubbingChangeListener(capture())
        }

    private fun getEnabledChangeListener(): SeekBarViewModel.EnabledChangeListener = withArgCaptor {
        verify(seekBarViewModel).setEnabledChangeListener(capture())
    }
}
