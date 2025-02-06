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

package android.hardware.display

import android.graphics.PointF
import android.graphics.RectF
import android.hardware.display.DisplayTopology.TreeNode.POSITION_BOTTOM
import android.hardware.display.DisplayTopology.TreeNode.POSITION_LEFT
import android.hardware.display.DisplayTopology.TreeNode.POSITION_TOP
import android.hardware.display.DisplayTopology.TreeNode.POSITION_RIGHT
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.Display
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayTopologyTest {
    private var topology = DisplayTopology()

    @Test
    fun addOneDisplay() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId)
        verifyDisplay(topology.root!!, displayId, width, height, noOfChildren = 0)
    }

    @Test
    fun addTwoDisplays() {
        val displayId1 = 1
        val width1 = 800f
        val height1 = 600f

        val displayId2 = 2
        val width2 = 1000f
        val height2 = 1500f

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)
        verifyDisplay(display1.children[0], displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 0)
    }

    @Test
    fun addManyDisplays() {
        val displayId1 = 1
        val width1 = 800f
        val height1 = 600f

        val displayId2 = 2
        val width2 = 1000f
        val height2 = 1500f

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        val noOfDisplays = 30
        for (i in 3..noOfDisplays) {
            topology.addDisplay(/* displayId= */ i, width1, height1)
        }

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)

        val display2 = display1.children[0]
        verifyDisplay(display2, displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        var display = display2
        for (i in 3..noOfDisplays) {
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, width1, height1, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }
    }

    @Test
    fun updateDisplay() {
        val displayId = 1
        val width = 800f
        val height = 600f

        val newWidth = 1000f
        val newHeight = 500f
        topology.addDisplay(displayId, width, height)
        assertThat(topology.updateDisplay(displayId, newWidth, newHeight)).isTrue()

        assertThat(topology.primaryDisplayId).isEqualTo(displayId)
        verifyDisplay(topology.root!!, displayId, newWidth, newHeight, noOfChildren = 0)
    }

    @Test
    fun updateDisplay_notUpdated() {
        val displayId = 1
        val width = 800f
        val height = 600f
        topology.addDisplay(displayId, width, height)

        // Same size
        assertThat(topology.updateDisplay(displayId, width, height)).isFalse()

        // Display doesn't exist
        assertThat(topology.updateDisplay(/* displayId= */ 100, width, height)).isFalse()

        assertThat(topology.primaryDisplayId).isEqualTo(displayId)
        verifyDisplay(topology.root!!, displayId, width, height, noOfChildren = 0)
    }

    @Test
    fun updateDisplayDoesNotAffectDefaultTopology() {
        val width1 = 700f
        val height = 600f
        topology.addDisplay(/* displayId= */ 1, width1, height)

        val width2 = 800f
        val noOfDisplays = 30
        for (i in 2..noOfDisplays) {
            topology.addDisplay(/* displayId= */ i, width2, height)
        }

        val displaysToUpdate = arrayOf(3, 7, 18)
        val newWidth = 1000f
        val newHeight = 1500f
        for (i in displaysToUpdate) {
            assertThat(topology.updateDisplay(/* displayId= */ i, newWidth, newHeight)).isTrue()
        }

        assertThat(topology.primaryDisplayId).isEqualTo(1)

        val display1 = topology.root!!
        verifyDisplay(display1, id = 1, width1, height, noOfChildren = 1)

        val display2 = display1.children[0]
        verifyDisplay(display2, id = 2, width2, height, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        var display = display2
        for (i in 3..noOfDisplays) {
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, if (i in displaysToUpdate) newWidth else width2,
                if (i in displaysToUpdate) newHeight else height, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }
    }

    @Test
    fun removeDisplays() {
        val displayId1 = 1
        val width1 = 800f
        val height1 = 600f

        val displayId2 = 2
        val width2 = 1000f
        val height2 = 1500f

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        val noOfDisplays = 30
        for (i in 3..noOfDisplays) {
            topology.addDisplay(/* displayId= */ i, width1, height1)
        }

        var removedDisplays = arrayOf(20)
        assertThat(topology.removeDisplay(20)).isTrue()

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        var display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)

        var display2 = display1.children[0]
        verifyDisplay(display2, displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        var display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, width1, height1, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }

        assertThat(topology.removeDisplay(22)).isTrue()
        removedDisplays += 22
        assertThat(topology.removeDisplay(23)).isTrue()
        removedDisplays += 23
        assertThat(topology.removeDisplay(25)).isTrue()
        removedDisplays += 25

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)

        display2 = display1.children[0]
        verifyDisplay(display2, displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, width1, height1, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }
    }

    @Test
    fun removeAllDisplays() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)
        assertThat(topology.removeDisplay(displayId)).isTrue()

        assertThat(topology.primaryDisplayId).isEqualTo(Display.INVALID_DISPLAY)
        assertThat(topology.root).isNull()
    }

    @Test
    fun removeDisplayThatDoesNotExist() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)
        assertThat(topology.removeDisplay(3)).isFalse()

        assertThat(topology.primaryDisplayId).isEqualTo(displayId)
        verifyDisplay(topology.root!!, displayId, width, height, noOfChildren = 0)
    }

    @Test
    fun removePrimaryDisplay() {
        val displayId1 = 1
        val displayId2 = 2
        val width = 800f
        val height = 600f

        topology = DisplayTopology(/* root= */ null, displayId2)
        topology.addDisplay(displayId1, width, height)
        topology.addDisplay(displayId2, width, height)
        assertThat(topology.removeDisplay(displayId2)).isTrue()

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)
        verifyDisplay(topology.root!!, displayId1, width, height, noOfChildren = 0)
    }

    @Test
    fun normalization_clampsOffsets() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 800f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_LEFT, /* offset= */ -300f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_TOP, /* offset= */ 1000f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 2)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 600f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay1.children[1]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_LEFT,
            offset = -200f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_TOP,
            offset = 600f, noOfChildren = 0)
    }

    @Test
    fun normalization_noOverlaps_leavesTopologyUnchanged() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 2)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay1.children[1]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 400f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 0)
    }

    @Test
    fun normalization_moveDisplayWithoutReparenting() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 10f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        // Display 3 becomes a child of display 2. Display 4 gets moved without changing its parent.
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 1)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 2)

        val actualDisplay3 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 10f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[1]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 210f, noOfChildren = 0)
    }

    @Test
    fun normalization_moveDisplayWithoutReparenting_offsetOutOfBounds() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 50f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 10f)
        display1.addChild(display3)

        topology = DisplayTopology(display1, primaryDisplayId)
        // Display 3 gets moved and its left side is still on the same line as the right side
        // of Display 1, but it no longer touches it (the offset is out of bounds), so Display 2
        // becomes its new parent.
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 50f, noOfChildren = 1)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_BOTTOM,
            offset = 0f, noOfChildren = 0)
    }

    @Test
    fun normalization_moveAndReparentDisplay() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 1)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 400f, noOfChildren = 1)

        val actualDisplay4 = actualDisplay3.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = -400f, noOfChildren = 0)
    }

    @Test
    fun rearrange_twoDisplays() {
        val root = rearrangeRects(
            // Arrange in staggered manner, connected vertically.
            RectF(100f, 100f, 250f, 200f),
            RectF(150f, 200f, 300f, 300f),
        )

        verifyDisplay(root, id = 0, width = 150f, height = 100f, noOfChildren = 1)
        val node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 150f, height = 100f, POSITION_BOTTOM, offset = 50f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_reverseOrderOfSeveralDisplays() {
        val root = rearrangeRects(
            RectF(0f, 0f, 150f, 100f),
            RectF(-150f, 0f, 0f, 100f),
            RectF(-300f, 0f, -150f, 100f),
            RectF(-450f, 0f, -300f, 100f),
        )

        verifyDisplay(root, id = 0, width = 150f, height = 100f, noOfChildren = 1)
        var node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 2, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 3, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_crossWithRootInCenter() {
        val root = rearrangeRects(
            RectF(0f, 0f, 150f, 100f),
            RectF(-150f, 0f, 0f, 100f),
            RectF(0f, -100f, 150f, 0f),
            RectF(150f, 0f, 300f, 100f),
            RectF(0f, 100f, 150f, 200f),
        )

        verifyDisplay(root, id = 0, width = 150f, height = 100f, noOfChildren = 4)
        verifyDisplay(
                root.children[0], id = 1, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[1], id = 2, width = 150f, height = 100f, POSITION_TOP, offset = 0f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[2], id = 3, width = 150f, height = 100f, POSITION_RIGHT, offset = 0f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[3], id = 4, width = 150f, height = 100f, POSITION_BOTTOM, offset = 0f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_elbowArrangementDoesNotUseCornerAdjacency1() {
        val root = rearrangeRects(
            //     2
            //     |
            // 0 - 1

            RectF(0f, 0f, 100f, 100f),
            RectF(100f, 0f, 200f, 100f),
            RectF(100f, -100f, 200f, 0f),
        )

        verifyDisplay(root, id = 0, width = 100f, height = 100f, noOfChildren = 1)
        var node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 100f, height = 100f, POSITION_RIGHT, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 2, width = 100f, height = 100f, POSITION_TOP,
                offset = 0f, noOfChildren = 0)
    }

    @Test
    fun rearrange_elbowArrangementDoesNotUseCornerAdjacency2() {
        val root = rearrangeRects(
            //     0
            //     |
            //     1
            //     |
            // 3 - 2

            RectF(0f, 0f, 100f, 100f),
            RectF(0f, 100f, 100f, 200f),
            RectF(0f, 200f, 100f, 300f),
            RectF(-100f, 200f, 0f, 300f),
        )

        verifyDisplay(root, id = 0, width = 100f, height = 100f, noOfChildren = 1)
        var node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 100f, height = 100f, POSITION_BOTTOM, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 2, width = 100f, height = 100f, POSITION_BOTTOM, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 3, width = 100f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_useLargerEdge() {
        val root = rearrangeRects(
            // 444111
            // 444111
            // 444111
            //   000222
            //   000222
            //   000222
            //     333
            //     333
            //     333
            RectF(20f, 30f, 50f, 60f),
            RectF(30f, 0f, 60f, 30f),
            RectF(50f, 30f, 80f, 60f),
            RectF(40f, 60f, 70f, 90f),
            RectF(0f, 0f, 30f, 30f),
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 2)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_TOP,
                offset = 10f, noOfChildren = 1)
        verifyDisplay(
                root.children[0].children[0], id = 4, width = 30f, height = 30f, POSITION_LEFT,
                offset = 0f, noOfChildren = 0)
        verifyDisplay(
                root.children[1], id = 2, width = 30f, height = 30f, POSITION_RIGHT,
                offset = 0f, noOfChildren = 1)
        verifyDisplay(
                root.children[1].children[0], id = 3, width = 30f, height = 30f, POSITION_BOTTOM,
                offset = -10f, noOfChildren = 0)
    }

    @Test
    fun rearrange_closeGaps() {
        val root = rearrangeRects(
            // 000
            // 000 111
            // 000 111
            //     111
            //
            //         222
            //         222
            //         222
            RectF(0f, 0f, 30f, 30f),
            RectF(40f, 10f, 70f, 40f),
            RectF(80.5f, 50f, 110f, 80f),
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 1)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_RIGHT, offset = 10f,
                noOfChildren = 1)
        verifyDisplay(
                root.children[0].children[0], id = 2, width = 29.5f, height = 30f, POSITION_RIGHT,
                offset = 30f, noOfChildren = 0)
    }

    @Test
    fun rearrange_preferLessShiftInOverlapDimension() {
        val root = rearrangeRects(
            // '*' represents overlap
            // Clamping requires moving display 2 and 1 slightly to avoid overlap with 0. We should
            // shift the minimal amount to avoid overlap - e.g. display 2 shifts left (10 pixels)
            // rather than up (20 pixels).
            // 222
            // 22*00
            // 22*00
            //   0**1
            //    111
            //    111
            RectF(20f, 10f, 50f, 40f),
            RectF(30f, 30f, 60f, 60f),
            RectF(0f, 0f, 30f, 30f),
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 2)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_BOTTOM, offset = 10f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[1], id = 2, width = 30f, height = 30f, POSITION_LEFT, offset = -10f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_doNotAttachCornerForShortOverlapOnLongEdgeBottom() {
        val root = rearrangeRects(
            RectF(0f, 0f, 1920f, 1080f),
            RectF(1850f, 1070f, 3770f, 2150f),
        )

        verifyDisplay(root, id = 0, width = 1920f, height = 1080f, noOfChildren = 1)
        verifyDisplay(
                root.children[0], id = 1, width = 1920f, height = 1080f, POSITION_BOTTOM,
                offset = 1850f, noOfChildren = 0)
    }

    @Test
    fun rearrange_doNotAttachCornerForShortOverlapOnLongEdgeLeft() {
        val root = rearrangeRects(
            RectF(0f, 0f, 1080f, 1920f),
            RectF(-1070f, -1880f, 10f, 40f),
        )

        verifyDisplay(root, id = 0, width = 1080f, height = 1920f, noOfChildren = 1)
        verifyDisplay(
                root.children[0], id = 1, width = 1080f, height = 1920f, POSITION_LEFT,
                offset = -1880f, noOfChildren = 0)
    }

    @Test
    fun rearrange_preferLongHorizontalShiftOverAttachToCorner() {
        // An earlier implementation decided vertical or horizontal clamp direction based on the abs
        // value of the overlap in each dimension, rather than the raw overlap.

        // This horizontal span is twice the height of displays, making abs(xOverlap) > yOverlap,
        // i.e. abs(-60) > 30
        //      |
        //    |----|
        // 000      111
        // 000      111
        // 000      111

        // Before fix:
        // 000
        // 000
        // 000
        //    111
        //    111
        //    111

        // After fix:
        // 000111
        // 000111
        // 000111

        val root = rearrangeRects(
            RectF(0f, 0f, 30f, 30f),
            RectF(90f, 0f, 120f, 30f),
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 1)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_RIGHT,
                offset = 0f, noOfChildren = 0)
    }

    @Test
    fun rearrange_preferLongVerticalShiftOverAttachToCorner() {
        // Before:
        // 111
        // 111
        // 111
        //        |
        //        |- This vertical span is 40dp
        //        |
        //        |
        //   000
        //   000
        //   000

        // After:
        // 111
        // 111
        // 111
        //   000
        //   000
        //   000

        val root = rearrangeRects(
            RectF(20f, 70f, 50f, 100f),
            RectF(00f, 0f, 30f, 30f),
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 1)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_TOP,
                offset = -20f, noOfChildren = 0)
    }

    @Test
    fun copy() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        val copy = topology.copy()

        assertThat(copy.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = copy.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 2)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay1.children[1]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 400f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 0)
    }

    @Test
    fun coordinates() {
        // 1122222244
        // 1122222244
        // 11      44
        // 11      44
        // 1133333344
        // 1133333344

        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val display3 = DisplayTopology.TreeNode(/* displayId= */ 3, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, /* primaryDisplayId= */ 1)
        val coords = topology.absoluteBounds

        val expectedCoords = SparseArray<RectF>()
        expectedCoords.append(1, RectF(0f, 0f, 200f, 600f))
        expectedCoords.append(2, RectF(200f, 0f, 800f, 200f))
        expectedCoords.append(3, RectF(200f, 400f, 800f, 600f))
        expectedCoords.append(4, RectF(800f, 0f, 1000f, 600f))
        assertThat(coords.contentEquals(expectedCoords)).isTrue()
    }

    @Test
    fun graph() {
        // 1122222244
        // 1122222244
        // 11      44
        // 11      44
        // 1133333344
        // 1133333344
        //        555
        //        555
        //        555

        val densityPerDisplay = SparseIntArray()

        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)
        val density1 = 100
        densityPerDisplay.append(1, density1)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)
        val density2 = 200
        densityPerDisplay.append(2, density2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)
        val density3 = 150
        densityPerDisplay.append(3, density3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)
        val density4 = 300
        densityPerDisplay.append(4, density4)

        val display5 = DisplayTopology.TreeNode(/* displayId= */ 5, /* width= */ 300f,
            /* height= */ 300f, POSITION_BOTTOM, /* offset= */ -100f)
        display4.addChild(display5)
        val density5 = 300
        densityPerDisplay.append(5, density5)

        topology = DisplayTopology(display1, primaryDisplayId)
        val graph = topology.getGraph(densityPerDisplay)!!
        val nodes = graph.displayNodes

        assertThat(graph.primaryDisplayId).isEqualTo(primaryDisplayId)
        assertThat(nodes.map {it.displayId}).containsExactly(1, 2, 3, 4, 5)
        for (node in nodes) {
            assertThat(node.density).isEqualTo(densityPerDisplay.get(node.displayId))
            when (node.displayId) {
                1 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_RIGHT,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_RIGHT,
                        /* offsetDp= */ 400f))
                2 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_LEFT,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 4, POSITION_RIGHT,
                        /* offsetDp= */ 0f))
                3 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_LEFT,
                        /* offsetDp= */ -400f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 4, POSITION_RIGHT,
                        /* offsetDp= */ -400f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 5, POSITION_BOTTOM,
                        /* offsetDp= */ 500f))
                4 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_LEFT,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_LEFT,
                        /* offsetDp= */ 400f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 5, POSITION_BOTTOM,
                        /* offsetDp= */ -100f))
                5 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_TOP,
                        /* offsetDp= */ -500f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 4, POSITION_TOP,
                        /* offsetDp= */ 100f))
            }
        }
    }

    @Test
    fun graph_corner() {
        // 1122244
        // 1122244
        // 1122244
        //   333
        // 55

        val densityPerDisplay = SparseIntArray()

        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 300f, /* position= */ 0, /* offset= */ 0f)
        val density1 = 100
        densityPerDisplay.append(1, density1)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 300f,
            /* height= */ 300f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)
        val density2 = 200
        densityPerDisplay.append(2, density2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 300f,
            /* height= */ 100f, POSITION_BOTTOM, /* offset= */ 0f)
        display2.addChild(display3)
        val density3 = 150
        densityPerDisplay.append(3, density3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 300f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)
        val density4 = 300
        densityPerDisplay.append(4, density4)

        val display5 = DisplayTopology.TreeNode(/* displayId= */ 5, /* width= */ 200f,
            /* height= */ 100f, POSITION_BOTTOM, /* offset= */ -200f)
        display3.addChild(display5)
        val density5 = 300
        densityPerDisplay.append(5, density5)

        topology = DisplayTopology(display1, primaryDisplayId)
        val graph = topology.getGraph(densityPerDisplay)!!
        val nodes = graph.displayNodes

        assertThat(graph.primaryDisplayId).isEqualTo(primaryDisplayId)
        assertThat(nodes.map {it.displayId}).containsExactly(1, 2, 3, 4, 5)
        for (node in nodes) {
            assertThat(node.density).isEqualTo(densityPerDisplay.get(node.displayId))
            when (node.displayId) {
                1 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_RIGHT,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_RIGHT,
                        /* offsetDp= */ 300f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_BOTTOM,
                        /* offsetDp= */ 200f))
                2 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_LEFT,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_BOTTOM,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 4, POSITION_RIGHT,
                        /* offsetDp= */ 0f))
                3 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_LEFT,
                        /* offsetDp= */ -300f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_TOP,
                        /* offsetDp= */ -200f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_TOP,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 4, POSITION_RIGHT,
                        /* offsetDp= */ -300f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 4, POSITION_TOP,
                        /* offsetDp= */ 300f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 5, POSITION_LEFT,
                        /* offsetDp= */ 100f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 5, POSITION_BOTTOM,
                        /* offsetDp= */ -200f))
                4 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_LEFT,
                        /* offsetDp= */ 0f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_LEFT,
                        /* offsetDp= */ 300f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_BOTTOM,
                        /* offsetDp= */ -300f))
                5 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_TOP,
                        /* offsetDp= */ 200f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_RIGHT,
                        /* offsetDp= */ -100f))
            }
        }
    }

    @Test
    fun graph_smallGap() {
        // 11122
        // 11122
        // 11133
        // 11133

        // There is a gap between displays 2 and 3, small enough for them to still be adjacent.

        val densityPerDisplay = SparseIntArray()

        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 300f,
            /* height= */ 400f, /* position= */ 0, /* offset= */ 0f)
        val density1 = 100
        densityPerDisplay.append(1, density1)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ -1f)
        display1.addChild(display2)
        val density2 = 200
        densityPerDisplay.append(2, density2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 200f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 201f)
        display1.addChild(display3)
        val density3 = 150
        densityPerDisplay.append(3, density3)

        topology = DisplayTopology(display1, primaryDisplayId)
        val graph = topology.getGraph(densityPerDisplay)!!
        val nodes = graph.displayNodes

        assertThat(graph.primaryDisplayId).isEqualTo(primaryDisplayId)
        assertThat(nodes.map {it.displayId}).containsExactly(1, 2, 3)
        for (node in nodes) {
            assertThat(node.density).isEqualTo(densityPerDisplay.get(node.displayId))
            when (node.displayId) {
                1 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_RIGHT,
                        /* offsetDp= */ -1f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_RIGHT,
                        /* offsetDp= */ 201f))
                2 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_LEFT,
                        /* offsetDp= */ 1f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 3, POSITION_BOTTOM,
                        /* offsetDp= */ 0f))
                3 -> assertThat(node.adjacentDisplays.toSet()).containsExactly(
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 1, POSITION_LEFT,
                        /* offsetDp= */ -201f),
                    DisplayTopologyGraph.AdjacentDisplay(/* displayId= */ 2, POSITION_TOP,
                        /* offsetDp= */ 0f))
            }
        }
    }

    /**
     * Runs the rearrange algorithm and returns the resulting tree as a list of nodes, with the
     * root at index 0. The number of nodes is inferred from the number of positions passed.
     *
     * Returns the root node.
     */
    private fun rearrangeRects(vararg pos: RectF): DisplayTopology.TreeNode {
        // Generates a linear sequence of nodes in order in the List from root to leaf,
        // left-to-right. IDs are ascending from 0 to count - 1.

        val nodes = pos.indices.map {
            DisplayTopology.TreeNode(it, pos[it].width(), pos[it].height(), POSITION_RIGHT, 0f)
        }

        nodes.forEachIndexed { id, node ->
            if (id > 0) {
                nodes[id - 1].addChild(node)
            }
        }

        DisplayTopology(nodes[0], 0).rearrange(pos.indices.associateWith {
            PointF(pos[it].left, pos[it].top)
        })

        return nodes[0]
    }

    private fun verifyDisplay(display: DisplayTopology.TreeNode, id: Int, width: Float,
                              height: Float, @DisplayTopology.TreeNode.Position position: Int = 0,
                              offset: Float = 0f, noOfChildren: Int) {
        assertThat(display.displayId).isEqualTo(id)
        assertThat(display.width).isEqualTo(width)
        assertThat(display.height).isEqualTo(height)
        assertThat(display.position).isEqualTo(position)
        assertThat(display.offset).isEqualTo(offset)
        assertThat(display.children).hasSize(noOfChildren)
    }
}
