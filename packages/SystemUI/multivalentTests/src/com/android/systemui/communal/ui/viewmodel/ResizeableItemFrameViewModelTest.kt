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

package com.android.systemui.communal.ui.viewmodel

import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ResizeableItemFrameViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.resizeableItemFrameViewModel

    /** Total viewport height of the entire grid */
    private val viewportHeightPx = 100
    /** Total amount of vertical padding around the viewport */
    private val verticalContentPaddingPx = 20f

    private val singleSpanGrid =
        GridLayout(
            verticalItemSpacingPx = 10f,
            verticalContentPaddingPx = verticalContentPaddingPx,
            viewportHeightPx = viewportHeightPx,
            currentRow = 0,
            currentSpan = 1,
            maxHeightPx = Int.MAX_VALUE,
            minHeightPx = 0,
            resizeMultiple = 1,
            totalSpans = 1,
        )

    @Before
    fun setUp() {
        underTest.activateIn(testScope)
    }

    @Test
    fun testDefaultState() {
        val topState = underTest.topDragState
        assertThat(topState.currentValue).isEqualTo(0)
        assertThat(topState.offset).isEqualTo(0f)
        assertThat(topState.anchors.toList()).containsExactly(0 to 0f)

        val bottomState = underTest.bottomDragState
        assertThat(bottomState.currentValue).isEqualTo(0)
        assertThat(bottomState.offset).isEqualTo(0f)
        assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
    }

    @Test
    fun testSingleSpanGrid() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid)

            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    /**
     * Verifies element in first row which is already at the minimum size can only be expanded
     * downwards.
     */
    @Test
    fun testTwoSpanGrid_elementInFirstRow_sizeSingleSpan() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(currentRow = 0, totalSpans = 2))
            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 45f)
        }

    /**
     * Verifies element in second row which is already at the minimum size can only be expanded
     * upwards.
     */
    @Test
    fun testTwoSpanGrid_elementInSecondRow_sizeSingleSpan() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(currentRow = 1, totalSpans = 2))
            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f, -1 to -45f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    /**
     * Verifies element in first row which is already at full size (2 span) can only be shrunk from
     * the bottom.
     */
    @Test
    fun testTwoSpanGrid_elementInFirstRow_sizeTwoSpan() =
        testScope.runTest {
            val adjustedGridLayout = singleSpanGrid.copy(currentSpan = 2, totalSpans = 2)

            updateGridLayout(adjustedGridLayout)

            val topState = underTest.topDragState
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(topState.currentValue).isEqualTo(0)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.anchors.toList()).containsExactly(-1 to -45f, 0 to 0f)
            assertThat(bottomState.currentValue).isEqualTo(0)
        }

    /**
     * Verifies element in a middle row at minimum size can be expanded from either top or bottom.
     */
    @Test
    fun testThreeSpanGrid_elementInMiddleRow_sizeOneSpan() =
        testScope.runTest {
            val adjustedGridLayout =
                singleSpanGrid.copy(currentRow = 1, currentSpan = 1, totalSpans = 3)

            updateGridLayout(adjustedGridLayout)

            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f, -1 to -30f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 30f)
        }

    @Test
    fun testThreeSpanGrid_elementInTopRow_sizeOneSpan() =
        testScope.runTest {
            val adjustedGridLayout =
                singleSpanGrid.copy(currentRow = 0, currentSpan = 1, totalSpans = 3)

            updateGridLayout(adjustedGridLayout)

            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 30f, 2 to 60f)
        }

    @Test
    fun testSixSpanGrid_minSpanThree_itemInFourthRow_sizeThreeSpans() =
        testScope.runTest {
            val adjustedGridLayout =
                singleSpanGrid.copy(
                    currentRow = 3,
                    currentSpan = 3,
                    resizeMultiple = 3,
                    totalSpans = 6,
                )

            updateGridLayout(adjustedGridLayout)

            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f, -3 to -45f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test
    fun testTwoSpanGrid_elementMovesFromFirstRowToSecondRow() =
        testScope.runTest {
            val firstRowLayout =
                singleSpanGrid.copy(
                    currentRow = 0,
                    currentSpan = 1,
                    resizeMultiple = 1,
                    totalSpans = 2,
                )
            updateGridLayout(firstRowLayout)

            val topState = underTest.topDragState
            val bottomState = underTest.bottomDragState

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 45f)

            val secondRowLayout = firstRowLayout.copy(currentRow = 1)
            updateGridLayout(secondRowLayout)

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f, -1 to -45f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test
    fun testTwoSpanGrid_expandElementFromBottom() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)

        val adjustedGridLayout = singleSpanGrid.copy(resizeMultiple = 1, totalSpans = 2)

        updateGridLayout(adjustedGridLayout)

        underTest.bottomDragState.anchoredDrag { dragTo(45f) }

        assertThat(resizeInfo).isEqualTo(ResizeInfo(1, DragHandle.BOTTOM))
    }

    @Test
    fun testThreeSpanGrid_expandMiddleElementUpwards() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(currentRow = 1, totalSpans = 3))

        underTest.topDragState.anchoredDrag { dragTo(-30f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(1, DragHandle.TOP))
    }

    @Test
    fun testThreeSpanGrid_expandTopElementDownBy2Spans() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(totalSpans = 3))

        assertThat(resizeInfo).isNull()
        underTest.bottomDragState.anchoredDrag { dragTo(60f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(2, DragHandle.BOTTOM))
    }

    @Test
    fun testTwoSpanGrid_shrinkElementFromBottom() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(totalSpans = 2, currentSpan = 2))

        assertThat(resizeInfo).isNull()
        underTest.bottomDragState.anchoredDrag { dragTo(-45f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(-1, DragHandle.BOTTOM))
    }

    @Test
    fun testRowInfoBecomesNull_revertsBackToDefault() =
        testScope.runTest {
            val gridLayout = singleSpanGrid.copy(currentRow = 1, resizeMultiple = 1, totalSpans = 3)
            updateGridLayout(gridLayout)

            val topState = underTest.topDragState
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f, -1 to -30f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 30f)

            // Set currentRow to null to simulate the row info becoming null
            updateGridLayout(gridLayout.copy(currentRow = null))
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test
    fun testEqualMaxAndMinHeight_cannotResize() =
        testScope.runTest {
            val heightPx = 20
            updateGridLayout(
                singleSpanGrid.copy(maxHeightPx = heightPx, minHeightPx = heightPx, totalSpans = 2)
            )

            val topState = underTest.topDragState
            val bottomState = underTest.bottomDragState

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test
    fun testMinHeightTwoRows_canExpandButNotShrink() =
        testScope.runTest {
            val threeRowGrid =
                singleSpanGrid.copy(
                    maxHeightPx = 80,
                    minHeightPx = 50,
                    totalSpans = 3,
                    currentSpan = 2,
                    currentRow = 0,
                )

            updateGridLayout(threeRowGrid)

            val topState = underTest.topDragState
            val bottomState = underTest.bottomDragState
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(bottomState.anchors.toList()).containsAtLeast(0 to 0f, 1 to 30f)
        }

    @Test
    fun testMaxHeightTwoRows_canShrinkButNotExpand() =
        testScope.runTest {
            val threeRowGrid =
                singleSpanGrid.copy(
                    maxHeightPx = 50,
                    minHeightPx = 20,
                    totalSpans = 3,
                    currentSpan = 2,
                    currentRow = 0,
                )

            updateGridLayout(threeRowGrid)

            val topState = underTest.topDragState
            val bottomState = underTest.bottomDragState

            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, -1 to -30f)

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test
    fun testMinHeightEqualToAvailableSpan_cannotResize() =
        testScope.runTest {
            val twoRowGrid =
                singleSpanGrid.copy(
                    minHeightPx = (viewportHeightPx - verticalContentPaddingPx.toInt()),
                    totalSpans = 2,
                    currentSpan = 2,
                )

            updateGridLayout(twoRowGrid)

            val topState = underTest.topDragState
            val bottomState = underTest.bottomDragState

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_maxHeightLessThanMinHeight() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(maxHeightPx = 50, minHeightPx = 100))
        }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_currentSpanExceedsTotalSpans() =
        testScope.runTest { updateGridLayout(singleSpanGrid.copy(currentSpan = 3, totalSpans = 2)) }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_resizeMultipleZeroOrNegative() =
        testScope.runTest { updateGridLayout(singleSpanGrid.copy(resizeMultiple = 0)) }

    private fun TestScope.updateGridLayout(gridLayout: GridLayout) {
        underTest.setGridLayoutInfo(
            verticalItemSpacingPx = gridLayout.verticalItemSpacingPx,
            currentRow = gridLayout.currentRow,
            maxHeightPx = gridLayout.maxHeightPx,
            minHeightPx = gridLayout.minHeightPx,
            currentSpan = gridLayout.currentSpan,
            resizeMultiple = gridLayout.resizeMultiple,
            totalSpans = gridLayout.totalSpans,
            viewportHeightPx = gridLayout.viewportHeightPx,
            verticalContentPaddingPx = gridLayout.verticalContentPaddingPx,
        )
        runCurrent()
    }

    private fun DraggableAnchors<Int>.toList() = buildList {
        for (index in 0 until this@toList.size) {
            add(anchorAt(index) to positionAt(index))
        }
    }

    private fun runTestWithSnapshots(testBody: suspend TestScope.() -> Unit) {
        val globalWriteObserverHandle =
            Snapshot.registerGlobalWriteObserver {
                // This is normally done by the compose runtime.
                Snapshot.sendApplyNotifications()
            }

        try {
            testScope.runTest(testBody = testBody)
        } finally {
            globalWriteObserverHandle.dispose()
        }
    }

    private data class GridLayout(
        val verticalItemSpacingPx: Float,
        val verticalContentPaddingPx: Float,
        val viewportHeightPx: Int,
        val currentRow: Int?,
        val currentSpan: Int,
        val maxHeightPx: Int,
        val minHeightPx: Int,
        val resizeMultiple: Int,
        val totalSpans: Int,
    )
}
