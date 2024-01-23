/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedSizeEdgeDetectorTest {
    private val detector = FixedSizeEdgeDetector(30.dp)
    private val layoutSize = IntSize(100, 100)
    private val density = Density(1f)

    @Test
    fun horizontalEdges() {
        fun horizontalEdge(position: Int): Edge? =
            detector.source(
                layoutSize,
                position = IntOffset(position, 0),
                density,
                Orientation.Horizontal,
            )

        assertThat(horizontalEdge(0)).isEqualTo(Edge.Left)
        assertThat(horizontalEdge(30)).isEqualTo(Edge.Left)
        assertThat(horizontalEdge(31)).isEqualTo(null)
        assertThat(horizontalEdge(69)).isEqualTo(null)
        assertThat(horizontalEdge(70)).isEqualTo(Edge.Right)
        assertThat(horizontalEdge(100)).isEqualTo(Edge.Right)
    }

    @Test
    fun verticalEdges() {
        fun verticalEdge(position: Int): Edge? =
            detector.source(
                layoutSize,
                position = IntOffset(0, position),
                density,
                Orientation.Vertical,
            )

        assertThat(verticalEdge(0)).isEqualTo(Edge.Top)
        assertThat(verticalEdge(30)).isEqualTo(Edge.Top)
        assertThat(verticalEdge(31)).isEqualTo(null)
        assertThat(verticalEdge(69)).isEqualTo(null)
        assertThat(verticalEdge(70)).isEqualTo(Edge.Bottom)
        assertThat(verticalEdge(100)).isEqualTo(Edge.Bottom)
    }
}
