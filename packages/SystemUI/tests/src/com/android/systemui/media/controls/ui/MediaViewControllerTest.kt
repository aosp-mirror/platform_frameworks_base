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

package com.android.systemui.media.controls.ui

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.ANIMATION_BASE_DURATION
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.CONTROLS_DELAY
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.DETAILS_DELAY
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.DURATION
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.MEDIACONTAINERS_DELAY
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.MEDIATITLES_DELAY
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.TRANSFORM_BEZIER
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
    @Mock private lateinit var bgWidgetState: WidgetState
    @Mock private lateinit var mediaTitleWidgetState: WidgetState
    @Mock private lateinit var mediaContainerWidgetState: WidgetState

    val delta = 0.0001F

    private lateinit var mediaViewController: MediaViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mediaViewController =
            MediaViewController(context, configurationController, mediaHostStatesManager, logger)
    }

    @Test
    fun testObtainViewState_applySquishFraction_toPlayerTransitionViewState_height() {
        mediaViewController.attach(player, MediaViewController.TYPE.PLAYER)
        player.measureState = TransitionViewState().apply {
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
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_forMediaPlayer() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_progress_bar to controlWidgetState,
                    R.id.header_artist to detailWidgetState
                )
            )

        val detailSquishMiddle =
            TRANSFORM_BEZIER.getInterpolation(
                (DETAILS_DELAY + DURATION / 2) / ANIMATION_BASE_DURATION
            )
        mediaViewController.squishViewState(mockViewState, detailSquishMiddle)
        verify(detailWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }

        val detailSquishEnd =
            TRANSFORM_BEZIER.getInterpolation((DETAILS_DELAY + DURATION) / ANIMATION_BASE_DURATION)
        mediaViewController.squishViewState(mockViewState, detailSquishEnd)
        verify(detailWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }

        val controlSquishMiddle =
            TRANSFORM_BEZIER.getInterpolation(
                (CONTROLS_DELAY + DURATION / 2) / ANIMATION_BASE_DURATION
            )
        mediaViewController.squishViewState(mockViewState, controlSquishMiddle)
        verify(controlWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }

        val controlSquishEnd =
            TRANSFORM_BEZIER.getInterpolation((CONTROLS_DELAY + DURATION) / ANIMATION_BASE_DURATION)
        mediaViewController.squishViewState(mockViewState, controlSquishEnd)
        verify(controlWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
    }

    @Test
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_forRecommendation() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_title1 to mediaTitleWidgetState,
                    R.id.media_cover1_container to mediaContainerWidgetState
                )
            )

        val containerSquishMiddle =
            TRANSFORM_BEZIER.getInterpolation(
                (MEDIACONTAINERS_DELAY + DURATION / 2) / ANIMATION_BASE_DURATION
            )
        mediaViewController.squishViewState(mockViewState, containerSquishMiddle)
        verify(mediaContainerWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }

        val containerSquishEnd =
            TRANSFORM_BEZIER.getInterpolation(
                (MEDIACONTAINERS_DELAY + DURATION) / ANIMATION_BASE_DURATION
            )
        mediaViewController.squishViewState(mockViewState, containerSquishEnd)
        verify(mediaContainerWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }

        val titleSquishMiddle =
            TRANSFORM_BEZIER.getInterpolation(
                (MEDIATITLES_DELAY + DURATION / 2) / ANIMATION_BASE_DURATION
            )
        mediaViewController.squishViewState(mockViewState, titleSquishMiddle)
        verify(mediaTitleWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }

        val titleSquishEnd =
            TRANSFORM_BEZIER.getInterpolation(
                (MEDIATITLES_DELAY + DURATION) / ANIMATION_BASE_DURATION
            )
        mediaViewController.squishViewState(mockViewState, titleSquishEnd)
        verify(mediaTitleWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
    }
}
