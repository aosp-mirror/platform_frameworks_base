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

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Rect
import android.graphics.Region
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.internal.policy.SystemBarUtils
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.SyncTransactionQueue
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * Test class for [DesktopModeVisualIndicator]
 *
 * Usage: atest WMShellUnitTests:DesktopModeVisualIndicatorTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopModeVisualIndicatorTest : ShellTestCase() {
    @Mock private lateinit var taskInfo: RunningTaskInfo
    @Mock private lateinit var syncQueue: SyncTransactionQueue
    @Mock private lateinit var displayController: DisplayController
    @Mock private lateinit var taskSurface: SurfaceControl
    @Mock private lateinit var taskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var displayLayout: DisplayLayout

    private lateinit var visualIndicator: DesktopModeVisualIndicator

    @Before
    fun setUp() {
        whenever(displayLayout.width()).thenReturn(DISPLAY_BOUNDS.width())
        whenever(displayLayout.height()).thenReturn(DISPLAY_BOUNDS.height())
        whenever(displayLayout.stableInsets()).thenReturn(STABLE_INSETS)
    }

    @Test
    fun testFullscreenRegionCalculation() {
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 2400, 2 * STABLE_INSETS.top))

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        val transitionHeight = SystemBarUtils.getStatusBarHeight(context)
        val toFullscreenScale = mContext.resources.getFloat(
            R.dimen.desktop_mode_fullscreen_region_scale
        )
        val toFullscreenWidth = displayLayout.width() * toFullscreenScale
        assertThat(testRegion.bounds).isEqualTo(Rect(
            (DISPLAY_BOUNDS.width() / 2f - toFullscreenWidth / 2f).toInt(),
            -50,
            (DISPLAY_BOUNDS.width() / 2f + toFullscreenWidth / 2f).toInt(),
            transitionHeight))

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 2400, 2 * STABLE_INSETS.top))

        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        testRegion = visualIndicator.calculateFullscreenRegion(displayLayout, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 2400, transitionHeight))
    }

    @Test
    fun testSplitLeftRegionCalculation() {
        val transitionHeight = context.resources.getDimensionPixelSize(
            R.dimen.desktop_mode_split_from_desktop_height)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion = visualIndicator.calculateSplitLeftRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 32, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        testRegion = visualIndicator.calculateSplitLeftRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, transitionHeight, 32, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion = visualIndicator.calculateSplitLeftRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 32, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        testRegion = visualIndicator.calculateSplitLeftRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(0, -50, 32, 1600))
    }

    @Test
    fun testSplitRightRegionCalculation() {
        val transitionHeight = context.resources.getDimensionPixelSize(
            R.dimen.desktop_mode_split_from_desktop_height)
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        var testRegion = visualIndicator.calculateSplitRightRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, -50, 2400, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FREEFORM)
        testRegion = visualIndicator.calculateSplitRightRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, transitionHeight, 2400, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_SPLIT)
        testRegion = visualIndicator.calculateSplitRightRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, -50, 2400, 1600))
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
        testRegion = visualIndicator.calculateSplitRightRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        assertThat(testRegion.bounds).isEqualTo(Rect(2368, -50, 2400, 1600))
    }

    @Test
    fun testToDesktopRegionCalculation() {
        createVisualIndicator(DesktopModeVisualIndicator.DragStartState.FROM_FULLSCREEN)
        val fullscreenRegion = visualIndicator.calculateFullscreenRegion(displayLayout,
            CAPTION_HEIGHT)
        val splitLeftRegion = visualIndicator.calculateSplitLeftRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        val splitRightRegion = visualIndicator.calculateSplitRightRegion(displayLayout,
            TRANSITION_AREA_WIDTH, CAPTION_HEIGHT)
        val desktopRegion = visualIndicator.calculateToDesktopRegion(displayLayout,
            splitLeftRegion, splitRightRegion, fullscreenRegion)
        var testRegion = Region()
        testRegion.union(DISPLAY_BOUNDS)
        testRegion.op(splitLeftRegion, Region.Op.DIFFERENCE)
        testRegion.op(splitRightRegion, Region.Op.DIFFERENCE)
        testRegion.op(fullscreenRegion, Region.Op.DIFFERENCE)
        assertThat(desktopRegion).isEqualTo(testRegion)
    }

    private fun createVisualIndicator(dragStartState: DesktopModeVisualIndicator.DragStartState) {
        visualIndicator = DesktopModeVisualIndicator(syncQueue, taskInfo, displayController,
            context, taskSurface, taskDisplayAreaOrganizer, dragStartState)
    }

    companion object {
        private const val TRANSITION_AREA_WIDTH = 32
        private const val CAPTION_HEIGHT = 50
        private val DISPLAY_BOUNDS = Rect(0, 0, 2400, 1600)
        private const val NAVBAR_HEIGHT = 50
        private val STABLE_INSETS = Rect(
            DISPLAY_BOUNDS.left,
            DISPLAY_BOUNDS.top + CAPTION_HEIGHT,
            DISPLAY_BOUNDS.right,
            DISPLAY_BOUNDS.bottom - NAVBAR_HEIGHT
        )
    }
}