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

package com.android.systemui.scene.ui.viewmodel

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.End
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.Resolved.Bottom
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.Resolved.Left
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.Resolved.Right
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.Resolved.TopLeft
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.Resolved.TopRight
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.Start
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.TopEnd
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge.TopStart
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SplitEdgeDetectorTest : SysuiTestCase() {

    private val edgeSize = 40
    private val screenWidth = 800
    private val screenHeight = 600

    private var edgeSplitFraction = 0.7f

    private val underTest =
        SplitEdgeDetector(
            topEdgeSplitFraction = { edgeSplitFraction },
            edgeSize = edgeSize.dp,
        )

    @Test
    fun source_noEdge_detectsNothing() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = screenWidth / 2,
                y = screenHeight / 2,
            )
        assertThat(detectedEdge).isNull()
    }

    @Test
    fun source_swipeVerticallyOnTopLeft_detectsTopLeft() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = 1,
                y = edgeSize - 1,
            )
        assertThat(detectedEdge).isEqualTo(TopLeft)
    }

    @Test
    fun source_swipeHorizontallyOnTopLeft_detectsLeft() {
        val detectedEdge =
            swipeHorizontallyFrom(
                x = 1,
                y = edgeSize - 1,
            )
        assertThat(detectedEdge).isEqualTo(Left)
    }

    @Test
    fun source_swipeVerticallyOnTopRight_detectsTopRight() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = screenWidth - 1,
                y = edgeSize - 1,
            )
        assertThat(detectedEdge).isEqualTo(TopRight)
    }

    @Test
    fun source_swipeHorizontallyOnTopRight_detectsRight() {
        val detectedEdge =
            swipeHorizontallyFrom(
                x = screenWidth - 1,
                y = edgeSize - 1,
            )
        assertThat(detectedEdge).isEqualTo(Right)
    }

    @Test
    fun source_swipeVerticallyToLeftOfSplit_detectsTopLeft() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = (screenWidth * edgeSplitFraction).toInt() - 1,
                y = edgeSize - 1,
            )
        assertThat(detectedEdge).isEqualTo(TopLeft)
    }

    @Test
    fun source_swipeVerticallyToRightOfSplit_detectsTopRight() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = (screenWidth * edgeSplitFraction).toInt() + 1,
                y = edgeSize - 1,
            )
        assertThat(detectedEdge).isEqualTo(TopRight)
    }

    @Test
    fun source_edgeSplitFractionUpdatesDynamically() {
        val middleX = (screenWidth * 0.5f).toInt()
        val topY = 0

        // Split closer to the right; middle of screen is considered "left".
        edgeSplitFraction = 0.6f
        assertThat(swipeVerticallyFrom(x = middleX, y = topY)).isEqualTo(TopLeft)

        // Split closer to the left; middle of screen is considered "right".
        edgeSplitFraction = 0.4f
        assertThat(swipeVerticallyFrom(x = middleX, y = topY)).isEqualTo(TopRight)

        // Illegal fraction.
        edgeSplitFraction = 1.2f
        assertFailsWith<IllegalArgumentException> { swipeVerticallyFrom(x = middleX, y = topY) }

        // Illegal fraction.
        edgeSplitFraction = -0.3f
        assertFailsWith<IllegalArgumentException> { swipeVerticallyFrom(x = middleX, y = topY) }
    }

    @Test
    fun source_swipeVerticallyOnBottom_detectsBottom() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = screenWidth / 3,
                y = screenHeight - (edgeSize / 2),
            )
        assertThat(detectedEdge).isEqualTo(Bottom)
    }

    @Test
    fun source_swipeHorizontallyOnBottom_detectsNothing() {
        val detectedEdge =
            swipeHorizontallyFrom(
                x = screenWidth / 3,
                y = screenHeight - (edgeSize - 1),
            )
        assertThat(detectedEdge).isNull()
    }

    @Test
    fun source_swipeHorizontallyOnLeft_detectsLeft() {
        val detectedEdge =
            swipeHorizontallyFrom(
                x = edgeSize - 1,
                y = screenHeight / 2,
            )
        assertThat(detectedEdge).isEqualTo(Left)
    }

    @Test
    fun source_swipeVerticallyOnLeft_detectsNothing() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = edgeSize - 1,
                y = screenHeight / 2,
            )
        assertThat(detectedEdge).isNull()
    }

    @Test
    fun source_swipeHorizontallyOnRight_detectsRight() {
        val detectedEdge =
            swipeHorizontallyFrom(
                x = screenWidth - edgeSize + 1,
                y = screenHeight / 2,
            )
        assertThat(detectedEdge).isEqualTo(Right)
    }

    @Test
    fun source_swipeVerticallyOnRight_detectsNothing() {
        val detectedEdge =
            swipeVerticallyFrom(
                x = screenWidth - edgeSize + 1,
                y = screenHeight / 2,
            )
        assertThat(detectedEdge).isNull()
    }

    @Test
    fun resolve_startInLtr_resolvesLeft() {
        val resolvedEdge = Start.resolve(LayoutDirection.Ltr)
        assertThat(resolvedEdge).isEqualTo(Left)
    }

    @Test
    fun resolve_startInRtl_resolvesRight() {
        val resolvedEdge = Start.resolve(LayoutDirection.Rtl)
        assertThat(resolvedEdge).isEqualTo(Right)
    }

    @Test
    fun resolve_endInLtr_resolvesRight() {
        val resolvedEdge = End.resolve(LayoutDirection.Ltr)
        assertThat(resolvedEdge).isEqualTo(Right)
    }

    @Test
    fun resolve_endInRtl_resolvesLeft() {
        val resolvedEdge = End.resolve(LayoutDirection.Rtl)
        assertThat(resolvedEdge).isEqualTo(Left)
    }

    @Test
    fun resolve_topStartInLtr_resolvesTopLeft() {
        val resolvedEdge = TopStart.resolve(LayoutDirection.Ltr)
        assertThat(resolvedEdge).isEqualTo(TopLeft)
    }

    @Test
    fun resolve_topStartInRtl_resolvesTopRight() {
        val resolvedEdge = TopStart.resolve(LayoutDirection.Rtl)
        assertThat(resolvedEdge).isEqualTo(TopRight)
    }

    @Test
    fun resolve_topEndInLtr_resolvesTopRight() {
        val resolvedEdge = TopEnd.resolve(LayoutDirection.Ltr)
        assertThat(resolvedEdge).isEqualTo(TopRight)
    }

    @Test
    fun resolve_topEndInRtl_resolvesTopLeft() {
        val resolvedEdge = TopEnd.resolve(LayoutDirection.Rtl)
        assertThat(resolvedEdge).isEqualTo(TopLeft)
    }

    private fun swipeVerticallyFrom(x: Int, y: Int): SceneContainerEdge.Resolved? {
        return swipeFrom(x, y, Orientation.Vertical)
    }

    private fun swipeHorizontallyFrom(x: Int, y: Int): SceneContainerEdge.Resolved? {
        return swipeFrom(x, y, Orientation.Horizontal)
    }

    private fun swipeFrom(x: Int, y: Int, orientation: Orientation): SceneContainerEdge.Resolved? {
        return underTest.source(
            layoutSize = IntSize(width = screenWidth, height = screenHeight),
            position = IntOffset(x, y),
            density = Density(1f),
            orientation = orientation,
        )
    }
}
