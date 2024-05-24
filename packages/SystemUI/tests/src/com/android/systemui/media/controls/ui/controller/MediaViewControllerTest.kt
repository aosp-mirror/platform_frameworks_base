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

import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.view.RecommendationViewHolder
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.res.R
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionViewState
import com.android.systemui.util.animation.WidgetState
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.floatThat
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class MediaViewControllerTest : SysuiTestCase() {
    private val mediaHostStateHolder = MediaHost.MediaHostStateHolder()
    private val mediaHostStatesManager = MediaHostStatesManager()
    private val configurationController =
        com.android.systemui.statusbar.phone.ConfigurationControllerImpl(context)
    private var player = TransitionLayout(context, /* attrs */ null, /* defStyleAttr */ 0)
    private var recommendation = TransitionLayout(context, /* attrs */ null, /* defStyleAttr */ 0)
    @Mock lateinit var logger: MediaViewLogger
    @Mock private lateinit var mockViewState: TransitionViewState
    @Mock private lateinit var mockCopiedState: TransitionViewState
    @Mock private lateinit var detailWidgetState: WidgetState
    @Mock private lateinit var controlWidgetState: WidgetState
    @Mock private lateinit var mediaTitleWidgetState: WidgetState
    @Mock private lateinit var mediaSubTitleWidgetState: WidgetState
    @Mock private lateinit var mediaContainerWidgetState: WidgetState
    @Mock private lateinit var mediaFlags: MediaFlags

    private val delta = 0.1F

    private lateinit var mediaViewController: MediaViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mediaViewController =
            MediaViewController(
                context,
                configurationController,
                mediaHostStatesManager,
                logger,
                mediaFlags,
            )
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
}
