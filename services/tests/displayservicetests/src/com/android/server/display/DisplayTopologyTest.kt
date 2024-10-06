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

package com.android.server.display

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayTopologyTest {
    private val topology = DisplayTopology()

    @Test
    fun oneDisplay() {
        val displayId = 1
        val width = 800.0
        val height = 600.0

        topology.addDisplay(displayId, width, height)

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId)

        val display = topology.mRoot!!
        assertThat(display.mDisplayId).isEqualTo(displayId)
        assertThat(display.mWidth).isEqualTo(width)
        assertThat(display.mHeight).isEqualTo(height)
        assertThat(display.mChildren).isEmpty()
    }

    @Test
    fun twoDisplays() {
        val displayId1 = 1
        val width1 = 800.0
        val height1 = 600.0

        val displayId2 = 2
        val width2 = 1000.0
        val height2 = 1500.0

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.mRoot!!
        assertThat(display1.mDisplayId).isEqualTo(displayId1)
        assertThat(display1.mWidth).isEqualTo(width1)
        assertThat(display1.mHeight).isEqualTo(height1)
        assertThat(display1.mChildren).hasSize(1)

        val display2 = display1.mChildren[0]
        assertThat(display2.mDisplayId).isEqualTo(displayId2)
        assertThat(display2.mWidth).isEqualTo(width2)
        assertThat(display2.mHeight).isEqualTo(height2)
        assertThat(display2.mChildren).isEmpty()
        assertThat(display2.mPosition).isEqualTo(
            DisplayTopology.TreeNode.Position.POSITION_TOP)
        assertThat(display2.mOffset).isEqualTo(width1 / 2 - width2 / 2)
    }

    @Test
    fun manyDisplays() {
        val displayId1 = 1
        val width1 = 800.0
        val height1 = 600.0

        val displayId2 = 2
        val width2 = 1000.0
        val height2 = 1500.0

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        val noOfDisplays = 30
        for (i in 3..noOfDisplays) {
            topology.addDisplay(/* displayId= */ i, width1, height1)
        }

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.mRoot!!
        assertThat(display1.mDisplayId).isEqualTo(displayId1)
        assertThat(display1.mWidth).isEqualTo(width1)
        assertThat(display1.mHeight).isEqualTo(height1)
        assertThat(display1.mChildren).hasSize(1)

        val display2 = display1.mChildren[0]
        assertThat(display2.mDisplayId).isEqualTo(displayId2)
        assertThat(display2.mWidth).isEqualTo(width2)
        assertThat(display2.mHeight).isEqualTo(height2)
        assertThat(display2.mChildren).hasSize(1)
        assertThat(display2.mPosition).isEqualTo(
            DisplayTopology.TreeNode.Position.POSITION_TOP)
        assertThat(display2.mOffset).isEqualTo(width1 / 2 - width2 / 2)

        var display = display2
        for (i in 3..noOfDisplays) {
            display = display.mChildren[0]
            assertThat(display.mDisplayId).isEqualTo(i)
            assertThat(display.mWidth).isEqualTo(width1)
            assertThat(display.mHeight).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.mChildren).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.mPosition).isEqualTo(
                DisplayTopology.TreeNode.Position.POSITION_RIGHT)
            assertThat(display.mOffset).isEqualTo(0)
        }
    }
}