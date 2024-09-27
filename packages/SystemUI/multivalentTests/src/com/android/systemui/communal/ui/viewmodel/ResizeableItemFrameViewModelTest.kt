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
import kotlin.time.Duration
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
            maxItemSpan = 1,
            minItemSpan = 1,
            currentSpan = 1,
            currentRow = 0,
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
        testScope.runTest(timeout = Duration.INFINITE) {
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
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2))

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
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2, currentRow = 1))

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
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2, currentSpan = 2))

            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, -1 to -45f)
        }

    /**
     * Verifies element in a middle row at minimum size can be expanded from either top or bottom.
     */
    @Test
    fun testThreeSpanGrid_elementInMiddleRow_sizeOneSpan() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 3, currentRow = 1))

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
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 3))

            val topState = underTest.topDragState
            assertThat(topState.currentValue).isEqualTo(0)
            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)

            val bottomState = underTest.bottomDragState
            assertThat(bottomState.currentValue).isEqualTo(0)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 30f, 2 to 60f)
        }

    @Test
    fun testSixSpanGrid_minSpanThree_itemInThirdRow_sizeThreeSpans() =
        testScope.runTest {
            updateGridLayout(
                singleSpanGrid.copy(
                    maxItemSpan = 6,
                    currentRow = 3,
                    currentSpan = 3,
                    minItemSpan = 3,
                )
            )

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
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2))

            val topState = underTest.topDragState
            val bottomState = underTest.bottomDragState

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f, 1 to 45f)

            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2, currentRow = 1))

            assertThat(topState.anchors.toList()).containsExactly(0 to 0f, -1 to -45f)
            assertThat(bottomState.anchors.toList()).containsExactly(0 to 0f)
        }

    @Test
    fun testTwoSpanGrid_expandElementFromBottom() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2))

        assertThat(resizeInfo).isNull()
        underTest.bottomDragState.anchoredDrag { dragTo(45f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(1, DragHandle.BOTTOM))
    }

    @Test
    fun testThreeSpanGrid_expandMiddleElementUpwards() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(maxItemSpan = 3, currentRow = 1))

        assertThat(resizeInfo).isNull()
        underTest.topDragState.anchoredDrag { dragTo(-30f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(1, DragHandle.TOP))
    }

    @Test
    fun testThreeSpanGrid_expandTopElementDownBy2Spans() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(maxItemSpan = 3))

        assertThat(resizeInfo).isNull()
        underTest.bottomDragState.anchoredDrag { dragTo(60f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(2, DragHandle.BOTTOM))
    }

    @Test
    fun testTwoSpanGrid_shrinkElementFromBottom() = runTestWithSnapshots {
        val resizeInfo by collectLastValue(underTest.resizeInfo)
        updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2, currentSpan = 2))

        assertThat(resizeInfo).isNull()
        underTest.bottomDragState.anchoredDrag { dragTo(-45f) }
        assertThat(resizeInfo).isEqualTo(ResizeInfo(-1, DragHandle.BOTTOM))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_maxSpanSmallerThanMinSpan() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2, minItemSpan = 3))
        }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_minSpanOfZero() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 2, minItemSpan = 0))
        }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_maxSpanOfZero() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 0, minItemSpan = 0))
        }

    @Test(expected = IllegalArgumentException::class)
    fun testIllegalState_currentRowNotMultipleOfMinSpan() =
        testScope.runTest {
            updateGridLayout(singleSpanGrid.copy(maxItemSpan = 6, minItemSpan = 3, currentSpan = 2))
        }

    private fun TestScope.updateGridLayout(gridLayout: GridLayout) {
        underTest.setGridLayoutInfo(
            gridLayout.verticalItemSpacingPx,
            gridLayout.verticalContentPaddingPx,
            gridLayout.viewportHeightPx,
            gridLayout.maxItemSpan,
            gridLayout.minItemSpan,
            gridLayout.currentRow,
            gridLayout.currentSpan,
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
        val maxItemSpan: Int,
        val minItemSpan: Int,
        val currentRow: Int,
        val currentSpan: Int,
    )
}
