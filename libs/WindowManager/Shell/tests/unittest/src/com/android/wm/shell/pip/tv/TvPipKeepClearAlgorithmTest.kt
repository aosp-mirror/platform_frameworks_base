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

package com.android.wm.shell.pip.tv

import android.graphics.Insets
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.util.Size
import android.view.Gravity
import org.junit.runner.RunWith
import com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_NONE
import com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_BOTTOM
import com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_RIGHT
import com.android.wm.shell.pip.PipBoundsState.STASH_TYPE_TOP
import com.android.wm.shell.pip.tv.TvPipKeepClearAlgorithm.Placement
import org.junit.Before
import org.junit.Test
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNull

@RunWith(AndroidTestingRunner::class)
class TvPipKeepClearAlgorithmTest {
    private val DEFAULT_PIP_SIZE = Size(384, 216)
    private val EXPANDED_WIDE_PIP_SIZE = Size(384*2, 216)
    private val DASHBOARD_WIDTH = 484
    private val BOTTOM_SHEET_HEIGHT = 524
    private val STASH_OFFSET = 64
    private val PADDING = 16
    private val SCREEN_SIZE = Size(1920, 1080)
    private val SCREEN_EDGE_INSET = 50

    private lateinit var pipSize: Size
    private lateinit var movementBounds: Rect
    private lateinit var algorithm: TvPipKeepClearAlgorithm
    private var currentTime = 0L
    private var restrictedAreas = mutableSetOf<Rect>()
    private var unrestrictedAreas = mutableSetOf<Rect>()
    private var gravity: Int = 0

    @Before
    fun setup() {
        movementBounds = Rect(0, 0, SCREEN_SIZE.width, SCREEN_SIZE.height)
        movementBounds.inset(SCREEN_EDGE_INSET, SCREEN_EDGE_INSET)

        restrictedAreas.clear()
        unrestrictedAreas.clear()
        currentTime = 0L
        pipSize = DEFAULT_PIP_SIZE
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        algorithm = TvPipKeepClearAlgorithm({ currentTime })
        algorithm.setScreenSize(SCREEN_SIZE)
        algorithm.setMovementBounds(movementBounds)
        algorithm.pipAreaPadding = PADDING
        algorithm.stashOffset = STASH_OFFSET
        algorithm.stashDuration = 5000L
        algorithm.setGravity(gravity)
        algorithm.maxRestrictedDistanceFraction = 0.3
    }

    @Test
    fun testAnchorPosition_BottomRight() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_TopRight() {
        gravity = Gravity.TOP or Gravity.RIGHT
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_TopLeft() {
        gravity = Gravity.TOP or Gravity.LEFT
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_BottomLeft() {
        gravity = Gravity.BOTTOM or Gravity.LEFT
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_Right() {
        gravity = Gravity.RIGHT
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_Left() {
        gravity = Gravity.LEFT
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_Top() {
        gravity = Gravity.TOP
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_Bottom() {
        gravity = Gravity.BOTTOM
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_TopCenterHorizontal() {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_BottomCenterHorizontal() {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_RightCenterVertical() {
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        testAnchorPosition()
    }

    @Test
    fun testAnchorPosition_LeftCenterVertical() {
        gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        testAnchorPosition()
    }

    fun testAnchorPosition() {
        val placement = getActualPlacement()

        assertEquals(getExpectedAnchorBounds(), placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottomRight_KeepClearNotObstructing_StayAtAnchor() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val sidebar = makeSideBar(DASHBOARD_WIDTH, Gravity.LEFT)
        unrestrictedAreas.add(sidebar)

        val expectedBounds = getExpectedAnchorBounds()

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottomRight_UnrestrictedRightSidebar_PushedLeft() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val sidebar = makeSideBar(DASHBOARD_WIDTH, Gravity.RIGHT)
        unrestrictedAreas.add(sidebar)

        val expectedBounds = anchorBoundsOffsetBy(SCREEN_EDGE_INSET - sidebar.width() - PADDING, 0)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorTopRight_UnrestrictedRightSidebar_PushedLeft() {
        gravity = Gravity.TOP or Gravity.RIGHT

        val sidebar = makeSideBar(DASHBOARD_WIDTH, Gravity.RIGHT)
        unrestrictedAreas.add(sidebar)

        val expectedBounds = anchorBoundsOffsetBy(SCREEN_EDGE_INSET - sidebar.width() - PADDING, 0)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottomLeft_UnrestrictedRightSidebar_StayAtAnchor() {
        gravity = Gravity.BOTTOM or Gravity.LEFT

        val sidebar = makeSideBar(DASHBOARD_WIDTH, Gravity.RIGHT)
        unrestrictedAreas.add(sidebar)

        val expectedBounds = anchorBoundsOffsetBy(0, 0)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottom_UnrestrictedRightSidebar_StayAtAnchor() {
        gravity = Gravity.BOTTOM

        val sidebar = makeSideBar(DASHBOARD_WIDTH, Gravity.RIGHT)
        unrestrictedAreas.add(sidebar)

        val expectedBounds = anchorBoundsOffsetBy(0, 0)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun testExpanded_AnchorBottom_UnrestrictedRightSidebar_StayAtAnchor() {
        pipSize = EXPANDED_WIDE_PIP_SIZE
        gravity = Gravity.BOTTOM

        val sidebar = makeSideBar(DASHBOARD_WIDTH, Gravity.RIGHT)
        unrestrictedAreas.add(sidebar)

        val expectedBounds = anchorBoundsOffsetBy(0, 0)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottomRight_RestrictedSmallBottomBar_PushedUp() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(96)
        restrictedAreas.add(bottomBar)

        val expectedBounds = anchorBoundsOffsetBy(0,
                SCREEN_EDGE_INSET - bottomBar.height() - PADDING)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottomRight_RestrictedBottomSheet_StashDownAtAnchor() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        restrictedAreas.add(bottomBar)

        val expectedBounds = getExpectedAnchorBounds()
        expectedBounds.offsetTo(expectedBounds.left, SCREEN_SIZE.height - STASH_OFFSET)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_BOTTOM, placement.stashType)
        assertEquals(getExpectedAnchorBounds(), placement.unstashDestinationBounds)
        assertEquals(algorithm.stashDuration, placement.unstashTime)
    }

    @Test
    fun test_AnchorBottomRight_UnrestrictedBottomSheet_PushUp() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        unrestrictedAreas.add(bottomBar)

        val expectedBounds = anchorBoundsOffsetBy(0,
                SCREEN_EDGE_INSET - bottomBar.height() - PADDING)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_AnchorBottomRight_UnrestrictedBottomSheet_RestrictedSidebar_StashAboveBottomSheet() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        unrestrictedAreas.add(bottomBar)

        val maxRestrictedHorizontalPush =
                (algorithm.maxRestrictedDistanceFraction * SCREEN_SIZE.width).toInt()
        val sideBar = makeSideBar(maxRestrictedHorizontalPush + 100, Gravity.RIGHT)
        restrictedAreas.add(sideBar)

        val expectedUnstashBounds =
                anchorBoundsOffsetBy(0, SCREEN_EDGE_INSET - bottomBar.height() - PADDING)

        val expectedBounds = Rect(expectedUnstashBounds)
        expectedBounds.offsetTo(SCREEN_SIZE.width - STASH_OFFSET, expectedBounds.top)

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_RIGHT, placement.stashType)
        assertEquals(expectedUnstashBounds, placement.unstashDestinationBounds)
        assertEquals(algorithm.stashDuration, placement.unstashTime)
    }

    @Test
    fun test_AnchorBottomRight_UnrestrictedBottomSheet_UnrestrictedSidebar_PushUpLeft() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        unrestrictedAreas.add(bottomBar)

        val maxRestrictedHorizontalPush =
                (algorithm.maxRestrictedDistanceFraction * SCREEN_SIZE.width).toInt()
        val sideBar = makeSideBar(maxRestrictedHorizontalPush + 100, Gravity.RIGHT)
        unrestrictedAreas.add(sideBar)

        val expectedBounds = anchorBoundsOffsetBy(
                SCREEN_EDGE_INSET - sideBar.width() - PADDING,
                SCREEN_EDGE_INSET - bottomBar.height() - PADDING
        )

        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_Stashed_UnstashBoundsBecomeUnobstructed_Unstashes() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        unrestrictedAreas.add(bottomBar)

        val maxRestrictedHorizontalPush =
                (algorithm.maxRestrictedDistanceFraction * SCREEN_SIZE.width).toInt()
        val sideBar = makeSideBar(maxRestrictedHorizontalPush + 100, Gravity.RIGHT)
        restrictedAreas.add(sideBar)

        val expectedUnstashBounds =
                anchorBoundsOffsetBy(0, SCREEN_EDGE_INSET - bottomBar.height() - PADDING)

        val expectedBounds = Rect(expectedUnstashBounds)
        expectedBounds.offsetTo(SCREEN_SIZE.width - STASH_OFFSET, expectedBounds.top)

        var placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_RIGHT, placement.stashType)
        assertEquals(expectedUnstashBounds, placement.unstashDestinationBounds)
        assertEquals(algorithm.stashDuration, placement.unstashTime)

        currentTime += 1000

        restrictedAreas.remove(sideBar)
        placement = getActualPlacement()
        assertEquals(expectedUnstashBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_Stashed_UnstashBoundsStaysObstructed_UnstashesAfterTimeout() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        unrestrictedAreas.add(bottomBar)

        val maxRestrictedHorizontalPush =
                (algorithm.maxRestrictedDistanceFraction * SCREEN_SIZE.width).toInt()
        val sideBar = makeSideBar(maxRestrictedHorizontalPush + 100, Gravity.RIGHT)
        restrictedAreas.add(sideBar)

        val expectedUnstashBounds =
                anchorBoundsOffsetBy(0, SCREEN_EDGE_INSET - bottomBar.height() - PADDING)

        val expectedBounds = Rect(expectedUnstashBounds)
        expectedBounds.offsetTo(SCREEN_SIZE.width - STASH_OFFSET, expectedBounds.top)

        var placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_RIGHT, placement.stashType)
        assertEquals(expectedUnstashBounds, placement.unstashDestinationBounds)
        assertEquals(algorithm.stashDuration, placement.unstashTime)

        currentTime += algorithm.stashDuration

        placement = getActualPlacement()
        assertEquals(expectedUnstashBounds, placement.bounds)
        assertNotStashed(placement)
    }

    @Test
    fun test_Stashed_UnstashBoundsObstructionChanges_UnstashTimeExtended() {
        gravity = Gravity.BOTTOM or Gravity.RIGHT

        val bottomBar = makeBottomBar(BOTTOM_SHEET_HEIGHT)
        unrestrictedAreas.add(bottomBar)

        val maxRestrictedHorizontalPush =
                (algorithm.maxRestrictedDistanceFraction * SCREEN_SIZE.width).toInt()
        val sideBar = makeSideBar(maxRestrictedHorizontalPush + 100, Gravity.RIGHT)
        restrictedAreas.add(sideBar)

        val expectedUnstashBounds =
                anchorBoundsOffsetBy(0, SCREEN_EDGE_INSET - bottomBar.height() - PADDING)

        val expectedBounds = Rect(expectedUnstashBounds)
        expectedBounds.offsetTo(SCREEN_SIZE.width - STASH_OFFSET, expectedBounds.top)

        var placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_RIGHT, placement.stashType)
        assertEquals(expectedUnstashBounds, placement.unstashDestinationBounds)
        assertEquals(algorithm.stashDuration, placement.unstashTime)

        currentTime += 1000

        val newObstruction = Rect(
                0,
                expectedUnstashBounds.top,
                expectedUnstashBounds.right,
                expectedUnstashBounds.bottom
        )
        restrictedAreas.add(newObstruction)

        placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_RIGHT, placement.stashType)
        assertEquals(expectedUnstashBounds, placement.unstashDestinationBounds)
        assertEquals(currentTime + algorithm.stashDuration, placement.unstashTime)
    }

    @Test
    fun test_ExpandedPiPHeightExceedsMovementBounds_AtAnchor() {
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        pipSize = Size(DEFAULT_PIP_SIZE.width, SCREEN_SIZE.height)
        testAnchorPosition()
    }

    @Test
    fun test_ExpandedPiPHeightExceedsMovementBounds_BottomBar_StashedUp() {
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        pipSize = Size(DEFAULT_PIP_SIZE.width, SCREEN_SIZE.height)
        val bottomBar = makeBottomBar(96)
        unrestrictedAreas.add(bottomBar)

        val expectedBounds = getExpectedAnchorBounds()
        expectedBounds.offset(0, -bottomBar.height() - PADDING)
        val placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
        assertEquals(STASH_TYPE_TOP, placement.stashType)
        assertEquals(getExpectedAnchorBounds(), placement.unstashDestinationBounds)
    }

    @Test
    fun test_PipInsets() {
        val permInsets = Insets.of(-1, -2, -3, -4)
        algorithm.setPipPermanentDecorInsets(permInsets)
        testInsetsForAllPositions(permInsets)

        val tempInsets = Insets.of(-4, -3, -2, -1)
        algorithm.setPipPermanentDecorInsets(Insets.NONE)
        algorithm.setPipTemporaryDecorInsets(tempInsets)
        testInsetsForAllPositions(tempInsets)

        algorithm.setPipPermanentDecorInsets(permInsets)
        algorithm.setPipTemporaryDecorInsets(tempInsets)
        testInsetsForAllPositions(Insets.add(permInsets, tempInsets))
    }

    private fun testInsetsForAllPositions(insets: Insets) {
        gravity = Gravity.BOTTOM or Gravity.RIGHT
        testAnchorPositionWithInsets(insets)

        gravity = Gravity.BOTTOM or Gravity.LEFT
        testAnchorPositionWithInsets(insets)

        gravity = Gravity.TOP or Gravity.LEFT
        testAnchorPositionWithInsets(insets)

        gravity = Gravity.TOP or Gravity.RIGHT
        testAnchorPositionWithInsets(insets)

        pipSize = EXPANDED_WIDE_PIP_SIZE

        gravity = Gravity.BOTTOM
        testAnchorPositionWithInsets(insets)

        gravity = Gravity.TOP
        testAnchorPositionWithInsets(insets)

        pipSize = Size(pipSize.height, pipSize.width)

        gravity = Gravity.LEFT
        testAnchorPositionWithInsets(insets)

        gravity = Gravity.RIGHT
        testAnchorPositionWithInsets(insets)
    }

    private fun testAnchorPositionWithInsets(insets: Insets) {
        var pipRect = Rect(0, 0, pipSize.width, pipSize.height)
        pipRect.inset(insets)
        var expectedBounds = Rect()
        Gravity.apply(gravity, pipRect.width(), pipRect.height(), movementBounds, expectedBounds)
        val reverseInsets = Insets.subtract(Insets.NONE, insets)
        expectedBounds.inset(reverseInsets)

        var placement = getActualPlacement()
        assertEquals(expectedBounds, placement.bounds)
    }

    private fun makeSideBar(width: Int, @Gravity.GravityFlags side: Int): Rect {
        val sidebar = Rect(0, 0, width, SCREEN_SIZE.height)
        if (side == Gravity.RIGHT) {
            sidebar.offsetTo(SCREEN_SIZE.width - width, 0)
        }
        return sidebar
    }

    private fun makeBottomBar(height: Int): Rect {
        return Rect(0, SCREEN_SIZE.height - height, SCREEN_SIZE.width, SCREEN_SIZE.height)
    }

    private fun getExpectedAnchorBounds(): Rect {
        val expectedBounds = Rect()
        Gravity.apply(gravity, pipSize.width, pipSize.height, movementBounds, expectedBounds)
        return expectedBounds
    }

    private fun anchorBoundsOffsetBy(dx: Int, dy: Int): Rect {
        val bounds = getExpectedAnchorBounds()
        bounds.offset(dx, dy)
        return bounds
    }

    private fun getActualPlacement(): Placement {
        algorithm.setGravity(gravity)
        return algorithm.calculatePipPosition(pipSize, restrictedAreas, unrestrictedAreas)
    }

    private fun assertNotStashed(actual: Placement) {
        assertEquals(STASH_TYPE_NONE, actual.stashType)
        assertNull(actual.unstashDestinationBounds)
        assertEquals(0L, actual.unstashTime)
    }
}
