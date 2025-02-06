/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.desktopmode

import android.animation.AnimatorTestRule
import android.app.ActivityManager.RunningTaskInfo
import android.graphics.PointF
import android.graphics.Rect
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.internal.policy.SystemBarUtils
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.shared.bubbles.BubbleDropTargetBoundsProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Test class for [DesktopModeVisualIndicator]
 *
 * Usage: atest WMShellUnitTests:DesktopModeVisualIndicatorTest
 */
@SmallTest
@RunWithLooper
@RunWith(AndroidTestingRunner::class)
@EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
class DesktopModeVisualIndicatorTest : ShellTestCase() {

    @JvmField @Rule val animatorTestRule = AnimatorTestRule(this)

    private lateinit var taskInfo: RunningTaskInfo
    @Mock private lateinit var syncQueue: SyncTransactionQueue
    @Mock private lateinit var displayController: DisplayController
    @Mock private lateinit var taskSurface: SurfaceControl
    @Mock private lateinit var taskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var displayLayout: DisplayLayout
    @Mock private lateinit var bubbleBoundsProvider: BubbleDropTargetBoundsProvider

    private lateinit var visualIndicator: DesktopModeVisualIndicator

    @Before
    fun setUp() {
        whenever(displayLayout.width()).thenReturn(DISPLAY_BOUNDS.width())
        whenever(displayLayout.height()).thenReturn(DISPLAY_BOUNDS.height())
        whenever(displayLayout.stableInsets()).thenReturn(STABLE_INSETS)
        whenever(displayController.getDisplayLayout(anyInt())).thenReturn(displayLayout)
        whenever(displayController.getDisplay(anyInt())).thenReturn(mContext.display)
        whenever(bubbleBoundsProvider.getBubbleBarExpandedViewDropTargetBounds(any()))
            .thenReturn(Rect())
        taskInfo = DesktopTestHelpers.createFullscreenTask()
    }

    @Test
    fun testFullscreenRegionCalculation() {
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        assertThat(testRegion.bounds)
            .isEqualTo(Rect(0, Short.MIN_VALUE.toInt(), 2400, 2 * STABLE_INSETS.top))

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        val transitionHeight = SystemBarUtils.getStatusBarHeight(context)
        val toFullscreenScale =
            mContext.resources.getFloat(R.dimen.desktop_mode_fullscreen_region_scale)
        val toFullscreenWidth = displayLayout.width() * toFullscreenScale
        assertThat(testRegion.bounds)
            .isEqualTo(
                Rect(
                    (DISPLAY_BOUNDS.width() / 2f - toFullscreenWidth / 2f).toInt(),
                    Short.MIN_VALUE.toInt(),
                    (DISPLAY_BOUNDS.width() / 2f + toFullscreenWidth / 2f).toInt(),
                    transitionHeight,
                )
            )

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        assertThat(testRegion.bounds)
            .isEqualTo(Rect(0, Short.MIN_VALUE.toInt(), 2400, 2 * STABLE_INSETS.top))

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        assertThat(testRegion.bounds)
            .isEqualTo(Rect(0, Short.MIN_VALUE.toInt(), 2400, transitionHeight))
    }

    @Test
    fun testSplitLeftRegionCalculation() {
        val transitionHeight =
            context.resources.getDimensionPixelSize(R.dimen.desktop_mode_split_from_desktop_height)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion =
            visualIndicator.calculateSplitLeftRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 32, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        testRegion =
            visualIndicator.calculateSplitLeftRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(0, transitionHeight, 32, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion =
            visualIndicator.calculateSplitLeftRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 32, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        testRegion =
            visualIndicator.calculateSplitLeftRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 32, 1600))
    }

    @Test
    fun testSplitRightRegionCalculation() {
        val transitionHeight =
            context.resources.getDimensionPixelSize(R.dimen.desktop_mode_split_from_desktop_height)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion =
            visualIndicator.calculateSplitRightRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, -50, 2400, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        testRegion =
            visualIndicator.calculateSplitRightRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, transitionHeight, 2400, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion =
            visualIndicator.calculateSplitRightRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, -50, 2400, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        testRegion =
            visualIndicator.calculateSplitRightRegion(
                displayLayout,
                TRANSITION_AREA_WIDTH,
                CAPTION_HEIGHT,
            )
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, -50, 2400, 1600))
    }

    @Test
    fun testBubbleLeftRegionCalculation() {
        val bubbleRegionWidth =
            context.resources.getDimensionPixelSize(R.dimen.bubble_transform_area_width)
        val bubbleRegionHeight =
            context.resources.getDimensionPixelSize(R.dimen.bubble_transform_area_height)
        val expectedRect = Rect(0, 1600 - bubbleRegionHeight, bubbleRegionWidth, 1600)

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion = visualIndicator.calculateBubbleLeftRegion(displayLayout)
        assertThat(testRegion.bounds).isEqualTo(expectedRect)

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion = visualIndicator.calculateBubbleLeftRegion(displayLayout)
        assertThat(testRegion.bounds).isEqualTo(expectedRect)
    }

    @Test
    fun testBubbleRightRegionCalculation() {
        val bubbleRegionWidth =
            context.resources.getDimensionPixelSize(R.dimen.bubble_transform_area_width)
        val bubbleRegionHeight =
            context.resources.getDimensionPixelSize(R.dimen.bubble_transform_area_height)
        val expectedRect = Rect(2400 - bubbleRegionWidth, 1600 - bubbleRegionHeight, 2400, 1600)

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion = visualIndicator.calculateBubbleRightRegion(displayLayout)
        assertThat(testRegion.bounds).isEqualTo(expectedRect)

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion = visualIndicator.calculateBubbleRightRegion(displayLayout)
        assertThat(testRegion.bounds).isEqualTo(expectedRect)
    }

    @Test
    fun testDefaultIndicators() {
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var result = visualIndicator.updateIndicatorType(PointF(-10000f, 500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_LEFT_INDICATOR)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        result = visualIndicator.updateIndicatorType(PointF(10000f, 500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_RIGHT_INDICATOR)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        result = visualIndicator.updateIndicatorType(PointF(500f, 10000f))
        assertThat(result).isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        result = visualIndicator.updateIndicatorType(PointF(500f, 10000f))
        assertThat(result).isEqualTo(DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR)
    }

    @Test
    @EnableFlags(
        com.android.wm.shell.Flags.FLAG_ENABLE_BUBBLE_TO_FULLSCREEN,
        com.android.wm.shell.Flags.FLAG_ENABLE_CREATE_ANY_BUBBLE,
    )
    fun testDefaultIndicatorWithNoDesktop() {
        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_isDesktopModeSupported,
            false,
        )
        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_isDesktopModeDevOptionSupported,
            false,
        )

        // Fullscreen to center, no desktop indicator
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var result = visualIndicator.updateIndicatorType(PointF(500f, 500f))
        assertThat(result).isEqualTo(DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR)
        // Fullscreen to split
        result = visualIndicator.updateIndicatorType(PointF(10000f, 500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_RIGHT_INDICATOR)
        result = visualIndicator.updateIndicatorType(PointF(-10000f, 500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_LEFT_INDICATOR)
        // Fullscreen to bubble
        result = visualIndicator.updateIndicatorType(PointF(100f, 1500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_BUBBLE_LEFT_INDICATOR)
        result = visualIndicator.updateIndicatorType(PointF(2300f, 1500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_BUBBLE_RIGHT_INDICATOR)
        // Split to center, no desktop indicator
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        result = visualIndicator.updateIndicatorType(PointF(500f, 500f))
        assertThat(result).isEqualTo(DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR)
        // Split to fullscreen
        result = visualIndicator.updateIndicatorType(PointF(500f, 0f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_FULLSCREEN_INDICATOR)
        // Split to bubble
        result = visualIndicator.updateIndicatorType(PointF(100f, 1500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_BUBBLE_LEFT_INDICATOR)
        result = visualIndicator.updateIndicatorType(PointF(2300f, 1500f))
        assertThat(result)
            .isEqualTo(DesktopModeVisualIndicator.IndicatorType.TO_BUBBLE_RIGHT_INDICATOR)
        // Drag app to center, no desktop indicator
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        result = visualIndicator.updateIndicatorType(PointF(500f, 500f))
        assertThat(result).isEqualTo(DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR)
    }

    @Test
    @EnableFlags(
        com.android.wm.shell.Flags.FLAG_ENABLE_BUBBLE_TO_FULLSCREEN,
        com.android.wm.shell.Flags.FLAG_ENABLE_CREATE_ANY_BUBBLE,
    )
    fun testBubbleLeftVisualIndicatorSize() {
        val dropTargetBounds = Rect(100, 100, 500, 1500)
        whenever(bubbleBoundsProvider.getBubbleBarExpandedViewDropTargetBounds(/* onLeft= */ true))
            .thenReturn(dropTargetBounds)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        visualIndicator.updateIndicatorType(PointF(100f, 1500f))

        animatorTestRule.advanceTimeBy(200)

        assertThat(visualIndicator.indicatorBounds).isEqualTo(dropTargetBounds)
    }

    @Test
    @EnableFlags(
        com.android.wm.shell.Flags.FLAG_ENABLE_BUBBLE_TO_FULLSCREEN,
        com.android.wm.shell.Flags.FLAG_ENABLE_CREATE_ANY_BUBBLE,
    )
    fun testBubbleRightVisualIndicatorSize() {
        val dropTargetBounds = Rect(1900, 100, 2300, 1500)
        whenever(bubbleBoundsProvider.getBubbleBarExpandedViewDropTargetBounds(/* onLeft= */ false))
            .thenReturn(dropTargetBounds)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        visualIndicator.updateIndicatorType(PointF(2300f, 1500f))

        animatorTestRule.advanceTimeBy(200)

        assertThat(visualIndicator.indicatorBounds).isEqualTo(dropTargetBounds)
    }

    private fun createVisualIndicator(dragStartState: DesktopModeVisualIndicator.DragStartState) {
        visualIndicator =
            DesktopModeVisualIndicator(
                syncQueue,
                taskInfo,
                displayController,
                context,
                taskSurface,
                taskDisplayAreaOrganizer,
                dragStartState,
                bubbleBoundsProvider,
            )
    }

    companion object {
        private const val TRANSITION_AREA_WIDTH = 32
        private const val CAPTION_HEIGHT = 50
        private val DISPLAY_BOUNDS = Rect(0, 0, 2400, 1600)
        private const val NAVBAR_HEIGHT = 50
        private val STABLE_INSETS =
            Rect(
                DISPLAY_BOUNDS.left,
                DISPLAY_BOUNDS.top + CAPTION_HEIGHT,
                DISPLAY_BOUNDS.right,
                DISPLAY_BOUNDS.bottom - NAVBAR_HEIGHT,
            )
    }
}
